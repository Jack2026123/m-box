package com.github.catvod.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

public class Json {

    public static Map<String, String> toMap(JsonElement json) {
        Map<String, String> map = new HashMap<>();
        if (json == null || !json.isJsonObject()) return map;
        JsonObject obj = json.getAsJsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : obj.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) map.put(entry.getKey(), entry.getValue().getAsString());
        }
        return map;
    }
}
