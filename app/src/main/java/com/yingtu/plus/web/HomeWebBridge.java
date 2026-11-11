package com.yingtu.plus.web;

import android.app.Activity;
import android.text.TextUtils;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.yingtu.plus.App;
import com.yingtu.plus.setting.Setting;
import com.yingtu.plus.api.SiteApi;
import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.bean.History;
import com.yingtu.plus.bean.Site;

import com.yingtu.plus.ui.activity.HomeActivity;
import com.yingtu.plus.ui.activity.SearchActivity;
import com.yingtu.plus.util.HistoryHelper;





import com.yingtu.plus.ui.activity.VideoActivity;
import com.yingtu.plus.utils.ImgUtil;
import com.yingtu.plus.utils.Notify;
import com.yingtu.plus.utils.Task;
import com.yingtu.plus.web.ext.WebHomeExtensionRegistry;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HomeWebBridge {

    private static final int INLINE_LIMIT = 12000;
    private static final int CHUNK_SIZE = 60000;

    private final HomeWebController controller;
    private final Activity activity;
    private final WebView webView;
    private final Map<String, String> results;

    public HomeWebBridge(HomeWebController controller, Activity activity, WebView webView) {
        this.controller = controller;
        this.activity = activity;
        this.webView = webView;
        this.results = new ConcurrentHashMap<>();
    }

    @JavascriptInterface
    public void invoke(String requestId, String method, String payload) {
        Task.execute(() -> handle(requestId, method, WebCall.object(payload)));
    }

    @JavascriptInterface
    public void console(String level, String message) {
        controller.dispatchDebugConsole(level, message);
    }

    @JavascriptInterface
    public void network(String type, String method, String url, int status, long durationMs, String detail) {
        controller.dispatchDebugNetwork(type, method, url, status, durationMs, detail);
    }

    @JavascriptInterface
    public String resourceUrl(String url, String options) {
        JsonObject object = WebCall.object(options);
        StringBuilder builder = new StringBuilder(WebServer.get().getAddress("/webResource?url=")).append(encode(url));
        if (object.has("headers")) builder.append("&headers=").append(encode(object.get("headers").toString()));
        if ("include".equals(Json.safeString(object, "credentials"))) builder.append("&credentials=include");
        return builder.toString();
    }

    @JavascriptInterface
    public int resultLength(String id) {
        String result = results.get(id);
        return result == null ? 0 : result.length();
    }

    @JavascriptInterface
    public String resultChunk(String id, int start) {
        String result = results.get(id);
        if (result == null || start < 0 || start >= result.length()) return "";
        return result.substring(start, Math.min(start + CHUNK_SIZE, result.length()));
    }

    @JavascriptInterface
    public void clearResult(String id) {
        results.remove(id);
    }

    private void handle(String requestId, String method, JsonObject payload) {
        try {
            SpiderDebug.log("webhome", "invoke method=%s payload=%s", method, payload);
            String result = switch (method) {
                case "net.request" -> WebCall.request(payload, controller);
                case "net.resourceUrl" -> quote(resourceUrl(Json.safeString(payload, "url"), payload.toString()));
                case "player.playUrl" -> playUrl(payload);
                case "player.playVod" -> playVod(payload);
                case "player.playVodInline" -> playVodInline(payload);
                case "player.preloadArtwork" -> preloadArtwork(payload);
                case "player.control" -> control(payload);
                case "player.status" -> WebCall.request(statusPayload());
                case "app.search" -> search(payload);
                case "app.openVod" -> openVod();
                case "app.openLive" -> openLive();
                case "app.openKeep" -> openKeep();
                case "app.openSetting" -> openSetting();
                case "app.history" -> history();
                case "pan.check" -> checkLinks(payload);
                case "pan.play" -> playPan(payload);
                case "cache.get" -> quote(Prefers.getString(cacheKey(payload)));
                case "cache.set" -> cacheSet(payload);
                case "cache.del" -> cacheDel(payload);
                case "device.info" -> device();
                case "site.info" -> site();
                case "config.info" -> config();
                case "ext.info" -> extInfo();
                case "ext.log" -> extLog(payload);
                case "ext.toast" -> extToast(payload);
                case "ui.setToolbar" -> setToolbar(payload);
                case "ui.setChrome" -> setChrome(payload);
                case "ui.restoreChrome" -> restoreChrome();
                case "ui.getViewport" -> controller.getViewportJson();
                case "navigation.back" -> back();
                case "navigation.reload" -> reload();
                default -> throw new IllegalArgumentException("Unknown method: " + method);
            };
            resolve(requestId, result);
        } catch (Throwable e) {
            reject(requestId, e.getMessage());
        }
    }

    private String playUrl(JsonObject payload) {
        String url = Json.safeString(payload, "url");
        String title = Json.safeString(payload, "title");
        String pic = Json.safeString(payload, "pic");
        String wall = wallPic(payload);
        String content = content(payload);
        if (payload.has("headers") || "include".equals(Json.safeString(payload, "credentials"))) url = resourceUrl(url, payload.toString());
        final String playUrl = url;
        final String playTitle = TextUtils.isEmpty(title) ? playUrl : title;
        final String playPic = pic;
        final String playWall = wall;
        final String playContent = content;
        SpiderDebug.log("webhome", "player.playUrl title=%s url=%s", playTitle, playUrl);
        App.post(() -> VideoActivity.start(activity, SiteApi.PUSH, playUrl, playTitle, playPic, null, playWall, playContent));
        return "{}";
    }

    private String playVod(JsonObject payload) {
        String siteKey = Json.safeString(payload, "siteKey");
        String vodId = Json.safeString(payload, "vodId");
        String title = Json.safeString(payload, "title");
        String pic = Json.safeString(payload, "pic");
        String wall = wallPic(payload);
        String content = content(payload);
        App.post(() -> VideoActivity.start(activity, siteKey, vodId, title, pic, null, wall, content));
        return "{}";
    }

    private String playVodInline(JsonObject payload) {
        String vodId = WebHomeInlineVodStore.put(payload);
        String title = Json.safeString(payload, "title");
        if (TextUtils.isEmpty(title)) title = Json.safeString(payload, "vod_name");
        String pic = Json.safeString(payload, "pic");
        if (TextUtils.isEmpty(pic)) pic = Json.safeString(payload, "vod_pic");
        String mark = Json.safeString(payload, "mark");
        String wall = wallPic(payload);
        String content = content(payload);
        final String playTitle = TextUtils.isEmpty(title) ? vodId : title;
        final String playPic = pic;
        final String playMark = mark;
        final String playWall = wall;
        final String playContent = content;
        SpiderDebug.log("webhome", "player.playVodInline title=%s id=%s mark=%s", playTitle, vodId, playMark);
        App.post(() -> VideoActivity.start(activity, WebHomeInlineVodStore.KEY, vodId, playTitle, playPic, playMark, playWall, playContent));
        JsonObject result = new JsonObject();
        result.addProperty("siteKey", WebHomeInlineVodStore.KEY);
        result.addProperty("vodId", vodId);
        return result.toString();
    }

    private String preloadArtwork(JsonObject payload) {
        String pic = Json.safeString(payload, "pic");
        String wall = wallPic(payload);
        App.post(() -> {
            ImgUtil.preload(activity, pic);
            if (!TextUtils.isEmpty(wall) && !TextUtils.equals(wall, pic)) ImgUtil.preload(activity, wall);
        });
        return "{}";
    }

    private String wallPic(JsonObject payload) {
        return Json.safeString(payload, "wallPic");
    }

    private String content(JsonObject payload) {
        String content = Json.safeString(payload, "content");
        if (TextUtils.isEmpty(content)) content = Json.safeString(payload, "vod_content");
        if (TextUtils.isEmpty(content)) content = Json.safeString(payload, "desc");
        if (TextUtils.isEmpty(content)) content = Json.safeString(payload, "description");
        return content;
    }

    private String control(JsonObject payload) {
        // No playback service in this tree: control is a no-op, page keeps working.
        return "{}";
    }

    private JsonObject statusPayload() {
        return new JsonObject();
    }

    private String search(JsonObject payload) {
        String keyword = Json.safeString(payload, "keyword");
        String pic = Json.safeString(payload, "pic");
        String wall = wallPic(payload);
        boolean direct = payload.has("direct") && payload.get("direct").getAsBoolean();
        App.post(() -> {
            android.content.Intent intent = new android.content.Intent(activity, SearchActivity.class);
            intent.putExtra("keyword", keyword);
            activity.startActivity(intent);
        });
        return "{}";
    }

    private String openLive() {
        App.post(() -> Notify.show("暂无直播页"));
        return "{}";
    }

    private String openVod() {
        App.post(controller::openVod);
        return "{}";
    }

    private String openKeep() {
        App.post(() -> {
            android.content.Intent intent = new android.content.Intent(activity, HomeActivity.class);
            intent.putExtra(HomeActivity.KEY_TAB, 4);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(intent);
        });
        return "{}";
    }

    private String openSetting() {
        App.post(controller::openSetting);
        return "{}";
    }

    private String history() {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (History item : HistoryHelper.getAll()) {
            JsonObject object = new JsonObject();
            object.addProperty("key", item.key);
            object.addProperty("vodPic", item.vodPic);
            object.addProperty("vodName", item.vodName);
            object.addProperty("vodFlag", String.valueOf(item.playFlag));
            object.addProperty("vodRemarks", item.episodeName);
            object.addProperty("position", item.position);
            object.addProperty("duration", item.duration);
            object.addProperty("createTime", item.updateTime);
            array.add(object);
        }
        return array.toString();
    }

    private String checkLinks(JsonObject payload) {
        return "[]";
    }

    private String playPan(JsonObject payload) {
        String url = Json.safeString(payload, "url");
        String title = Json.safeString(payload, "title");
        String type = Json.safeString(payload, "type");
        String pic = Json.safeString(payload, "pic");
        String wall = wallPic(payload);
        String content = content(payload);
        if (TextUtils.isEmpty(url)) throw new IllegalArgumentException("url不能为空");
        final String playUrl = stripPush(url.trim());
        final String playTitle = TextUtils.isEmpty(title) ? playUrl : title;
        final String playPic = pic;
        final String playWall = wall;
        final String playContent = content;
        SpiderDebug.log("webhome", "pan.play route=%s type=%s title=%s url=%s", SiteApi.PUSH, type, playTitle, playUrl);
        App.post(() -> VideoActivity.start(activity, SiteApi.PUSH, playUrl, playTitle, playPic, null, playWall, playContent));
        return "{}";
    }

    private String stripPush(String url) {
        return url.regionMatches(true, 0, "push://", 0, 7) ? url.substring(7) : url;
    }

    private String cacheSet(JsonObject payload) {
        Prefers.put(cacheKey(payload), Json.safeString(payload, "value"));
        return "{}";
    }

    private String cacheDel(JsonObject payload) {
        Prefers.remove(cacheKey(payload));
        return "{}";
    }

    private String cacheKey(JsonObject payload) {
        String rule = Json.safeString(payload, "rule");
        String key = Json.safeString(payload, "key");
        return "cache_" + (TextUtils.isEmpty(rule) ? "" : rule + "_") + key;
    }

    private String device() {
        JsonObject object = new JsonObject();
        object.addProperty("uuid", android.provider.Settings.Secure.getString(activity.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID));
        object.addProperty("name", android.os.Build.MODEL);
        object.addProperty("ip", WebServer.get().getAddress(""));
        object.addProperty("type", 1);
        object.addProperty("time", System.currentTimeMillis());
        return object.toString();
    }

    private String site() {
        Site site = VodConfig.get().getHome();
        JsonObject object = new JsonObject();
        object.addProperty("key", site.getKey());
        object.addProperty("name", site.getName());
        object.addProperty("homePage", site.getHomePage());
        object.addProperty("chromeMode", site.getChromeMode());
        object.add("webHomeChrome", site.getWebHomeChrome());
        object.addProperty("type", site.getType());
        object.add("header", App.gson().toJsonTree(site.getHeader()));
        return object.toString();
    }

    private String config() {
        JsonObject object = new JsonObject();
        object.addProperty("id", VodConfig.getCid());
        object.addProperty("url", VodConfig.getUrl());
        object.addProperty("desc", VodConfig.getDesc());
        object.addProperty("driveCheck", false);
        return object.toString();
    }

    private String extInfo() {
        JsonObject object = new JsonObject();
        Site site = VodConfig.get().getHome();
        object.addProperty("siteKey", site.getKey());
        object.addProperty("siteName", site.getName());
        object.addProperty("homePage", site.getHomePage());
        WebHomeExtensionRegistry.Snapshot snapshot = WebHomeExtensionRegistry.get().snapshot();
        object.addProperty("enabled", snapshot.enabled);
        object.addProperty("matched", snapshot.matchedCount);
        object.addProperty("ready", snapshot.readyCount);
        return object.toString();
    }

    private String extLog(JsonObject payload) {
        WebHomeExtensionRegistry.get().recordScriptLog(payload);
        SpiderDebug.log("webhome-ext", "script message=%s data=%s", Json.safeString(payload, "message"), payload.has("data") ? payload.get("data") : "");
        return "{}";
    }

    private String extToast(JsonObject payload) {
        String message = Json.safeString(payload, "message");
        if (!TextUtils.isEmpty(message)) App.post(() -> Notify.show(message));
        return "{}";
    }

    private String setToolbar(JsonObject payload) {
        boolean visible = !payload.has("visible") || payload.get("visible").getAsBoolean();
        App.post(() -> controller.setToolbar(visible));
        return "{}";
    }

    private String setChrome(JsonObject payload) {
        App.post(() -> controller.setChrome(payload));
        return "{}";
    }

    private String restoreChrome() {
        App.post(controller::restoreChrome);
        return "{}";
    }

    private String back() {
        App.post(controller::handleBack);
        return "{}";
    }

    private String reload() {
        App.post(controller::reload);
        return "{}";
    }

    private void resolve(String requestId, String data) {
        String payload = TextUtils.isEmpty(data) ? "null" : data;
        if (payload.length() > INLINE_LIMIT) {
            String resultId = requestId + "_" + System.nanoTime();
            results.put(resultId, payload);
            payload = "{\"__fmResultId\":" + quote(resultId) + "}";
        }
        eval("window.fongmiNative&&window.fongmiNative.resolve(" + quote(requestId) + "," + payload + ")");
    }

    private void reject(String requestId, String error) {
        eval("window.fongmiNative&&window.fongmiNative.reject(" + quote(requestId) + "," + quote(error) + ")");
    }

    private void eval(String script) {
        App.post(() -> webView.evaluateJavascript(script, null));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String quote(String text) {
        return App.gson().toJson(text == null ? "" : text);
    }
}
