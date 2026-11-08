package com.yingtu.plus.api;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.yingtu.plus.api.loader.PyLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 一键写 py（从修仙版移植，Java 重写；生成器本体是修仙版自己的工具文件，
 * 原样放在 assets/py_tools/yijianxiepy.py 里，经影图现成的 Chaquopy 桥跑）。
 *
 * 流程（与修仙版一致）：
 * 1. 域名精确匹配：输入 host 对模板库 assets/onekey_templates.json 里
 *    site_* 模板的 probe host，命中只当 hint（只显示，不决定生成路子）；
 * 2. 调工具：PyLoader 载入工具 py，调 Spider.action("onekey_generate {url,name,hint}")，
 *    70 秒超时；工具内按固定顺序实测（苹果 CMS → TVBox 接口 → MacCMS →
 *    WordPress → Next.js），命中不上走 generic_html 通用嗅探；
 * 3. 校验：Java 正则验 class Spider + 7 个核心方法（修仙版 validate_source 的规则：
 *    缺 playerContent 不通过，其余缺失可补默认）；
 * 4. 导入：同名站已存在就地更新（换域名重生成，key/站身份不变），否则走
 *    PySiteImporter.importFiles 新建；多域名用 url1|url2 写法进 BASES 轮换，
 *    url#站名写法也能定站名。
 *
 * 注意：与修仙版一样，这里只做探测命中 + 语法校验，不跑首页/分类/搜索/取流、
 * 不验真播；生成的站去视频源页点站可测。
 */
public final class OneKeyPyService {

    /** 工具 py（修仙版原文件）与模板库在 assets 里的位置。 */
    public static final String TOOL_ASSET = "py_tools/yijianxiepy.py";
    public static final String TEMPLATES_ASSET = "onekey_templates.json";

    /** 调工具超时（秒，与修仙版 PyBridge.call 的 70 秒一致）。 */
    private static final long CALL_TIMEOUT_SECONDS = 70;
    /** 工具蜘蛛在 PyLoader 里的 key（只供本页调，不注册成站）。 */
    private static final String TOOL_KEY = "onekey_tool";

    /** 修仙版 validate_source 查的 7 个核心方法。 */
    private static final String[] CORE_METHODS = {
            "init", "homeContent", "homeVideoContent", "categoryContent",
            "detailContent", "searchContent", "playerContent"
    };

    /** 生成结果。 */
    public static class Result {
        public final boolean ok;
        public final String siteName;
        public final String mode;
        public final String templateId;
        public final String api;
        public final String entryKey;
        public final String message;
        public final boolean updated;

        public Result(boolean ok, String siteName, String mode, String templateId,
                      String api, String entryKey, String message, boolean updated) {
            this.ok = ok;
            this.siteName = siteName == null ? "" : siteName;
            this.mode = mode == null ? "" : mode;
            this.templateId = templateId == null ? "" : templateId;
            this.api = api == null ? "" : api;
            this.entryKey = entryKey == null ? "" : entryKey;
            this.message = message == null ? "" : message;
            this.updated = updated;
        }

        public static Result failure(String message) {
            return new Result(false, "", "", "", "", "", message, false);
        }
    }

    public interface Callback {
        void onDone(Result result);
    }

    /** 校验结果（修仙版 validate_source 的 Java 版：正则验，不跑 Python）。 */
    public static class Validation {
        public final boolean ok;
        public final boolean hasSpider;
        public final List<String> missing;

        public Validation(boolean ok, boolean hasSpider, List<String> missing) {
            this.ok = ok;
            this.hasSpider = hasSpider;
            this.missing = missing == null ? new ArrayList<>() : missing;
        }
    }

    private static final OneKeyPyService INSTANCE = new OneKeyPyService();

    public static OneKeyPyService get() {
        return INSTANCE;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** 工具调用单独一个线程：与 worker 分开，否则 Future.get 会自己等自己死锁。 */
    private final ExecutorService toolCalls = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 工具专用的 PyLoader（独立于播放链的 loader，不动播放用的那份）。 */
    private final PyLoader toolLoader = new PyLoader();

    private final Object templatesLock = new Object();
    private Map<String, String> domainTemplates;
    private boolean templatesLoaded;

    private OneKeyPyService() {
    }

    // ===== 模板匹配 =====

    /** 模板库域名精确匹配：输入网址的 host 对上已知站，返回模板 id；没对上返回 null。 */
    public String matchDomainTemplate(Context context, String url) {
        ensureTemplates(context);
        String host = hostOf(url);
        if (host == null) return null;
        if (domainTemplates.containsKey(host)) return domainTemplates.get(host);
        if (host.startsWith("www.")) {
            String bare = host.substring(4);
            if (domainTemplates.containsKey(bare)) return domainTemplates.get(bare);
        }
        return null;
    }

    private void ensureTemplates(Context context) {
        synchronized (templatesLock) {
            if (templatesLoaded) return;
            templatesLoaded = true;
            Map<String, String> map = new HashMap<>();
            try {
                String raw = readAsset(context, TEMPLATES_ASSET);
                JSONObject root = new JSONObject(raw);
                JSONArray items = root.optJSONArray("templates");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null) continue;
                        String id = item.optString("id", "").trim();
                        if (id.isEmpty()) continue;
                        JSONObject detection = item.optJSONObject("detection");
                        if (detection == null) continue;
                        JSONArray probes = detection.optJSONArray("probe");
                        if (probes == null) continue;
                        for (int j = 0; j < probes.length(); j++) {
                            String host = hostOf(probes.optString(j, ""));
                            if (host == null || host.contains("{")) continue;
                            if (!map.containsKey(host)) map.put(host, id);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            domainTemplates = map;
        }
    }

    /** 取 host：没写协议的先补 https://；带 { 占位符的 probe 取不到 host。 */
    private static String hostOf(String url) {
        if (TextUtils.isEmpty(url)) return null;
        try {
            String u = url.trim();
            if (!u.contains("://")) u = "https://" + u;
            // probe 形如 "GET https://host/"，先去掉方法词。
            int space = u.indexOf(' ');
            if (space >= 0) u = u.substring(space + 1).trim();
            Uri uri = Uri.parse(u);
            String host = uri.getHost();
            return TextUtils.isEmpty(host) ? null : host.toLowerCase();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ===== 校验 =====

    /** 验 class Spider + 7 个核心方法；缺 playerContent 不通过，其余缺失可补默认。 */
    public static Validation validatePySource(String source) {
        List<String> missing = new ArrayList<>();
        boolean hasSpider = source != null && source.contains("class Spider");
        if (source == null) source = "";
        for (String method : CORE_METHODS) {
            Pattern p = Pattern.compile("def\\s+" + method + "\\s*\\(");
            if (!p.matcher(source).find()) missing.add(method);
        }
        boolean ok = hasSpider && !missing.contains("playerContent");
        return new Validation(ok, hasSpider, missing);
    }

    // ===== 生成并导入（后台跑，主线程回推） =====

    public void generate(Context context, String url, String name, Callback callback) {
        final Context appContext = context == null ? null : context.getApplicationContext();
        final String urlArg = url == null ? "" : url.trim();
        final String nameArg = name == null ? "" : name.trim();
        worker.execute(() -> {
            Result result = generateSync(appContext, urlArg, nameArg);
            mainHandler.post(() -> {
                if (callback != null) callback.onDone(result);
            });
        });
    }

    private Result generateSync(Context context, String url, String name) {
        if (TextUtils.isEmpty(url)) return Result.failure("请先输入网站地址");
        if (context == null) return Result.failure("上下文缺失");
        String hint = matchDomainTemplate(context, url);
        String toolSource;
        try {
            toolSource = readAsset(context, TOOL_ASSET);
        } catch (Throwable e) {
            return Result.failure("工具文件读不到：" + shortMsg(e));
        }
        if (TextUtils.isEmpty(toolSource) || !toolSource.contains("class Spider")) {
            return Result.failure("工具文件损坏：找不到 Spider");
        }
        String raw;
        try {
            JSONObject payload = new JSONObject();
            payload.put("url", url);
            payload.put("name", name);
            payload.put("hint", hint == null ? "" : hint);
            Spider spider = toolLoader.getSpider(TOOL_KEY, toolSource, "");
            raw = callWithTimeout(spider, "onekey_generate " + payload.toString());
        } catch (TimeoutException e) {
            return Result.failure("生成超时（" + CALL_TIMEOUT_SECONDS + " 秒）：工具可能在等上游响应，请重试");
        } catch (Throwable e) {
            return Result.failure("生成失败：" + shortMsg(e));
        }
        JSONObject data;
        try {
            data = new JSONObject(raw == null ? "" : raw);
        } catch (Throwable e) {
            return Result.failure("工具返回的不是 JSON：" + shortMsg(e));
        }
        if (!data.optBoolean("ok", false)) {
            return Result.failure(data.optString("msg", data.optString("error", "生成失败")));
        }
        String source = data.optString("source", "");
        if (TextUtils.isEmpty(source.trim())) {
            return Result.failure("生成的 py 源码为空");
        }
        String siteName = data.optString("name", "").trim();
        String mode = data.optString("mode", "");
        String templateId = data.optString("template_id", "");
        String api = data.optString("api", "");
        Validation validation = validatePySource(source);
        if (!validation.ok) {
            return Result.failure("生成的 py 校验不过："
                    + (validation.hasSpider ? "缺方法 " + TextUtils.join("、", validation.missing)
                    : "缺少 class Spider"));
        }
        if (TextUtils.isEmpty(siteName)) siteName = "一键生成";
        // 同名站已存在：就地更新（换域名重生成），不重复建站。
        String updatedKey = PySiteImporter.updatePyImportByName(context, siteName, source);
        if (updatedKey != null) {
            return new Result(true, siteName, mode, templateId, api, updatedKey,
                    "已更新同名站", true);
        }
        List<PySiteImporter.Result> imported = PySiteImporter.importFiles(context,
                Collections.singletonList(
                        new PySiteImporter.Input(siteName + ".py", source)));
        if (imported == null || imported.isEmpty() || !imported.get(0).ok) {
            String err = imported != null && !imported.isEmpty()
                    ? imported.get(0).error : "导入失败";
            return Result.failure("生成好了但导入失败：" + err);
        }
        return new Result(true, siteName, mode, templateId, api,
                imported.get(0).key, "已生成并导入", false);
    }

    private String callWithTimeout(final Spider spider, final String action)
            throws ExecutionException, InterruptedException, TimeoutException {
        Future<String> future = toolCalls.submit(() -> spider.action(action));
        try {
            return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private static String readAsset(Context context, String asset) throws IOException {
        InputStream in = context.getAssets().open(asset);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private static String shortMsg(Throwable e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        if (TextUtils.isEmpty(m)) return e.getClass().getSimpleName();
        if (m.length() > 60) m = m.substring(0, 60) + "…";
        return m;
    }
}
