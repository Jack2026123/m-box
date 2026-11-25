package com.github.tvbox.osc.reading

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import com.github.tvbox.osc.util.AppContextHolder
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One QuickJS runtime bound to a single (source, chain) execution: the
 * Legado `JsExtensions` surface real sources use — java.get/put/
 * getString/getStringList/getElement/getElements/setContent/ajax/
 * ajaxAll/connect/post/md5Encode/base64Encode/base64Decode/
 * createSymmetricCrypto/aesBase64DecodeToString/t2s/toast/longToast/
 * getCookie/encodeURI/getWebViewUA/log/timeFormat/timeFormatUTC,
 * source.get/put/getVariable/setVariable/getLoginHeader,
 * book.getVariable/putVariable, a `cache` global, plus the ambient
 * bindings result/src/baseUrl/key/page/book/chapter.
 *
 * List-valued `result`/`src` bind as real JS arrays (with a Rhino-style
 * `.toArray()` shim) so list-position rules can iterate them; object
 * values keep the Java map string form Legado sources regex against.
 * Java class access from scripts (org.jsoup, Packages, java.lang) is
 * not available on QuickJS and stays unsupported.
 *
 * Not thread-safe by design: create, use and destroy on one IO thread.
 */
class LegadoJs(
    private val source: BookSource,
    private val ruleHost: RuleHost,
    sharedVars: MutableMap<String, String>? = null,
) : AutoCloseable {

    interface RuleHost {
        /** Evaluate a rule string against the chain's current content. */
        fun evalRuleString(rule: String): String
        /** Synchronous GET with the source's headers; returns body text. */
        fun httpGet(url: String): String
        fun httpPost(url: String, body: String, headers: Map<String, String>): String

        /** Elements a rule selects from the chain's current content. */
        fun evalRuleElements(rule: String): List<Any> = emptyList()

        /** String list a rule produces from the chain's current content. */
        fun evalRuleStringList(rule: String): List<String> = emptyList()

        /** Replace the chain's current content (java.setContent). */
        fun setContent(content: Any?, baseUrl: String?) {}
    }

    // Lazy so rule-only (non-JS) evaluation never pays for a JS runtime —
    // and so the pure rule engine stays unit-testable on the JVM.
    private val ctxLazy = lazy { QuickJSContext.create() }
    private val ctx: QuickJSContext by ctxLazy
    private var libLoaded = false

    val vars: MutableMap<String, String> = sharedVars ?: HashMap()

    var result: Any? = null
    var baseUrl: String = ""
    var key: String = ""
    var page: Int = 1
    var book: LegadoBook? = null
    var chapter: LegadoChapter? = null

    fun loadLib() {
        if (libLoaded) return
        libLoaded = true
        val lib = source.jsLib
        if (!lib.isNullOrBlank()) {
            try {
                ctx.evaluate(lib)
            } catch (e: Throwable) {
                LOG.d("LegadoJs", "jsLib eval failed for ${source.bookSourceName}: ${e.message}")
            }
        }
    }

    /** Bind ambient variables and evaluate [script]; returns the raw JS value. */
    fun eval(script: String, resultValue: Any?): Any? {
        loadLib()
        val global = ctx.globalObject
        ctx.setProperty(global, "result", jsValue(resultValue))
        ctx.setProperty(global, "src", jsValue(resultValue))
        ctx.setProperty(global, "baseUrl", baseUrl)
        ctx.setProperty(global, "key", key)
        ctx.setProperty(global, "page", page)
        ctx.setProperty(global, "book", bookObject())
        ctx.setProperty(global, "chapter", chapterObject())
        ctx.setProperty(global, "java", javaObject())
        ctx.setProperty(global, "source", sourceObject())
        ctx.setProperty(global, "cache", cacheObject())
        installArrayShim()
        return ctx.evaluate(script)
    }

    /**
     * Rhino's `result` is a Java list with `.toArray()`; a real JS array
     * has no such method, so install it on array-valued bindings.
     */
    private fun installArrayShim() {
        runCatching {
            ctx.evaluate(
                "(function(){try{" +
                    "if(typeof result!=='undefined'&&Array.isArray(result)&&typeof result.toArray!=='function')" +
                    "{result.toArray=function(){return this.slice();}}" +
                    "if(typeof src!=='undefined'&&Array.isArray(src)&&typeof src.toArray!=='function')" +
                    "{src.toArray=function(){return this.slice();}}" +
                    "}catch(e){}})()",
            )
        }
    }

    fun evalString(script: String, resultValue: Any?): String =
        try {
            jsToString(eval(script, resultValue))
        } catch (e: Throwable) {
            // One bad script degrades its own field to empty, Legado-style;
            // it must never sink the whole chain.
            LOG.d("LegadoJs", "eval failed for ${source.bookSourceName}: ${e.message}")
            ""
        }

    fun evalStringList(script: String, resultValue: Any?): List<String> {
        // Evaluate, stash the completion value in a global, then read it
        // back as JSON — avoids depending on JSArray accessor details.
        val stashed = try {
            eval(
                "globalThis.__legado_list = (function(){ return eval(" +
                    JSONObject.quote(script) + "); })()",
                resultValue,
            )
            true
        } catch (e: Throwable) {
            LOG.d("LegadoJs", "evalStringList failed for ${source.bookSourceName}: ${e.message}")
            false
        }
        if (!stashed) return emptyList()
        val json = runCatching {
            ctx.evaluate("JSON.stringify(globalThis.__legado_list)") as? String
        }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: run {
            // A script may return the JSON array as a *string*.
            val unquoted = runCatching {
                JSONObject("{\"v\":$json}").optString("v")
            }.getOrNull()
            val inner = unquoted?.let { runCatching { JSONArray(it) }.getOrNull() }
            if (inner != null) return@run inner
            val single = runCatching { JSONObject(json) }.getOrNull()
            return if (single != null) listOf(single.toString()) else emptyList()
        }
        return (0 until arr.length()).map { LenientJson.valueToString(arr.opt(it)) }
            .filter { it.isNotBlank() }
    }

    private fun jsValue(v: Any?): Any? = when (v) {
        null, is String, is Number, is Boolean -> v
        is JSONArray -> jsValue((0 until v.length()).map { v.opt(it) })
        is List<*> ->
            // Bind lists as real JS arrays so list-position scripts can
            // index/iterate them (Legado binds the live list there).
            runCatching {
                ctx.evaluate("JSON.parse(" + JSONObject.quote(toJsonText(v)) + ")")
            }.getOrElse { LenientJson.valueToString(v) }
        else -> LenientJson.valueToString(v)
    }

    /** JSON text for any JSON-ish value (used for JS array round-trips). */
    private fun toJsonText(v: Any?): String = when (v) {
        null -> "null"
        is JSONObject, is JSONArray -> v.toString()
        is String -> JSONObject.quote(v)
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> {
            val obj = JSONObject()
            for ((k, x) in v) obj.put(k.toString(), toJsonValue(x))
            obj.toString()
        }
        is List<*> -> {
            val arr = JSONArray()
            for (x in v) arr.put(toJsonValue(x))
            arr.toString()
        }
        is Element -> JSONObject.quote(v.outerHtml())
        else -> JSONObject.quote(v.toString())
    }

    private fun toJsonValue(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Number, is Boolean -> v
        is Map<*, *> -> {
            val obj = JSONObject()
            for ((k, x) in v) obj.put(k.toString(), toJsonValue(x))
            obj
        }
        is List<*> -> {
            val arr = JSONArray()
            for (x in v) arr.put(toJsonValue(x))
            arr
        }
        is Element -> v.outerHtml()
        else -> v.toString()
    }

    /** A JS array of strings for java.* list-returning helpers. */
    private fun jsStringArray(items: List<String>): Any? =
        runCatching {
            ctx.evaluate("JSON.parse(" + JSONObject.quote(toJsonText(items)) + ")")
        }.getOrElse { items }

    private fun elementText(element: Any?): String = when (element) {
        null -> ""
        is Element -> element.outerHtml()
        is JSONObject, is JSONArray -> element.toString()
        else -> LenientJson.valueToString(element)
    }

    private fun bookObject(): JSObject {
        val obj = ctx.createNewJSObject()
        val b = book
        ctx.setProperty(obj, "name", b?.name ?: "")
        ctx.setProperty(obj, "author", b?.author ?: "")
        ctx.setProperty(obj, "bookUrl", b?.bookUrl ?: "")
        ctx.setProperty(obj, "tocUrl", b?.tocUrl ?: "")
        ctx.setProperty(obj, "origin", source.bookSourceUrl)
        ctx.setProperty(obj, "originName", source.bookSourceName)
        ctx.setProperty(obj, "kind", b?.kind ?: "")
        ctx.setProperty(obj, "intro", b?.intro ?: "")
        ctx.setProperty(obj, "coverUrl", b?.coverUrl ?: "")
        val bookKey = b?.bookKey ?: ""
        ctx.setProperty(obj, "getVariable", fn { args ->
            val k = args.getOrNull(0)?.toString() ?: ""
            ReadingStore.bookVar(bookKey, k) ?: ""
        })
        ctx.setProperty(obj, "putVariable", fn { args ->
            val k = args.getOrNull(0)?.toString() ?: ""
            val v = args.getOrNull(1)?.let { jsToString(it) } ?: ""
            ReadingStore.putBookVar(bookKey, k, v)
            v
        })
        return obj
    }

    private fun chapterObject(): JSObject {
        val obj = ctx.createNewJSObject()
        val c = chapter
        ctx.setProperty(obj, "title", c?.name ?: "")
        ctx.setProperty(obj, "url", c?.url ?: "")
        ctx.setProperty(obj, "index", c?.index ?: 0)
        return obj
    }

    private fun cacheObject(): JSObject {
        val obj = ctx.createNewJSObject()
        ctx.setProperty(obj, "get", fn { args ->
            val k = args.getOrNull(0)?.toString() ?: ""
            cacheMap[k]
        })
        ctx.setProperty(obj, "put", fn { args ->
            val k = args.getOrNull(0)?.toString() ?: ""
            val v = args.getOrNull(1)?.let { jsToString(it) } ?: ""
            cacheMap[k] = v
            v
        })
        ctx.setProperty(obj, "delete", fn { args ->
            val k = args.getOrNull(0)?.toString() ?: ""
            cacheMap.remove(k)
            null
        })
        return obj
    }

    private fun fn(body: (Array<out Any?>) -> Any?): JSCallFunction =
        object : JSCallFunction {
            override fun call(vararg args: Any?): Any? = body(args)
        }

    private fun javaObject(): JSObject {
        val java = ctx.createNewJSObject()
        ctx.setProperty(java, "get", fn { args ->
            val k = args.getOrNull(0)?.toString() ?: ""
            vars[k] ?: ""
        })
        ctx.setProperty(java, "put", fn { args ->
            val k = args.getOrNull(0)?.toString() ?: ""
            val v = args.getOrNull(1)?.let { jsToString(it) } ?: ""
            vars[k] = v
            v
        })
        ctx.setProperty(java, "getString", fn { args ->
            val k = args.getOrNull(0)?.toString() ?: ""
            when {
                vars.containsKey(k) -> vars[k]
                k.startsWith("$") || k.startsWith("@") || k.contains("@") ->
                    runCatching { ruleHost.evalRuleString(k) }.getOrDefault("")
                else -> vars[k] ?: ""
            }
        })
        ctx.setProperty(java, "getStringList", fn { args ->
            val rule = args.getOrNull(0)?.toString() ?: ""
            val items = runCatching { ruleHost.evalRuleStringList(rule) }
                .getOrDefault(emptyList())
            jsStringArray(items)
        })
        ctx.setProperty(java, "getElement", fn { args ->
            val rule = args.getOrNull(0)?.toString() ?: ""
            val elements = runCatching { ruleHost.evalRuleElements(rule) }
                .getOrDefault(emptyList())
            elementText(elements.firstOrNull())
        })
        ctx.setProperty(java, "getElements", fn { args ->
            val rule = args.getOrNull(0)?.toString() ?: ""
            val elements = runCatching { ruleHost.evalRuleElements(rule) }
                .getOrDefault(emptyList())
            jsStringArray(elements.map { elementText(it) })
        })
        ctx.setProperty(java, "setContent", fn { args ->
            val content = args.getOrNull(0)
            val newBase = args.getOrNull(1)?.toString()?.takeIf { it.isNotBlank() }
            runCatching { ruleHost.setContent(content, newBase) }
            content?.let { jsToString(it) } ?: ""
        })
        ctx.setProperty(java, "ajax", fn { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            runCatching { ruleHost.httpGet(url) }.getOrDefault("")
        })
        ctx.setProperty(java, "ajaxAll", fn { args ->
            ajaxAll(args.getOrNull(0))
        })
        ctx.setProperty(java, "connect", fn { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val resp = ctx.createNewJSObject()
            val bodyText = runCatching { ruleHost.httpGet(url) }.getOrDefault("")
            ctx.setProperty(resp, "code", 200)
            ctx.setProperty(resp, "body", fn { bodyText })
            ctx.setProperty(resp, "headers", fn { "" })
            resp
        })
        ctx.setProperty(java, "post", fn { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val body = args.getOrNull(1)?.let { jsToString(it) } ?: ""
            runCatching { ruleHost.httpPost(url, body, emptyMap()) }.getOrDefault("")
        })
        ctx.setProperty(java, "md5Encode", fn { args ->
            MD5.encode(args.getOrNull(0)?.toString() ?: "")
        })
        ctx.setProperty(java, "md5Encode16", fn { args ->
            (MD5.encode(args.getOrNull(0)?.toString() ?: "") ?: "").let {
                if (it.length >= 24) it.substring(8, 24) else it
            }
        })
        ctx.setProperty(java, "base64Encode", fn { args ->
            val text = args.getOrNull(0)?.let { jsToString(it) } ?: ""
            runCatching {
                Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }.getOrDefault("")
        })
        ctx.setProperty(java, "base64Decode", fn { args ->
            val text = args.getOrNull(0)?.toString() ?: ""
            runCatching {
                String(Base64.decode(text, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("")
        })
        ctx.setProperty(java, "createSymmetricCrypto", fn { args ->
            val transformation = args.getOrNull(0)?.toString()
                ?.takeIf { it.isNotBlank() } ?: "AES/CBC/PKCS5Padding"
            val keyText = args.getOrNull(1)?.let { jsToString(it) } ?: ""
            val ivText = args.getOrNull(2)?.let { jsToString(it) } ?: ""
            symmetricCryptoObject(transformation, keyText, ivText)
        })
        ctx.setProperty(java, "aesBase64DecodeToString", fn { args ->
            val data = args.getOrNull(0)?.let { jsToString(it) } ?: ""
            val keyText = args.getOrNull(1)?.let { jsToString(it) } ?: ""
            val ivText = args.getOrNull(2)?.let { jsToString(it) } ?: ""
            val transformation = args.getOrNull(3)?.toString()
                ?.takeIf { it.isNotBlank() } ?: "AES/CBC/PKCS5Padding"
            symmetricDecrypt(transformation, keyText, ivText, data)
        })
        ctx.setProperty(java, "t2s", fn { args ->
            T2S.convert(args.getOrNull(0)?.let { jsToString(it) } ?: "")
        })
        ctx.setProperty(java, "getCookie", fn { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val name = args.getOrNull(1)?.toString()?.takeIf { it.isNotBlank() }
            if (name == null) LegadoCookies.cookieString(url)
            else LegadoCookies.cookieValue(url, name) ?: ""
        })
        ctx.setProperty(java, "encodeURI", fn { args ->
            encodeUri(args.getOrNull(0)?.let { jsToString(it) } ?: "")
        })
        ctx.setProperty(java, "getWebViewUA", fn {
            LegadoFetcher.DEFAULT_UA
        })
        ctx.setProperty(java, "log", fn { args ->
            val msg = args.getOrNull(0)?.let { jsToString(it) } ?: ""
            LOG.d("LegadoJs", "[${source.bookSourceName}] $msg")
            msg
        })
        ctx.setProperty(java, "longToast", fn { args ->
            toast(args.getOrNull(0)?.let { jsToString(it) } ?: "", long = true)
            null
        })
        ctx.setProperty(java, "toast", fn { args ->
            toast(args.getOrNull(0)?.let { jsToString(it) } ?: "", long = false)
            null
        })
        ctx.setProperty(java, "timeFormat", fn { args ->
            val ts = (args.getOrNull(0) as? Number)?.toLong()
                ?: args.getOrNull(0)?.toString()?.toLongOrNull() ?: 0L
            val pattern = args.getOrNull(1)?.toString() ?: "yyyy-MM-dd HH:mm"
            runCatching {
                SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
            }.getOrDefault("")
        })
        ctx.setProperty(java, "timeFormatUTC", fn { args ->
            val raw = args.getOrNull(0)?.toString() ?: ""
            val pattern = args.getOrNull(1)?.toString() ?: "yyyy-MM-dd HH:mm"
            val offset = (args.getOrNull(2) as? Number)?.toInt() ?: 8
            runCatching {
                val millis = raw.toLongOrNull() ?: parseDateLoose(raw)
                val fmt = SimpleDateFormat(pattern, Locale.getDefault())
                fmt.timeZone = TimeZone.getTimeZone("GMT" + (if (offset >= 0) "+" else "") + offset)
                fmt.format(Date(millis))
            }.getOrDefault("")
        })
        return java
    }

    private fun toast(msg: String, long: Boolean) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(
                    AppContextHolder.context(), msg,
                    if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun ajaxAll(urlsValue: Any?): Any? {
        val urls: List<String> = when (urlsValue) {
            is JSObject -> runCatching {
                urlsValue.toArray().map { jsToString(it) }
            }.getOrDefault(emptyList())
            is List<*> -> urlsValue.map { jsToString(it) }
            is String -> listOf(urlsValue)
            else -> emptyList()
        }
        val responses = urls.map { u ->
            val resp = ctx.createNewJSObject()
            val bodyText = runCatching { ruleHost.httpGet(u) }.getOrDefault("")
            ctx.setProperty(resp, "code", 200)
            ctx.setProperty(resp, "body", fn { bodyText })
            ctx.setProperty(resp, "url", fn { u })
            resp
        }
        val array = runCatching { ctx.evaluate("[]") as? JSObject }.getOrNull()
            ?: return responses
        responses.forEachIndexed { idx, resp ->
            runCatching { ctx.setProperty(array, idx.toString(), resp) }
        }
        runCatching { ctx.setProperty(array, "length", responses.size) }
        return array
    }

    // ------------------------------------------------------------ crypto

    private fun symmetricCryptoObject(
        transformation: String,
        key: String,
        iv: String,
    ): JSObject {
        val obj = ctx.createNewJSObject()
        ctx.setProperty(obj, "decryptStr", fn { args ->
            symmetricDecrypt(transformation, key, iv, jsToString(args.getOrNull(0)))
        })
        ctx.setProperty(obj, "encryptStr", fn { args ->
            symmetricEncrypt(transformation, key, iv, jsToString(args.getOrNull(0)))
        })
        return obj
    }

    private fun newCipher(transformation: String, key: String, iv: String, mode: Int): Cipher {
        val cipher = Cipher.getInstance(transformation)
        val algorithm = transformation.substringBefore('/').ifBlank { "AES" }
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), algorithm)
        if (transformation.contains("ECB", ignoreCase = true) || iv.isEmpty()) {
            cipher.init(mode, keySpec)
        } else {
            cipher.init(mode, keySpec, IvParameterSpec(iv.toByteArray(Charsets.UTF_8)))
        }
        return cipher
    }

    private fun symmetricDecrypt(
        transformation: String,
        key: String,
        iv: String,
        data: String,
    ): String = runCatching {
        val clean = data.trim()
        val bytes = decodeCipherBytes(clean) ?: return ""
        String(newCipher(transformation, key, iv, Cipher.DECRYPT_MODE).doFinal(bytes), Charsets.UTF_8)
    }.getOrElse {
        LOG.d("LegadoJs", "decrypt failed for ${source.bookSourceName}: ${it.message}")
        ""
    }

    private fun symmetricEncrypt(
        transformation: String,
        key: String,
        iv: String,
        plain: String,
    ): String = runCatching {
        val out = newCipher(transformation, key, iv, Cipher.ENCRYPT_MODE)
            .doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(out, Base64.NO_WRAP)
    }.getOrElse {
        LOG.d("LegadoJs", "encrypt failed for ${source.bookSourceName}: ${it.message}")
        ""
    }

    /** Ciphertext arrives Base64 in most sources, hex in a few. */
    private fun decodeCipherBytes(clean: String): ByteArray? {
        val b64 = runCatching { Base64.decode(clean, Base64.DEFAULT) }.getOrNull()
        if (b64 != null && b64.isNotEmpty() && b64.size % 16 == 0) return b64
        return hexToBytes(clean) ?: b64?.takeIf { it.isNotEmpty() }
    }

    private fun hexToBytes(s: String): ByteArray? {
        if (s.length < 2 || s.length % 2 != 0) return null
        if (!s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(s.length / 2) {
            s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    private fun encodeUri(text: String): String = runCatching {
        URLEncoder.encode(text, "UTF-8")
            .replace("+", "%20")
            .replace("%7E", "~")
            .replace("%2F", "/").replace("%3A", ":").replace("%3F", "?")
            .replace("%26", "&").replace("%3D", "=").replace("%40", "@")
            .replace("%23", "#").replace("%24", "$").replace("%2B", "+")
            .replace("%2C", ",").replace("%3B", ";")
    }.getOrDefault(text)

    private fun parseDateLoose(raw: String): Long {
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd",
        )
        for (p in patterns) {
            try {
                return SimpleDateFormat(p, Locale.getDefault()).parse(raw)?.time ?: continue
            } catch (e: Exception) {
                // try next
            }
        }
        return 0L
    }

    private fun sourceObject(): JSObject {
        val obj = ctx.createNewJSObject()
        ctx.setProperty(obj, "get", fn { args ->
            val k = args.getOrNull(0)?.toString() ?: ""
            ReadingStore.sourceVar(source.bookSourceUrl, k) ?: ""
        })
        ctx.setProperty(obj, "put", fn { args ->
            val k = args.getOrNull(0)?.toString() ?: ""
            val v = args.getOrNull(1)?.let { jsToString(it) } ?: ""
            ReadingStore.putSourceVar(source.bookSourceUrl, k, v)
            v
        })
        // This shell has no interactive login flow, so there is never a
        // stored login header; return empty instead of throwing (rules
        // concatenate it into header strings).
        ctx.setProperty(obj, "getLoginHeader", fn { "" })
        ctx.setProperty(obj, "getVariable", fn { args ->
            val k = args.getOrNull(0)?.toString()?.takeIf { it.isNotBlank() }
            if (k == null) {
                val stored = ReadingStore.sourceVar(source.bookSourceUrl, SOURCE_VAR_KEY) ?: ""
                if (stored.isNotBlank()) stored
                else if (source.bookSourceType == 2 && source.bookSourceUrl.isNotBlank()) {
                    JSONObject().put("custom", source.bookSourceUrl.trimEnd('/')).toString()
                } else ""
            } else ReadingStore.sourceVar(source.bookSourceUrl, "var:$k") ?: ""
        })
        ctx.setProperty(obj, "setVariable", fn { args ->
            if (args.size >= 2) {
                val k = args.getOrNull(0)?.toString() ?: ""
                val v = jsToString(args.getOrNull(1))
                ReadingStore.putSourceVar(source.bookSourceUrl, "var:$k", v)
                v
            } else {
                val v = jsToString(args.getOrNull(0))
                ReadingStore.putSourceVar(source.bookSourceUrl, SOURCE_VAR_KEY, v)
                v
            }
        })
        ctx.setProperty(obj, "name", source.bookSourceName)
        ctx.setProperty(obj, "bookSourceName", source.bookSourceName)
        ctx.setProperty(obj, "bookSourceUrl", source.bookSourceUrl)
        ctx.setProperty(obj, "bookSourceGroup", source.bookSourceGroup)
        return obj
    }

    companion object {
        private const val SOURCE_VAR_KEY = "variable"

        /** Process-wide `cache` global backing store (Legado semantics). */
        private val cacheMap = ConcurrentHashMap<String, String>()

        fun jsToString(v: Any?): String = when (v) {
            null -> ""
            is String -> v
            is Double -> if (v % 1.0 == 0.0 && !v.isInfinite()) v.toLong().toString() else v.toString()
            is Number -> v.toString()
            is Boolean -> v.toString()
            is JSObject -> runCatching { v.toString() }.getOrDefault("")
            else -> v.toString()
        }

        fun jsToStringList(v: Any?): List<String> = when (v) {
            null -> emptyList()
            is String -> if (v.isBlank()) emptyList() else listOf(v)
            is JSObject -> emptyList()
            is List<*> -> v.map { jsToString(it) }
            else -> listOf(jsToString(v))
        }
    }

    override fun close() {
        runCatching {
            // Only destroy when the runtime was actually created.
            if (ctxLazy.isInitialized()) ctx.destroy()
        }
    }
}
