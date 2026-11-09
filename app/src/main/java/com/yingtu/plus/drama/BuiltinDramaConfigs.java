package com.yingtu.plus.drama;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yingtu.plus.api.ApiConfig;
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
 * Built-in short-drama site loader (mirror of BuiltinConfigs, drama zone).
 *
 * Two jobs, done once at app start, independent of any subscription:
 * 1. Read assets/builtin_drama.json and merge it into the site table with
 *    ApiConfig.parseConfigMerge (no wipe, no overwrite of user sites), then
 *    record this batch's keys in dramaKeys.
 * 2. Make sure assets/jars/zakatv.jar is cached where JarLoader expects it
 *    (same jar + MD5 as the midnight batch; shared file, shared checksum).
 *
 * A subscription load (ApiConfig.parseConfig) wipes the whole table, so the
 * drama zone re-runs ensureLoaded() every time it becomes visible.
 *
 * NOT registered here: abjav, chinaxselecla08, dingjiaseku, 51xz, qipaosp
 * (placement undecided, parent agent to decide). They are absent from
 * builtin_drama.json and from DramaSites on purpose.
 */
public final class BuiltinDramaConfigs {

    /** Built-in drama config (22 sites: 19 py + 3 jar). */
    public static final String DRAMA_CONFIG_ASSET = "builtin_drama.json";
    /** assets jar file (shared with the midnight batch). */
    public static final String JAR_ASSET = "jars/zakatv.jar";
    /** Jar name as used for the JarLoader cache key (MD5 of this string). */
    public static final String JAR_NAME = "assets/jars/zakatv.jar";
    /** File MD5 of the zakatv.jar in assets (same as BuiltinConfigs). */
    public static final String JAR_MD5 = "b63fb5aa7f0ecb17cbd56465709a6242";
    /** Full jar spec used in the config's jar field. */
    public static final String JAR_SPEC = JAR_NAME + ";md5;" + JAR_MD5;

    /** Sentinel keys: first py site + first jar site of this batch. */
    private static final String SENTINEL_PY = "ai2026";
    private static final String SENTINEL_JAR = "ZakaHuangdou";

    /** This batch's site keys (only the 22 in builtin_drama.json). */
    private static final Set<String> dramaKeys = new LinkedHashSet<>();
    /** This batch's original names (key -> name in JSON): rename overlays apply on top. */
    private static final java.util.Map<String, String> dramaNames = new java.util.LinkedHashMap<>();

    private BuiltinDramaConfigs() {
    }

    /** Call once at app start (after ApiConfig.init). Idempotent. */
    public static synchronized void load(Context context) {
        if (context == null) return;
        try {
            String json = readAsset(context, DRAMA_CONFIG_ASSET);
            if (!TextUtils.isEmpty(json)) {
                ApiConfig.get().parseConfigMerge(json);
                recordDramaKeys(json);
            }
        } catch (Throwable ignored) {
        }
        ensureJarCached(context);
    }

    /** Drama zone calls this when it becomes visible: re-merge if wiped. */
    public static synchronized void ensureLoaded(Context context) {
        if (context == null) return;
        if (isDramaPresent()) return;
        load(context);
    }

    /** Whether this batch's sites are still in the table. */
    public static boolean isDramaPresent() {
        return ApiConfig.get().getSource(SENTINEL_JAR) != null
                && ApiConfig.get().getSource(SENTINEL_PY) != null;
    }

    /** This batch's sites in config order, for the drama zone page.
     * Overrides apply here: hidden/deleted sites are left out, renamed ones show the new name. */
    public static List<SourceBean> getDramaSites() {
        List<SourceBean> result = new ArrayList<>();
        for (SourceBean s : ApiConfig.get().getSourceBeanList()) {
            if (!dramaKeys.contains(s.getKey())) continue;
            if (!com.yingtu.plus.api.BuiltinOverrides.isVisible(
                    com.yingtu.plus.api.BuiltinOverrides.ZONE_DRAMA, s.getKey())) continue;
            s.setName(com.yingtu.plus.api.BuiltinOverrides.displayName(
                    com.yingtu.plus.api.BuiltinOverrides.ZONE_DRAMA, s.getKey(),
                    dramaNames.getOrDefault(s.getKey(), s.getName())));
            result.add(s);
        }
        return result;
    }

    /** All of this batch's sites except deleted ones (hidden ones included),
     * for the builtin zone management list. */
    public static List<SourceBean> getDramaSitesAll() {
        List<SourceBean> result = new ArrayList<>();
        for (SourceBean s : ApiConfig.get().getSourceBeanList()) {
            if (!dramaKeys.contains(s.getKey())) continue;
            if (com.yingtu.plus.api.BuiltinOverrides.isDeleted(
                    com.yingtu.plus.api.BuiltinOverrides.ZONE_DRAMA, s.getKey())) continue;
            s.setName(com.yingtu.plus.api.BuiltinOverrides.displayName(
                    com.yingtu.plus.api.BuiltinOverrides.ZONE_DRAMA, s.getKey(),
                    dramaNames.getOrDefault(s.getKey(), s.getName())));
            result.add(s);
        }
        return result;
    }

    /** Whether the key belongs to this batch's drama sites. */
    public static boolean isDramaSite(String key) {
        return key != null && dramaKeys.contains(key);
    }

    private static void recordDramaKeys(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("sites")) return;
            JsonArray sites = root.getAsJsonArray("sites");
            for (JsonElement e : sites) {
                JsonObject obj = e.getAsJsonObject();
                if (obj.has("key") && !obj.get("key").isJsonNull()) {
                    String key = obj.get("key").getAsString();
                    dramaKeys.add(key);
                    if (obj.has("name") && !obj.get("name").isJsonNull()) {
                        dramaNames.put(key, obj.get("name").getAsString());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Copy the jar to JarLoader's cache dir when missing or MD5-mismatched. */
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
