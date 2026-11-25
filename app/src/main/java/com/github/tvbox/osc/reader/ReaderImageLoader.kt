package com.github.tvbox.osc.reader

import com.github.tvbox.osc.reading.LegadoImageDecode

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.github.tvbox.osc.net.OkGoHelper
import com.github.tvbox.osc.util.AppContextHolder
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Coil loader for comic pages: same OkHttp fetcher family as the app
 * image pipeline, plus the spider player result's headers (Referer /
 * User-Agent / Cookie) injected per request — most comic CDNs reject
 * hot-linking without them. The URL-suffix headers ("@Referer=..") that
 * pic urls may already carry are handled again here harmlessly because
 * explicit values win only when the request does not already have them.
 *
 * Caching is explicit and shared: every per-header loader instance
 * points at the SAME disk + memory caches, so a page fetched under
 * one header set is a cache hit under any other, the totals are
 * bounded, and the disk cache lives under the app cache dir
 * (cacheDir/reader_images, 256 MB, Coil's own LRU eviction) where
 * the settings "clear cache" action and AppReset wipe it with the
 * rest of the app cache. Previously each loader silently carried its
 * own builder-default caches — same default directory, unshared
 * instances, unrecorded quota.
 */
object ReaderImageLoader {

    private const val DISK_CACHE_MAX_BYTES = 256L * 1024 * 1024

    private val loaders = ConcurrentHashMap<String, ImageLoader>()

    private val sharedDiskCache: DiskCache by lazy {
        val context = checkNotNull(AppContextHolder.context()) { "app context not installed" }
        DiskCache.Builder()
            .directory(File(context.cacheDir, "reader_images").absolutePath.toPath())
            .maxSizeBytes(DISK_CACHE_MAX_BYTES)
            .build()
    }

    private val sharedMemoryCache: MemoryCache by lazy {
        val context = checkNotNull(AppContextHolder.context()) { "app context not installed" }
        MemoryCache.Builder()
            .maxSizePercent(context, 0.20)
            .build()
    }

    fun get(context: Context, headers: Map<String, String>): ImageLoader {
        val key = headers.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
        loaders[key]?.let { return it }
        val client = OkHttpClient.Builder()
            .apply { OkGoHelper.getDefaultClient()?.dns?.let { dns(it) } }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                val builder = chain.request().newBuilder()
                var sourceKey: String? = null
                for ((k, v) in headers) {
                    if (k.equals("X-Legado-Source-Key", ignoreCase = true)) {
                        sourceKey = v
                        continue
                    }
                    if (chain.request().header(k) == null) {
                        runCatching { builder.header(k, v) }
                    }
                }
                val response = chain.proceed(builder.build())
                if (sourceKey.isNullOrBlank()) return@Interceptor response
                val body = response.body ?: return@Interceptor response
                val bytes = body.bytes()
                val decoded = LegadoImageDecode.decrypt(sourceKey, bytes)
                if (decoded === bytes || decoded.contentEquals(bytes)) return@Interceptor response
                response.newBuilder()
                    .body(decoded.toResponseBody(body.contentType()))
                    .build()
            })
            .build()
        val loader = ImageLoader.Builder(context.applicationContext)
            .components {
                add(OkHttpNetworkFetcherFactory(client))
            }
            .diskCache(sharedDiskCache)
            .memoryCache(sharedMemoryCache)
            .build()
        loaders[key] = loader
        return loader
    }
}
