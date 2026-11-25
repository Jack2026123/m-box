package com.github.tvbox.osc.api.loader
import android.text.TextUtils
import com.github.catvod.utils.Prefers
/**
 * r475 源「活域名」记忆 —— 对齐 muse-build：浏览/蜘蛛实际用了哪个镜像就记哪个，
 * 下次网页 Tab 优先直接开记下来的那个；10 分钟有效期；切换源时清掉别的站的记录。
 *
 * <p>存储：{@code live_host_<key> = "<url>\t<过期时间戳>"}，外加 {@code live_host_keys}
 * 维护已记录过的 key 列表（{@code Prefers} 不暴露 getAll，需要自己留名以便切换源清理）。
 */
object SiteLiveHost {
    private const val PREFIX = "live_host_"
    private const val KEYS = "live_host_keys"
    private const val TTL = 10L * 60L * 1000L   // 10 分钟

    /** 取该源记下的活域名；不存在或过期返回 null。 */
    @JvmStatic
    fun get(key: String?): String? {
        if (TextUtils.isEmpty(key)) return null
        val raw = Prefers.getString(PREFIX + key, "") ?: return null
        val tab = raw.indexOf('\t')
        if (tab <= 0) return null
        val url = raw.substring(0, tab)
        val ts = raw.substring(tab + 1).toLongOrNull() ?: return null
        if (System.currentTimeMillis() > ts) {
            Prefers.remove(PREFIX + key!!)
            return null
        }
        return url.ifEmpty { null }
    }

    /** 记录/刷新该源当前可用的活域名。 */
    @JvmStatic
    fun note(key: String?, url: String?) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(url)) return
        Prefers.put(PREFIX + key!!, url!!.trim() + "\t" + (System.currentTimeMillis() + TTL))
        rememberKey(key)
    }

    /** 切换源时调用：清掉除当前 key 外所有站的活域名记录。 */
    @JvmStatic
    fun clearOthers(keepKey: String?) {
        val keys = readKeys()
        for (k in keys) if (k != keepKey) Prefers.remove(PREFIX + k)
        Prefers.put(KEYS, if (keepKey.isNullOrEmpty()) "" else keepKey)
    }

    private fun rememberKey(key: String) {
        val set = LinkedHashSet(readKeys())
        set.add(key)
        Prefers.put(KEYS, set.joinToString("\n"))
    }

    private fun readKeys(): List<String> {
        val s = Prefers.getString(KEYS, "") ?: ""
        return s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
}