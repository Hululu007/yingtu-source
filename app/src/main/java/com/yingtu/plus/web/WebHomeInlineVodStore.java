package com.yingtu.plus.web;

import android.text.TextUtils;

import com.yingtu.plus.App;
import com.yingtu.plus.bean.Result;
import com.yingtu.plus.bean.Vod;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebHome 临时多集片库（fm.vodInline 的落点）。
 *
 * HomeWebBridge.playVodInline 把整集 payload 存进来，返回一个 id；详情页
 * 用该 id 走 SiteApi.detailContent/playerContent，分别由 detail()/player()
 * 提供数据。集地址以 "集名$地址#集名$地址" 拼进 Vod.playUrl，与
 * Vod.setFlags() 解析的格式一致。
 */
public class WebHomeInlineVodStore {

    public static final String KEY = "webhome_inline";

    private static final String HLS_FORMAT = "application/x-mpegURL";
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0 Mobile Safari/537.36";

    private static final Map<String, Entry> ITEMS = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> URL_HEADERS = new ConcurrentHashMap<>();

    private WebHomeInlineVodStore() {}

    /** 存入一集多集 payload，返回详情/取流用的 id。 */
    public static String put(JsonObject payload) {
        Vod vod = new Vod();
        String id = first(safe(payload, "vodId"), safe(payload, "id"), "webhome-" + UUID.randomUUID());
        String name = first(safe(payload, "vodName"), safe(payload, "vod_name"), safe(payload, "title"), id);
        String pic = first(safe(payload, "vodPic"), safe(payload, "vod_pic"), safe(payload, "pic"));
        String playFrom = first(safe(payload, "vodPlayFrom"), safe(payload, "vod_play_from"), safe(payload, "playFrom"), "WebHome");
        Map<String, String> headers = parseHeaders(payload.get("headers"));
        String playUrl = buildPlayUrl(payload.get("episodes"), headers);
        vod.setId(id);
        vod.setName(name);
        vod.setPic(pic);
        vod.setPlayFrom(playFrom);
        vod.setPlayUrl(playUrl);
        ITEMS.put(id, new Entry(App.gson().toJson(vod), headers));
        return id;
    }

    /** 详情：返回该 id 的 Vod。 */
    public static Result detail(String id) {
        Entry entry = ITEMS.get(id);
        if (entry == null || TextUtils.isEmpty(entry.vodJson)) return Result.error("WebHome inline VOD not found");
        return Result.vod(Vod.objectFrom(entry.vodJson));
    }

    /**
     * 取流：id 通常是集地址（VideoActivity 传 Episode.getUrl()），
     * 也兼容直接传 vod id（取首集）。flag 是线路名，原样回填。
     */
    public static Result player(String flag, String id) {
        String url = id;
        Map<String, String> headers = URL_HEADERS.get(id);
        if (headers == null) {
            Entry entry = ITEMS.get(id);
            if (entry != null) {
                url = firstEpisodeUrl(entry.vodJson);
                headers = entry.headers;
            }
        }
        if (headers == null) headers = new HashMap<>();
        Map<String, String> out = new HashMap<>(headers);
        if (!hasKeyIgnoreCase(out, "User-Agent")) out.put("User-Agent", DEFAULT_UA);
        Result result = new Result();
        result.setUrl(url == null ? "" : url);
        result.setParse(0);
        result.setFlag(flag == null ? "" : flag);
        result.setHeader(out);
        if (isHls(url)) result.setFormat(HLS_FORMAT);
        SpiderDebug.log("webhome-inline", "player flag=%s id=%s url=%s", flag, id, url);
        return result;
    }

    private static String buildPlayUrl(JsonElement element, Map<String, String> defaultHeaders) {
        if (element == null || !element.isJsonArray()) return "";
        StringBuilder builder = new StringBuilder();
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement child = array.get(i);
            JsonObject object = child != null && child.isJsonObject() ? child.getAsJsonObject() : new JsonObject();
            String url = first(safe(object, "mediaUrl"), safe(object, "pageUrl"), safe(object, "url"), safe(object, "href"),
                    child != null && child.isJsonPrimitive() ? child.getAsString() : "");
            if (TextUtils.isEmpty(url)) continue;
            Map<String, String> episodeHeaders = new HashMap<>(defaultHeaders);
            episodeHeaders.putAll(parseHeaders(object.get("headers")));
            String referer = safe(object, "referer");
            if (!TextUtils.isEmpty(referer)) episodeHeaders.put("Referer", referer);
            URL_HEADERS.put(url, episodeHeaders);
            String name = first(safe(object, "name"), safe(object, "label"), safe(object, "title"), String.format("%02d", i + 1));
            if (builder.length() > 0) builder.append("#");
            builder.append(name.replace("$", " ").replace("#", " ")).append("$").append(url);
        }
        return builder.toString();
    }

    private static String firstEpisodeUrl(String vodJson) {
        try {
            Vod vod = Vod.objectFrom(vodJson);
            String playUrl = vod == null ? "" : vod.getPlayUrl();
            if (TextUtils.isEmpty(playUrl)) return "";
            String first = playUrl.split("#")[0];
            int idx = first.indexOf('$');
            return idx >= 0 ? first.substring(idx + 1) : first;
        } catch (Throwable e) {
            return "";
        }
    }

    private static Map<String, String> parseHeaders(JsonElement element) {
        Map<String, String> map = new HashMap<>();
        if (element != null && element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String k : object.keySet()) {
                JsonElement value = object.get(k);
                if (value != null && value.isJsonPrimitive()) map.put(k, value.getAsString());
            }
        }
        return map;
    }

    private static boolean hasKeyIgnoreCase(Map<String, String> map, String key) {
        for (String k : map.keySet()) if (k.equalsIgnoreCase(key)) return true;
        return false;
    }

    private static boolean isHls(String url) {
        String value = url == null ? "" : url.toLowerCase();
        return value.contains(".m3u8") || value.contains("/playlist/");
    }

    private static String safe(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }

    private static String first(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value.trim();
        return "";
    }

    private static class Entry {
        final String vodJson;
        final Map<String, String> headers;

        Entry(String vodJson, Map<String, String> headers) {
            this.vodJson = vodJson;
            this.headers = headers;
        }
    }
}
