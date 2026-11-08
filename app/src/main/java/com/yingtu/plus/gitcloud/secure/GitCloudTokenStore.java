package com.yingtu.plus.gitcloud.secure;

import android.content.Context;
import android.content.SharedPreferences;

import com.yingtu.plus.App;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * Git 云盘令牌存储：令牌存在独立的 SharedPreferences（git_cloud_tokens），
 * 不与登录态文件混在一起；LoginStateSync 导出/恢复登录态时经本类整体
 * 打包与还原，返回导入成功的条数。
 */
public class GitCloudTokenStore {

    public static final String PREFS_NAME = "git_cloud_tokens";

    private GitCloudTokenStore() {}

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 导出全部令牌（空则返回空 JsonObject）。 */
    public static JsonObject exportTokens() {
        JsonObject object = new JsonObject();
        try {
            Map<String, ?> all = prefs().getAll();
            if (all == null) return object;
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                if (entry.getValue() == null) continue;
                object.addProperty(entry.getKey(), String.valueOf(entry.getValue()));
            }
        } catch (Throwable ignored) {
        }
        return object;
    }

    /** 导入令牌，返回写入成功的条数。 */
    public static int importTokens(JsonObject object) {
        if (object == null || object.size() == 0) return 0;
        SharedPreferences.Editor editor = prefs().edit();
        int count = 0;
        for (String key : object.keySet()) {
            if (object.get(key).isJsonNull()) continue;
            editor.putString(key, object.get(key).getAsString());
            count++;
        }
        editor.apply();
        return count;
    }
}
