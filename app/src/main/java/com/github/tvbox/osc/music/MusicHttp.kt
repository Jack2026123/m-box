package com.github.tvbox.osc.music

import com.github.tvbox.osc.util.LOG
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * MusicFree 插件的 HTTP 桥。
 *
 * 返回的是**约定的包装 JSON**，不是原始响应体：
 *
 *     { "__status": 200, "__statusText": "OK",
 *       "__headers": {...}, "__data": <能解析成 JSON 就解析，否则字符串> }
 *
 * 为什么这么绕：插件里的 axios 期望 resp.data 已经是对象
 * （MusicFree 本体的 axios 自动 JSON.parse），
 * 但 QuickJS 和 Kotlin 之间只能传字符串，
 * 所以在 Kotlin 侧先解析一次，再用 __data 保持结构。
 */
object MusicHttp {

    fun call(
        method: String,
        url: String,
        headersJson: String,
        body: String,
        timeoutMs: Long,
    ): String {
        try {
            val client = com.github.tvbox.osc.net.OkGoHelper.getDefaultClient()
                ?: return fail("http client not ready")

            val builder = okhttp3.Request.Builder().url(url)

            runCatching {
                if (headersJson.isNotBlank()) {
                    val h = JSONObject(headersJson)
                    val keys = h.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = h.optString(k)
                        if (v.isNotBlank()) {
                            runCatching { builder.header(k, v) }
                        }
                    }
                }
            }

            val m = method.uppercase()
            if (m == "GET" || m == "HEAD") {
                builder.get()
            } else {
                val type = contentTypeOf(headersJson) ?: "application/json; charset=utf-8"
                val mediaType = type.toMediaType()
                builder.method(m, body.toRequestBody(mediaType))
            }

            val call = client.newCall(builder.build())
            runCatching {
                call.timeout()
                    .timeout(timeoutMs.coerceIn(3_000L, 60_000L), java.util.concurrent.TimeUnit.MILLISECONDS)
            }

            call.execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val result = JSONObject()
                result.put("__status", resp.code)
                result.put("__statusText", resp.message.orEmpty())

                val hObj = JSONObject()
                runCatching {
                    for ((k, v) in resp.headers) {
                        hObj.put(k, v)
                    }
                }
                result.put("__headers", hObj)

                val asObj = runCatching { JSONObject(text) }.getOrNull()
                val asArr = if (asObj == null) {
                    runCatching { org.json.JSONArray(text) }.getOrNull()
                } else {
                    null
                }
                when {
                    asObj != null -> result.put("__data", asObj)
                    asArr != null -> result.put("__data", asArr)
                    else -> result.put("__data", text)
                }
                return result.toString()
            }
        } catch (th: Throwable) {
            LOG.e("mf-http-failed " + method + " " + url + " : " + th.message)
            return fail(th.message ?: th.javaClass.simpleName)
        }
    }

    private fun fail(msg: String): String {
        val o = JSONObject()
        o.put("__status", 0)
        o.put("__statusText", msg)
        o.put("__headers", JSONObject())
        o.put("__data", "")
        return o.toString()
    }

    private fun contentTypeOf(headersJson: String): String? {
        if (headersJson.isBlank()) return null
        return runCatching {
            val h = JSONObject(headersJson)
            val keys = h.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.equals("Content-Type", ignoreCase = true)) {
                    return h.optString(k).takeIf { it.isNotBlank() }
                }
            }
            null
        }.getOrNull()
    }
}