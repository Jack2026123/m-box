package com.github.tvbox.osc.api.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.util.KV
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2.21 py 写源技能包（可上传多个）：用户上传多份写源技能包（.zip / .md / .txt / .py），
 * 每份存到 App 私有目录 files/ai_skill_pack/<id>/（pack.bin 原件 + pack.txt 提取文本），
 * 清单（id/名字/启用）存 KV 的 JSON 数组；写源（建源工坊）与修源调模型时，把全部已启用
 * 技能包的文本按上传顺序拼进 system prompt 最前面，模型按技能包的规矩写源/修源。
 * 每份可单独开关、单独删除；全关或未上传时就是裸跑（不拼技能包）。
 * 上传走系统文件选择（ACTION_OPEN_DOCUMENT），MainActivity 转发 onActivityResult
 * （View 对话框无法注册 Launcher）。
 */
object AiSkillPack {

    const val REQUEST_PICK_SKILL_PACK = 0x5A02

    private const val KEY_PACKS = "ai_skill_packs"
    private const val DIR_NAME = "ai_skill_pack"
    private const val RAW_FILE = "pack.bin"
    private const val TEXT_FILE = "pack.txt"
    private const val BUNDLED_VERSION = "ai_builtin_skill_version"

    /** 内置两份技能首次使用时落一份到私有目录；之后与用户上传包走同一分诊和 read_skill 路。 */
    @Synchronized
    fun ensureBundled(context: Context) {
        if (KV.get(BUNDLED_VERSION, 0) >= 1) return
        val packs = listOf(
            "py_spider_v952.zip" to "内置_v9.5.2_OneNav_Agent.zip",
            "tvbox_skill_book.zip" to "内置_写源技能书.zip",
        )
        try {
            for ((asset, displayName) in packs) {
                val bytes = context.assets.open("ai_skill/$asset").use { it.readBytes() }
                addPack(context, displayName, bytes)
            }
            KV.put(BUNDLED_VERSION, 1)
        } catch (ignored: Throwable) {
        }
    }

    private val CORE_RULES = """
【TVBox Python 写源统一门禁（v9.5.2 + 写源技能书综合）】
先实测站点，再写代码；禁止未经分析套模板。输出必须是 from base.spider import Spider 的 class Spider(Spider)，六接口完整。
homeContent/homeVideoContent 返回 list；categoryContent 返回 page/pagecount/limit/total/list；详情返回 list，线路用 $$$、剧集用 #、集名与播放ID用 $。
vod_id 去重；相对 URL 用 urljoin；选择器以实测命中为准，class 用 contains/多属性兜底；单条异常隔离并 continue。
列表不得逐条请求详情补字段。HTTP 200 不代表有数据，必须看 list 数与真实字段。
播放页、iframe、.html 不得冒充直链；playerData/iframe/API 二次解析后，parse:0 只给实测 HTTP(S) m3u8/mp4。
先静态校验，再本地加载，再六接口真跑；失败只修列表选择器/路由/播放解析中的故障项，保留上一版基线。
未配置模型、缺实测、真跑失败或触发验证码/WAF 时必须明确未通过，禁止用起点骨架或伪造数据交付。
""".trimIndent()

    /** 上传/删除/开关变化后，当前打开的 Hub 面板刷新用；Hub 显示时设置、关闭时清掉。 */
    var onChanged: (() -> Unit)? = null

    data class PackEntry(val id: String, val name: String, val enabled: Boolean)

    /** 技能包信息：id、名字、原始大小（字节）、文本字符数、预览（前 200 字）、是否启用。 */
    data class Info(val id: String, val name: String, val sizeBytes: Long, val chars: Int, val preview: String, val enabled: Boolean)

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun packDir(context: Context, id: String): File = File(dir(context), id)

    private fun packTextFile(context: Context, id: String): File = File(packDir(context, id), TEXT_FILE)

    /** 技能包清单（按上传顺序）；KV 里是 JSON 数组 [{id,name,enabled}]。 */
    fun listPacks(): List<PackEntry> {
        val raw = KV.get(KEY_PACKS, "")
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<PackEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val name = o.optString("name", "")
                if (id.isEmpty() || name.isEmpty()) continue
                out.add(PackEntry(id, name, o.optBoolean("enabled", true)))
            }
        } catch (ignored: Throwable) {
        }
        return out
    }

    private fun savePacks(entries: List<PackEntry>) {
        try {
            val arr = JSONArray()
            for (e in entries) {
                val o = JSONObject()
                o.put("id", e.id)
                o.put("name", e.name)
                o.put("enabled", e.enabled)
                arr.put(o)
            }
            KV.put(KEY_PACKS, arr.toString())
        } catch (ignored: Throwable) {
        }
    }

    fun hasPack(): Boolean = listPacks().isNotEmpty()

    fun hasEnabledPack(): Boolean = listPacks().any { it.enabled }

    /** 全部已启用技能包的信息（按上传顺序），供面板列表显示。 */
    fun infos(context: Context): List<Info> {
        ensureBundled(context)
        val out = ArrayList<Info>()
        for (e in listPacks()) {
            val raw = File(packDir(context, e.id), RAW_FILE)
            val txt = packTextFile(context, e.id)
            val text = try {
                if (txt.exists()) txt.readText(Charsets.UTF_8) else ""
            } catch (ignored: Throwable) {
                ""
            }
            out.add(Info(e.id, e.name, if (raw.exists()) raw.length() else 0L, text.length, text.take(200), e.enabled))
        }
        return out
    }

    /** 已启用技能包的文本总字数（面板与工坊显示用）。 */
    fun enabledChars(context: Context): Int {
        ensureBundled(context)
        var total = 0
        for (e in listPacks()) {
            if (!e.enabled) continue
            val txt = packTextFile(context, e.id)
            if (txt.exists()) {
                try {
                    total += txt.readText(Charsets.UTF_8).length
                } catch (ignored: Throwable) {
                }
            }
        }
        return total
    }

    /** 拼进 system prompt 前面的技能包块（全部已启用的，最新上传的排最前面）；未上传或全关时返回空串。字数不设上限：上传多少就用多少。 */
    fun systemPrefix(context: Context?): String {
        val ctx = context ?: appContext() ?: return CORE_RULES
        ensureBundled(ctx)
        val enabled = listPacks().filter { it.enabled }
        if (enabled.isEmpty()) return CORE_RULES
        val sb = StringBuilder(CORE_RULES).append("\n")
        sb.append("【内置及用户上传的写源技能包（共 ").append(enabled.size).append(" 个），最新上传的排在最前面，写源/修源时优先按最新的执行】\n")
        // 最新上传的放最前面，写源优先按最新的执行。
        for (e in enabled.asReversed()) {
            val txt = packTextFile(ctx, e.id)
            if (!txt.exists()) continue
            val text = try {
                txt.readText(Charsets.UTF_8)
            } catch (ignored: Throwable) {
                ""
            }
            if (text.isBlank()) continue
            sb.append("\n===== 技能包：").append(e.name).append(" =====\n").append(text.take(60000))
        }
        sb.append("\n【技能包结束】\n")
        return sb.toString()
    }

    /** 无 Context 调用（AiRepair / 建源工坊的 callChat 走这里）：用 Application 实例读技能包。 */
    fun systemPrefix(): String = systemPrefix(null)

    /**
     * 2.23 技能包步骤整合：按站点特征分诊（SKILL.md 第 7 节模块加载矩阵），只加载匹配的模块。
     * 信号来自站点 URL + 侦察到的首页内容；分诊不到任何模块时返回空，由 systemPrefixFor 回落到全文。
     */
    fun selectModules(siteUrl: String, recon: String): List<String> {
        val hay = (siteUrl + "\n" + recon).lowercase()
        fun hit(vararg signals: String): Boolean = signals.any { hay.contains(it) }
        val picked = ArrayList<String>()
        // 与技能包 maccms.md / gates R3 对齐：myui/mytheme/伪静态也算 MacCMS
        if (hit(
                "/index.php/vod", "/vod/show", "/vod/detail", "/frim/index", "player_aaaa", "mac_cms", "maccms",
                "myui", "mytheme", "macplayer", "player_data", "vodss-", "cates-", "plays-",
                "template/mytheme", "苹果cms", "myui-vodlist"
            )) picked.add("maccms")
        // 2.36 v9.5.2 5.1 硬门禁：非标准伪静态（myui/mytheme/?cates-/?vodss-/?plays-）强制 maccms_mytheme 模板
        if (hit("myui", "mytheme", "vodss-", "cates-", "plays-", "template/mytheme", "myui-vodlist")) picked.add("mytheme")
        // 2.37 OneNav 导航目录站：App 侧先分诊停掉；模型侧再兜底（边界站：有 OneNav 签名但带媒体信号）
        if (hit("wp-content/themes/onenav", "onenav")) picked.add("onenav")
        if (hit("api.php/provide/vod", "ac=list")) picked.add("applecms_api")
        if (hit("__nuxt_data__", "__nuxt__", "_payload.js")) picked.add("nuxt_devalue_api")
        if (hit("__next_f")) picked.add("rsc_flight")
        if (hit("playerconfig.js", "player/api.php", "customstrdecode")) picked.add("encrypted_iframe")
        if (hit("enc:true", "parameteriv", "cryptojs")) picked.add("encrypted_api")
        if (hit("just a moment", "滑动验证", "/_waf/", "cf-chl")) picked.add("shield_routing")
        if (hit("huadong", "slidebox")) picked.add("slider_huadong")
        if (hit("play_data", "pako", "inflateraw")) picked.add("compressed_payload")
        if (hit("eval(function(p,a,c,k,e,d)")) picked.add("js_unpack")
        if (hit("#ext-x-key", "ext-x-key")) picked.add("hls_localproxy")
        if (hit("words.random", "appconfig")) picked.add("domain_release")
        if (hit("zz_line", "最新地址")) picked.add("resilience")
        if (hit("vod_tag")) picked.add("folder_routing")
        if (hit("localproxy", "proxypic")) picked.add("proxy_network")
        if (hit("vite_base_url", "parameterkey", "id=\"app\"")) picked.add("api_spa")
        return picked
    }

    /** 从已启用包的 pack.bin 原件里按路径取各模块文本（不动 pack.txt）；非 zip 包或结构不对时返回空。 */
    private fun moduleTexts(context: Context): Map<String, String> {
        val out = LinkedHashMap<String, StringBuilder>()
        fun put(key: String, name: String, content: String) {
            out.getOrPut(key) { StringBuilder() }.append("\n===== ").append(name).append(" =====\n").append(content)
        }
        for (e in listPacks()) {
            if (!e.enabled) continue
            val raw = File(packDir(context, e.id), RAW_FILE)
            if (!raw.exists()) continue
            try {
                ZipInputStream(raw.inputStream()).use { zin ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory) {
                            val lower = name.lowercase()
                            val content = zin.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")
                            if (content.isNotBlank()) {
                                when {
                                    lower == "skill.md" || lower.endsWith("/skill.md") -> put("skill", name, content)
                                    lower.contains("/schemas/") && lower.endsWith(".json") -> put("schemas", name, content)
                                    lower.contains("/validators/gates.md") -> put("gates", name, content)
                                    lower.contains("/templates/code/spider_skeleton.py") -> put("skeleton", name, content)
                                    lower.contains("/templates/maccms.md") -> put("maccms_doc", name, content)
                                    lower.contains("/templates/code/maccms_v10.py") || lower.contains("/templates/code/maccms_v8.py") -> put("maccms_code", name, content)
                                    lower.contains("/templates/code/maccms_mytheme.py") -> put("maccms_mytheme_code", name, content)
                                    lower.contains("/templates/code/example_chanmea.py") -> put("maccms_example", name, content)
                                    lower.contains("/strategies/maccms_mytheme_route.md") -> put("maccms_mytheme_route", name, content)
                                    lower.contains("/strategies/wordpress_onenav_directory.md") -> put("onenav_directory", name, content)
                                    lower.contains("/strategies/agent_workflow.md") -> put("agent_workflow", name, content)
                                    lower.endsWith("tvbox_影视仓_自动化锻造技能书_v2.0.md") || lower.endsWith("tvbox_spider_skill_fixed.md") -> put("generic_guide", name, content)
                                    lower.contains("/templates/api_spa.md") -> put("api_spa_doc", name, content)
                                    lower.contains("/templates/code/api.py") -> put("api_spa_code", name, content)
                                    lower.contains("/templates/nonvideo.md") -> put("nonvideo_doc", name, content)
                                    lower.contains("/templates/code/nonvideo.py") -> put("nonvideo_code", name, content)
                                    lower.contains("/strategies/applecms_api.md") -> put("applecms_api", name, content)
                                    lower.contains("/strategies/nuxt_devalue_api.md") -> put("nuxt_devalue_api", name, content)
                                    lower.contains("/strategies/rsc_flight.md") -> put("rsc_flight", name, content)
                                    lower.contains("/strategies/encrypted_iframe.md") -> put("encrypted_iframe", name, content)
                                    lower.contains("/strategies/encrypted_api.md") -> put("encrypted_api", name, content)
                                    lower.contains("/strategies/shield_routing.md") -> put("shield_routing", name, content)
                                    lower.contains("/strategies/slider_huadong.md") -> put("slider_huadong", name, content)
                                    lower.contains("/strategies/compressed_payload.md") -> put("compressed_payload", name, content)
                                    lower.contains("/strategies/js_unpack.md") -> put("js_unpack", name, content)
                                    lower.contains("/strategies/hls_localproxy.md") -> put("hls_localproxy", name, content)
                                    lower.contains("/strategies/domain_release.md") -> put("domain_release", name, content)
                                    lower.contains("/strategies/resilience.md") -> put("resilience", name, content)
                                    lower.contains("/strategies/folder_routing.md") -> put("folder_routing", name, content)
                                    lower.contains("/strategies/proxy_network.md") -> put("proxy_network", name, content)
                                }
                            }
                        }
                        entry = zin.nextEntry
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
        val result = LinkedHashMap<String, String>()
        for ((k, v) in out) result[k] = v.toString()
        return result
    }

    /**
     * 2.38 完整工坊 read_skill：Agent 按需读一个技能模块。
     * 参数是模块 key（如 maccms_mytheme_code、maccms_example、onenav_directory、agent_workflow）
     * 或路径片段（如 templates/code/maccms_mytheme.py）；找不到返回空串。
     */
    fun readModule(context: Context?, keyOrPath: String): String {
        if (context == null) return ""
        ensureBundled(context)
        val q = keyOrPath.trim()
        if (q.isEmpty()) return ""
        return try {
            val modules = moduleTexts(context)
            modules[q]?.let { return it }
            val lower = q.lowercase()
            for ((k, v) in modules) {
                if (k.lowercase().contains(lower)) return v
            }
            for ((_, v) in modules) {
                if (v.contains(q)) return v
            }
            ""
        } catch (ignored: Throwable) {
            ""
        }
    }

    /**
     * 2.23 按站点分诊拼技能包：SKILL.md + schemas + gates.md 必载，再拼匹配模块的文本；
     * 分诊不到任何模块、或包里没有模块结构时回落到全文 systemPrefix()。
     */
    fun systemPrefixFor(context: Context?, siteUrl: String, recon: String): String {
        val ctx = context ?: appContext() ?: return CORE_RULES
        ensureBundled(ctx)
        val modules = moduleTexts(ctx)
        val skill = modules["skill"] ?: return systemPrefix(ctx)
        val picked = selectModules(siteUrl, recon)
        val wanted = ArrayList<String>()
        if (picked.contains("maccms")) {
            wanted.add("maccms_doc")
            if (!picked.contains("mytheme")) wanted.add("maccms_code")
        }
        if (picked.contains("mytheme")) { wanted.add("maccms_mytheme_code"); wanted.add("maccms_mytheme_route"); wanted.add("maccms_example") }
        if (picked.contains("onenav")) wanted.add("onenav_directory")
        if (picked.contains("applecms_api")) { wanted.add("applecms_api"); wanted.add("api_spa_doc"); wanted.add("api_spa_code") }
        if (picked.contains("api_spa")) { wanted.add("api_spa_doc"); wanted.add("api_spa_code") }
        if (picked.contains("nuxt_devalue_api")) wanted.add("nuxt_devalue_api")
        if (picked.contains("rsc_flight")) wanted.add("rsc_flight")
        if (picked.contains("encrypted_iframe")) wanted.add("encrypted_iframe")
        if (picked.contains("encrypted_api")) wanted.add("encrypted_api")
        if (picked.contains("shield_routing")) wanted.add("shield_routing")
        if (picked.contains("slider_huadong")) wanted.add("slider_huadong")
        if (picked.contains("compressed_payload")) wanted.add("compressed_payload")
        if (picked.contains("js_unpack")) wanted.add("js_unpack")
        if (picked.contains("hls_localproxy")) wanted.add("hls_localproxy")
        if (picked.contains("domain_release")) wanted.add("domain_release")
        if (picked.contains("resilience")) wanted.add("resilience")
        if (picked.contains("folder_routing")) wanted.add("folder_routing")
        if (picked.contains("proxy_network")) wanted.add("proxy_network")
        val sb = StringBuilder(CORE_RULES).append("\n")
        sb.append("【内置及用户上传的写源技能包（按本站点分诊，只加载匹配模块；分诊结果：").append(picked.joinToString("、")).append("）】\n")
        sb.append(skill)
        modules["schemas"]?.let { sb.append(it) }
        modules["gates"]?.let { sb.append(it) }
        modules["skeleton"]?.let { sb.append(it) }
        modules["agent_workflow"]?.let { sb.append(it) }
        for (key in wanted) {
            modules[key]?.let { sb.append(it) }
        }
        if (wanted.isEmpty()) modules["generic_guide"]?.let { sb.append(it.take(30000)) }
        sb.append("\n【技能包结束】\n")
        return sb.toString()
    }

    private fun appContext(): Context? {
        return try {
            App.getInstance()
        } catch (ignored: Throwable) {
            null
        }
    }

    fun pickSkillPack(activity: Activity) {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            intent.putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "text/plain", "text/markdown", "text/x-python", "application/x-python-code")
            )
            activity.startActivityForResult(intent, REQUEST_PICK_SKILL_PACK)
        } catch (ignored: Throwable) {
        }
    }

    /** MainActivity 转发 onActivityResult 到这里；返回 true 表示已接手。 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?, context: Context): Boolean {
        if (requestCode != REQUEST_PICK_SKILL_PACK) return false
        if (resultCode != Activity.RESULT_OK || data?.data == null) return false
        val uri = data.data ?: return false
        try {
            val displayName = queryDisplayName(context, uri) ?: "skill_pack"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
            if (bytes.isEmpty()) return false
            addPack(context, displayName, bytes)
            onChanged?.invoke()
        } catch (ignored: Throwable) {
        }
        return true
    }

    /** 新增一份技能包：存原始字节并提取文本，追加到清单末尾（默认启用）。 */
    fun addPack(context: Context, displayName: String, bytes: ByteArray): PackEntry {
        // 同名覆盖：已有同名包时只换内容，不新增条目，启用状态保持不变。
        val existing = listPacks().find { it.name == displayName }
        if (existing != null) {
            val d = packDir(context, existing.id)
            if (!d.exists()) d.mkdirs()
            File(d, RAW_FILE).writeBytes(bytes)
            val text = extractText(bytes, displayName)
            File(d, TEXT_FILE).writeText(text, Charsets.UTF_8)
            return existing
        }
        val id = UUID.randomUUID().toString()
        val d = packDir(context, id)
        if (!d.exists()) d.mkdirs()
        File(d, RAW_FILE).writeBytes(bytes)
        val text = extractText(bytes, displayName)
        File(d, TEXT_FILE).writeText(text, Charsets.UTF_8)
        val entry = PackEntry(id, displayName, true)
        val list = listPacks().toMutableList()
        list.add(entry)
        savePacks(list)
        return entry
    }

    /** 删除一份技能包（文件 + 清单）。 */
    fun removePack(context: Context, id: String) {
        try {
            packDir(context, id).deleteRecursively()
        } catch (ignored: Throwable) {
        }
        val list = listPacks().filter { it.id != id }
        savePacks(list)
        onChanged?.invoke()
    }

    /** 单份技能包开关。 */
    fun setPackEnabled(id: String, enabled: Boolean) {
        val list = listPacks().map { if (it.id == id) it.copy(enabled = enabled) else it }
        savePacks(list)
        onChanged?.invoke()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    /**
     * zip 解开按优先级取文本：SKILL.md → 模板代码 → Schema → 模板文档 → 策略 → 校验器 → 归档；
     * 单个文件直接读 UTF-8 文本；字数不设上限，多少就取多少。zip 内按原顺序读会先被归档大文件占满，
     * 模板代码（写源最要紧）反而读不到，所以必须先排优先级再取。
     */
    fun extractText(bytes: ByteArray, displayName: String): String {
        val isZip = displayName.endsWith(".zip", ignoreCase = true) ||
            (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())
        if (!isZip) {
            return bytes.toString(Charsets.UTF_8).replace("\r\n", "\n")
        }
        val texts = ArrayList<Pair<Int, String>>()
        try {
            ZipInputStream(bytes.inputStream()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory) {
                        val lower = name.lowercase()
                        val prio = when {
                            lower == "skill.md" || lower.endsWith("/skill.md") -> 0
                            (lower.contains("/templates/code/") || lower.contains("/templates/lib/")) && lower.endsWith(".py") -> 1
                            lower.contains("/schemas/") && lower.endsWith(".json") -> 2
                            lower.contains("/templates/") && lower.endsWith(".md") -> 3
                            lower.contains("/strategies/") && lower.endsWith(".md") -> 4
                            lower.contains("/validators/") -> 5
                            lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".py") -> 6
                            else -> 99
                        }
                        if (prio < 99) {
                            val content = zin.readBytes().toString(Charsets.UTF_8)
                            if (content.isNotBlank()) texts.add(Pair(prio, "\n===== " + name + " =====\n" + content))
                        }
                    }
                    entry = zin.nextEntry
                }
            }
        } catch (ignored: Throwable) {
        }
        texts.sortBy { it.first }
        val sb = StringBuilder()
        for ((_, t) in texts) {
            sb.append(t)
        }
        val out = sb.toString().trim()
        if (out.isEmpty()) return "(zip 里没有可读的 .md/.txt/.py/.json 文本)"
        return out.replace("\r\n", "\n")
    }
}
