package com.github.tvbox.osc.miniapp.engine

import org.json.JSONObject
import java.util.UUID

object MiniAppScript {

    const val ALL_FUNCTIONS_END = "eval(getJsPlugin());\n"

    private val ticketByTitle = HashMap<String, String>()
    private val titleByTicket = HashMap<String, String>()

    @Synchronized
    fun generateTicket(title: String?): String {
        if (title.isNullOrEmpty()) return title ?: ""
        val existing = ticketByTitle[title]
        if (!existing.isNullOrEmpty()) return existing
        val ticket = UUID.randomUUID().toString()
        ticketByTitle[title] = ticket
        titleByTicket[ticket] = title
        return ticket
    }

    fun getRuleTitle(ticket: String?): String? {
        if (ticket.isNullOrEmpty()) return ticket
        return try {
            val title = titleByTicket[ticket]
            if (!title.isNullOrEmpty()) title else ticket
        } catch (e: Throwable) {
            ticket
        }
    }

    fun getMyRule(rule: MiniAppRule?): String {
        val json = rule?.toRuleJson()?.toString() ?: "null"
        val title = rule?.title ?: ""
        val displayName = rule?.displayName ?: ""
        val sb = StringBuilder()
        sb.append("let my_rule = '").append(MiniAppText.escapeJsString(json)).append("';\n")
        sb.append(" const MY_RULE = JSON.parse(my_rule);\n my_rule = null;\n ")
        if (MiniAppText.isNotEmpty(displayName)) {
            sb.append("const _displayName = '").append(MiniAppText.escapeJsString(displayName)).append("';\n")
        }
        sb.append("const MY_TICKET = '").append(MiniAppText.escapeJsString(generateTicket(title))).append("';\n")
        sb.append("eval(getJsPlugin());\n")
        sb.append("eval(getJsLazyPlugin());\n")
        return sb.toString()
    }

    fun generateMY(name: String, value: Any?): String {
        if (value == null) return "var $name = null;\n"
        if (value is String) return "var $name = '${MiniAppText.escapeJsString(value)}';\n"
        if (value is Int) return "var $name = $value;\n"
        val lower = name.lowercase()
        val json = when (value) {
            is JSONObject -> value.toString()
            is Map<*, *> -> JSONObject(value as Map<*, *>).toString()
            is List<*> -> org.json.JSONArray(value).toString()
            else -> value.toString()
        }
        return "var $lower = '${MiniAppText.escapeJsString(json)}';\n var $name = JSON.parse($lower);\n"
    }

    fun generateInjectMap(map: Map<String, Any?>?): String {
        if (map == null) return ""
        val sb = StringBuilder()
        for ((key, value) in map) {
            val head = "var $key = "
            when {
                value is String -> sb.append(head).append("'").append(MiniAppText.escapeJsString(value)).append("';\n")
                value == null -> sb.append(head).append("null;\n")
                value is Int || value is Float || value is Boolean -> sb.append(head).append(value).append(";\n")
                else -> {
                    val json = when (value) {
                        is JSONObject -> value.toString()
                        is Map<*, *> -> JSONObject(value as Map<*, *>).toString()
                        is List<*> -> org.json.JSONArray(value).toString()
                        else -> value.toString()
                    }
                    sb.append(head).append("JSON.parse('").append(MiniAppText.escapeJsString(json)).append("');\n")
                }
            }
        }
        return sb.toString()
    }

    fun generateMyParams(params: String?): String {
        if (MiniAppText.isEmpty(params)) return "var MY_PARAMS = {};\n"
        return "var _my_params = '${MiniAppText.escapeJsString(params)}';\n var MY_PARAMS = JSON.parse(_my_params);\n"
    }

    fun getMyType(type: String): String = "var MY_TYPE = '$type';\n"

    fun getMyCallbackKey(uuid: String): String = "var CALLBACK_KEY = '$uuid';\n"

    fun getMyUrl(url: String?): String {
        val raw = url ?: ""
        val esc = MiniAppText.escapeJsString(raw)
        return "var MY_URL = '$esc';\nvar MY_HOME = '${MiniAppText.getHome(esc)}';\n"
    }

    fun getMyInput(input: String?): String =
        "var input = '${MiniAppText.escapeJsString(input)}';\n"

    fun getMyJs(rule: String?): String {
        if (rule == null) return ""
        val idx = rule.indexOf("js:")
        if (idx < 0) return rule
        return rule.substring(0, idx) + rule.substring(idx + 3)
    }

    fun appendLineVar(script: String?): String? {
        if (script.isNullOrEmpty()) return script
        var count = 0
        for (line in script.split("\n")) {
            val trim = line.trim()
            if (trim.isEmpty()) {
                count++
                continue
            }
            if (trim.startsWith("const my_") || trim.startsWith("var my_") ||
                trim.startsWith("const MY_") || trim.startsWith("var MY_") ||
                trim.startsWith("var input =") || trim.startsWith("var CALLBACK_") ||
                trim.startsWith("eval(getJs") || trim.startsWith("const _displayName")
            ) {
                count++
                continue
            }
            break
        }
        return "const MY_PRE_LINE = $count;\n$script"
    }

    fun getReplaceJS(script: String?, sync: Boolean = false): String? {
        if (script.isNullOrEmpty()) return script
        val replaced = script.replace(
            "if (b != null && b.length() > 0) {",
            "if (b != null && b.length > 0) {",
        )
        return if (sync) replaced else "try{\n$replaced\n}catch(e){\nsetError(e);\n}"
    }

    fun assembleHome(
        rule: MiniAppRule,
        url: String,
        findRule: String,
        inject: Map<String, Any?>?,
        callbackKey: String,
    ): String {
        return "\n" + getMyRule(rule) + generateInjectMap(inject) +
            generateMyParams(rule.params) + getMyType("home") +
            getMyCallbackKey(callbackKey) + getMyUrl(url) + getMyJs(findRule)
    }

    fun assembleSearch(
        movieRule: MiniMovieRule,
        url: String,
        inject: Map<String, Any?>?,
        callbackKey: String,
    ): String {
        val rule = MiniAppRule()
        rule.title = movieRule.title
        rule.displayName = movieRule.displayName
        rule.params = movieRule.params
        rule.searchUrl = movieRule.searchUrl
        rule.searchFind = movieRule.searchFind
        rule.url = movieRule.url
        return getMyCallbackKey(callbackKey) + getMyRule(rule) + generateInjectMap(inject) +
            generateMyParams(movieRule.params) + getMyUrl(url) +
            getMyType("search") + getMyJs(movieRule.searchFind)
    }

    fun assemblePreRule(
        rule: MiniAppRule,
        callbackKey: String,
    ): String {
        return getMyCallbackKey(callbackKey) + getMyType("preHome") +
            getMyRule(rule) + generateMyParams(rule.params) + rule.preRule
    }

    fun assembleLastChapter(
        rule: MiniAppRule,
        url: String,
        lastChapterRule: String,
        inject: Map<String, Any?>?,
        callbackKey: String,
    ): String {
        return getMyRule(rule) + generateMyParams(rule.params) + generateInjectMap(inject) +
            getMyType("lastChapter") + getMyCallbackKey(callbackKey) +
            getMyUrl(url) + getMyJs(lastChapterRule)
    }

    fun assembleEval(
        code: String,
        input: String,
        callbackKey: String,
        needPlugin: Boolean,
    ): String {
        val head = getMyInput(input) +
            (if (code.contains("let my_rule = '")) "" else getMyRule(null)) +
            getMyType("eval") + getMyCallbackKey(callbackKey)
        val body = if (needPlugin) {
            head + MiniAppText.decodeConflictStr(code)
        } else {
            head + code
        }
        return getReplaceJS(appendLineVar(body)) ?: ""
    }

    fun assembleLazyEval(
        rule: MiniAppRule,
        myUrlEscaped: String,
        position: Int,
        strippedCode: String,
        base: String,
        callbackKey: String,
    ): String {
        val code = getMyRule(rule) + MiniAppText.decodeConflictStr(
            generateMY("MY_URL", myUrlEscaped) + generateMY("MY_POSITION", position) + strippedCode,
        )
        val full = getMyInput(base) + getMyType("eval") + getMyCallbackKey(callbackKey) + code
        return getReplaceJS(appendLineVar(full)) ?: ""
    }
}
