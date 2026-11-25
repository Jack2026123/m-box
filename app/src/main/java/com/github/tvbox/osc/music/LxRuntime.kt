package com.github.tvbox.osc.music

import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 洛雪（lx-music）自定义音源运行时。
 *
 * ══════════════════════════════════════════════════════════════
 * 脚本期望的宿主接口（缺一不可）：
 *
 *   globalThis.lx = {
 *     EVENT_NAMES: { request, inited, updateAlert },
 *     version: '2.0.0',
 *     env, currentScriptInfo,
 *     on(name, handler),      // 脚本注册取链处理器
 *     send(name, data),       // 脚本落地 inited（源表 + 音质档位）
 *     request(url, opt, cb),  // 脚本发 HTTP
 *     utils: { crypto, buffer }
 *   }
 *
 * ══════════════════════════════════════════════════════════════
 * 三条硬约束（都是踩坑踩出来的）：
 *
 *  1. **线程**：所有 QuickJS 调用必须固定在同一个线程（jsExecutor）。
 *     脚本的 handler 里会再调 lx.request，如果回调跑到别的线程上碰
 *     JS 值，会直接崩。
 *
 *  2. **回调不能嵌套在 JS 调用栈里**：lx.request 是同步取 HTTP，但
 *     回调必须排队、等 handler 执行完再跑。若在 JS 调用栈里就地回调，
 *     等于递归进 JS，Promise 还没落定，脚本会拿到半成品。
 *
 *  3. **响应对象必须是可写的 JS 对象**：脚本常写 `resp.body.xxx = 1`，
 *     所以响应在 JS 侧构造（JSON.parse），不能传 Kotlin Map。
 *
 * ══════════════════════════════════════════════════════════════
 * 取链流程（脚本形态各不相同，所以做了兼容）：
 *
 *   handler({source, action:'musicUrl', info, quality})
 *     ├─ 直接 return 'http...'              → 命中
 *     ├─ 返回 Promise（async 函数）          → 用 resolve 取
 *     └─ 内部调 lx.request(..., cb)
 *          └─ cb(err, resp) 里再 lx.request → 继续排队
 *          └─ resp.body.url / finalUrl        → 命中
 *
 * 命中统一走 resolveUrl()，谁先到用谁，只生效一次。
 */
class LxRuntime private constructor(
    private val code: String,
    private val sourceName: String,
) {
    private val jsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lxjs-" + sourceName)
    }

    /** 负责把 lx.request 的回调送回 jsExecutor 执行 */
    private val pump: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "lxpump-" + sourceName)
    }

    private val destroyed = AtomicBoolean(false)

    @Volatile
    private var ctx: QuickJSContext? = null

    @Volatile
    private var requestHandler: JSObject? = null

    @Volatile
    var qualityMap: Map<String, List<String>> = emptyMap()
        private set

    @Volatile
    var platformNames: Map<String, String> = emptyMap()
        private set

    @Volatile
    var lastError: String = ""
        private set

    val isReady: Boolean get() = requestHandler != null

    val sourcePlatforms: Set<String> get() = qualityMap.keys

    // ------------------------------------------------------------ 回调队列

    private val pendingCallbacks = java.util.ArrayDeque<Runnable>()

    // ------------------------------------------------------------ 取链结果

    private val resultLock = Any()
    private var resultUrl: String? = null
    private var resultError: String? = null
    private var resultLatch: CountDownLatch? = null

    // ============================================================ 启动

    private fun ensureStarted() {
        jsExecutor.execute {
            if (destroyed.get()) return@execute
            try {
                boot()
            } catch (th: Throwable) {
                lastError = "脚本加载失败：" + (th.message ?: th.javaClass.simpleName)
                LOG.e("lx-boot-failed " + sourceName + " : " + lastError)
            }
        }
    }

    private fun boot() {
        if (ctx != null) return
        val context = QuickJSContext.create()
        context.setConsole(object : QuickJSContext.Console {
            override fun log(info: String?) {
                LOG.i("lxjs[" + sourceName + "] " + info.orEmpty())
            }

            override fun info(info: String?) {
                LOG.i("lxjs[" + sourceName + "] " + info.orEmpty())
            }

            override fun warn(info: String?) {
                LOG.i("lxjs[" + sourceName + "] warn " + info.orEmpty())
            }

            override fun error(info: String?) {
                LOG.e("lxjs[" + sourceName + "] " + info.orEmpty())
            }
        })
        ctx = context

        installLx(context)

        // 洛雪脚本是普通脚本（不是 module），直接执行
        context.evaluate(code, sourceName + ".js")
        lastError = ""
        LOG.i("lx-boot-ok " + sourceName)
    }

    // ============================================================ 注入 lx

    private fun installLx(context: QuickJSContext) {
        val lx = context.createNewJSObject()

        val events = context.createNewJSObject()
        context.setProperty(events, "request", "request")
        context.setProperty(events, "inited", "inited")
        context.setProperty(events, "updateAlert", "updateAlert")
        context.setProperty(lx, "EVENT_NAMES", events)

        // 手机端洛雪是 2.x，部分脚本会拿它拼 UA 头
        context.setProperty(lx, "version", "2.0.0")

        val info = context.createNewJSObject()
        context.setProperty(info, "name", sourceName)
        context.setProperty(info, "description", "")
        context.setProperty(info, "version", "")
        context.setProperty(info, "author", "")
        context.setProperty(info, "homepage", "")
        context.setProperty(info, "rawScript", code)
        context.setProperty(lx, "currentScriptInfo", info)

        val env = context.createNewJSObject()
        context.setProperty(env, "os", "android")
        context.setProperty(env, "lang", "zh-CN")
        context.setProperty(lx, "env", env)

        context.setProperty(lx, "on", JSCallFunction { args ->
            val name = args.getOrNull(0)?.toString().orEmpty()
            val handler = args.getOrNull(1)
            if (name == "request" && handler is JSObject) {
                requestHandler = handler
                LOG.i("lx-handler-registered " + sourceName)
            }
            null
        })

        context.setProperty(lx, "send", JSCallFunction { args ->
            when (args.getOrNull(0)?.toString().orEmpty()) {
                "inited" -> parseInited(context, args.getOrNull(1))
                "updateAlert" -> LOG.i("lx-update-alert " + sourceName)
            }
            null
        })

        context.setProperty(lx, "request", JSCallFunction { args ->
            val url = args.getOrNull(0)?.toString().orEmpty()
            handleRequest(context, url, args.getOrNull(1), args.getOrNull(2))
            null
        })

        context.setProperty(lx, "utils", buildUtils(context))
        context.setProperty(context.globalObject, "lx", lx)
    }

    private fun buildUtils(context: QuickJSContext): JSObject {
        val utils = context.createNewJSObject()
        val crypto = context.createNewJSObject()

        context.setProperty(crypto, "md5", JSCallFunction { args ->
            MD5.string2MD5(args.getOrNull(0)?.toString().orEmpty())
        })
        context.setProperty(crypto, "randomBytes", JSCallFunction { args ->
            val size = (args.getOrNull(0) as? Number)?.toInt() ?: 16
            val bytes = ByteArray(size)
            java.security.SecureRandom().nextBytes(bytes)
            buildString { for (b in bytes) append(String.format("%02x", b)) }
        })
        context.setProperty(crypto, "aesEncrypt", JSCallFunction { args ->
            aesEncrypt(
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
                args.getOrNull(2)?.toString().orEmpty(),
                args.getOrNull(3)?.toString().orEmpty(),
            )
        })
        context.setProperty(crypto, "rsaEncrypt", JSCallFunction { args ->
            rsaEncrypt(
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
            )
        })
        context.setProperty(utils, "crypto", crypto)

        // buffer：脚本常用 buffer.from(str) 和 bufToString(buf)
        // 用纯 JS 实现，避免把 Uint8Array 跨语言来回搬
        val buffer = context.createNewJSObject()
        context.setProperty(buffer, "from", JSCallFunction { args ->
            val v = args.getOrNull(0) ?: return@JSCallFunction null
            if (v is JSObject) v else v.toString()
        })
        context.setProperty(buffer, "bufToString", JSCallFunction { args ->
            val v = args.getOrNull(0) ?: return@JSCallFunction ""
            if (v is JSObject) {
                val len = context.getProperty(v, "length")
                if (len is Number && len.toInt() > 0) {
                    val bytes = ByteArray(len.toInt())
                    for (i in 0 until len.toInt()) {
                        bytes[i] = (jsItem(context, v, i) as? Number)?.toInt()?.toByte() ?: 0
                    }
                    String(bytes, Charsets.UTF_8)
                } else {
                    v.toString().orEmpty()
                }
            } else {
                v.toString()
            }
        })
        context.setProperty(utils, "buffer", buffer)

        return utils
    }

    // ============================================================ inited

    private fun parseInited(context: QuickJSContext, data: Any?) {
        try {
            if (data !is JSObject) {
                LOG.i("lx-inited-not-object " + sourceName)
                return
            }
            val sources = context.getProperty(data, "sources")
            if (sources !is JSObject) {
                LOG.i("lx-inited-no-sources " + sourceName)
                drain()
                return
            }
            val result = LinkedHashMap<String, List<String>>()
            val names = LinkedHashMap<String, String>()
            val keys = objectKeys(context, sources)
            for (platform in keys) {
                val entry = context.getProperty(sources, platform) ?: continue
                if (entry !is JSObject) continue
                val declared = context.getProperty(entry, "name")?.toString()?.trim().orEmpty()
                if (declared.isNotEmpty()) names[platform] = declared
                val qualitys = context.getProperty(entry, "qualitys")
                val list = ArrayList<String>()
                if (qualitys is JSObject) {
                    val len = context.getProperty(qualitys, "length")
                    if (len is Number) {
                        for (i in 0 until len.toInt()) {
                            jsItem(context, qualitys, i)?.let { list.add(it.toString()) }
                        }
                    }
                }
                if (list.isEmpty()) list.addAll(DEFAULT_QUALITIES)
                result[platform] = list
            }
            qualityMap = result
            platformNames = names
            LOG.i(
                "lx-inited-ok " + sourceName + " => " +
                    result.entries.joinToString(", ") { it.key + ":" + it.value.joinToString("/") },
            )
            drain()
        } catch (th: Throwable) {
            LOG.e("lx-parse-inited-failed " + sourceName + " : " + th.message)
            drain()
        }
    }

    // ============================================================ lx.request

    private fun handleRequest(
        context: QuickJSContext,
        url: String,
        opt: Any?,
        cb: Any?,
    ) {
        val callback = cb as? JSObject
        if (callback == null) {
            LOG.i("lx-request-no-callback " + sourceName)
            return
        }
        if (url.isBlank()) {
            enqueueCallback(context, callback, "url is empty", null)
            return
        }
        val response = try {
            doHttp(context, url, opt)
        } catch (th: Throwable) {
            LOG.i("lx-http-failed " + sourceName + " : " + th.message)
            enqueueCallback(context, callback, th.message ?: th.javaClass.simpleName, null)
            return
        }
        enqueueCallback(context, callback, null, response)
    }

    private fun doHttp(context: QuickJSContext, url: String, opt: Any?): String {
        var method = "GET"
        var body: String? = null
        var timeoutMs = 20_000L
        val headers = LinkedHashMap<String, String>()

        if (opt is JSObject) {
            context.getProperty(opt, "method")?.toString()?.let { method = it.uppercase() }
            context.getProperty(opt, "timeout")?.let { if (it is Number) timeoutMs = it.toLong() }
            val h = context.getProperty(opt, "headers")
            if (h is JSObject) {
                for (k in objectKeys(context, h)) {
                    context.getProperty(h, k)?.let { headers[k] = it.toString() }
                }
            }
            val b = context.getProperty(opt, "body")
            if (b != null) body = if (b is JSObject) b.toString() else b.toString()
        }

        val client = com.github.tvbox.osc.net.OkGoHelper.getDefaultClient()
            ?: throw IllegalStateException("http client not ready")

        val builder = okhttp3.Request.Builder().url(url)
        headers.forEach { (k, v) -> runCatching { builder.header(k, v) } }
        if (headers.keys.none { it.equals("User-Agent", true) }) {
            builder.header("User-Agent", "lx-music-request/2.0.0")
        }

        val requestBody = body?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            ?.let { bytes ->
                val mediaType = headers.entries
                    .firstOrNull { it.key.equals("Content-Type", true) }
                    ?.value
                    ?.let { it.toMediaType() }
                    ?: "application/json; charset=utf-8".toMediaType()
                bytes.toRequestBody(mediaType)
            }

        builder.method(
            method,
            if (method == "GET" || method == "HEAD") null else requestBody
                ?: okhttp3.RequestBody.create(null, ByteArray(0)),
        )

        val quick = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        quick.newCall(builder.build()).execute().use { resp ->
            // 把响应做成 JS 值，交给脚本前先放进 ctx 的一个临时变量
            val text = resp.body?.string().orEmpty()
            val headersJson = JSONObject()
            for (name in resp.headers.names()) {
                headersJson.put(name, resp.headers.values(name).joinToString(","))
            }
            val envelope = JSONObject()
            envelope.put("statusCode", resp.code)
            envelope.put("statusMessage", resp.message)
            envelope.put("headers", headersJson)
            envelope.put("body", text)

            // 用 JSON.parse 在 JS 侧造对象：可写、可改、脚本能直接操作
            val parsed = context.evaluate(
                "(function(s){try{return JSON.parse(s)}catch(e){return s}})",
                "lx_parse.js",
            )
            val fn = asJsFunction(parsed)
            if (fn != null) {
                val jsonText = envelope.toString()
                val result = fn.call(jsonText)
                return if (result is JSObject) holdAndReturn(context, result) else jsonText
            }
            return envelope.toString()
        }
    }

    /**
     * 把 JS 对象挂到 globalThis 上取回来。
     *
     * QuickJS wrapper 里 `JSObject` 作为函数返回值时会被 release，
     * 所以要 hold() 住，交给回调用完再释放（这里简化为直接 hold，交给 GC）。
     */
    private fun holdAndReturn(context: QuickJSContext, obj: JSObject): String {
        val key = "__lx_resp_" + System.nanoTime()
        context.setProperty(context.globalObject, key, obj)
        return key
    }

    private fun enqueueCallback(
        context: QuickJSContext,
        callback: JSObject,
        error: String?,
        responseKey: String?,
    ) {
        pendingCallbacks.addLast(Runnable {
            if (destroyed.get()) return@Runnable
            try {
                val respObj = responseKey?.let { key ->
                    context.getProperty(context.globalObject, key)
                }
                asJsFunction(callback)?.call(error, respObj)
                tryHarvestUrl(context, respObj)
            } catch (th: Throwable) {
                LOG.e("lx-callback-failed " + sourceName + " : " + th.message)
            } finally {
                drain()
            }
        })
        pump.execute { jsExecutor.execute { drain() } }
    }

    private fun drain() {
        if (destroyed.get()) return
        var guard = 0
        while (pendingCallbacks.isNotEmpty() && guard++ < 32) {
            val next = pendingCallbacks.pollFirst() ?: break
            runCatching { next.run() }
        }
    }

    // ============================================================ 取链

    /**
     * 取播放地址。
     *
     * @param musicInfo 必须是洛雪原生的 musicInfo（带 songmid/hash 等），
     *                  传别家的歌脚本解不出来
     */
    fun getMusicUrl(musicInfo: JSONObject, quality: String = "320k"): String? {
        val handler = requestHandler
        if (handler == null) {
            lastError = if (lastError.isNotBlank()) lastError else "脚本未注册请求处理器"
            return null
        }
        val context = ctx ?: run {
            lastError = "脚本未加载"
            return null
        }

        // 取链前把平台字段补齐：不同平台靠不同键认歌，
        // 缺一个就会报 "kg song hash missing" / "cannot read property 'songmid'"。
        // 这里按 id 兜一层，值本来就是同一个 id 的不同别名。
        val normalized = normalizeMusicInfo(musicInfo)

        if (qualityMap.isNotEmpty() && sourcePlatforms.none { it == normalized.optString("source") }) {
            // 源不支持这个平台，直接说清楚，别让用户等 30 秒
            lastError = "这个源不支持 " + normalized.optString("source") + " 平台"
            return null
        }

        val latch = CountDownLatch(1)
        synchronized(resultLock) {
            resultUrl = null
            resultError = null
            resultLatch = latch
        }

        // ------------------------------------------------------------------
        // 关键结构：洛雪脚本读的是 event.info.musicInfo.xxx，不是 event.info.xxx。
        //
        // 之前把 musicInfo 的字段平铺进 info，脚本里 info.musicInfo 恒为 undefined，
        // 于是抛：
        //   cannot read property 'songmid' of undefined
        //   kg song hash missing
        //   缺少参数: source/platform, songId/songmid/id, quality/type
        // 表现就是「能搜到、点了播不出」。官方洛雪和旧标识的实现都是嵌一层。
        // ------------------------------------------------------------------
        val infoWrapper = JSONObject().apply {
            put("musicInfo", normalized)
            put("type", quality)
        }
        val payload = JSONObject().apply {
            put("source", normalized.optString("source"))
            put("action", "musicUrl")
            put("info", infoWrapper)
            put("quality", quality)
            put("type", quality)
        }

        jsExecutor.execute {
            try {
                val arg = jsonToJs(context, payload)
                val ret = asJsFunction(handler)?.call(arg)
                when {
                    isUrl(ret) -> resolveUrl(ret.toString())
                    ret is JSObject -> waitPromise(context, ret)
                    // 同步返回但为空：等 lx.request 回调
                }
            } catch (th: Throwable) {
                resolveError(th.message ?: th.javaClass.simpleName)
            }
        }

        // 脚本内部一般会走 lx.request，等回调链跑完
        val finished = latch.await(30, TimeUnit.SECONDS)
        if (!finished) {
            // 超时不直接放弃：可能结果刚落定
            synchronized(resultLock) {
                if (resultUrl == null) {
                    lastError = if (resultError != null) resultError.orEmpty() else "取播放地址超时（30s）"
                    return null
                }
            }
        }
        synchronized(resultLock) {
            if (resultUrl == null) {
                lastError = resultError ?: "脚本没有返回播放地址"
            }
            return resultUrl
        }
    }

    /**
     * 补齐脚本认歌所需的关键字段。
     *
     * 参考旧标识/影视 的 LxMusicInfo.build：五家平台的「命门字段」都在 extras 里，
     * 只给少数几个别名不够脚本换链。这里做两件事：
     *   1. 把 id 铺到各平台的主键上（songmid/hash/rid/copyrightId），
     *      值是同一个 id 的不同叫法；
     *   2. 补洛雪契约里的本名字段（name/singer/albumName），脚本按这个读。
     * 已有的值绝不覆盖。
     */
    private fun normalizeMusicInfo(raw: JSONObject): JSONObject {
        val out = JSONObject(raw.toString())
        val id = out.optString("id").ifBlank {
            out.optString("songmid").ifBlank {
                out.optString("hash").ifBlank { out.optString("DC_TARGETID") }
            }
        }
        if (id.isNotBlank()) {
            for (key in listOf("songmid", "hash", "rid", "copyrightId", "id")) {
                if (out.optString(key).isBlank()) out.put(key, id)
            }
        }
        // 平台名：脚本按 source 判断走哪个分支，缺了就是「缺少参数: source/platform」
        if (out.optString("source").isBlank()) {
            out.optString("platform").takeIf { it.isNotBlank() }?.let { out.put("source", it) }
        }
        // 洛雪契约本名
        val title = out.optString("title").ifBlank { out.optString("name") }
        if (out.optString("name").isBlank() && title.isNotBlank()) out.put("name", title)
        if (out.optString("title").isBlank() && title.isNotBlank()) out.put("title", title)
        val artist = out.optString("artist").ifBlank { out.optString("singer") }
        if (out.optString("singer").isBlank() && artist.isNotBlank()) out.put("singer", artist)
        val album = out.optString("album").ifBlank { out.optString("albumName") }
        if (out.optString("albumName").isBlank() && album.isNotBlank()) out.put("albumName", album)
        return out
    }

    /** 处理 async handler 返回的 Promise */
    private fun waitPromise(context: QuickJSContext, promise: JSObject) {
        // 先挂 then/catch，等 Promise 落定；落定前 lx.request 的回调也会继续跑
        try {
            val thenFn = context.evaluate(
                "(function(p,ok,err){try{p.then(ok,err)}catch(e){err(e)}})",
                "lx_promise.js",
            ) as? JSObject
            val onOk = JSCallFunction { args ->
                val v = args.getOrNull(0)
                if (isUrl(v)) resolveUrl(v.toString())
                null
            }
            val onErr = JSCallFunction { args ->
                resolveError(args.getOrNull(0)?.toString() ?: "promise rejected")
                null
            }
            asJsFunction(thenFn)?.call(promise, onOk, onErr)
        } catch (th: Throwable) {
            LOG.e("lx-promise-attach-failed " + sourceName + " : " + th.message)
        }
    }

    /**
     * 从 lx.request 的响应里捞播放地址。
     *
     * 脚本各家写法不一样，这里覆盖看到过的全部形态：
     *   resp.body.url / resp.body.finalUrl / resp.body.data.url
     *   resp.body 本身就是 url 字符串
     */
    private fun tryHarvestUrl(context: QuickJSContext, resp: Any?) {
        if (resp !is JSObject) return
        val body = context.getProperty(resp, "body")
        if (body is JSObject) {
            for (key in URL_KEYS) {
                val v = context.getProperty(body, key)
                if (isUrl(v)) {
                    resolveUrl(v.toString())
                    return
                }
            }
            val data = context.getProperty(body, "data")
            if (data is JSObject) {
                for (key in URL_KEYS) {
                    val v = context.getProperty(data, key)
                    if (isUrl(v)) {
                        resolveUrl(v.toString())
                        return
                    }
                }
            }
        } else if (isUrl(body)) {
            resolveUrl(body.toString())
        }
    }

    private fun isUrl(value: Any?): Boolean {
        val s = value?.toString() ?: return false
        return s.startsWith("http://") || s.startsWith("https://")
    }

    private fun resolveUrl(url: String) {
        synchronized(resultLock) {
            // 只认第一次（脚本有时会连发几个请求，先到先得）
            if (resultUrl == null && resultError == null) {
                resultUrl = url
                resultLatch?.countDown()
            }
        }
    }

    private fun resolveError(message: String) {
        synchronized(resultLock) {
            if (resultUrl == null && resultError == null) {
                resultError = message
                resultLatch?.countDown()
            }
        }
    }

    // ============================================================ 工具

    /**
     * 把 JSObject 当函数调用。
     *
     * wrapper 里 `JSObject` 接口本身没有 call()，只有 `JSFunction` 有
     * （`JSFunction extends JSObject`）。context.evaluate 出来的函数、
     * 以及脚本回传的函数，运行时实例都是 JSFunction，这里做一次安全转换。
     * 不是函数则返回 null，调用方按"没结果"处理，不会崩。
     */
    private fun asJsFunction(obj: Any?): JSFunction? = obj as? JSFunction

    /** 读数组/类数组对象的下标元素。JSObject 只有 getProperty(String)，取下标得走 JSArray.get */
    private fun jsItem(context: QuickJSContext, obj: JSObject, index: Int): Any? {
        return if (obj is com.whl.quickjs.wrapper.JSArray) {
            runCatching { obj.get(index) }.getOrNull()
        } else {
            runCatching { context.getProperty(obj, index.toString()) }.getOrNull()
        }
    }


    /** 读取 JS 对象的可枚举 key（wrapper 没现成 API，用 JS 侧 for-in） */
    private fun objectKeys(context: QuickJSContext, obj: JSObject): List<String> {
        val keys = ArrayList<String>()
        try {
            val fn = context.evaluate(
                "(function(o){var r=[];for(var k in o)r.push(k);return r.join('\\u0001')})",
                "lx_keys.js",
            ) as? JSObject ?: return keys
            val joined = asJsFunction(fn)?.call(obj)?.toString().orEmpty()
            if (joined.isEmpty()) return keys
            joined.split('\u0001').forEach { if (it.isNotEmpty()) keys.add(it) }
        } catch (th: Throwable) {
            LOG.e("lx-object-keys-failed " + sourceName + " : " + th.message)
        }
        return keys
    }

    /** Kotlin JSONObject -> JS 对象（深转换，脚本可以随便改） */
    private fun jsonToJs(context: QuickJSContext, value: Any?): Any? {
        val parsed = context.evaluate(
            "(function(s){return JSON.parse(s)})",
            "lx_tojs.js",
        ) as? JSObject ?: return null
        val result = asJsFunction(parsed)?.call(value?.toString() ?: "null")
        if (result is JSObject) {
            // hold 住，防止被回收
            runCatching { result.hold() }
        }
        return result
    }

    // ============================================================ 加密

    private fun aesEncrypt(buffer: String, mode: String, key: String, iv: String): String {
        return try {
            val isCbc = mode.contains("cbc", true)
            val c = javax.crypto.Cipher.getInstance(
                if (isCbc) "AES/CBC/PKCS5Padding" else "AES/ECB/PKCS5Padding",
            )
            val keySpec = javax.crypto.spec.SecretKeySpec(padKey(key), "AES")
            if (isCbc) {
                c.init(
                    javax.crypto.Cipher.ENCRYPT_MODE,
                    keySpec,
                    javax.crypto.spec.IvParameterSpec(padKey(iv)),
                )
            } else {
                c.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec)
            }
            android.util.Base64.encodeToString(
                c.doFinal(buffer.toByteArray(Charsets.UTF_8)),
                android.util.Base64.NO_WRAP,
            )
        } catch (th: Throwable) {
            LOG.e("lx-aes-failed " + sourceName + " : " + th.message)
            ""
        }
    }

    private fun padKey(value: String): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8)
        if (raw.size == 16 || raw.size == 24 || raw.size == 32) return raw
        val out = ByteArray(16)
        System.arraycopy(raw, 0, out, 0, minOf(raw.size, 16))
        return out
    }

    private fun rsaEncrypt(buffer: String, key: String): String {
        return try {
            val cleaned = key
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace(Regex("\\s"), "")
            val spec = java.security.spec.X509EncodedKeySpec(
                android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT),
            )
            val pub = java.security.KeyFactory.getInstance("RSA").generatePublic(spec)
            val c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, pub)
            android.util.Base64.encodeToString(
                c.doFinal(buffer.toByteArray(Charsets.UTF_8)),
                android.util.Base64.NO_WRAP,
            )
        } catch (th: Throwable) {
            LOG.e("lx-rsa-failed " + sourceName + " : " + th.message)
            ""
        }
    }

    // ============================================================ 销毁

    fun destroy() {
        if (destroyed.getAndSet(true)) return
        pendingCallbacks.clear()
        runCatching { resultLatch?.countDown() }
        pump.shutdownNow()
        jsExecutor.execute {
            runCatching { ctx?.destroy() }
            ctx = null
        }
        jsExecutor.shutdown()
        LOG.i("lx-destroyed " + sourceName)
    }

    companion object {
        private val DEFAULT_QUALITIES = listOf("128k", "320k")
        private val URL_KEYS = listOf("url", "finalUrl", "src", "playUrl")

        private val pool = HashMap<String, LxRuntime>()

        /**
         * 拿一个已就绪的运行时。
         *
         * 会等脚本发 inited（最多 8 秒）。调用方应在后台线程调。
         */
        fun obtain(source: MusicSource): LxRuntime? {
            if (source.codeMissing) return null
            pool[source.id]?.let { existing ->
                if (!existing.destroyed.get()) return existing
                pool.remove(source.id)
            }
            val runtime = LxRuntime(source.code, source.name)
            pool[source.id] = runtime
            runtime.ensureStarted()

            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline) {
                if (runtime.isReady || runtime.lastError.isNotBlank()) break
                Thread.sleep(40)
            }
            LOG.i(
                "lx-obtain [" + source.name + "] ready=" + runtime.isReady +
                    " platforms=" + runtime.sourcePlatforms.joinToString(",") +
                    (if (runtime.lastError.isBlank()) "" else " err=" + runtime.lastError),
            )
            return runtime
        }

        fun releaseAll() {
            pool.values.forEach { runCatching { it.destroy() } }
            pool.clear()
        }

        fun release(sourceId: String) {
            pool.remove(sourceId)?.let { runCatching { it.destroy() } }
        }
    }
}