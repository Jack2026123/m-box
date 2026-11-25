package com.github.tvbox.osc.api.loader;

import com.github.tvbox.osc.bean.Rule;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.AdDomains;

import java.util.List;

/**
 * 分站规则 + 广告域名单（从 muse-build 移植，P0-1，已按 AVBox 接线改过）。
 *
 * muse 原版是懒合并 VodConfig + LiveConfig 的 rules/ads；AVBox 只有一个主配置，
 * 所以这里直接持有：rules 由 ApiConfig.parseJson 从配置 "rules" 数组解析后 set 进来，
 * 没有 rules 段就是空列表（muse 同款行为）；ads 直接读 AdBlocker 的主机表
 * （配置 "ads" + default_config.json 早已由 ConfigApplier.applyAds 写进 AdBlocker，
 * 两边同源，不另存一份）。
 */
public class RuleConfig {

    private List<Rule> rules = List.of();

    public static RuleConfig get() {
        return Loader.INSTANCE;
    }

    public List<Rule> getRules() {
        return rules;
    }

    /** ApiConfig.parseJson 解析到配置 "rules" 数组后调这个；没有 rules 段就传空列表。 */
    public void setRules(List<Rule> rules) {
        this.rules = rules == null ? List.of() : rules;
    }

    /** 广告域名单：与 AdBlocker 同源（配置 ads + 默认 ads）。 */
    public List<String> getAds() {
        return AdBlocker.getHosts();
    }

    /**
     * 该 host 是否命中广告单。
     * 与 AdBlocker.isAd 同款判定（fix 4 的域名边界匹配 + AdDomains），不走子串匹配防误杀；
     * 另外认带正则元字符的规则条目（muse 的 ads 允许写正则），按整串正则算。
     */
    public boolean isAdHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase().trim();
        for (String ad : getAds()) {
            String a = ad == null ? "" : ad.toLowerCase().trim();
            if (a.isEmpty()) continue;
            if (h.equals(a) || h.endsWith("." + a)) return true;
            if (looksLikeRegex(a)) {
                try {
                    if (h.matches(a)) return true;
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            if (AdDomains.isAdDomain(h)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 条目含正则元字符就当正则算，否则只按域名边界匹配（不走子串，防 myad.com 含 ad.com 这类误杀）。 */
    private static boolean looksLikeRegex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' || c == '+' || c == '?' || c == '^' || c == '$' || c == '[' || c == '(' || c == '|' || c == '\\') return true;
        }
        return false;
    }

    /** 子串包含或整串正则匹配（muse 的 Util.containOrMatch；AVBox 的 catvod 里没有，搬到这里）。 */
    public static boolean containOrMatch(String text, String rule) {
        if (text == null || rule == null) return false;
        String r = rule.trim();
        if (r.isEmpty()) return false;
        if (text.contains(r)) return true;
        try {
            return text.matches(r);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static class Loader {
        static volatile RuleConfig INSTANCE = new RuleConfig();
    }
}
