package com.github.tvbox.osc.api

import com.github.tvbox.osc.bean.ParseBean
import com.github.tvbox.osc.net.OkGoHelper
import com.github.tvbox.osc.util.AdBlocker
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.M3u8
import com.github.tvbox.osc.util.VideoParseRuler
import com.google.gson.JsonObject

import java.util.ArrayList

object ConfigApplier {

    @JvmStatic
    fun applyHostRules(infoJson: JsonObject) {
        try {
            applyHostRulesInner(infoJson)
        } catch (th: Throwable) {
            LOG.e("echo-apply host rules failed, section skipped", th)
        }
    }

    private fun applyHostRulesInner(infoJson: JsonObject) {
        if (infoJson.has("rules")) {
            val rulesElement = infoJson.get("rules")
            if (rulesElement == null || !rulesElement.isJsonArray) {
                LOG.e("echo-rules field is not an array, skipped")
                return
            }
            VideoParseRuler.clearRule()
            for (oneHostRule in rulesElement.asJsonArray) {
                if (oneHostRule == null || !oneHostRule.isJsonObject) {
                    LOG.e("echo-skip bad rule entry (not an object): " + oneHostRule)
                    continue
                }
                try {
                    applyOneHostRule(oneHostRule.asJsonObject)
                } catch (th: Throwable) {
                    LOG.e("echo-skip bad rule entry: " + oneHostRule, th)
                }
            }
        }
    }

    private fun applyOneHostRule(obj: JsonObject) {
        if (obj.has("host")) {
            val host = obj.get("host").asString
            if (obj.has("rule")) {
                val ruleJsonArr = obj.getAsJsonArray("rule")
                val rule = ArrayList<String>()
                for (one in ruleJsonArr) {
                    val oneRule = one.asString
                    rule.add(oneRule)
                }
                if (rule.size > 0) {
                    VideoParseRuler.addHostRule(host, rule)
                }
            }
            if (obj.has("filter")) {
                val filterJsonArr = obj.getAsJsonArray("filter")
                val filter = ArrayList<String>()
                for (one in filterJsonArr) {
                    val oneFilter = one.asString
                    filter.add(oneFilter)
                }
                if (filter.size > 0) {
                    VideoParseRuler.addHostFilter(host, filter)
                }
            }
        }
        if (obj.has("hosts") && obj.has("regex")) {
            val rule = ArrayList<String>()
            val ads = ArrayList<String>()
            val regexArray = obj.getAsJsonArray("regex")
            for (one in regexArray) {
                val regex = one.asString
                if (M3u8.isAd(regex)) ads.add(regex) else rule.add(regex)
            }
            val array = obj.getAsJsonArray("hosts")
            for (one in array) {
                val host = one.asString
                VideoParseRuler.addHostRule(host, rule)
                VideoParseRuler.addHostRegex(host, ads)
            }
        }
        if (obj.has("hosts") && obj.has("script")) {
            val scripts = ArrayList<String>()
            val scriptArray = obj.getAsJsonArray("script")
            for (one in scriptArray) {
                val script = one.asString
                scripts.add(script)
            }
            val array = obj.getAsJsonArray("hosts")
            for (one in array) {
                val host = one.asString
                VideoParseRuler.addHostScript(host, scripts)
            }
        }
        if (obj.has("hosts") && obj.has("exclude")
                && obj.get("hosts").isJsonArray && obj.get("exclude").isJsonArray) {
            val excludes = ArrayList<String>()
            for (one in obj.getAsJsonArray("exclude")) {
                excludes.add(one.asString)
            }
            if (!excludes.isEmpty()) {
                for (one in obj.getAsJsonArray("hosts")) {
                    VideoParseRuler.addHostExclude(one.asString, excludes)
                }
            }
        }
    }

    @JvmStatic
    fun applyDoh(infoJson: JsonObject) {
        var dohJson = ""
        if (infoJson.has("doh")) {
            try {
                dohJson = infoJson.getAsJsonArray("doh").toString()
            } catch (e: Exception) {
                LOG.e("ApiConfig", e)
            }
        }
        OkGoHelper.applyDohConfig(dohJson)
    }

    @JvmStatic
    fun applyAds(infoJson: JsonObject) {
        try {
            if (infoJson.has("ads")) {
                val adsElement = infoJson.get("ads")
                if (adsElement == null || !adsElement.isJsonArray) {
                    LOG.e("echo-ads field is not an array, skipped")
                    return
                }
                for (host in adsElement.asJsonArray) {
                    try {
                        if (host == null || !host.isJsonPrimitive) {
                            LOG.e("echo-skip bad ads entry: " + host)
                            continue
                        }
                        if (!AdBlocker.hasHost(host.asString)) {
                            AdBlocker.addAdHost(host.asString)
                        }
                    } catch (th: Throwable) {
                        LOG.e("echo-skip bad ads entry: " + host, th)
                    }
                }
            }
        } catch (th: Throwable) {
            LOG.e("echo-apply ads failed, section skipped", th)
        }
    }

    @JvmStatic
    fun parseParseBeans(infoJson: JsonObject): List<ParseBean> {
        val parseBeans: MutableList<ParseBean> = ArrayList()
        if (infoJson.has("parses")) {
            val parsesElement = infoJson.get("parses")
            if (parsesElement == null || !parsesElement.isJsonArray) {
                LOG.e("echo-parses field is not an array, treat as no parses")
                return parseBeans
            }
            for (opt in parsesElement.asJsonArray) {
                if (opt == null || !opt.isJsonObject) {
                    LOG.e("echo-skip bad parse entry (not an object): " + opt)
                    continue
                }
                val obj = opt.asJsonObject
                var label = "?"
                try {
                    if (obj.has("name") && obj.get("name").isJsonPrimitive) label = obj.get("name").asString
                    val pb = ParseBean()
                    pb.name = obj.get("name").asString.trim { it <= ' ' }
                    pb.url = obj.get("url").asString.trim { it <= ' ' }
                    val ext = if (obj.has("ext") && obj.get("ext").isJsonObject) obj.get("ext").asJsonObject.toString() else ""
                    pb.ext = ext
                    pb.type = DefaultConfig.safeJsonInt(obj, "type", 0)
                    parseBeans.add(pb)
                } catch (th: Throwable) {
                    LOG.e("echo-skip bad parse entry name=" + label, th)
                }
            }
        }
        return parseBeans
    }

}
