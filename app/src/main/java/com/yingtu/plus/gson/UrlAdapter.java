package com.yingtu.plus.gson;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.yingtu.plus.bean.Url;
import com.yingtu.plus.bean.Value;

import java.lang.reflect.Type;

/**
 * Url 反序列化器，支持单个 URL 字符串、数组或 Url 对象形态。
 * 返回类型必须与 Result.url 字段一致（Url）：旧版返回 List<String>，Gson 往 Url 字段塞 ArrayList 时抛
 * IllegalArgumentException，Result.objectFrom() catch 后返回 empty()，getRealUrl() 为空，播放全灭。
 */
public class UrlAdapter implements JsonDeserializer<Url> {

    @Override
    public Url deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
        if (json == null || json.isJsonNull()) {
            return Url.create();
        }
        if (json.isJsonPrimitive()) {
            return Url.create().add(json.getAsString());
        }
        if (json.isJsonArray()) {
            Url url = Url.create();
            for (JsonElement element : json.getAsJsonArray()) {
                if (element.isJsonPrimitive()) {
                    url.add(element.getAsString());
                } else if (element.isJsonObject() && element.getAsJsonObject().has("v")) {
                    Value value = context.deserialize(element, Value.class);
                    if (value != null) url.getValues().add(value);
                }
            }
            return url;
        }
        if (json.isJsonObject() && json.getAsJsonObject().has("v")) {
            Value value = context.deserialize(json, Value.class);
            Url url = Url.create();
            if (value != null) url.getValues().add(value);
            return url;
        }
        if (json.isJsonObject()) {
            Url url = context.deserialize(json, Url.class);
            return url == null ? Url.create() : url;
        }
        return Url.create();
    }
}
