package com.yingtu.plus.api;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yingtu.plus.bean.SourceBean;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内置午夜区配置加载器（H 批）。
 *
 * 两件事，App 启动时做一次，不依赖订阅：
 * 1. 读 assets/builtin_midnight.json，走 ApiConfig.parseConfigMerge 合并进站表
 *    （不清表、不覆盖用户站、不改动当前选中源），并把 key 登记进内置站集合。
 * 2. 把 assets/jars/zakatv.jar 拷到 JarLoader 的缓存位置
 *    （Path.jar("assets/jars/zakatv.jar")，即 cache/jar/&lt;md5(名)&gt;.jar），
 *    并按文件 MD5 校验；JarLoader.parseJar 认 "jar;md5;..." 写法，缓存命中即直接加载，
 *    不走本地 HTTP 服务（本树 Server 全部回 404，assets:// 形式的地址下载不到）。
 *
 * 订阅加载（ApiConfig.parseConfig）会清空全表，内置站随之消失；
 * 午夜区每次可见时调 ensureLoaded()，缺失即重新合并回来。
 */
public class BuiltinConfigs {

    /** 内置午夜区配置（162 站：142 个 jar 站 + 20 个 py 站，以 builtin_midnight.json 为准）。 */
    public static final String MIDNIGHT_CONFIG_ASSET = "builtin_midnight.json";
    /** assets 里的 jar 文件。 */
    public static final String JAR_ASSET = "jars/zakatv.jar";
    /** 配置里登记的 jar 名（JarLoader 缓存键用这个字符串算 MD5）。 */
    public static final String JAR_NAME = "assets/jars/zakatv.jar";
    /** 火蚁TV树 zakatv.jar 的文件 MD5（与 lib/zakatv_builtin.dart 的 zakatvJarMd5 一致）。 */
    public static final String JAR_MD5 = "44c139f76ca17a4ffcfe89df5bb19910";
    /** 配置里 spider / 各 jar 站 jar 字段用的完整写法。 */
    public static final String JAR_SPEC = JAR_NAME + ";md5;" + JAR_MD5;

    /** 哨兵 key：合并是否还在表里（jar 站首条 + py 站首条）。 */
    private static final String SENTINEL_JAR = "ZakaHuangguo";
    private static final String SENTINEL_PY = "hmav";

    /** 本批内置午夜站的 key 全集（只含 builtin_midnight.json 里的站，站数以 JSON 为准）。 */
    private static final Set<String> midnightKeys = new LinkedHashSet<>();
    /** 本批站的原名（key → JSON 里的 name）：改名覆盖后仍能以原名为底还原显示。 */
    private static final java.util.Map<String, String> midnightNames = new java.util.LinkedHashMap<>();

    private BuiltinConfigs() {
    }

    /** App 启动时调一次（App.onCreate，ApiConfig.init 之后）。幂等，可重复调。 */
    public static synchronized void load(Context context) {
        if (context == null) return;
        try {
            String json = readAsset(context, MIDNIGHT_CONFIG_ASSET);
            if (!TextUtils.isEmpty(json)) {
                ApiConfig.get().parseConfigMerge(json);
                recordMidnightKeys(json);
            }
        } catch (Throwable ignored) {
        }
        ensureJarCached(context);
    }

    /**
     * 午夜区每次可见时调：内置站还在表里就什么都不做；
     * 被订阅加载清空后重新合并回来（已存在的 key 不重复、不覆盖）。
     */
    public static synchronized void ensureLoaded(Context context) {
        if (context == null) return;
        if (isMidnightPresent()) return;
        load(context);
    }

    /** 内置午夜站是否还在站表里。 */
    public static boolean isMidnightPresent() {
        return ApiConfig.get().getSource(SENTINEL_JAR) != null
                && ApiConfig.get().getSource(SENTINEL_PY) != null;
    }

    /**
     * 内置午夜站列表（按配置顺序），供午夜区页面直接用。
     * 注意：不要用 ApiConfig.isBuiltinSite 过滤——短剧区内置站同样会被登记为内置站，
     * 用本批自己的 midnightKeys 才只含午夜区站。
     * 覆盖清单在这里生效：隐藏/删掉的站不出列表，改过名的显示新名。
     */
    public static List<SourceBean> getMidnightSites() {
        List<SourceBean> result = new ArrayList<>();
        for (SourceBean s : ApiConfig.get().getSourceBeanList()) {
            if (!midnightKeys.contains(s.getKey())) continue;
            if (!BuiltinOverrides.isVisible(BuiltinOverrides.ZONE_MIDNIGHT, s.getKey())) continue;
            s.setName(BuiltinOverrides.displayName(BuiltinOverrides.ZONE_MIDNIGHT,
                    s.getKey(), midnightNames.getOrDefault(s.getKey(), s.getName())));
            result.add(s);
        }
        return result;
    }

    /**
     * 全部内置午夜站（不含删掉的，含隐藏的），供内置区列表管理页用——
     * 管理页本身要列出隐藏的站才能再打开，隐藏的站只在分区 tab 与各站列表里不出现。
     */
    public static List<SourceBean> getMidnightSitesAll() {
        List<SourceBean> result = new ArrayList<>();
        for (SourceBean s : ApiConfig.get().getSourceBeanList()) {
            if (!midnightKeys.contains(s.getKey())) continue;
            if (BuiltinOverrides.isDeleted(BuiltinOverrides.ZONE_MIDNIGHT, s.getKey())) continue;
            s.setName(BuiltinOverrides.displayName(BuiltinOverrides.ZONE_MIDNIGHT,
                    s.getKey(), midnightNames.getOrDefault(s.getKey(), s.getName())));
            result.add(s);
        }
        return result;
    }

    /** 该 key 是否本批内置午夜站。 */
    public static boolean isMidnightSite(String key) {
        return key != null && midnightKeys.contains(key);
    }

    /** 从配置 JSON 里记下本站的 key 全集与原名。 */
    private static void recordMidnightKeys(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("sites")) return;
            JsonArray sites = root.getAsJsonArray("sites");
            for (JsonElement e : sites) {
                JsonObject obj = e.getAsJsonObject();
                if (obj.has("key") && !obj.get("key").isJsonNull()) {
                    String key = obj.get("key").getAsString();
                    midnightKeys.add(key);
                    if (obj.has("name") && !obj.get("name").isJsonNull()) {
                        midnightNames.put(key, obj.get("name").getAsString());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** jar 缓存缺失或内容对不上 MD5 时，从 assets 拷一份过去；对上则不动。 */
    private static void ensureJarCached(Context context) {
        try {
            if (Util.equals(JAR_NAME, JAR_MD5)) return;
            File target = Path.jar(JAR_NAME);
            try (InputStream in = context.getAssets().open(JAR_ASSET);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            if (!Util.equals(JAR_NAME, JAR_MD5)) target.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 读 assets 文件为字符串。 */
    private static String readAsset(Context context, String assetPath) throws IOException {
        InputStream in = context.getAssets().open(assetPath);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
