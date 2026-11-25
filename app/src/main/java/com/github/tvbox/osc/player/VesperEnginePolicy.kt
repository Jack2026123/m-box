package com.github.tvbox.osc.player

import androidx.media3.mpvplayer.MpvPlayer as MpvCore
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV

object VesperEnginePolicy {

    @Volatile
    var mpvDecodeOverride: Boolean? = null

    @JvmStatic
    fun isMpvAvailable(): Boolean = runCatching { MpvCore.isAvailable() }.getOrDefault(false)

    @JvmStatic
    fun resolveMpv(url: String?, requestedMpv: Boolean): Boolean {
        if (url != null && url.startsWith("smb://")) return false
        if (!requestedMpv) return false
        return isMpvAvailable()
    }

    @JvmStatic
    fun mpvDecodeHard(): Boolean {
        mpvDecodeOverride?.let { return it }
        return KV.get(HawkConfig.EXO_DECODE, "硬解码") != "软解码"
    }

    @JvmStatic
    fun clearMpvDecodeOverride() {
        mpvDecodeOverride = null
    }
}
