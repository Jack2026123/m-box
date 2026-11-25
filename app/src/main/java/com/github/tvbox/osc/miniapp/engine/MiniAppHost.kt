package com.github.tvbox.osc.miniapp.engine

import android.content.Context
import android.util.Log
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class MiniAppHost(private val appContext: Context) {

    class Execution(
        val rule: MiniAppRule,
        val callbackKey: String,
        val type: String,
        var resCode: String = "",
    ) {
        var items: MutableList<MiniAppListItem>? = null
        var preItems: MutableList<MiniAppListItem>? = null
        var strResult: String? = null
        var error: String? = null
        var pageTitle: String = ""
        var pageParams: String = ""
        var pagePicUrl: String = ""
        var pageReverse: Boolean = false
        val logs = ArrayList<String>()
        var hasPre: Boolean = false

        fun finalItems(): List<MiniAppListItem>? = items ?: preItems
    }

    companion object {
        const val TAG = "MiniAppHost"
        const val EXEC_TIMEOUT_MS = 30000L
        const val MAX_JS_EVAL_DEPTH = 8

        val varMap = HashMap<String, String>()

        private val jsEvalDepth = java.util.concurrent.atomic.AtomicInteger(0)

        @Synchronized
        fun clearRuleVars(title: String) {
            val prefix = "$title@"
            val it = varMap.keys.iterator()
            while (it.hasNext()) {
                if (it.next().startsWith(prefix)) it.remove()
            }
        }

        fun jsString(value: Any?): String? {
            return when (value) {
                null -> null
                is String -> value
                is Number, is Boolean -> value.toString()
                else -> value.toString()
            }
        }
    }

    private var hikerSource: String? = null
    private var pluginSource: String? = null
    private var cryptoJsCache: String? = null

    /** 供规则 eval(getCryptoJS())：返回 crypto-js 源码字符串；读不到返回空串 */
    private fun loadCryptoJsSource(): String {
        cryptoJsCache?.let { return it }
        val src = try {
            appContext.assets.open("js/lib/crypto-js.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Throwable) {
            try {
                appContext.assets.open("miniapp/crypto-js.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e2: Throwable) {
                Log.d(TAG, "loadCryptoJsSource failed: $e2")
                ""
            }
        }
        cryptoJsCache = src
        return src
    }

    /** logtest终极版: per-host task pool; fire re-evaluates the task JS
     * through this host's evalJs (fresh context, shared varMap). */
    val schedulePool = MiniAppSchedulePool(fire = { task -> evalJs(task.js, task.id) })

    var onBackRequested: (() -> Unit)? = null
    var onRefreshRequested: (() -> Unit)? = null
    private fun postToMain(action: () -> Unit) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    action()
                } catch (e: Throwable) {
                }
            }
        } catch (e: Throwable) {
        }
    }

    fun hikerJs(): String {
        hikerSource?.let { return it }
        val raw = try {
            appContext.assets.open("miniapp/Hikerurl.js").bufferedReader(Charsets.UTF_8).readText()
        } catch (e: Throwable) {
            ""
        }
        val fixed = raw.replace("let id = ", "id = ")
        hikerSource = fixed
        return fixed
    }

    fun pluginJs(): String {
        pluginSource?.let { return it }
        val raw = try {
            appContext.assets.open("miniapp/plugin.js").bufferedReader(Charsets.UTF_8).readText()
        } catch (e: Throwable) {
            ""
        }
        pluginSource = raw
        return raw
    }

    private fun toJsString(ctx: QuickJSContext, value: Any?): String? {
        return when (value) {
            null -> null
            is String -> value
            is Number, is Boolean -> value.toString()
            is JSObject -> try {
                ctx.stringify(value)
            } catch (e: Throwable) {
                value.toString()
            }
            else -> value.toString()
        }
    }

    private fun toJsJson(ctx: QuickJSContext, value: Any?): String? {
        if (value == null) return null
        if (value is String) return value
        return toJsString(ctx, value)
    }

    private fun parseItems(json: String?): List<MiniAppListItem>? {
        if (json.isNullOrEmpty()) return null
        return try {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                val out = ArrayList<MiniAppListItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(MiniAppListItem.fromJson(o))
                }
                out
            } else {
                val obj = JSONObject(trimmed)
                val data = obj.opt("data")
                if (data is JSONArray) {
                    val out = ArrayList<MiniAppListItem>()
                    for (i in 0 until data.length()) {
                        val o = data.optJSONObject(i) ?: continue
                        out.add(MiniAppListItem.fromJson(o))
                    }
                    out
                } else {
                    null
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun callbackHome(exec: Execution, dataJson: String?, isPre: Boolean) {
        if (dataJson == null) {
            exec.error = "setHomeResult is not JSONObject"
            return
        }
        val items = parseItems(dataJson)
        if (items == null) {
            exec.error = "分类结果解析失败！请检查规则：setHomeResult is not JSONObject"
            return
        }
        if (isPre) {
            exec.preItems = items.toMutableList()
            exec.hasPre = true
        } else {
            if (exec.hasPre && exec.preItems != null) {
                val merged = ArrayList<MiniAppListItem>(exec.preItems!!)
                merged.addAll(items)
                exec.items = merged
                exec.preItems = null
                exec.hasPre = false
            } else {
                exec.items = items.toMutableList()
            }
        }
    }

    private fun callbackSearch(exec: Execution, dataJson: String?) {
        if (dataJson == null) {
            exec.error = "setSearchResult is not JSONObject"
            return
        }
        val items = parseItems(dataJson)
        if (items == null) {
            exec.error = "搜索结果解析失败！请检查规则：setSearchResult is not JSONObject"
            return
        }
        exec.items = items.toMutableList()
    }

    private fun callbackStr(exec: Execution, data: Any?, ctx: QuickJSContext) {
        val s = toJsString(ctx, data)
        if (s == null) {
            exec.error = exec.rule.title + "---解析失败！请检查规则"
            return
        }
        exec.strResult = s
    }

    private fun bindHost(ctx: QuickJSContext, exec: Execution) {
        val global = ctx.getGlobalObject()
        fun fn(name: String, impl: (Array<out Any?>) -> Any?) {
            ctx.setProperty(
                global,
                name,
                object : JSCallFunction {
                    override fun call(vararg args: Any?): Any? {
                        return try {
                            impl(args)
                        } catch (e: Throwable) {
                            Log.d(TAG, "host fn $name failed: $e")
                            null
                        }
                    }
                },
            )
        }

        fn("__host_setHomeResult") { args ->
            val data = toJsJson(ctx, args.getOrNull(0))
            val type = jsString(args.getOrNull(3)) ?: exec.type
            if (type == "search") callbackSearch(exec, data) else callbackHome(exec, data, false)
            null
        }
        fn("__host_setSearchResult") { args ->
            val data = toJsJson(ctx, args.getOrNull(0))
            val type = jsString(args.getOrNull(3)) ?: exec.type
            if (type == "home") callbackHome(exec, data, false) else callbackSearch(exec, data)
            null
        }
        fn("__host_setResult") { args ->
            val data = toJsJson(ctx, args.getOrNull(0))
            val type = jsString(args.getOrNull(3)) ?: exec.type
            when (type) {
                "lastChapter" -> callbackStr(exec, args.getOrNull(0), ctx)
                "search" -> callbackSearch(exec, data)
                else -> callbackHome(exec, data, false)
            }
            null
        }
        fn("__host_setPreResult") { args ->
            val data = toJsJson(ctx, args.getOrNull(0))
            val type = jsString(args.getOrNull(3)) ?: exec.type
            if (type == "lastChapter" || type == "search") {
                null
            } else {
                callbackHome(exec, data, true)
                null
            }
        }
        fn("__host_setLastChapterResult") { args ->
            callbackStr(exec, args.getOrNull(0), ctx)
            null
        }
        fn("__host_setStrResult") { args ->
            callbackStr(exec, args.getOrNull(0), ctx)
            null
        }
        fn("__host_setError") { args ->
            var msg = toJsString(ctx, args.getOrNull(0)) ?: ""
            if (msg.isBlank() || msg == "{}") {
                msg = "执行失败：规则报错但未返回有效错误信息（${exec.type}）"
            }
            if (msg.length > 2000) msg = msg.substring(0, 2000)
            exec.error = msg
            null
        }
        fn("__host_parseDom") { args ->
            val html = jsString(args.getOrNull(0)) ?: ""
            val rule = jsString(args.getOrNull(1)) ?: ""
            val urlKey = jsString(args.getOrNull(2)) ?: ""
            try {
                MiniAppSelector.parseDomForUrl(html, rule, urlKey) { code, input -> evalJs(code, input) }
            } catch (e: Throwable) {
                ""
            }
        }
        fn("__host_parseDomForArray") { args ->
            val html = jsString(args.getOrNull(0)) ?: ""
            val rule = jsString(args.getOrNull(1)) ?: ""
            try {
                val list = MiniAppSelector.parseDomForList(html, rule) { code, input -> evalJs(code, input) }
                JSONArray(list).toString()
            } catch (e: Throwable) {
                "[]"
            }
        }
        fn("__host_parseDomForHtml") { args ->
            val html = jsString(args.getOrNull(0)) ?: ""
            val rule = jsString(args.getOrNull(1)) ?: ""
            try {
                MiniAppSelector.parseDomForUrl(html, rule, "") { code, input -> evalJs(code, input) }
            } catch (e: Throwable) {
                ""
            }
        }
        fn("__host__pd") { args ->
            val html = jsString(args.getOrNull(0)) ?: ""
            val rule = jsString(args.getOrNull(1)) ?: ""
            val urlKey = jsString(args.getOrNull(2)) ?: ""
            try {
                MiniAppSelector.parseDomForUrl(html, rule, urlKey) { code, input -> evalJs(code, input) }
            } catch (e: Throwable) {
                ""
            }
        }
        fn("__host__pdfh") { args ->
            val html = jsString(args.getOrNull(0)) ?: ""
            val rule = jsString(args.getOrNull(1)) ?: ""
            try {
                MiniAppSelector.parseDomForUrl(html, rule, "") { code, input -> evalJs(code, input) }
            } catch (e: Throwable) {
                ""
            }
        }
        fn("__host__pdfa") { args ->
            val html = jsString(args.getOrNull(0)) ?: ""
            val rule = jsString(args.getOrNull(1)) ?: ""
            try {
                val list = MiniAppSelector.parseDomForArray(html, rule)
                JSONArray(list).toString()
            } catch (e: Throwable) {
                "[]"
            }
        }
        fn("__host__pdfl") { args ->
            val html = jsString(args.getOrNull(0)) ?: ""
            val listRule = jsString(args.getOrNull(1)) ?: ""
            val urlRule = jsString(args.getOrNull(2)) ?: ""
            val extraRule = jsString(args.getOrNull(3)) ?: ""
            val extraUrlKey = jsString(args.getOrNull(4)) ?: ""
            try {
                val list = MiniAppSelector.parseDomForList5(html, listRule, urlRule, extraRule, extraUrlKey) { code, input -> evalJs(code, input) }
                JSONArray(list).toString()
            } catch (e: Throwable) {
                "[]"
            }
        }
        fn("__host_getUrl") { args -> jsString(args.getOrNull(0)) ?: "" }
        fn("__host_getParam") { args ->
            val key = jsString(args.getOrNull(0)) ?: ""
            val def = jsString(args.getOrNull(1)) ?: ""
            val urlKey = jsString(args.getOrNull(2)) ?: ""
            MiniAppHttp.getParamsByUrl(urlKey)[key] ?: def
        }
        fn("__host_getResCode") { args -> exec.resCode }
        fn("__host_fetch") { args ->
            val url = jsString(args.getOrNull(0)) ?: ""
            val options = toJsJson(ctx, args.getOrNull(1))
            hostFetch(url, options, null, exec)
        }
        fn("__host_request") { args ->
            val url = jsString(args.getOrNull(0)) ?: ""
            val options = toJsJson(ctx, args.getOrNull(1))
            hostFetch(url, options, null, exec)
        }
        fn("__host_fetchPC") { args ->
            val url = jsString(args.getOrNull(0)) ?: ""
            val options = toJsJson(ctx, args.getOrNull(1))
            hostFetch(url, options, MiniAppUa.PC, exec)
        }
        fn("__host_post") { args ->
            val url = jsString(args.getOrNull(0)) ?: ""
            val options = toJsJson(ctx, args.getOrNull(1))
            hostPost(url, options, null, exec)
        }
        fn("__host_postPC") { args ->
            val url = jsString(args.getOrNull(0)) ?: ""
            val options = toJsJson(ctx, args.getOrNull(1))
            hostPost(url, options, MiniAppUa.PC, exec)
        }
        fn("__host_postRequest") { args ->
            val url = jsString(args.getOrNull(0)) ?: ""
            val options = toJsJson(ctx, args.getOrNull(1))
            hostPost(url, options, null, exec)
        }
        fn("__host_getVar") { args ->
            val key = jsString(args.getOrNull(0)) ?: ""
            val def = jsString(args.getOrNull(1)) ?: ""
            synchronized(varMap) { varMap[key] } ?: def
        }
        fn("__host_require") { args ->
            val name = jsString(args.getOrNull(0)) ?: ""
            findRequirePage(exec.rule, name)?.rule ?: ""
        }
        fn("__host_putVar") { args ->
            val first = args.getOrNull(0)
            val second = args.getOrNull(1)
            if (second != null) {
                val key = jsString(first) ?: ""
                val value = toJsJson(ctx, second) ?: ""
                if (key.isNotEmpty()) {
                    synchronized(varMap) { varMap[key] = value }
                }
            } else if (first != null) {
                val json = toJsJson(ctx, first)
                if (json != null) {
                    try {
                        val obj = JSONObject(json)
                        val key = obj.optString("key")
                        val value = obj.optString("value")
                        if (key.isNotEmpty()) {
                            synchronized(varMap) { varMap[key] = value }
                        }
                    } catch (e: Throwable) {
                    }
                }
            }
            null
        }
        fn("__host_putVar2") { args ->
            val key = jsString(args.getOrNull(0)) ?: ""
            val value = toJsJson(ctx, args.getOrNull(1)) ?: ""
            if (key.isNotEmpty()) {
                synchronized(varMap) { varMap[key] = value }
            }
            null
        }
        fn("__host_clearVar") { args ->
            val key = jsString(args.getOrNull(0)) ?: ""
            synchronized(varMap) { varMap.remove(key) }
            null
        }
        fn("__host_getMyVar") { args ->
            val key = jsString(args.getOrNull(0)) ?: ""
            val def = jsString(args.getOrNull(1)) ?: ""
            val title = MiniAppScript.getRuleTitle(jsString(args.getOrNull(2))) ?: ""
            synchronized(varMap) { varMap["$title@$key"] } ?: def
        }
        fn("__host_putMyVar") { args ->
            val key = jsString(args.getOrNull(0)) ?: ""
            val value = toJsJson(ctx, args.getOrNull(1)) ?: ""
            val title = MiniAppScript.getRuleTitle(jsString(args.getOrNull(2))) ?: ""
            synchronized(varMap) { varMap["$title@$key"] = value }
            null
        }
        fn("__host_clearMyVar") { args ->
            val key = jsString(args.getOrNull(0)) ?: ""
            val title = MiniAppScript.getRuleTitle(jsString(args.getOrNull(1))) ?: ""
            synchronized(varMap) { varMap.remove("$title@$key") }
            null
        }
        fn("__host_listMyVarKeys") { args ->
            val title = MiniAppScript.getRuleTitle(jsString(args.getOrNull(0))) ?: ""
            val prefix = "$title@"
            val keys = synchronized(varMap) {
                varMap.keys.filter { it.startsWith(prefix) }.map { it.substring(prefix.length) }
            }
            JSONArray(keys).toString()
        }
        fn("__host_getItem") { args ->
            val key = jsString(args.getOrNull(0)) ?: ""
            val def = jsString(args.getOrNull(1)) ?: ""
            val title = MiniAppScript.getRuleTitle(jsString(args.getOrNull(2))) ?: ""
            synchronized(varMap) { varMap["$title@$key"] } ?: def
        }
        fn("__host_setItem") { args ->
            val key = jsString(args.getOrNull(0)) ?: ""
            val value = toJsJson(ctx, args.getOrNull(1)) ?: ""
            val title = MiniAppScript.getRuleTitle(jsString(args.getOrNull(2))) ?: ""
            synchronized(varMap) { varMap["$title@$key"] = value }
            null
        }
        fn("__host_clearItem") { args ->
            val key = jsString(args.getOrNull(0)) ?: ""
            val title = MiniAppScript.getRuleTitle(jsString(args.getOrNull(1))) ?: ""
            synchronized(varMap) { varMap.remove("$title@$key") }
            null
        }
        fn("__host_setPublicItem") { args ->
            val key = jsString(args.getOrNull(0)) ?: ""
            val value = toJsJson(ctx, args.getOrNull(1)) ?: ""
            synchronized(varMap) { varMap[key] = value }
            null
        }
        fn("__host_getPublicItem") { args ->
            val key = jsString(args.getOrNull(0)) ?: ""
            val def = jsString(args.getOrNull(1)) ?: ""
            synchronized(varMap) { varMap[key] } ?: def
        }
        fn("__host_clearPublicItem") { args ->
            val key = jsString(args.getOrNull(0)) ?: ""
            synchronized(varMap) { varMap.remove(key) }
            null
        }
        fn("__host_encodeStr") { args ->
            val s = jsString(args.getOrNull(0))
            val charset = jsString(args.getOrNull(1)) ?: "UTF-8"
            if (s == null) "" else MiniAppHttp.encodeUrl(s, charset) ?: s
        }
        fn("__host_decodeStr") { args ->
            val s = jsString(args.getOrNull(0))
            val charset = jsString(args.getOrNull(1)) ?: "UTF-8"
            if (s == null) "" else MiniAppHttp.decodeUrl(s, charset) ?: s
        }
        fn("__host_base64Encode") { args ->
            val s = jsString(args.getOrNull(0)) ?: return@fn ""
            MiniAppText.base64Encode(s)
        }
        fn("__host_base64Decode") { args ->
            val s = jsString(args.getOrNull(0)) ?: return@fn ""
            MiniAppText.base64Decode(s)
        }
        fn("__host_md5") { args ->
            val s = jsString(args.getOrNull(0)) ?: ""
            MiniAppText.md5(s)
        }
        fn("__host_hexToBase64") { args ->
            val s = jsString(args.getOrNull(0)) ?: ""
            val bytes = MiniAppText.hexToBytes(s) ?: return@fn ""
            MiniAppText.base64EncodeBytes(bytes)
        }
        fn("__host_hexToBytes") { args ->
            val s = jsString(args.getOrNull(0)) ?: ""
            val bytes = MiniAppText.hexToBytes(s) ?: ByteArray(0)
            try {
                val arr: JSArray = ctx.createNewJSArray()
                for (i in bytes.indices) {
                    arr.set((bytes[i].toInt() and 0xFF).toDouble(), i)
                }
                arr
            } catch (e: Throwable) {
                null
            }
        }
        fn("__host_aesEncode") { args ->
            val key = jsString(args.getOrNull(0))
            val text = jsString(args.getOrNull(1))
            if (key == null || text == null) "" else aesEncrypt(key, text) ?: ""
        }
        fn("__host_aesDecode") { args ->
            val key = jsString(args.getOrNull(0))
            val text = jsString(args.getOrNull(1))
            if (key == null || text == null) "" else aesDecrypt(key, text) ?: ""
        }
        fn("__host_joinUrl") { args ->
            val o1 = jsString(args.getOrNull(0))
            val o2 = jsString(args.getOrNull(1))
            if (o1 == null || o2 == null) "" else MiniAppText.joinUrl(o1, o2)
        }
        fn("__host_getUaObject") { args ->
            JSONObject().apply {
                put("mobileUa", MiniAppUa.MOBILE)
                put("pcUa", MiniAppUa.PC)
            }.toString()
        }
        fn("__host_getColTypes") { args ->
            JSONArray(MiniAppColType.getCodeArray()).toString()
        }
        fn("__host_getHome") { args ->
            val url = jsString(args.getOrNull(0)) ?: ""
            val urlKey = jsString(args.getOrNull(1)) ?: ""
            if (url.isNotEmpty()) MiniAppText.getHome(url) ?: "" else MiniAppText.getHome(urlKey) ?: ""
        }
        fn("__host_log") { args ->
            val msg = toJsString(ctx, args.getOrNull(0)) ?: ""
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date())
            var title = ""
            val ruleArg = toJsJson(ctx, args.getOrNull(1))
            if (!ruleArg.isNullOrEmpty()) {
                try {
                    title = JSONObject(ruleArg).optString("title")
                } catch (e: Throwable) {
                }
            }
            val line = if (title.isNotEmpty()) "$time: $title: $msg" else "$time: $msg"
            if (exec.logs.size >= 10000) exec.logs.removeAt(0)
            exec.logs.add(line)
            Log.d(TAG, line)
            null
        }
        fn("__host_setPageTitle") { args ->
            exec.pageTitle = jsString(args.getOrNull(0)) ?: ""
            null
        }
        fn("__host_updateItem") { args ->
            val id = jsString(args.getOrNull(0)) ?: ""
            val itemJson = toJsJson(ctx, args.getOrNull(1))
            val items = exec.items ?: return@fn null
            val idx = items.indexOfFirst { it.url == id || it.title == id }
            if (idx >= 0 && itemJson != null) {
                try {
                    items[idx] = MiniAppListItem.fromJson(JSONObject(itemJson))
                } catch (e: Throwable) {
                }
            }
            null
        }
        fn("__host_addItemAfter") { args ->
            val id = jsString(args.getOrNull(0)) ?: ""
            val itemJson = toJsJson(ctx, args.getOrNull(1))
            val items = exec.items ?: return@fn null
            val idx = items.indexOfFirst { it.url == id || it.title == id }
            if (idx >= 0 && itemJson != null) {
                try {
                    items.add(idx + 1, MiniAppListItem.fromJson(JSONObject(itemJson)))
                } catch (e: Throwable) {
                }
            }
            null
        }
        fn("__host_addItemBefore") { args ->
            val id = jsString(args.getOrNull(0)) ?: ""
            val itemJson = toJsJson(ctx, args.getOrNull(1))
            val items = exec.items ?: return@fn null
            val idx = items.indexOfFirst { it.url == id || it.title == id }
            if (idx >= 0 && itemJson != null) {
                try {
                    items.add(idx, MiniAppListItem.fromJson(JSONObject(itemJson)))
                } catch (e: Throwable) {
                }
            }
            null
        }
        fn("__host_deleteItem") { args ->
            val id = jsString(args.getOrNull(0)) ?: ""
            val items = exec.items ?: return@fn null
            items.removeAll { it.url == id || it.title == id }
            null
        }
        fn("__host_deleteItemByCls") { args ->
            val cls = jsString(args.getOrNull(0)) ?: ""
            val items = exec.items ?: return@fn null
            items.removeAll { it.type == cls }
            null
        }
        fn("__host__findItem") { args ->
            val id = jsString(args.getOrNull(0)) ?: ""
            val items = exec.items ?: return@fn "null"
            val found = items.firstOrNull { it.url == id || it.title == id }
            found?.toJson()?.toString() ?: "null"
        }
        fn("__host__findItemsByCls") { args ->
            val cls = jsString(args.getOrNull(0)) ?: ""
            val items = exec.items ?: return@fn "[]"
            val arr = JSONArray()
            for (it in items) {
                if (it.type == cls) arr.put(it.toJson())
            }
            arr.toString()
        }
        fn("__host_getJsPlugin") { args -> hikerJs() }
        fn("__host_getJsLazyPlugin") { args -> pluginJs() }
        fn("__host_fileExist") { args ->
            val path = jsString(args.getOrNull(0)) ?: ""
            MiniAppFiles.exists(appContext, path)
        }
        fn("__host_getPath") { args ->
            val path = jsString(args.getOrNull(0)) ?: ""
            MiniAppFiles.absolutePath(appContext, path)
        }
        fn("__host_fetchCache") { args ->
            val path = jsString(args.getOrNull(0)) ?: ""
            if (MiniAppFiles.isHikerFilesUrl(path) || path.startsWith("hiker://files")) {
                MiniAppFiles.readText(appContext, path)
            } else {
                ""
            }
        }
        fn("__host_writeFile") { args ->
            val path = jsString(args.getOrNull(0)) ?: ""
            val body = jsString(args.getOrNull(1)) ?: ""
            MiniAppFiles.writeText(appContext, path, body)
            null
        }
        fn("__host_getCryptoJS") { args ->
            loadCryptoJsSource()
        }
        fn("__host_readFile") { args ->
            val path = jsString(args.getOrNull(0)) ?: ""
            MiniAppFiles.readText(appContext, path)
        }
        fn("__host_getMyUrl") { args ->
            val url = jsString(args.getOrNull(0)) ?: ""
            "var MY_URL = '" + MiniAppText.escapeJsString(url) + "';\nvar MY_HOME = '" + (MiniAppText.getHome(url) ?: "") + "';\n"
        }
        fn("__host_getPageTitle") { args -> exec.pageTitle }
        fn("__host_back") { args ->
            postToMain { onBackRequested?.invoke() }
            null
        }
        fn("__host_refreshPage") { args ->
            postToMain { onRefreshRequested?.invoke() }
            null
        }
        fn("__host_toast") { args ->
            val msg = jsString(args.getOrNull(0)) ?: ""
            if (msg.isNotEmpty()) {
                postToMain {
                    try {
                        android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Throwable) {
                    }
                }
            }
            null
        }
        fn("__host_getCookie") { args ->
            val url = jsString(args.getOrNull(0)) ?: ""
            MiniAppHttp.cookieHeader(exec.rule.title, url)
        }
        fn("__host_fetchCookie") { args ->
            val url = jsString(args.getOrNull(0)) ?: ""
            val options = toJsJson(ctx, args.getOrNull(1))
            MiniAppHttp.fetchCookieHeader(url, optionsHeaders(hostFetchOptions(options)))
        }
        fn("__host_evalLazyRule") { args ->
            val firstJson = toJsJson(ctx, args.getOrNull(0))
            val (codeRaw, urlRaw) = if (firstJson != null && firstJson.trimStart().startsWith("{")) {
                (jsString(args.getOrNull(1)) ?: "") to (jsString(args.getOrNull(2)) ?: "")
            } else {
                (jsString(args.getOrNull(0)) ?: "") to (jsString(args.getOrNull(1)) ?: "")
            }
            val myUrl = urlRaw.ifEmpty { exec.rule.url }
            val prefix = when {
                codeRaw.startsWith(".js:") -> ".js:"
                codeRaw.startsWith("js:") -> "js:"
                else -> null
            }
            if (prefix != null) {
                evalLazy(exec.rule, myUrl, 0, codeRaw.removePrefix(prefix), myUrl)
            } else {
                evalJs(codeRaw, myUrl)
            }
        }
        fn("__host_setLastChapterRule") { args ->
            exec.rule.lastChapterRule = jsString(args.getOrNull(0)) ?: ""
            null
        }
        fn("__host_setPageParams") { args ->
            exec.pageParams = toJsJson(ctx, args.getOrNull(0)) ?: ""
            null
        }
        fn("__host_setPagePicUrl") { args ->
            exec.pagePicUrl = jsString(args.getOrNull(0)) ?: ""
            null
        }
        fn("__host_setPageReverse") { args ->
            exec.pageReverse = when (val v = args.getOrNull(0)) {
                is Boolean -> v
                is String -> v.equals("true", ignoreCase = true)
                else -> false
            }
            null
        }
        fn("__host_xpath") { args ->
            val html = jsString(args.getOrNull(0)) ?: ""
            val expr = jsString(args.getOrNull(1)) ?: ""
            MiniAppSelector.xpath(html, expr)
        }
        fn("__host_xpathArray") { args ->
            val html = jsString(args.getOrNull(0)) ?: ""
            val expr = jsString(args.getOrNull(1)) ?: ""
            JSONArray(MiniAppSelector.xpathArray(html, expr)).toString()
        }
        // logtest终极版 registerTask/unRegisterTask: real SchedulePool
        // (海阔 JSEngine.schedulePool 对标, 见 MiniAppSchedulePool).
        // Task JS is a self-contained string, re-evaluated per tick
        // through this host's evalJs; no kept-alive context needed.
        fn("__host_registerTask") { args ->
            var id = jsString(args.getOrNull(0))
            if (id.isNullOrEmpty() || id == "null" || id == "undefined") {
                id = java.util.UUID.randomUUID().toString()
            }
            val rawTime = args.getOrNull(1)
            var interval = when (rawTime) {
                is Number -> rawTime.toLong()
                else -> 10000L
            }
            if (interval < 500L) interval = 500L
            val js = jsString(args.getOrNull(2)) ?: ""
            schedulePool.add(
                exec.rule.title,
                MiniAppTask(id = id, intervalMs = interval, js = js),
            )
            id
        }
        fn("__host_unRegisterTask") { args ->
            val id = jsString(args.getOrNull(0)) ?: ""
            schedulePool.removeById(id)
            true
        }
    }

    private fun hostFetchOptions(optionsJson: String?): Map<String, Any?> {
        if (optionsJson.isNullOrEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(optionsJson)
            val out = HashMap<String, Any?>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.opt(k)
            }
            out
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    private fun optionsHeaders(options: Map<String, Any?>): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        val raw = options["headers"]
        if (raw is JSONObject) {
            val keys = raw.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                headers[k] = raw.optString(k)
            }
        }
        return headers
    }

    private fun hostFetch(url: String, optionsJson: String?, forceUa: String?, exec: Execution): String {
        if (MiniAppFiles.isHikerFilesUrl(url) || url.startsWith("hiker://files")) {
            return MiniAppFiles.readText(appContext, url)
        }
        if (url.startsWith("hiker://page/")) {
            return resolvePageModule(url, exec)
        }
        val options = hostFetchOptions(optionsJson)
        val headers = optionsHeaders(options).toMutableMap()
        if (forceUa != null && headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers["User-Agent"] = forceUa
        }
        val method = (options["method"] as? String) ?: "GET"
        val timeout = (options["timeout"] as? Number)?.toLong() ?: MiniAppHttp.READ_TIMEOUT_MS
        val charset = (options["charset"] as? String) ?: "UTF-8"
        val followRedirects = when (val r = options["redirect"]) {
            is Boolean -> r
            else -> when (val f = options["followRedirects"]) {
                is Boolean -> f
                else -> true
            }
        }
        // fetch 的 POST body（海阔 fetch 与 post 同款：body 可为对象或字串；
        // 62 之前 hostFetch 直接丢了 body，剧果取 token 的 POST 拿不到 token）。
        val bodyOpt = options["body"] ?: options["data"]
        val isJsonContent = headers.entries.any {
            it.key.equals("Content-Type", ignoreCase = true) &&
                it.value.contains("application/json", ignoreCase = true)
        }
        val formParams: Map<String, String>? = when (bodyOpt) {
            is JSONObject -> if (isJsonContent) null else {
                val map = LinkedHashMap<String, String>()
                val keys = bodyOpt.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = bodyOpt.optString(k)
                }
                map
            }
            else -> null
        }
        val rawBody: String? = when (bodyOpt) {
            is String -> bodyOpt
            is JSONObject -> if (isJsonContent) bodyOpt.toString() else null
            else -> null
        }
        val rawBodyMediaType: String? = if (bodyOpt is JSONObject && isJsonContent) {
            "application/json"
        } else {
            null
        }
        return try {
            val result = MiniAppHttp.fetch(
                url = url,
                headers = headers,
                method = method,
                formParams = formParams,
                rawBody = rawBody,
                rawBodyMediaType = rawBodyMediaType,
                charset = charset,
                ruleTitle = exec.rule.title,
                followRedirects = followRedirects,
                timeoutMs = timeout,
            )
            // withHeaders:true 回海阔同款 JSON 字串：{body, headers:{名:[值]}}；
            // 62 之前只回正文，剧果懒 JS 的 r.body 取不到直接空。
            if (options["withHeaders"] == true || options["withHeaders"] == "true") {
                val out = JSONObject()
                out.put("body", result.body)
                val h = JSONObject()
                for ((k, v) in result.headersList) {
                    val arr = org.json.JSONArray()
                    for (s in v) arr.put(s)
                    h.put(k, arr)
                }
                out.put("headers", h)
                out.toString()
            } else {
                result.body
            }
        } catch (e: Throwable) {
            Log.d(TAG, "hostFetch failed: $e")
            ""
        }
    }

    private fun hostPost(url: String, optionsJson: String?, forceUa: String?, exec: Execution): String {
        if (MiniAppFiles.isHikerFilesUrl(url) || url.startsWith("hiker://files")) {
            return MiniAppFiles.readText(appContext, url)
        }
        if (url.startsWith("hiker://page/")) {
            return resolvePageModule(url, exec)
        }
        val options = hostFetchOptions(optionsJson)
        val headers = optionsHeaders(options).toMutableMap()
        if (forceUa != null && headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers["User-Agent"] = forceUa
        }
        val bodyOpt = options["body"] ?: options["data"]
        val formParams: Map<String, String>? = when (bodyOpt) {
            is JSONObject -> {
                val map = LinkedHashMap<String, String>()
                val keys = bodyOpt.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = bodyOpt.optString(k)
                }
                map
            }
            else -> null
        }
        val rawBody: String? = when (bodyOpt) {
            is String -> bodyOpt
            else -> null
        }
        val timeout = (options["timeout"] as? Number)?.toLong() ?: MiniAppHttp.READ_TIMEOUT_MS
        val charset = (options["charset"] as? String) ?: "UTF-8"
        return try {
            MiniAppHttp.fetch(
                url = url,
                headers = headers,
                method = "POST",
                formParams = formParams,
                rawBody = rawBody,
                charset = charset,
                ruleTitle = exec.rule.title,
                timeoutMs = timeout,
            ).body
        } catch (e: Throwable) {
            Log.d(TAG, "hostPost failed: $e")
            ""
        }
    }

    private fun resolvePageModule(url: String, exec: Execution): String {
        return try {
            var path = url.removePrefix("hiker://page/")
            val qIndex = path.indexOf('?')
            if (qIndex >= 0) path = path.substring(0, qIndex)
            val semiIndex = path.indexOf(';')
            if (semiIndex >= 0) path = path.substring(0, semiIndex)
            val page = exec.rule.pageList.firstOrNull { it.path == path }
            if (page == null) {
                ""
            } else {
                JSONObject().apply { put("rule", page.rule) }.toString()
            }
        } catch (e: Throwable) {
            ""
        }
    }

    private fun aesEncrypt(key: String, text: String): String? {
        return try {
            val padded = if (key.length >= 32) key else key + "0".repeat(32 - key.length)
            val keySpec = javax.crypto.spec.SecretKeySpec(padded.toByteArray(Charsets.UTF_8), "AES")
            val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec)
            MiniAppText.base64EncodeBytes(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
        } catch (e: Throwable) {
            null
        }
    }

    private fun aesDecrypt(key: String, text: String): String? {
        return try {
            val padded = if (key.length >= 32) key else key + "0".repeat(32 - key.length)
            val keySpec = javax.crypto.spec.SecretKeySpec(padded.toByteArray(Charsets.UTF_8), "AES")
            val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec)
            val bytes = MiniAppText.base64DecodeBytes(text) ?: return null
            String(cipher.doFinal(bytes), Charsets.UTF_8)
        } catch (e: Throwable) {
            null
        }
    }

    internal fun prelude(): String {
        val sb = StringBuilder()
        fun s(name: String, params: String, body: String) {
            sb.append("function ").append(name).append("(").append(params).append("){")
                .append(body).append("}\n")
        }
        fun strRet(name: String, params: String, call: String) {
            s(name, params, "var retStr = $call; return retStr == null ? retStr : retStr + '';")
        }
        fun jsonRet(name: String, params: String, call: String) {
            s(name, params, "var retStr = $call; return JSON.parse(retStr);")
        }
        fun voidFn(name: String, params: String, call: String) {
            s(name, params, "$call;")
        }
        voidFn("setHomeResult", "param0", "__host_setHomeResult(param0, CALLBACK_KEY, MY_RULE, MY_TYPE)")
        voidFn("setSearchResult", "param0", "__host_setSearchResult(param0, CALLBACK_KEY, MY_RULE, MY_TYPE)")
        voidFn("setResult", "param0", "__host_setResult(param0, CALLBACK_KEY, MY_RULE, MY_TYPE)")
        voidFn("setPreResult", "param0", "__host_setPreResult(param0, CALLBACK_KEY, MY_RULE, MY_TYPE)")
        voidFn("setLastChapterResult", "param0", "__host_setLastChapterResult(param0, CALLBACK_KEY, MY_RULE)")
        voidFn("setStrResult", "param0", "__host_setStrResult(param0, CALLBACK_KEY, MY_RULE)")
        voidFn("setError", "param0", "__host_setError(param0 && param0.message ? param0.message : param0, CALLBACK_KEY, MY_RULE)")
        sb.append("var error = setError;\n")
        strRet("parseDom", "param0, param1, param2", "__host_parseDom(param0, param1, param2 = param2 || MY_URL)")
        sb.append("var pd = parseDom;\n")
        jsonRet("parseDomForArray", "param0, param1", "__host_parseDomForArray(param0, param1)")
        sb.append("var pdfa = parseDomForArray;\n")
        strRet("parseDomForHtml", "param0, param1", "__host_parseDomForHtml(param0, param1)")
        sb.append("var pdfh = parseDomForHtml;\n")
        strRet("_pd", "param0, param1, param2", "__host__pd(param0, param1, param2 = param2 || MY_URL)")
        strRet("_pdfh", "param0, param1", "__host__pdfh(param0, param1)")
        jsonRet("_pdfa", "param0, param1", "__host__pdfa(param0, param1)")
        jsonRet("_pdfl", "param0, param1, param2, param3, param4", "__host__pdfl(param0, param1, param2, param3, param4)")
        strRet("getUrl", "param0", "__host_getUrl(param0 = param0 || MY_URL)")
        strRet("getParam", "param0, param1, param2", "__host_getParam(param0, param1, param2 = param2 || MY_URL)")
        strRet("getResCode", "param0", "__host_getResCode(param0 = CALLBACK_KEY)")
        sb.append("var getCode = getResCode;\n")
        strRet("fetch", "param0, param1", "__host_fetch(param0, param1)")
        strRet("request", "param0, param1, param2", "__host_request(param0, param1, param2 = MY_RULE)")
        strRet("fetchPC", "param0, param1, param2", "__host_fetchPC(param0, param1, param2 = MY_RULE)")
        strRet("post", "param0, param1, param2", "__host_post(param0, param1, param2)")
        strRet("postPC", "param0, param1, param2", "__host_postPC(param0, param1, param2)")
        strRet("postRequest", "param0, param1, param2", "__host_postRequest(param0, param1, param2)")
        strRet("getVar", "param0, param1", "__host_getVar(param0, param1)")
        voidFn("putVar", "param0, param1", "__host_putVar(param0, param1)")
        voidFn("putVar2", "param0, param1", "__host_putVar2(param0, param1)")
        voidFn("clearVar", "param0", "__host_clearVar(param0)")
        strRet("getMyVar", "param0, param1, param2", "__host_getMyVar(param0, param1, param2 = MY_TICKET)")
        voidFn("putMyVar", "param0, param1, param2", "__host_putMyVar(param0, param1, param2 = MY_TICKET)")
        voidFn("clearMyVar", "param0, param1", "__host_clearMyVar(param0, param1 = MY_TICKET)")
        jsonRet("listMyVarKeys", "param0", "__host_listMyVarKeys(param0 = MY_TICKET)")
        strRet("getItem", "param0, param1, param2", "__host_getItem(param0, param1, param2 = MY_TICKET)")
        voidFn("setItem", "param0, param1, param2", "__host_setItem(param0, param1, param2 = MY_TICKET)")
        voidFn("clearItem", "param0, param1", "__host_clearItem(param0, param1 = MY_TICKET)")
        voidFn("setPublicItem", "param0, param1", "__host_setPublicItem(param0, param1)")
        strRet("getPublicItem", "param0, param1", "__host_getPublicItem(param0, param1)")
        voidFn("clearPublicItem", "param0", "__host_clearPublicItem(param0)")
        strRet("encodeStr", "param0, param1", "__host_encodeStr(param0, param1 = param1 || \"UTF-8\")")
        strRet("decodeStr", "param0, param1", "__host_decodeStr(param0, param1 = param1 || \"UTF-8\")")
        strRet("base64Encode", "param0", "__host_base64Encode(param0)")
        strRet("base64Decode", "param0", "__host_base64Decode(param0)")
        strRet("md5", "param0", "__host_md5(param0)")
        strRet("hexToBase64", "param0", "__host_hexToBase64(param0)")
        s("hexToBytes", "param0", "return __host_hexToBytes(param0);")
        strRet("aesEncode", "param0, param1", "__host_aesEncode(param0, param1)")
        strRet("aesDecode", "param0, param1", "__host_aesDecode(param0, param1)")
        strRet("joinUrl", "param0, param1", "__host_joinUrl(param0, param1)")
        strRet("getUaObject", "", "__host_getUaObject()")
        jsonRet("getColTypes", "", "__host_getColTypes()")
        strRet("getHome", "param0, param1", "__host_getHome(param0, param1 = param1 || MY_URL)")
        voidFn("log", "param0, param1", "__host_log(param0, param1 = MY_RULE)")
        voidFn("setPageTitle", "param0", "__host_setPageTitle(param0)")
        voidFn("updateItem", "param0, param1", "__host_updateItem(param0, param1)")
        voidFn("addItemAfter", "param0, param1", "__host_addItemAfter(param0, param1)")
        voidFn("addItemBefore", "param0, param1", "__host_addItemBefore(param0, param1)")
        voidFn("deleteItem", "param0, param1", "__host_deleteItem(param0, param1 = MY_RULE)")
        voidFn("deleteItemByCls", "param0", "__host_deleteItemByCls(param0)")
        jsonRet("_findItem", "param0", "__host__findItem(param0)")
        jsonRet("_findItemsByCls", "param0", "__host__findItemsByCls(param0)")
        strRet("getJsPlugin", "", "__host_getJsPlugin()")
        strRet("getJsLazyPlugin", "", "__host_getJsLazyPlugin()")
        strRet("fetchCookie", "param0, param1", "__host_fetchCookie(param0, param1)")
        strRet("getMyUrl", "param0", "__host_getMyUrl(param0 = param0 || MY_URL)")
        strRet("getPageTitle", "", "__host_getPageTitle()")
        voidFn("back", "param0", "__host_back(param0)")
        voidFn("refreshPage", "param0, param1", "__host_refreshPage(param0, param1)")
        voidFn("toast", "param0, param1", "__host_toast(param0, param1)")
        strRet("getCookie", "param0", "__host_getCookie(param0 = param0 || MY_URL)")
        strRet("evalLazyRule", "param0, param1, param2", "__host_evalLazyRule(param0, param1, param2)")
        voidFn("setLastChapterRule", "param0", "__host_setLastChapterRule(param0)")
        voidFn("setPageParams", "param0", "__host_setPageParams(param0)")
        voidFn("setPagePicUrl", "param0", "__host_setPagePicUrl(param0)")
        voidFn("setPageReverse", "param0", "__host_setPageReverse(param0)")
        strRet("xpath", "param0, param1", "__host_xpath(param0, param1)")
        jsonRet("xpathArray", "param0, param1", "__host_xpathArray(param0, param1)")
        sb.append("var com = { example: { hikerview: { ui: { rules: { service: { require: { RequireUtils: { generateRequireMap: function(){} } } } } } } } };\n")
        s("fileExist", "param0", "return __host_fileExist(param0);")
        sb.append("var exist = fileExist;\n")
        strRet("getPath", "param0", "__host_getPath(param0)")
        strRet("fetchCache", "param0, param1, param2", "__host_fetchCache(param0, param1, param2)")
        voidFn("writeFile", "param0, param1", "__host_writeFile(param0, param1)")
        strRet("readFile", "param0", "__host_readFile(param0)")
        strRet("getCryptoJS", "", "__host_getCryptoJS()")
        strRet("registerTask", "param0, param1, param2", "__host_registerTask(param0, param1, param2)")
        s("unRegisterTask", "param0", "return __host_unRegisterTask(param0);")
        sb.append("var \$ = { exports: {}, require: function(name) {\n")
        sb.append("  var code = __host_require(name);\n")
        sb.append("  if (!code) return {};\n")
        sb.append("  if (typeof code !== 'string') return code;\n")
        sb.append("  if (code.indexOf('js:') === 0) code = code.substring(3);\n")
        sb.append("  var module = { exports: {} };\n")
        sb.append("  var exports = module.exports;\n")
        sb.append("  var __oldExports = \$.exports;\n")
        sb.append("  \$.exports = module.exports;\n")
        sb.append("  try { eval(code); } catch (e) { try { log('require '+name+' error: '+e); } catch(_e) {} }\n")
        sb.append("  if (\$.exports && \$.exports !== module.exports) module.exports = \$.exports;\n")
        sb.append("  \$.exports = __oldExports;\n")
        sb.append("  return module.exports;\n")
        sb.append("} };\n")
        sb.append("var MY_UA = JSON.parse(getUaObject()); var MOBILE_UA = MY_UA.mobileUa; var PC_UA = MY_UA.pcUa;\n")
        sb.append("var java = { util: { Date: Date }, lang: { String: { format: function(){ var a = Array.prototype.slice.call(arguments); var f = a.shift(); var i = 0; return String(f).replace(/%[sdif%]/g, function(m){ if (m === '%%') return '%'; var v = a[i++]; return v == null ? 'null' : v; }); } } }, text: { SimpleDateFormat: function(p){ this.pattern = p; this.format = function(d){ var dt = (d instanceof Date) ? d : new Date(d); var pad = function(n, l){ var s = String(n); while (s.length < l) s = '0' + s; return s; }; var y = dt.getFullYear(); var out = String(p); out = out.replace(/yyyy/g, String(y)); out = out.replace(/yy/g, pad(y % 100, 2)); out = out.replace(/MM/g, pad(dt.getMonth() + 1, 2)); out = out.replace(/dd/g, pad(dt.getDate(), 2)); out = out.replace(/HH/g, pad(dt.getHours(), 2)); out = out.replace(/mm/g, pad(dt.getMinutes(), 2)); out = out.replace(/ss/g, pad(dt.getSeconds(), 2)); out = out.replace(/SSS/g, pad(dt.getMilliseconds(), 3)); out = out.replace(/M/g, String(dt.getMonth() + 1)); out = out.replace(/d/g, String(dt.getDate())); out = out.replace(/H/g, String(dt.getHours())); out = out.replace(/m/g, String(dt.getMinutes())); out = out.replace(/s/g, String(dt.getSeconds())); return out; }; } } };\n")
        return sb.toString()
    }

    private fun rewriteHeaderToVar(script: String): String {
        val lines = script.split("\n")
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("let my_rule =") -> out.add(line.replaceFirst("let my_rule =", "var my_rule ="))
                trimmed.startsWith("const MY_RULE =") -> out.add(line.replaceFirst("const MY_RULE =", "var MY_RULE ="))
                trimmed.startsWith("const MY_TICKET =") -> out.add(line.replaceFirst("const MY_TICKET =", "var MY_TICKET ="))
                trimmed.startsWith("const _displayName =") -> out.add(line.replaceFirst("const _displayName =", "var _displayName ="))
                trimmed.startsWith("const MY_PRE_LINE =") -> out.add(line.replaceFirst("const MY_PRE_LINE =", "var MY_PRE_LINE ="))
                else -> out.add(line)
            }
        }
        return out.joinToString("\n")
    }

    private fun runInFreshContext(exec: Execution, script: String, sync: Boolean, prepared: Boolean): Any? {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future: Future<Any?> = executor.submit<Any?> {
                var ctx: QuickJSContext? = null
                try {
                    ctx = QuickJSContext.create()
                    try {
                        ctx.setMemoryLimit(64 * 1024 * 1024)
                    } catch (e: Throwable) {
                    }
                    try {
                        ctx.setMaxStackSize(1024 * 1024)
                    } catch (e: Throwable) {
                    }
                    bindHost(ctx, exec)
                    ctx.evaluate(prelude(), "miniapp_prelude.js")
                    val finalScript = if (prepared) {
                        script
                    } else {
                        MiniAppScript.getReplaceJS(MiniAppScript.appendLineVar(script), sync) ?: script
                    }
                    val rewritten = rewriteHeaderToVar(finalScript)
                    ctx.evaluate(rewritten, "miniapp_rule.js")
                } catch (e: Throwable) {
                    throw e
                } finally {
                    try {
                        ctx?.destroy()
                    } catch (e: Throwable) {
                    }
                }
            }
            return try {
                future.get(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Throwable) {
                future.cancel(true)
                throw RuntimeException("JS execution timeout or interrupted (script ${script.length} chars, limit 30s, memory 64MB): ${e.message}")
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun evalResultToString(ctx: QuickJSContext, value: Any?): String {
        return when (value) {
            null -> "undefined"
            is String -> value
            is JSObject -> try {
                ctx.stringify(value) ?: "undefined"
            } catch (e: Throwable) {
                value.toString()
            }
            else -> value.toString()
        }
    }

    fun runHome(rule: MiniAppRule, url: String, findRule: String, inject: Map<String, Any?>?, resCode: String = ""): Execution {
        val callbackKey = UUID.randomUUID().toString()
        val exec = Execution(rule, callbackKey, "home", resCode)
        val script = MiniAppScript.assembleHome(rule, url, findRule, inject, callbackKey)
        try {
            runInFreshContext(exec, script, false, false)
        } catch (e: Throwable) {
            if (exec.error == null) exec.error = "运行出错：" + e.toString()
        }
        return exec
    }

    fun runSearch(movieRule: MiniMovieRule, url: String, inject: Map<String, Any?>?, resCode: String = ""): Execution {
        val callbackKey = UUID.randomUUID().toString()
        val rule = MiniAppRule()
        rule.title = movieRule.title
        rule.displayName = movieRule.displayName
        val exec = Execution(rule, callbackKey, "search", resCode)
        val script = MiniAppScript.assembleSearch(movieRule, url, inject, callbackKey)
        try {
            runInFreshContext(exec, script, false, false)
        } catch (e: Throwable) {
            if (exec.error == null) exec.error = "运行出错：" + e.toString()
        }
        return exec
    }

    fun runPreRule(rule: MiniAppRule): Execution {
        val callbackKey = UUID.randomUUID().toString()
        val exec = Execution(rule, callbackKey, "preHome")
        val script = MiniAppScript.assemblePreRule(rule, callbackKey)
        try {
            runInFreshContext(exec, script, false, false)
        } catch (e: Throwable) {
            if (exec.error == null) exec.error = "运行出错：" + e.toString()
        }
        return exec
    }

    fun runLastChapter(rule: MiniAppRule, url: String, lastChapterRule: String, inject: Map<String, Any?>?, resCode: String = ""): Execution {
        val callbackKey = UUID.randomUUID().toString()
        val exec = Execution(rule, callbackKey, "lastChapter", resCode)
        val script = MiniAppScript.assembleLastChapter(rule, url, lastChapterRule, inject, callbackKey)
        try {
            runInFreshContext(exec, script, true, false)
        } catch (e: Throwable) {
            if (exec.error == null) exec.error = e.message
        }
        return exec
    }

    fun evalJs(code: String, input: String): String {
        if (jsEvalDepth.get() >= MAX_JS_EVAL_DEPTH) return input
        jsEvalDepth.incrementAndGet()
        try {
            val callbackKey = UUID.randomUUID().toString()
            val exec = Execution(MiniAppRule(), callbackKey, "eval")
            val script = MiniAppScript.assembleEval(code, input, callbackKey, true)
            return evalInFreshContext(exec, script)
        } finally {
            jsEvalDepth.decrementAndGet()
        }
    }

    // 规则页 @rule 求值：与 evalJs 相同，但绑定当前规则，使 $.require 能看到该规则的子页面。
    fun evalRuleJs(rule: MiniAppRule, code: String, myUrl: String = ""): String {
        if (jsEvalDepth.get() >= MAX_JS_EVAL_DEPTH) return ""
        jsEvalDepth.incrementAndGet()
        try {
            val callbackKey = UUID.randomUUID().toString()
            val exec = Execution(rule, callbackKey, "eval")
            val myUrlDecl = if (myUrl.isNotEmpty()) {
                "var MY_URL = '" + MiniAppText.escapeJsString(myUrl) + "';\n" +
                    "var MY_HOME = '" + (MiniAppText.getHome(myUrl) ?: "") + "';\n"
            } else {
                ""
            }
            val script = myUrlDecl + MiniAppScript.assembleEval(code, "", callbackKey, true)
            return evalInFreshContext(exec, script)
        } finally {
            jsEvalDepth.decrementAndGet()
        }
    }

    fun evalLazy(rule: MiniAppRule, myUrl: String, position: Int, strippedCode: String, base: String): String {
        if (jsEvalDepth.get() >= MAX_JS_EVAL_DEPTH) return ""
        jsEvalDepth.incrementAndGet()
        try {
            val callbackKey = UUID.randomUUID().toString()
            val exec = Execution(rule, callbackKey, "eval")
            val script = MiniAppScript.assembleLazyEval(
                rule,
                MiniAppText.escapeJsString(myUrl),
                position,
                strippedCode,
                base,
                callbackKey,
            )
            return evalInFreshContext(exec, script)
        } finally {
            jsEvalDepth.decrementAndGet()
        }
    }

    private fun evalInFreshContext(exec: Execution, script: String): String {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future: Future<String> = executor.submit<String> {
                var ctx: QuickJSContext? = null
                try {
                    ctx = QuickJSContext.create()
                    bindHost(ctx, exec)
                    ctx.evaluate(prelude(), "miniapp_prelude.js")
                    val rewritten = rewriteHeaderToVar(script)
                    val value = ctx.evaluate(rewritten, "miniapp_eval.js")
                    evalResultToString(ctx, value)
                } catch (e: Throwable) {
                    "error:" + e.message
                } finally {
                    try {
                        ctx?.destroy()
                    } catch (e: Throwable) {
                    }
                }
            }
            return try {
                future.get(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Throwable) {
                future.cancel(true)
                "error:timeout"
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
