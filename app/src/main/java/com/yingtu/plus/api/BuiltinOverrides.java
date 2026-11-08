package com.yingtu.plus.api;

import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内置站用户覆盖清单（Hawk 持久化，卸载重装才清掉）。
 *
 * 结构照火蚁TV的源显示列表（sources_screen.dart:679 SourceVisibilityScreen）
 * 抄过来、自己写 Java：
 * - hiddenSites：隐藏的站 id 集合（id = 分区:key，如 midnight:hmav）。
 *   关闭的站不会出现在分区 tab 与各站列表里；更新、检测与已缓存内容不受影响，
 *   随时可以重新开启；“全部显示”一键清掉整个集合。
 * - deleted：从列表删掉的站（只在覆盖清单里记一笔，不动 assets 里的
 *   JSON/py/jar 原文件；删掉的站不再出现，卸载重装才恢复）。
 * - renamed：改名（key → 新显示名），分区 tab 与内置区列表同步显示新名。
 * - autoBlock：检测到失效站自动屏蔽的总开关，默认开。
 *
 * 三件全放同一个 Hawk 键里管（builtin_overrides），重启 App 后仍有效。
 */
public final class BuiltinOverrides {

    public static final String ZONE_MIDNIGHT = "midnight";
    public static final String ZONE_DRAMA = "drama";
    public static final String ZONE_LIVE = "live";
    public static final String ZONE_READING = "reading";
    /** 用户远程配置站（检测结果存盘与内置区分区用同一个 zone 键）。 */
    public static final String ZONE_USER = "user";

    /** Hawk 键（HawkConfig.java 不在本批可改文件内，用字面量，与 SourceActivity 的 GROUP_STORE_KEY 同例）。 */
    private static final String STORE_KEY = "builtin_overrides";

    private static Set<String> hidden;
    private static Set<String> deleted;
    private static Map<String, String> renamed;
    private static Boolean autoBlock;
    private static Boolean autoRecheck;
    private static int recheckHours;
    private static boolean loaded;

    private BuiltinOverrides() {
    }

    /** 分区 + 站 key 拼成覆盖清单里的 id。 */
    public static String id(String zone, String key) {
        return zone + ":" + (key == null ? "" : key);
    }

    /** 该站是否在隐藏清单里（手动关掉，或检测自动屏蔽）。 */
    public static synchronized boolean isHidden(String zone, String key) {
        ensureLoaded();
        return hidden.contains(id(zone, key));
    }

    /** 该站是否被删掉（从列表移除，只有卸载重装恢复）。 */
    public static synchronized boolean isDeleted(String zone, String key) {
        ensureLoaded();
        return deleted.contains(id(zone, key));
    }

    /** 该站是否可见（既没隐藏也没删）。分区 tab 与各站列表都以此为准。 */
    public static synchronized boolean isVisible(String zone, String key) {
        return !isHidden(zone, key) && !isDeleted(zone, key);
    }

    /**
     * 设显示开关（照火蚁TV setSiteVisible）：开 = 从隐藏清单里拿掉，
     * 关 = 推进隐藏清单。删掉的站不在这里恢复（删了就是删了）。
     */
    public static synchronized void setVisible(String zone, String key, boolean visible) {
        ensureLoaded();
        String siteId = id(zone, key);
        if (visible) {
            hidden.remove(siteId);
        } else {
            hidden.add(siteId);
        }
        save();
    }

    /** “全部显示”：一键把隐藏清单清掉（删掉的站不恢复）。 */
    public static synchronized void showAll() {
        ensureLoaded();
        if (hidden.isEmpty()) return;
        hidden.clear();
        save();
    }

    /** 当前隐藏了几个站（给内置区列表标题与设置入口计数用）。 */
    public static synchronized int hiddenCount() {
        ensureLoaded();
        return hidden.size();
    }

    /** 隐藏清单的副本。 */
    public static synchronized Set<String> hiddenIds() {
        ensureLoaded();
        return new LinkedHashSet<>(hidden);
    }

    /** 删站：记进删除清单（同时从隐藏清单里拿掉，两边不重复记）。 */
    public static synchronized void delete(String zone, String key) {
        ensureLoaded();
        String siteId = id(zone, key);
        hidden.remove(siteId);
        deleted.add(siteId);
        save();
    }

    /** 改显示名：空名表示恢复原名。 */
    public static synchronized void rename(String zone, String key, String name) {
        ensureLoaded();
        String siteId = id(zone, key);
        if (name == null || name.trim().isEmpty()) {
            renamed.remove(siteId);
        } else {
            renamed.put(siteId, name.trim());
        }
        save();
    }

    /** 显示名：改过就用新名，没改过用原名。 */
    public static synchronized String displayName(String zone, String key, String def) {
        ensureLoaded();
        String name = renamed.get(id(zone, key));
        return name != null ? name : def;
    }

    /** 检测到失效站自动屏蔽的总开关，默认开。 */
    public static synchronized boolean isAutoBlock() {
        ensureLoaded();
        return autoBlock == null || autoBlock;
    }

    public static synchronized void setAutoBlock(boolean value) {
        ensureLoaded();
        autoBlock = value;
        save();
    }

    /** 定时复查：打开内置区列表时，上次检测超过间隔就自动重测一轮。默认开，默认 24 小时。 */
    public static synchronized boolean isAutoRecheck() {
        ensureLoaded();
        return autoRecheck == null || autoRecheck;
    }

    public static synchronized void setAutoRecheck(boolean value) {
        ensureLoaded();
        autoRecheck = value;
        save();
    }

    /** 定时复查间隔（小时），默认 24。 */
    public static synchronized int recheckHours() {
        ensureLoaded();
        return recheckHours <= 0 ? 24 : recheckHours;
    }

    public static synchronized void setRecheckHours(int hours) {
        ensureLoaded();
        recheckHours = hours <= 0 ? 24 : hours;
        save();
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        hidden = new LinkedHashSet<>();
        deleted = new LinkedHashSet<>();
        renamed = new HashMap<>();
        autoBlock = null;
        autoRecheck = null;
        recheckHours = 24;
        try {
            String raw = Hawk.get(STORE_KEY, "");
            if (raw == null || raw.isEmpty()) return;
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.optJSONArray("hidden");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) hidden.add(arr.optString(i));
            }
            arr = root.optJSONArray("deleted");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) deleted.add(arr.optString(i));
            }
            JSONObject obj = root.optJSONObject("renamed");
            if (obj != null) {
                for (String k : listKeys(obj)) renamed.put(k, obj.optString(k));
            }
            if (root.has("autoBlock")) autoBlock = root.optBoolean("autoBlock", true);
            if (root.has("autoRecheck")) autoRecheck = root.optBoolean("autoRecheck", true);
            if (root.has("recheckHours")) recheckHours = root.optInt("recheckHours", 24);
        } catch (Throwable ignored) {
        }
    }

    private static List<String> listKeys(JSONObject obj) {
        List<String> keys = new ArrayList<>();
        try {
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) keys.add(it.next());
        } catch (Throwable ignored) {
        }
        return keys;
    }

    private static void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("hidden", new JSONArray(hidden));
            root.put("deleted", new JSONArray(deleted));
            JSONObject names = new JSONObject();
            for (Map.Entry<String, String> e : renamed.entrySet()) {
                names.put(e.getKey(), e.getValue());
            }
            root.put("renamed", names);
            root.put("autoBlock", autoBlock == null || autoBlock);
            root.put("autoRecheck", autoRecheck == null || autoRecheck);
            root.put("recheckHours", recheckHours <= 0 ? 24 : recheckHours);
            Hawk.put(STORE_KEY, root.toString());
        } catch (Throwable ignored) {
        }
    }
}
