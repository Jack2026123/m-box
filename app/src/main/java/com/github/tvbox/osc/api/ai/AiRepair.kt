package com.github.tvbox.osc.api.ai

import android.text.TextUtils
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.util.FloatLog
import com.github.tvbox.osc.util.LOG
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// 1.45 AI 修源引擎（按 1.0.4 api/ai/ 的 AVBox 形态，分两档）。
// 第一档无 key 可用：取证（魔改 FloatLog / avbox_floatlog.txt）、分型、域名检查、
// 备份/原子写/报告。第二档模型修源与整站重写必须用户给 key 才能开；
// 未配置时直接拒绝，绝不发模型请求、不静默烧钱。
object AiRepair {

    data class Diagnosis(
        val category: String,
        val summary: String,
        val evidence: List<String>,
        val domainStatus: String,
    )

    data class TestResult(val ok: Boolean, val message: String, val elapsedMs: Long = 0)

    private fun client(timeoutSec: Long): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
    }

    // 第一档：诊断（不需要 key）。
    suspend fun diagnose(subscriptionUrl: String, siteKey: String?): Diagnosis {
        val evidence = withContext(Dispatchers.IO) { collectEvidence(subscriptionUrl, siteKey) }
        val category = triage(evidence)
        val domainStatus = withContext(Dispatchers.IO) { checkDomain(subscriptionUrl) }
        val summary = buildString {
            append("分型：").append(category).append("\n")
            append("订阅：").append(if (subscriptionUrl.isEmpty()) "（未填）" else subscriptionUrl).append("\n")
            if (!siteKey.isNullOrEmpty()) append("单源：").append(siteKey).append("\n")
            append("域名：").append(domainStatus).append("\n")
            append("证据 ").append(evidence.size).append(" 条（取自悬浮日志 avbox_floatlog.txt）")
        }
        return Diagnosis(category, summary, evidence, domainStatus)
    }

    private fun collectEvidence(subscriptionUrl: String, siteKey: String?): List<String> {
        val lines = ArrayList<String>()
        try {
            if (AiConfig.isLogEnabled()) {
                val app = App.getInstance()
                val file = if (app != null) File(app.filesDir, "avbox_floatlog.txt") else null
                if (file != null && file.exists()) {
                    val bytes = file.readBytes()
                    val tail = if (bytes.size > 200 * 1024) bytes.copyOfRange(bytes.size - 200 * 1024, bytes.size) else bytes
                    val text = String(tail, Charsets.UTF_8)
                    for (line in text.lines()) {
                        if (line.isBlank()) continue
                        if (!siteKey.isNullOrEmpty() && line.contains(siteKey)) lines.add(line.take(300))
                        else if (line.contains("失败") || line.contains("错误") || line.contains("Exception") ||
                            line.contains("timeout") || line.contains("Timeout") || line.contains("404") ||
                            line.contains("500") || line.contains("空")
                        ) {
                            lines.add(line.take(300))
                        }
                    }
                }
            }
            if (lines.isEmpty()) {
                for (entry in FloatLog.snapshot()) {
                    val text = (entry.tag ?: "") + " " + entry.text
                    if (!siteKey.isNullOrEmpty() && text.contains(siteKey)) lines.add(text.take(300))
                    if (lines.size >= 20) break
                }
            }
        } catch (e: Throwable) {
            LOG.e("AiRepair", e)
        }
        return lines.takeLast(20)
    }

    // 分型：按证据关键词分到网络/域名、解析/规则、空数据、播放、未分型五档。
    private fun triage(evidence: List<String>): String {
        if (evidence.isEmpty()) return "未分型（日志里没有相关失败证据）"
        val joined = evidence.joinToString("\n")
        return when {
            joined.contains("UnknownHost") || joined.contains("timeout") || joined.contains("Timeout") ||
                joined.contains("Connect") || joined.contains("SSL") -> "网络/域名问题"
            joined.contains("解析") || joined.contains("JSON") || joined.contains("规则") ||
                joined.contains("parse") || joined.contains("Parse") -> "解析/规则问题"
            joined.contains("空") || joined.contains("0条") || joined.contains("无数据") -> "空数据问题"
            joined.contains("播放") || joined.contains("地址") -> "播放地址问题"
            else -> "未分型（有失败证据，需人工看报告）"
        }
    }

    private fun checkDomain(subscriptionUrl: String): String {
        if (!AiConfig.isWebEnabled()) return "已关闭网页取证"
        val url = subscriptionUrl.trim()
        if (url.isEmpty()) return "未填订阅地址"
        if (url.startsWith("file://") || File(url).exists()) return "本地文件，跳过域名检查"
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "非 http(s) 地址，跳过域名检查"
        return try {
            val request = Request.Builder().url(url).head().build()
            val response = client(10).newCall(request).execute()
            val code = response.code
            response.close()
            if (code in 200..399) "可达 HTTP $code" else "HTTP $code（可能已失效）"
        } catch (e: Throwable) {
            "连不上：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun buildReport(subName: String, subUrl: String, diagnosis: Diagnosis): String {
        return buildString {
            append("# AI 诊断报告\n\n")
            append("订阅：").append(subName).append("\n")
            append("地址：").append(subUrl).append("\n\n")
            append(diagnosis.summary).append("\n\n")
            append("## 证据\n\n")
            if (diagnosis.evidence.isEmpty()) append("（无）\n") else {
                for (line in diagnosis.evidence) append("- ").append(line).append("\n")
            }
            append("\n> 本报告由第一档（无 key）诊断生成；模型修源需在 AI 设置里填 Key 后才能开。\n")
        }
    }

    fun saveReport(text: String): File? {
        return try {
            val dir = File(App.getInstance()?.cacheDir, "ai_repair")
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
            val file = File(dir, "ai_report_$stamp.txt")
            file.writeText(text, Charsets.UTF_8)
            file
        } catch (e: Throwable) {
            LOG.e("AiRepair", e)
            null
        }
    }

    // 备份：本次修复专用时间戳备份，永不与历史备份互相覆盖（按 1.0.4 AiStore）。
    fun backupOf(path: String): File {
        val src = File(path)
        val name = src.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        return File(src.parentFile, base + "_备份_" + stamp + ext)
    }

    // 原子写：先写同目录临时文件再改名，避免半截文件。
    fun writeAtomic(path: String, content: String): Boolean {
        if (path.isEmpty()) return false
        val target = File(path)
        val dir = target.parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        val tmp = File("$path.aibak.tmp")
        return try {
            FileOutputStream(tmp).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            if (target.exists() && !target.delete()) {
                target.writeText(content, Charsets.UTF_8)
                tmp.delete()
                return true
            }
            if (tmp.renameTo(target)) return true
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
            true
        } catch (e: Throwable) {
            LOG.e("AiRepair", e)
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // 第二档：模型修源（单源）。未配置直接拒绝，不发任何模型请求。
    suspend fun repairSite(siteJson: String, diagnosisText: String): Result<String> {
        if (!AiConfig.isConfigured()) {
            return Result.failure(
                IllegalStateException("AI 未配置：请先在 AI 设置里填 Key（Base URL 与模型已有缺省），未配置时不发任何模型请求"),
            )
        }
        if (siteJson.isBlank()) return Result.failure(IllegalStateException("单源规则 JSON 为空，无法修源"))
        val originalKey = try {
            JSONObject(siteJson).optString("key", "")
        } catch (e: Throwable) {
            ""
        }
        var lastError = ""
        val rounds = AiConfig.getRounds()
        for (round in 1..rounds) {
            try {
                val repaired = withContext(Dispatchers.IO) {
                    callModel(siteJson, diagnosisText, lastError)
                }
                val parsed = JSONObject(repaired)
                val key = parsed.optString("key", "")
                if (!originalKey.isEmpty() && key != originalKey) {
                    lastError = "返回的 key 与原规则不一致（原=$originalKey，返回=$key），请只修失效字段、保持 key 不变"
                    continue
                }
                return Result.success(repaired)
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                LOG.e("AiRepair", e)
            }
        }
        return Result.failure(IllegalStateException("模型修源 $rounds 轮均未返回可用 JSON：$lastError"))
    }

    private fun callModel(siteJson: String, diagnosisText: String, lastError: String): String {
        val body = JSONObject()
        body.put("model", AiConfig.getModel())
        body.put("temperature", 0)
        body.put("stream", false)
        val messages = JSONArray()
        val system = JSONObject()
        system.put("role", "system")
        system.put("content", AiSkillPack.systemPrefix() + "你是影视源规则修复助手。只输出修复后的 JSON 对象，不要解释、不要 markdown 包裹。保持 key 不变，只修失效字段。")
        messages.put(system)
        val user = JSONObject()
        user.put("role", "user")
        val prompt = buildString {
            append("原单源规则 JSON：\n").append(siteJson).append("\n\n")
            append("诊断：\n").append(diagnosisText).append("\n")
            if (!lastError.isEmpty()) append("\n上一轮问题：").append(lastError).append("\n请修正后重新输出完整 JSON。\n")
        }
        user.put("content", prompt)
        messages.put(user)
        body.put("messages", messages)
        val request = Request.Builder()
            .url(AiConfig.chatUrl())
            .addHeader("Authorization", "Bearer " + AiConfig.getApiKey())
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client(120).newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IllegalStateException("模型接口 HTTP " + response.code + "：" + text.take(200))
            val json = JSONObject(text)
            val choices = json.optJSONArray("choices") ?: throw IllegalStateException("模型返回里没有 choices")
            if (choices.length() == 0) throw IllegalStateException("模型返回 choices 为空")
            val message = choices.getJSONObject(0).optJSONObject("message")
            val content = message?.optString("content", "") ?: ""
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) throw IllegalStateException("模型返回里没有 JSON 对象")
            return content.substring(start, end + 1)
        }
    }

    // 连通性测试：发一条最小对话请求，一次验地址通、Key 对、模型名存在三件事。
    suspend fun testConnection(): TestResult {
        if (!AiConfig.isConfigured()) return TestResult(false, "未配置：请先填 Key（Base URL 与模型已有缺省）")
        return try {
            val body = JSONObject()
            body.put("model", AiConfig.getModel())
            body.put("temperature", 0)
            body.put("max_tokens", 8)
            body.put("stream", false)
            val messages = JSONArray()
            val one = JSONObject()
            one.put("role", "user")
            one.put("content", "ping")
            messages.put(one)
            body.put("messages", messages)
            val request = Request.Builder()
                .url(AiConfig.chatUrl())
                .addHeader("Authorization", "Bearer " + AiConfig.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val text = withContext(Dispatchers.IO) {
                client(60).newCall(request).execute().use { response ->
                    val t = response.body?.string() ?: ""
                    if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code + "：" + t.take(200))
                    t
                }
            }
            val choices = JSONObject(text).optJSONArray("choices")
            if (choices == null || choices.length() == 0) TestResult(false, "接口通了，但返回里没有 choices（不是对话接口，或该模型不支持对话）")
            else TestResult(true, "连接正常")
        } catch (e: Throwable) {
            TestResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    // 1.46：按字段值测试（设置对话框用，不依赖已保存配置；结果带耗时毫秒）。
    suspend fun testModel(baseUrl: String, apiKey: String, model: String): TestResult {
        val base = baseUrl.trim()
        val key = apiKey.trim()
        val name = model.trim()
        if (base.isEmpty() || key.isEmpty() || name.isEmpty()) return TestResult(false, "未配置：请先填 Base URL、Key 与模型")
        return try {
            val body = JSONObject()
            body.put("model", name)
            body.put("temperature", 0)
            body.put("max_tokens", 8)
            body.put("stream", false)
            val messages = JSONArray()
            val one = JSONObject()
            one.put("role", "user")
            one.put("content", "ping")
            messages.put(one)
            body.put("messages", messages)
            val request = Request.Builder()
                .url(AiConfig.chatUrl(base))
                .addHeader("Authorization", "Bearer " + key)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val start = System.currentTimeMillis()
            val text = withContext(Dispatchers.IO) {
                client(60).newCall(request).execute().use { response ->
                    val t = response.body?.string() ?: ""
                    if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code + "：" + t.take(200))
                    t
                }
            }
            val elapsed = System.currentTimeMillis() - start
            val choices = JSONObject(text).optJSONArray("choices")
            if (choices == null || choices.length() == 0) TestResult(false, "接口通了，但返回里没有 choices（不是对话接口，或该模型不支持对话）", elapsed)
            else TestResult(true, "连接正常", elapsed)
        } catch (e: Throwable) {
            TestResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    // 1.46：拉取模型列表（OpenAI 兼容 GET {base}/models；失败时抛错，由调用方展示）。
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> {
        var base = baseUrl.trim()
        while (base.endsWith("/")) base = base.substring(0, base.length - 1)
        if (base.isEmpty() || apiKey.isEmpty()) throw IllegalStateException("未配置：请先填 Base URL 与 Key")
        val request = Request.Builder()
            .url(base + "/models")
            .addHeader("Authorization", "Bearer " + apiKey.trim())
            .build()
        val text = withContext(Dispatchers.IO) {
            client(30).newCall(request).execute().use { response ->
                val t = response.body?.string() ?: ""
                if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code + "：" + t.take(200))
                t
            }
        }
        val out = ArrayList<String>()
        val data = JSONObject(text).optJSONArray("data")
        if (data != null) {
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id") ?: ""
                if (id.isNotEmpty()) out.add(id)
            }
        }
        return out
    }

    // 本地配置文件写回：按 key 替换 sites 里的一条，备份＋原子写；远程订阅返回 null（无法写回）。
    fun applyRepairedToLocalConfig(configPath: String, repairedJson: String): String? {
        return try {
            val file = File(configPath)
            if (!file.exists()) return "本地配置文件不存在：$configPath"
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val sites = root.optJSONArray("sites") ?: return "配置文件里没有 sites 数组"
            val repaired = JSONObject(repairedJson)
            val key = repaired.optString("key", "")
            if (key.isEmpty()) return "修复后 JSON 里没有 key，无法定位要替换的源"
            var replaced = false
            for (i in 0 until sites.length()) {
                val obj = sites.optJSONObject(i) ?: continue
                if (obj.optString("key", "") == key) {
                    sites.put(i, repaired)
                    replaced = true
                    break
                }
            }
            if (!replaced) return "本地配置里找不到 key=$key 的源，未写回"
            val backup = backupOf(configPath)
            file.copyTo(backup, overwrite = false)
            if (!writeAtomic(configPath, root.toString(2))) return "写回失败（备份在 ${backup.absolutePath}）"
            "已写回本地配置，备份在 ${backup.absolutePath}"
        } catch (e: Throwable) {
            LOG.e("AiRepair", e)
            "写回异常：" + (e.message ?: e.javaClass.simpleName)
        }
    }
}
