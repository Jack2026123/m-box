package com.github.tvbox.osc.reading

import org.json.JSONArray
import org.json.JSONObject

/**
 * Helpers for Legado-flavoured "JSON" that is not strict JSON: URL option
 * objects written with single quotes, unquoted keys or smart quotes
 * (`{'webView': true}`, `{webView:“true”}` both appear in real sources).
 */
object LenientJson {

    /** Parse any object/array/scalar text into org.json values, or null. */
    fun parseAny(text: String?): Any? {
        if (text.isNullOrBlank()) return null
        val t = text.trim()
        return try {
            when {
                t.startsWith("{") -> JSONObject(t)
                t.startsWith("[") -> JSONArray(t)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a lenient object literal (single quotes, unquoted keys, smart
     * quotes) into a JSONObject. Returns an empty object when unparseable.
     */
    fun parseObjectLenient(raw: String?): JSONObject {
        if (raw.isNullOrBlank()) return JSONObject()
        // org.json's own tokener is lenient enough to swallow smart quotes
        // as literal text, so when they appear they must be normalized to
        // real delimiters BEFORE any parse attempt.
        if (raw.any { it == '“' || it == '”' || it == '‘' || it == '’' }) {
            return try {
                JSONObject(normalize(raw))
            } catch (e: Exception) {
                JSONObject()
            }
        }
        val strict = try {
            JSONObject(raw.trim())
        } catch (e: Exception) {
            null
        }
        if (strict != null) return strict
        return try {
            JSONObject(normalize(raw))
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun normalize(raw: String): String {
        var s = raw.trim()
            .replace('“', '"').replace('”', '"')
            .replace('‘', '\'').replace('’', '\'')
        // single-quoted strings -> double-quoted (escape inner double quotes first)
        val sb = StringBuilder()
        var i = 0
        var inSingle = false
        var inDouble = false
        while (i < s.length) {
            val c = s[i]
            if (inSingle) {
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(c); sb.append(s[i + 1]); i += 2; continue
                }
                if (c == '\'') {
                    sb.append('"'); inSingle = false; i++; continue
                }
                if (c == '"') sb.append("\\\"") else sb.append(c)
                i++; continue
            }
            if (inDouble) {
                sb.append(c)
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(s[i + 1]); i += 2; continue
                }
                if (c == '"') inDouble = false
                i++; continue
            }
            when (c) {
                '\'' -> { sb.append('"'); inSingle = true }
                '"' -> { sb.append(c); inDouble = true }
                else -> sb.append(c)
            }
            i++
        }
        s = sb.toString()
        // unquoted keys: {key: / ,key: -> {"key":
        s = Regex("""([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)(\s*:)""").replace(s) { m ->
            "${m.groupValues[1]}\"${m.groupValues[2]}\"${m.groupValues[3]}"
        }
        // trailing commas
        s = Regex(""",\s*([}\]])""").replace(s) { m -> m.groupValues[1] }
        return s
    }

    /**
     * Java Map/List toString style: `{id=51060, name=x}`, `[a, b]`.
     * Legado binds JS `result` to the live JSON object, and real sources
     * regex against this exact format (e.g. /id=(\d+)/, /catalog_name/).
     */
    fun javaStyleString(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> {
            val sb = StringBuilder("{")
            val keys = value.keys()
            var first = true
            while (keys.hasNext()) {
                if (!first) sb.append(", ")
                first = false
                val k = keys.next()
                sb.append(k).append('=').append(javaStyleString(value.get(k)))
            }
            sb.append('}').toString()
        }
        is JSONArray -> (0 until value.length())
            .joinToString(", ", "[", "]") { javaStyleString(value.opt(it)) }
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") {
            "${it.key}=${javaStyleString(it.value)}"
        }
        is List<*> -> value.joinToString(", ", "[", "]") { javaStyleString(it) }
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    /** Stringify any JSON-ish value the Legado way (numbers without .0). */
    fun valueToString(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is Double -> if (value % 1.0 == 0.0 && !value.isInfinite()) value.toLong().toString() else value.toString()
        is Float -> valueToString(value.toDouble())
        is Number -> value.toString()
        is Boolean -> value.toString()
        is JSONArray -> (0 until value.length()).joinToString("\n") { valueToString(value.opt(it)) }
        is List<*> -> value.joinToString("\n") { valueToString(it) }
        else -> value.toString()
    }
}
