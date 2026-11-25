package com.github.tvbox.osc.bean;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

/**
 * 分站规则（从 muse-build 移植，P0-1）。
 * 一条规则对应一个或一组站：hosts 命中哪个站、regex 判定视频地址、script 是进站后要跑的 JS、exclude 是排除。
 * 配置 JSON 里 "rules" 数组的条目就长这样；没有 rules 段时返回空列表（muse 同款行为）。
 */
public class Rule {

    @SerializedName("name")
    private String name;
    @SerializedName("hosts")
    private List<String> hosts;
    @SerializedName("regex")
    private List<String> regex;
    @SerializedName("script")
    private List<String> script;
    @SerializedName("exclude")
    private List<String> exclude;

    public Rule(String name) {
        this.name = name;
    }

    public static Rule create(String name) {
        return new Rule(name);
    }

    public static Rule empty() {
        return new Rule("");
    }

    public static List<Rule> arrayFrom(JsonElement element) {
        Type listType = TypeToken.getParameterized(List.class, Rule.class).getType();
        List<Rule> items = new Gson().fromJson(element, listType);
        return items == null ? Collections.emptyList() : items;
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? "" : name;
    }

    public List<String> getHosts() {
        return hosts == null ? Collections.emptyList() : hosts;
    }

    public List<String> getRegex() {
        return regex == null ? Collections.emptyList() : regex;
    }

    public List<String> getScript() {
        return script == null ? Collections.emptyList() : script;
    }

    public List<String> getExclude() {
        return exclude == null ? Collections.emptyList() : exclude;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Rule it)) return false;
        return getName().equals(it.getName());
    }
}
