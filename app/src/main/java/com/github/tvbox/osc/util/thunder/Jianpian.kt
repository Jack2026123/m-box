package com.github.tvbox.osc.util.thunder

import com.github.tvbox.osc.util.LOG
import android.net.Uri
import android.text.TextUtils

import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.util.LocalIPAddress
import com.github.tvbox.osc.util.RegexUtils
import com.p2p.P2PClass

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import com.github.tvbox.osc.util.AppContextHolder


object Jianpian {

    @JvmStatic
    fun JPUrlDec(url: String): String? {
        if (App.getp2p() != null) {
            try {
                val decode = URLDecoder.decode(url, "UTF-8")
                val split = RegexUtils.getPattern("\\|").split(decode)
                var replace = split[0].replace("xg://", "ftp://")
                if (replace.contains("xgplay://")) {
                    replace = split[0].replace("xgplay://", "ftp://")
                }
                val burl = App.burl
                if (!burl.isNullOrEmpty()) {
                    val p2p = requireNotNull(App.getp2p()) { "p2p not initialized" }
                    p2p.P2Pdoxpause(burl.toByteArray(Charset.forName("GBK")))
                    p2p.P2Pdoxdel(burl.toByteArray(Charset.forName("GBK")))
                }
                App.burl = replace
                val p2p = requireNotNull(App.getp2p()) { "p2p not initialized" }
                p2p.P2Pdoxstart(replace.toByteArray(Charset.forName("GBK")))
                p2p.P2Pdoxadd(replace.toByteArray(Charset.forName("GBK")))
                return "http://" + LocalIPAddress.getIP(requireNotNull(AppContextHolder.context()) { "app context not initialized" }) + ":" + P2PClass.port + "/" + URLEncoder.encode(Uri.parse(replace).getLastPathSegment(), "GBK")
            } catch (e: Exception) {
                return e.localizedMessage
            }
        } else {
            return ""
        }
    }

    @JvmStatic
    fun finish() {
        // 任务收尾：pause+del 当前 xg 任务（取实例走 peekP2p，没起过引擎时不反向实例化）。
        try {
            val burl = App.burl
            if (!burl.isNullOrEmpty()) {
                val p2p = App.peekP2p()
                if (p2p != null) {
                    p2p.P2Pdoxpause(burl.toByteArray(Charset.forName("GBK")))
                    p2p.P2Pdoxdel(burl.toByteArray(Charset.forName("GBK")))
                    App.burl = ""
                }
            }
        } catch (th: Throwable) {
            LOG.e("Jianpian", th)
        }
        // 只收尾当前任务，不关 P2P 引擎：切集（手动/自动连播/切源）都经
        // stopOther() 到这里，引擎要保持热着供下一集直接用。引擎只在退出
        // 软件时关：AppManager.finishAllActivity() 入口 + App 的 Activity
        // 存活计数归零兜底（见 App.exitGuardCallbacks）。
    }

    @JvmStatic
    fun isJpUrl(url: String): Boolean {
        return url.startsWith("tvbox-xg:") || (Thunder.isFtp(url) && url.contains("gbl.114s"))
    }
}
