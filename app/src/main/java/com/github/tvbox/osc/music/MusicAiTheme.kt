package com.github.tvbox.osc.music

import com.github.tvbox.osc.api.ai.AiConfig
import com.github.tvbox.osc.util.LOG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// AI 主题自动生成（2.81）。提示词与清洗规则对标 Folia 的 aiThemePrompts.ts /
// themeSanitizer.ts，调用走工坊已配好的模型（AiConfig），不在前台弹窗、不堵播放。
// 没配模型（key 为空）或模型返回不可用时静默返回 null，调用方回落 2.80 的随机主题。

data class AiTheme(
    val name: String,
    val backgroundColor: Int,
    val primaryColor: Int,
    val accentColor: Int,
    val secondaryColor: Int,
    val wordColors: Map<String, Int>,
)

data class AiDualTheme(val light: AiTheme, val dark: AiTheme)

object MusicAiTheme {

    private val cache = ConcurrentHashMap<String, AiDualTheme>()

    fun cached(songKey: String): AiDualTheme? = cache[songKey]

    fun putCache(songKey: String, theme: AiDualTheme) {
        cache[songKey] = theme
    }

    private const val SYSTEM_PROMPT = """You are generating a visual theme for a music player from a song. Analyze the mood of the provided song text and generate TWO complete themes, one for LIGHT mode and one for DARK mode. Both capture the SAME emotional essence, with palettes suited to their mode.

RULES:
- Theme names must be in Chinese, max 10 characters, reflecting mood AND mode (e.g. light "忧郁破晓", dark "忧郁子夜").
- Description: one emotional sentence in Chinese, 15-30 characters, first-person listener perspective, stream-of-consciousness, youthful and literary (e.g. "戴上耳机的那一刻，喧嚣的世界瞬间消失了。").
- Identify 10-20 key emotional standalone words from the source text and give each a representative color in "wordColors". For Latin text each word must be one complete word, no punctuation, apostrophes, hyphens or spaces. For CJK, short meaningful terms are fine. Each word must appear in the source snippet.
- LIGHT: light backgrounds, diverse (warm cream, soft pastel blue, pale sage, gentle peach, warm sand, pale lavender), never plain white for every theme. Text dark enough, never pure black. accentColor visible on the background.
- DARK: dark backgrounds, rich (deep midnight blue, dark forest, charcoal, dark plum, deep chocolate, burgundy), never pure black. Text light enough, never pure white. accentColor contrasting, derived from the song mood.
- secondaryColor is used for secondary text and MUST contrast with backgroundColor (aim 4.5:1).
- wordColors must harmonize with the overall palettes.

OUTPUT CONTRACT:
Return one JSON object with "light" and "dark" objects. Each has: "name", "description", "backgroundColor", "primaryColor", "accentColor", "secondaryColor", "wordColors" (array of {"word","color"}), "lyricsIcons" (array of strings).
Colors are hex strings like "#RRGGBB". Return ONLY the JSON object, no other text."""

    private fun buildSourcePrompt(title: String, artist: String, snippet: String): String {
        val text = if (snippet.isBlank()) title else snippet
        return "Song title: " + title + " - " + artist + "\nSource snippet:\n" + text
    }

    suspend fun generate(title: String, artist: String, lyricSnippet: String): AiDualTheme? {
        val key = AiConfig.getApiKey()
        if (key.isBlank()) return null
        return try {
            withContext(Dispatchers.IO) {
                val body = JSONObject()
                body.put("model", AiConfig.getModel())
                body.put("temperature", 0)
                body.put("stream", false)
                val messages = JSONArray()
                val system = JSONObject()
                system.put("role", "system")
                system.put("content", SYSTEM_PROMPT)
                messages.put(system)
                val user = JSONObject()
                user.put("role", "user")
                user.put("content", buildSourcePrompt(title, artist, lyricSnippet))
                messages.put(user)
                body.put("messages", messages)
                val request = Request.Builder()
                    .url(AiConfig.chatUrl())
                    .addHeader("Authorization", "Bearer " + key)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                val client = OkHttpClient.Builder()
                    .connectTimeout(120, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .build()
                val result: AiDualTheme? = client.newCall(request).execute().use { response ->
                    val text = try {
                        response.body.string()
                    } catch (ignored: Exception) {
                        ""
                    }
                    if (!response.isSuccessful) {
                        LOG.i("echo-music ai theme http " + response.code)
                        null
                    } else {
                        val content = runCatching {
                            JSONObject(text).optJSONArray("choices")
                                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
                        }.getOrNull().orEmpty()
                        if (content.isBlank()) null else parseAiTheme(content)
                    }
                }
                result
            }
        } catch (e: Exception) {
            LOG.i("echo-music ai theme failed " + e.message)
            null
        }
    }

    private fun parseAiTheme(content: String): AiDualTheme? {
        val jsonText = extractJson(content) ?: return null
        return try {
            val root = JSONObject(jsonText)
            val light = parseTheme(root.optJSONObject("light")) ?: return null
            val dark = parseTheme(root.optJSONObject("dark")) ?: return null
            AiDualTheme(light, dark)
        } catch (ignored: Exception) {
            null
        }
    }

    private fun parseTheme(obj: JSONObject?): AiTheme? {
        if (obj == null) return null
        val background = parseHexColor(obj.optString("backgroundColor")) ?: return null
        val primary = parseHexColor(obj.optString("primaryColor")) ?: return null
        val accent = parseHexColor(obj.optString("accentColor")) ?: return null
        val secondary = parseHexColor(obj.optString("secondaryColor")) ?: return null
        val name = obj.optString("name").trim().ifBlank { "AI 主题" }
        val wordColors = LinkedHashMap<String, Int>()
        val arr = obj.optJSONArray("wordColors")
        if (arr != null) {
            for (i in 0 until minOf(arr.length(), 20)) {
                val entry = arr.optJSONObject(i) ?: continue
                val word = entry.optString("word").trim()
                if (word.isEmpty()) continue
                val color = parseHexColor(entry.optString("color")) ?: continue
                wordColors[word] = color
            }
        }
        return AiTheme(name, background, primary, accent, secondary, wordColors)
    }

    private fun parseHexColor(value: String?): Int? {
        val s = value?.trim() ?: return null
        if (!s.startsWith("#")) return null
        val hex = s.substring(1)
        val full = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            else -> return null
        }
        if (!full.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return try {
            (0xFF000000.toInt() or full.toInt(16))
        } catch (ignored: NumberFormatException) {
            null
        }
    }

    private fun extractJson(content: String): String? {
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(content)?.groupValues?.getOrNull(1)?.trim()
        if (!fence.isNullOrBlank()) {
            try {
                JSONObject(fence)
                return fence
            } catch (ignored: Exception) {
            }
        }
        val balanced = findBalancedJsonObject(content)
        if (balanced != null) return balanced
        val trimmed = content.trim()
        return try {
            JSONObject(trimmed)
            trimmed
        } catch (ignored: Exception) {
            null
        }
    }

    private fun findBalancedJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            if (ch == '"') {
                inString = true
            } else if (ch == '{') {
                depth += 1
            } else if (ch == '}') {
                depth -= 1
                if (depth == 0) return text.substring(start, index + 1)
            }
        }
        return null
    }
}
