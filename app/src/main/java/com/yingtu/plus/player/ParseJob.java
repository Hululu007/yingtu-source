package com.yingtu.plus.player;

import android.text.TextUtils;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.common.net.HttpHeaders;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.App;
import com.yingtu.plus.Constant;
import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.api.loader.BaseLoader;
import com.yingtu.plus.bean.Parse;
import com.yingtu.plus.bean.Result;
import com.yingtu.plus.impl.ParseCallback;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.util.Sniffer;
import com.yingtu.plus.utils.Task;
import com.yingtu.plus.utils.UrlUtil;
import com.yingtu.plus.utils.WebViewUtil;

import okhttp3.Call;
import okhttp3.Response;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ParseJob implements ParseCallback {

    private final AtomicBoolean done = new AtomicBoolean();
    private final List<WebView> webViews = new ArrayList<>();
    private ExecutorService executor;
    private ExecutorService infinite;
    private Future<?> task;
    private volatile Call currentCall;
    private ParseCallback callback;
    private Parse parse;

    private ParseJob(ParseCallback callback) {
        this.executor = Executors.newSingleThreadExecutor();
        this.infinite = Executors.newCachedThreadPool();
        this.callback = callback;
    }

    public static ParseJob create(ParseCallback callback) {
        return new ParseJob(callback);
    }

    public ParseJob start(Result result, boolean useParse) {
        if (result == null) {
            onParseError();
            return this;
        }
        setParse(result, useParse);
        execute(result);
        return this;
    }

    private void setParse(Result result, boolean useParse) {
        if (useParse) parse = VodConfig.get().getParse();
        if (result.getPlayUrl().startsWith("json:")) parse = Parse.get(1, result.getPlayUrl().substring(5));
        if (result.getPlayUrl().startsWith("parse:")) parse = VodConfig.get().getParse(result.getPlayUrl().substring(6));
        if (parse == null || parse.isEmpty()) parse = Parse.get(0, result.getPlayUrl());
        parse.setHeader(result.getHeader());
        parse.setClick(getClick(result));
    }

    private String getClick(Result result) {
        String click = VodConfig.get().getSite(result.getKey()).getClick();
        if (!TextUtils.isEmpty(click)) return click;
        return result.getClick();
    }

    private void execute(Result result) {
        Future<?> submitted = executor.submit(getTask(result));
        this.task = submitted;
        Task.schedule(() -> {
            if (!done.get() && submitted.cancel(true)) onParseError();
        }, Constant.TIMEOUT_PARSE_DEF, TimeUnit.MILLISECONDS);
    }

    private Runnable getTask(Result result) {
        return () -> {
            try {
                doInBackground(result.getKey(), result.getUrl().v(), result.getFlag());
            } catch (Throwable e) {
                e.printStackTrace();
                onParseError();
            }
        };
    }

    private void doInBackground(String key, String webUrl, String flag) throws Throwable {
        if (Sniffer.isVideoFormat(webUrl)) {
            onParseSuccess(parse.getHeader(), webUrl, parse.getName());
            return;
        }
        switch (parse.getType()) {
            case 0:
                startWeb(key, parse, webUrl);
                break;
            case 1:
                jsonParse(parse, webUrl, true);
                break;
            case 2:
                jsonExtend(webUrl);
                break;
            case 3:
                jsonMix(webUrl, flag);
                break;
            case 4:
                superParse(webUrl, flag);
                break;
            default:
                onParseError();
                break;
        }
    }

    private void jsonParse(Parse item, String webUrl, boolean fatal) throws Exception {
        Call call = OkHttp.newCall(item.getUrl() + webUrl, item.getHeader());
        currentCall = call;
        try (Response res = call.execute()) {
            if (res.body() == null) {
                if (fatal) onParseError();
                return;
            }
            JsonObject object = Json.parse(res.body().string()).getAsJsonObject();
            String url = Json.safeString(object, "url");
            if (url.isEmpty()) {
                JsonElement data = object.get("data");
                if (data != null && data.isJsonObject()) url = Json.safeString(data.getAsJsonObject(), "url");
            }
            checkResult(getHeader(object), url, item.getName(), fatal);
        } finally {
            if (currentCall == call) currentCall = null;
        }
    }

    private void jsonExtend(String webUrl) throws Throwable {
        LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
        for (Parse item : VodConfig.get().getParses()) if (item.getType() == 1) jxs.put(item.getName(), item.extUrl());
        checkResult(Result.fromObject(BaseLoader.get().jsonExt(parse.getUrl(), jxs, webUrl)));
    }

    private void jsonMix(String webUrl, String flag) throws Throwable {
        LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
        for (Parse item : VodConfig.get().getParses()) jxs.put(item.getName(), item.mixMap());
        checkResult(Result.fromObject(BaseLoader.get().jsonExtMix(flag, parse.getUrl(), parse.getName(), jxs, webUrl)));
    }

    private void superParse(String webUrl, String flag) throws Exception {
        List<Parse> json = VodConfig.get().getParses(1, flag);
        List<Parse> webs = VodConfig.get().getParses(0, flag);
        int count = json.size() + (webs.isEmpty() ? 0 : 1);
        if (count == 0) {
            onParseError();
            return;
        }
        CountDownLatch latch = new CountDownLatch(count);
        for (Parse item : json) infinite.execute(() -> jsonParse(latch, item, webUrl));
        if (!webs.isEmpty()) {
            Parse web = webs.get(0);
            startWeb("", web.getName(), web.getHeader(), web.getUrl() + webUrl, web.getClick());
        }
        latch.await();
        onParseError();
    }

    private void jsonParse(CountDownLatch latch, Parse item, String webUrl) {
        try {
            jsonParse(item, webUrl, false);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            latch.countDown();
        }
    }

    private void checkResult(Map<String, String> headers, String url, String from, boolean fatal) {
        if (url != null && url.length() > 40) onParseSuccess(headers, url, from);
        else if (fatal) onParseError();
    }

    private void checkResult(Result result) {
        if (result == null) {
            onParseError();
            return;
        }
        result.setHeader(parse.getHeader());
        if (result.getUrl().isEmpty()) onParseError();
        // 解析跳过防盗（默认开）：解析结果不再走二次 WebView 嗅探校验，直接放行直链。
        // 关时保持原流程：结果仍需解析就再建 WebView 嗅探一次。
        else if (skipAntiLeech()) onParseSuccess(result.getHeader(), result.getUrl().v(), result.getJxFrom());
        else if (result.needParse()) startWeb(result.getHeader(), UrlUtil.convert(result.getUrl().v()));
        else onParseSuccess(result.getHeader(), result.getUrl().v(), result.getJxFrom());
    }

    /** 全局嗅探器：关时不建 WebView 嗅探，解析直接失败（不假装嗅探）。 */
    private static boolean globalSnifferOn() {
        try {
            return Hawk.get(HawkConfig.GLOBAL_SNIFFER, true);
        } catch (Throwable e) {
            return true;
        }
    }

    /** 解析跳过防盗：开时跳过解析结果的二次 WebView 嗅探校验。 */
    private static boolean skipAntiLeech() {
        try {
            return Hawk.get(HawkConfig.SKIP_ANTI_LEECH, true);
        } catch (Throwable e) {
            return true;
        }
    }

    private void startWeb(String key, Parse item, String webUrl) {
        startWeb(key, item.getName(), item.getHeader(), item.getUrl() + webUrl, item.getClick());
    }

    private void startWeb(Map<String, String> headers, String url) {
        startWeb("", "", headers, url, "");
    }

    private void startWeb(String key, String from, Map<String, String> headers, String url, String click) {
        // 全局嗅探器关了就不建 WebView：直接失败，不假装嗅探。
        if (!globalSnifferOn()) {
            onParseError();
            return;
        }
        // 嗅探View：只有系统自带 WebView 可用；XWalkView 需另下几十 MB 的
        // Crosswalk 运行时，未接入——设置里选不到它，这里只是兜底。
        try {
            if (Hawk.get(HawkConfig.SNIFFER_VIEW, 0) != 0) {
                onParseError();
                return;
            }
        } catch (Throwable ignored) {
        }
        if (TextUtils.isEmpty(url) || !WebViewUtil.support()) {
            onParseError();
            return;
        }
        App.post(() -> {
            try {
                WebView webView = new WebView(App.get());
                webViews.add(webView);
                WebViewUtil.configureBase(webView, "parse");
                Map<String, String> loadHeaders = headersForLoad(headers);
                String ua = loadHeaders.remove(HttpHeaders.USER_AGENT);
                if (!TextUtils.isEmpty(ua)) webView.getSettings().setUserAgentString(ua);
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        checkRequestUrl(request.getUrl().toString(), from);
                        return null;
                    }

                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                        checkRequestUrl(url, from);
                        return null;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        String target = request.getUrl().toString();
                        if (Sniffer.isVideoFormat(target)) {
                            onParseSuccess(parse.getHeader(), target, from);
                            return true;
                        }
                        return false;
                    }

                    @Override
                    public void onPageFinished(WebView view, String pageUrl) {
                        if (!TextUtils.isEmpty(click)) view.evaluateJavascript(click, null);
                        if (!TextUtils.isEmpty(view.getUrl()) && Sniffer.isVideoFormat(view.getUrl())) {
                            onParseSuccess(parse.getHeader(), view.getUrl(), from);
                        }
                    }
                });
                if (loadHeaders.isEmpty()) webView.loadUrl(url);
                else webView.loadUrl(url, loadHeaders);
            } catch (Throwable e) {
                e.printStackTrace();
                onParseError();
            }
        });
    }

    private void checkRequestUrl(String url, String from) {
        if (!done.get() && Sniffer.isVideoFormat(url)) onParseSuccess(parse.getHeader(), url, from);
    }

    private Map<String, String> headersForLoad(Map<String, String> headers) {
        Map<String, String> copy = new HashMap<>();
        if (headers == null) return copy;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) copy.put(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    private Map<String, String> getHeader(JsonObject object) {
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isJsonPrimitive()) continue;
            String key = entry.getKey();
            if (key.equalsIgnoreCase(HttpHeaders.USER_AGENT) || key.equalsIgnoreCase(HttpHeaders.REFERER) || key.equalsIgnoreCase(HttpHeaders.COOKIE) || key.equalsIgnoreCase("ua")) {
                headers.put(UrlUtil.fixHeader(key), Json.safeString(object, key));
            }
        }
        return headers.isEmpty() ? parse.getHeader() : headers;
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!done.compareAndSet(false, true)) return;
        App.post(() -> {
            if (callback != null) callback.onParseSuccess(headers, url, from);
            stop();
        });
    }

    @Override
    public void onParseError() {
        if (!done.compareAndSet(false, true)) return;
        App.post(() -> {
            if (callback != null) callback.onParseError();
            stop();
        });
    }

    private void stopWeb() {
        if (webViews.isEmpty()) return;
        List<WebView> views = new ArrayList<>(webViews);
        webViews.clear();
        App.post(() -> {
            for (WebView webView : views) {
                try {
                    webView.stopLoading();
                    webView.loadUrl("about:blank");
                    webView.destroy();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public void stop() {
        done.set(true);
        if (task != null) task.cancel(true);
        if (currentCall != null) currentCall.cancel();
        if (executor != null) executor.shutdownNow();
        if (infinite != null) infinite.shutdownNow();
        executor = null;
        infinite = null;
        task = null;
        currentCall = null;
        callback = null;
        stopWeb();
    }
}
