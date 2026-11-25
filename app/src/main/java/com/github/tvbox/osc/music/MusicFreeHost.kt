package com.github.tvbox.osc.music

import com.github.tvbox.osc.util.LOG
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * MusicFree 插件运行时。
 *
 * 与 [LxRuntime] 同构（单线程 QuickJS + 回调排队），区别只在协议层：
 *
 *   洛雪       事件式   lx.on('request', handler) → 壳 send 事件
 *   MusicFree  模块式   module.exports = { search, getMediaSource, ... }
 *
 * ============================================================
 * 三条硬约束（和 LxRuntime 一样，别绕）
 * ------------------------------------------------------------
 * 1. 所有 QuickJS 调用锁在 jsExecutor 单线程。
 *    插件 handler 内部会调 axios → __mf_http → 回到 JS，
 *    换个线程碰 JS 值直接崩。
 *
 * 2. __mf_http 同步取 HTTP，但 Promise 的 resolve 由 drain()
 *    在 handler 返回后执行，否则「单线程自己等自己」→ 死锁。
 *
 * 3. console 必须挂上。
 *    2.65 的教训：不挂 console，插件一调 console.log 就抛异常，
 *    Promise 链被毒化，表现为「插件加载失败」。
 */
class MusicFreeHost private constructor(
    private val code: String,
    private val sourceName: String,
) {

    private val jsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mfjs-" + sourceName)
    }

    private val pump: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mfpump-" + sourceName)
    }

    private val destroyed = AtomicBoolean(false)

    @Volatile
    private var ctx: QuickJSContext? = null

    @Volatile
    private var booted = false

    @Volatile
    var platform: String = ""
        private set

    @Volatile
    var lastError: String = ""
        private set

    val isReady: Boolean get() = booted

    /** 插件声明实现了哪些方法，自检显示用 */
    @Volatile
    var supportedMethods: List<String> = emptyList()
        private set

    /** 待执行回调队列（跨线程安全） */
    private val pendingCallbacks = java.util.ArrayDeque<Runnable>()

    /** cheerio 文档句柄 */
    private val cheerioHandles = java.util.concurrent.ConcurrentHashMap<Int, MusicCheerio>()
    private val cheerioSeq = AtomicInteger(1)

    /** 一次异步调用的结果槽 */
    private class Slot {
        val latch = CountDownLatch(1)

        @Volatile
        var value: String? = null

        @Volatile
        var error: String? = null

        @Volatile
        var done = false

        fun finish(v: String?, e: String?) {
            if (done) return
            done = true
            value = v
            error = e
            latch.countDown()
        }
    }

    // ============================================================ 启动

    private fun ensureStarted() {
        jsExecutor.execute {
            if (destroyed.get()) return@execute
            if (ctx != null) return@execute
            try {
                boot()
            } catch (th: Throwable) {
                lastError = "插件加载失败：" + (th.message ?: th.javaClass.simpleName)
                LOG.e("mf-boot-failed " + sourceName + " : " + lastError)
            }
        }
    }

    private fun boot() {
        val context = QuickJSContext.create()
        context.setConsole(object : QuickJSContext.Console {
            override fun log(info: String?) {
                LOG.i("mfjs[" + sourceName + "] " + info.orEmpty())
            }

            override fun info(info: String?) {
                LOG.i("mfjs[" + sourceName + "] " + info.orEmpty())
            }

            override fun warn(info: String?) {
                LOG.i("mfjs[" + sourceName + "] warn " + info.orEmpty())
            }

            override fun error(info: String?) {
                LOG.e("mfjs[" + sourceName + "] " + info.orEmpty())
            }
        })
        ctx = context

        installBridge(context)

        // 1. prelude：提供 module / exports / require / console
        context.evaluate(MusicFreePrelude.build(), sourceName + ".prelude.js")
        LOG.i("mf-prelude-ok " + sourceName)

        // 2. 插件本体。包一层 IIFE，让 var 声明的全局变量不外泄，
        //    module.exports 仍然挂在 prelude 建的那个 module 上。
        context.evaluate("(function(){\n" + code + "\n})();", sourceName + ".js")
        LOG.i("mf-boot-ok " + sourceName)

        // 3. 读 platform
        runCatching {
            val p = context.evaluate("__mf_platform()")
            if (p is String && p.isNotBlank()) platform = p
        }

        // 4. 探测方法
        val methods = ArrayList<String>()
        for (m in PROBE_METHODS) {
            val has = runCatching { context.evaluate("__mf_hasMethod('" + m + "')") }.getOrNull()
            if (has == true) methods.add(m)
        }
        supportedMethods = methods

        LOG.i(
            "mf-plugin-ready " + sourceName + " platform=" + platform +
                " methods=" + methods.joinToString(","),
        )

        if (methods.isEmpty()) {
            lastError = "插件没导出任何方法（module.exports 为空？）"
            LOG.e("mf-plugin-empty " + sourceName)
        } else {
            booted = true
            lastError = ""
        }
    }

    // ============================================================ 桥

    private fun installBridge(context: QuickJSContext) {
        val global = context.globalObject ?: return

        context.setProperty(global, "__mf_log", JSCallFunction { args ->
            val level = args.getOrNull(0)?.toString().orEmpty()
            val text = args.getOrNull(1)?.toString().orEmpty()
            if (level == "e") {
                LOG.e("mfjs[" + sourceName + "] " + text)
            } else {
                LOG.i("mfjs[" + sourceName + "] " + text)
            }
            null
        })

        context.setProperty(global, "__mf_pluginLoaded", JSCallFunction { args ->
            val p = args.getOrNull(0)?.toString().orEmpty()
            if (p.isNotBlank()) platform = p
            LOG.i("mf-plugin-loaded " + sourceName + " platform=" + p)
            null
        })

        context.setProperty(global, "__mf_http", JSCallFunction { args ->
            val method = args.getOrNull(0)?.toString().orEmpty().ifBlank { "GET" }
            val url = args.getOrNull(1)?.toString().orEmpty()
            val headersJson = args.getOrNull(2)?.toString().orEmpty()
            val body = args.getOrNull(3)?.toString().orEmpty()
            val timeout = (args.getOrNull(4) as? Number)?.toLong() ?: 10_000L
            if (url.isBlank()) "{}" else MusicHttp.call(method, url, headersJson, body, timeout)
        })

        context.setProperty(global, "__mf_crypto", JSCallFunction { args ->
            val op = args.getOrNull(0)?.toString().orEmpty()
            val a = args.getOrNull(1)?.toString()
            val b = args.getOrNull(2)?.toString()
            val c = args.getOrNull(3)?.toString()
            MusicCrypto.run(op, a, b, c)
        })

        context.setProperty(global, "__mf_bigop", JSCallFunction { args ->
            MusicCrypto.bigIntOp(
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
                args.getOrNull(2)?.toString().orEmpty(),
            )
        })

        context.setProperty(global, "__mf_cheerioLoad", JSCallFunction { args ->
            val html = args.getOrNull(0)?.toString().orEmpty()
            val id = cheerioSeq.getAndIncrement()
            cheerioHandles[id] = MusicCheerio.parse(html)
            id
        })

        context.setProperty(global, "__mf_cheerioSelect", JSCallFunction { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: -1
            val selector = args.getOrNull(1)?.toString().orEmpty()
            cheerioHandles[id]?.selectJson(selector).orEmpty().ifBlank { "[]" }
        })

        // prelude 里贴切调用名（语义更明确）
        context.setProperty(global, "__mf_cheerioSelectJson", JSCallFunction { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: -1
            val selector = args.getOrNull(1)?.toString().orEmpty()
            cheerioHandles[id]?.selectJson(selector).orEmpty().ifBlank { "[]" }
        })

        context.setProperty(global, "__mf_cheerioText", JSCallFunction { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: -1
            cheerioHandles[id]?.rootText().orEmpty()
        })

        context.setProperty(global, "__mf_cheerioFree", JSCallFunction { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: -1
            if (id > 0) cheerioHandles.remove(id)?.close()
            null
        })
    }

    // ============================================================ 对外 API

    fun search(keyword: String, page: Int): MusicSearchPage {
        ensureStarted()
        awaitBoot(15_000)
        if (!isReady) return MusicSearchPage(emptyList(), true)

        val text = callAsync(
            expr = "__mf_search(" + JSONObject.quote(keyword) + "," + page + ",'music')",
            what = "search",
            timeoutSec = 20,
        )
        if (text.isNullOrBlank()) return MusicSearchPage(emptyList(), true)
        return MusicFreeMapper.parseSearch(text, platform, sourceName)
    }

    fun getMediaSource(itemJson: String, quality: String): MusicMedia? {
        ensureStarted()
        awaitBoot(15_000)
        if (!isReady) return null

        val text = callAsync(
            expr = "__mf_getMediaSource(" + JSONObject.quote(itemJson) + "," +
                JSONObject.quote(quality) + ")",
            what = "getMediaSource",
            timeoutSec = 20,
        ) ?: return null
        return MusicFreeMapper.parseMedia(text)
    }

    fun getLyric(itemJson: String): String? {
        ensureStarted()
        awaitBoot(15_000)
        if (!isReady) return null
        return callAsync(
            expr = "__mf_getLyric(" + JSONObject.quote(itemJson) + ")",
            what = "getLyric",
            timeoutSec = 15,
        )?.takeIf { it.isNotBlank() }
    }

    /**
     * 通用异步调用：求值 → 拿到 Promise → 等结果。
     *
     * 求值和 then 挂载都在 jsExecutor 上；等结果在外线程；
     * pump 定时把 drain 推回 jsExecutor，让排队的回调有机会跑。
     */
    private fun callAsync(expr: String, what: String, timeoutSec: Long): String? {
        val ctxNow = ctx ?: return null
        val slot = Slot()
        jsExecutor.execute {
            try {
                val result = ctxNow.evaluate(expr)
                when (result) {
                    is JSObject -> attachPromise(ctxNow, result, slot)
                    is String -> slot.finish(result, null)
                    null -> slot.finish(null, "返回 null")
                    else -> slot.finish(result.toString(), null)
                }
            } catch (th: Throwable) {
                slot.finish(null, th.message ?: th.javaClass.simpleName)
            }
        }
        // pump：每隔一小段把 drain 推回 jsExecutor
        repeat(4) { i ->
            pump.schedule({ jsExecutor.execute { drain() } }, (30 + i * 50).toLong(), TimeUnit.MILLISECONDS)
        }
        if (!slot.latch.await(timeoutSec, TimeUnit.SECONDS)) {
            LOG.e("mf-" + what + "-timeout " + sourceName)
            return null
        }
        if (slot.error != null) {
            LOG.e("mf-" + what + "-error " + sourceName + " : " + slot.error)
        }
        return slot.value
    }

    private fun attachPromise(context: QuickJSContext, promise: JSObject, slot: Slot) {
        val thenFn = runCatching { promise.getProperty("then") }.getOrNull()
        if (thenFn !is JSObject) {
            slot.finish(null, "对象没有 then 方法")
            return
        }
        val onOk = JSCallFunction { args ->
            slot.finish(stringifyJs(args.getOrNull(0)), null)
            pump.schedule({ jsExecutor.execute { drain() } }, 10, TimeUnit.MILLISECONDS)
            null
        }
        val onErr = JSCallFunction { args ->
            val v = args.getOrNull(0)
            val msg = runCatching {
                (v as? JSObject)?.getProperty("message")?.toString() ?: v?.toString()
            }.getOrNull() ?: "unknown"
            slot.finish(null, msg)
            pump.schedule({ jsExecutor.execute { drain() } }, 10, TimeUnit.MILLISECONDS)
            null
        }
        runCatching { asJsFunction(thenFn)?.call(onOk, onErr) }
    }

    /**
     * 把 JSObject 当函数调用。
     *
     * wrapper 里 `JSObject` 接口没有 call()，只有 `JSFunction` 有
     * （`JSFunction extends JSObject`）。evaluate 出来的函数运行时实例是 JSFunction。
     */
    private fun asJsFunction(obj: Any?): JSFunction? = obj as? JSFunction

    /** JS 值 → String。对象交给 JS 侧 JSON.stringify，Kotlin 只收字符串 */
    private fun stringifyJs(v: Any?): String? {
        if (v == null) return null
        if (v is String) return v
        if (v is Number || v is Boolean) return v.toString()
        val context = ctx ?: return runCatching { v.toString() }.getOrNull()
        return runCatching {
            val fn = context.evaluate(
                "(function(o){try{return JSON.stringify(o)}catch(e){return String(o)}})",
            )
            asJsFunction(fn)?.call(v)?.toString() ?: v.toString()
        }.getOrNull()
    }

    private fun drain() {
        if (destroyed.get()) return
        while (true) {
            val task = synchronized(pendingCallbacks) { pendingCallbacks.poll() } ?: break
            runCatching { task.run() }.onFailure {
                LOG.e("mf-drain " + sourceName + " : " + it.message)
            }
        }
    }

    private fun awaitBoot(timeoutMs: Long) {
        val start = System.currentTimeMillis()
        while (!booted && System.currentTimeMillis() - start < timeoutMs) {
            if (destroyed.get()) return
            if (lastError.isNotBlank()) return
            try {
                Thread.sleep(20)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        runCatching { cheerioHandles.values.forEach { it.close() } }
        cheerioHandles.clear()
        runCatching {
            jsExecutor.execute {
                runCatching { ctx?.destroy() }
                ctx = null
            }
        }
        runCatching { jsExecutor.shutdown() }
        runCatching { pump.shutdownNow() }
    }

    companion object {
        private val PROBE_METHODS = listOf(
            "search",
            "getMediaSource",
            "getLyric",
            "getTopLists",
            "getAlbumInfo",
            "getMusicInfo",
            "getArtistWorks",
            "getSheetInfo",
        )

        private val instances =
            java.util.concurrent.ConcurrentHashMap<String, MusicFreeHost>()

        /** 按源 id 拿运行时（懒建、复用） */
        fun obtain(source: MusicSource): MusicFreeHost {
            val existing = instances[source.id]
            if (existing != null && (existing.isReady || existing.lastError.isNotBlank())) {
                return existing
            }
            if (existing != null) existing.destroy()
            val host = MusicFreeHost(source.code, source.name)
            instances[source.id] = host
            host.ensureStarted()
            return host
        }

        fun clear(id: String) {
            instances.remove(id)?.destroy()
        }

        fun clearAll() {
            instances.values.forEach { it.destroy() }
            instances.clear()
        }
    }
}