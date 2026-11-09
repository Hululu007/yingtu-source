package com.yingtu.plus.gson;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.yingtu.plus.bean.Filter;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FilterAdapter implements JsonDeserializer<LinkedHashMap<String, List<Filter>>> {

    @Override
    public LinkedHashMap<String, List<Filter>> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
        LinkedHashMap<String, List<Filter>> map = new LinkedHashMap<>();
        try {
            if (json == null || json.isJsonNull()) return map;
            if (json.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                    map.put(entry.getKey(), parseList(entry.getValue(), context));
                }
                return map;
            }
            if (json.isJsonArray()) {
                List<Filter> items = parseList(json, context);
                if (!items.isEmpty()) map.put("", items);
                return map;
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private List<Filter> parseList(JsonElement json, JsonDeserializationContext context) {
        List<Filter> items = new ArrayList<>();
        if (json == null || json.isJsonNull()) return items;
        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            for (JsonElement element : array) {
                Filter filter = parseFilter(element, context);
                if (filter != null) items.add(filter);
            }
            return items;
        }
        Filter filter = parseFilter(json, context);
        if (filter != null) items.add(filter);
        return items;
    }

    private Filter parseFilter(JsonElement element, JsonDeserializationContext context) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject obj = element.getAsJsonObject();
        if (!obj.has("key") && !obj.has("name") && !obj.has("value")) return null;
        try {
            return context.deserialize(element, Filter.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
