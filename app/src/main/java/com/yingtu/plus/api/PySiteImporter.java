package com.yingtu.plus.api;

import android.content.Context;

import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.api.loader.BaseLoader;
import com.yingtu.plus.bean.Site;
import com.yingtu.plus.ui.activity.SourceActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地 py 文件导入成站点。
 *
 * 注册落点（没有现成注册链路，新建的落点）：
 * 1) ApiConfig 侧：不改 ApiConfig.java。导入的站以一个独立分组
 *    「本地 py 导入」记进 SourceActivity 的分组记录（Hawk api_config_groups），
 *    与订阅 JSON 的分组同一套机制：视频源页按分组展示，点站时由
 *    SourceActivity.switchToSource 把该组 JSON 重新 parseConfig 后切源。
 *    导入时不碰当前正在用的配置（不改 Hawk api_config_cache）。
 * 2) VodConfig 侧：内容链路（SourceViewModel → VodConfig.getSite(key) →
 *    SiteApi → BaseLoader → PyLoader）读的是 VodConfig 的站表；若 VodConfig
 *    当时已加载（站表非空），把导入的站同步加进这份内存站表，当次会话即可播；
 *    若 VodConfig 未加载（当前树里启动时没有加载 VodConfig 的调用），本站表
 *    加不进去——这是全站共有的前置缺口，不在本批可改文件内，回报里单独说明。
 * 3) 取流链路：走现成的 PyLoader。站的 api 字段就是 py 源码全文（不是文件路径）：
 *    com.fongmi.chaquo.Loader.spider(api) 在 api 非 http 时把 api 本身当源码写盘加载；
 *    所以导入时把文件内容原样放进 api。BaseLoader.isPy(api) 要求 api 含 ".py"
 *    子串，约半数真源源码里没有 ".py"，这种情况下在 api 末尾补一行 "# py"
 *    惰性注释（不改存盘的源文件，只改进 api 的那份字符串），否则路由会落到 SpiderNull。
 *
 * 安全边界：只读用户明确选中的文件/文件夹（单文件走系统选择器或外部
 * 分享进来的 Uri；批量只扫用户选中的那一个文件夹的顶层，不递归、不扫整机）；
 * 导入的 py 只做源注册，不执行 import 之外的动作；源文件另存一份到应用
 * 私有目录 files/py_sites/<key>.py 备查（原文件不动）。
 *
 * 缓存清理（上游移植时补，dm17 里还挂着）：PyLoader 按 key 缓存蜘蛛
 * （computeIfAbsent），重导/更新同名 py 后旧蜘蛛仍在缓存里、新源码不生效，
 * 此前建失败时缓存的 SpiderNull 同理。所以 importFiles 成功登记后与
 * updatePyImportByName 成功后都调 BaseLoader.get().clear()，清掉三路
 * loader 的蜘蛛缓存，下次点播重新按新 api 建蜘蛛。clear() 是异步的
 * （Task.execute），紧接着点播理论上有竞态，见 SourceActivity 的说明。
 */
public class PySiteImporter {

    /** 导入站点在视频源页里的分组名。 */
    public static final String GROUP_NAME = "本地 py 导入";

    /** 导入的 py 源文件在应用私有目录下的存放子目录。 */
    public static final String IMPORT_DIR = "py_sites";

    private static final Pattern GET_NAME_WINDOW = Pattern.compile("def\\s+getName\\s*\\(\\s*self\\s*\\)");
    private static final Pattern RETURN_LITERAL = Pattern.compile("return\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern RETURN_SITE_NAME = Pattern.compile("return\\s+SITE\\s*\\[\\s*['\"]name['\"]\\s*\\]");
    private static final Pattern SITE_DICT_NAME = Pattern.compile("SITE\\s*=\\s*\\{[^}]*?['\"]name['\"]\\s*:\\s*['\"]([^'\"]+)['\"]", Pattern.DOTALL);

    /** 调用方传进来的一个待导入文件：显示名 + 源码全文。 */
    public static class Input {
        public final String displayName;
        public final String source;

        public Input(String displayName, String source) {
            this.displayName = displayName == null ? "" : displayName;
            this.source = source == null ? "" : source;
        }
    }

    /** 单个文件的导入结果：逐个汇报，一个失败不影响其他。 */
    public static class Result {
        public final String fileName;
        public final boolean ok;
        /** 成功时为注册的站 key；失败时为空。 */
        public final String key;
        /** 成功时为站名。 */
        public final String name;
        /** 失败原因；成功时为空。 */
        public final String error;

        private Result(String fileName, boolean ok, String key, String name, String error) {
            this.fileName = fileName;
            this.ok = ok;
            this.key = key;
            this.name = name;
            this.error = error;
        }

        public static Result success(String fileName, String key, String name) {
            return new Result(fileName, true, key, name, null);
        }

        public static Result failure(String fileName, String error) {
            return new Result(fileName, false, null, null, error);
        }
    }

    /** 读 py 的 getName 当站名。解析不到就用文件名（去后缀）当站名。 */
    public static String parseSiteName(String source, String fallback) {
        String name = parseSiteName(source);
        if (name == null || name.trim().isEmpty()) return fallback == null ? "" : fallback.trim();
        return name.trim();
    }

    static String parseSiteName(String source) {
        if (source == null) return null;
        Matcher def = GET_NAME_WINDOW.matcher(source);
        if (!def.find()) return null;
        int end = Math.min(source.length(), def.end() + 400);
        String window = source.substring(def.start(), end);
        Matcher literal = RETURN_LITERAL.matcher(window);
        if (literal.find()) return literal.group(1);
        if (RETURN_SITE_NAME.matcher(window).find()) {
            Matcher dict = SITE_DICT_NAME.matcher(source);
            if (dict.find()) return dict.group(1);
        }
        return null;
    }

    /** 文件名 → 站 key：py_ + 小写字母数字下划线，其余字符变下划线。 */
    public static String sanitizeKey(String displayName) {
        String base = displayName == null ? "" : displayName.trim().toLowerCase(Locale.ROOT);
        if (base.endsWith(".py")) base = base.substring(0, base.length() - 3);
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        String key = "py_" + base.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if ("py_".equals(key)) key = "py_site";
        return key;
    }

    /** BaseLoader.isPy 要求 api 含 ".py"；源码里没有时末尾补一行惰性注释。 */
    public static String ensurePyMarker(String source) {
        if (source.contains(".py")) return source;
        return source + "\n# py\n";
    }

    /**
     * 批量导入（同步调用方在后台线程调）。逐个校验、逐个注册，一个失败不停全批；
     * 全部失败时不写分组记录。
     */
    public static List<Result> importFiles(Context context, List<Input> inputs) {
        List<Result> results = new ArrayList<>();
        List<JSONObject> newSites = new ArrayList<>();
        Map<String, JSONObject> kept = existingGroupSites();
        for (Input input : inputs) {
            String fileName = input.displayName.isEmpty() ? "unknown.py" : input.displayName;
            String source = input.source;
            if (source == null || source.trim().isEmpty()) {
                results.add(Result.failure(fileName, "文件为空"));
                continue;
            }
            if (!source.contains("class Spider")) {
                results.add(Result.failure(fileName, "不是 TVBox 蜘蛛：缺少 class Spider"));
                continue;
            }
            if (!source.contains("def getName")) {
                results.add(Result.failure(fileName, "缺少 getName 方法，无法取站名"));
                continue;
            }
            String fallback = fileName.toLowerCase(Locale.ROOT).endsWith(".py")
                    ? fileName.substring(0, fileName.length() - 3) : fileName;
            String name = parseSiteName(source, fallback);
            String baseKey = sanitizeKey(fileName);
            JSONObject old = kept.get(baseKey);
            if (old != null && ensurePyMarker(source).equals(old.optString("api"))) {
                results.add(Result.failure(fileName, "已导入过：同 key 同内容，未重复登记"));
                continue;
            }
            String key = uniqueKey(baseKey, kept, newSites);
            if (key == null) {
                results.add(Result.failure(fileName, "同名站点已存在"));
                continue;
            }
            File saved = saveSourceFile(context, key, source);
            if (saved == null) {
                results.add(Result.failure(fileName, "源文件存盘失败"));
                continue;
            }
            try {
                JSONObject site = new JSONObject();
                site.put("key", key);
                site.put("name", name);
                site.put("type", 3);
                site.put("api", ensurePyMarker(source));
                site.put("ext", "");
                site.put("searchable", 1);
                site.put("quickSearch", 1);
                site.put("filterable", 1);
                newSites.add(site);
                addToVodConfig(key, name, ensurePyMarker(source));
                results.add(Result.success(fileName, key, name));
            } catch (Exception e) {
                results.add(Result.failure(fileName, "登记失败：" + e.getMessage()));
            }
        }
        if (!newSites.isEmpty()) {
            try {
                JSONObject root = new JSONObject();
                JSONArray sites = new JSONArray();
                for (JSONObject keptSite : kept.values()) sites.put(keptSite);
                for (JSONObject site : newSites) sites.put(site);
                root.put("sites", sites);
                SourceActivity.recordConfigGroup(GROUP_NAME, root.toString());
            } catch (Exception ignored) {
            }
            // 移植补：清蜘蛛缓存，否则同名 key 的旧蜘蛛（或失败缓存的 SpiderNull）
            // 仍在 PyLoader 里，新源码不生效。
            BaseLoader.get().clear();
        }
        return results;
    }

    /**
     * 一键写 py 的换域名重生成：按显示名找已导入的同名站，就地更新源码。
     * 找不到同名站返回 null（调用方走 importFiles 新建）。
     * 只动三处：私有目录里的 <key>.py 文件、「本地 py 导入」分组 JSON 里的 api、
     * 内存站表里的 api；key 与站身份不变，本地数据共用同一站。
     * 调用方须先校验新源码（校验不过不要调这里，旧文件与登记一字不动）。
     */
    public static String updatePyImportByName(Context context, String name, String source) {
        if (name == null || name.trim().isEmpty()
                || source == null || source.trim().isEmpty()) return null;
        Map<String, JSONObject> kept = existingGroupSites();
        String targetKey = null;
        JSONObject targetSite = null;
        for (Map.Entry<String, JSONObject> e : kept.entrySet()) {
            if (name.trim().equals(e.getValue().optString("name").trim())) {
                targetKey = e.getKey();
                targetSite = e.getValue();
                break;
            }
        }
        if (targetKey == null || targetSite == null) return null;
        File saved = saveSourceFile(context, targetKey, source);
        if (saved == null) return null;
        try {
            targetSite.put("api", ensurePyMarker(source));
            JSONObject root = new JSONObject();
            JSONArray sites = new JSONArray();
            for (JSONObject s : kept.values()) sites.put(s);
            root.put("sites", sites);
            SourceActivity.recordConfigGroup(GROUP_NAME, root.toString());
        } catch (Exception ignored) {
        }
        try {
            List<Site> sites = VodConfig.get().getSites();
            if (sites != null) {
                for (Site site : sites) {
                    if (targetKey.equals(site.getKey())) {
                        site.setApi(ensurePyMarker(source));
                        site.setName(name.trim());
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 移植补：清蜘蛛缓存，否则 PyLoader 里仍是旧源码建出的蜘蛛，新源码不生效。
        BaseLoader.get().clear();
        return targetKey;
    }

    /**
     * 批量扫描：只扫用户选中的这一个文件夹的顶层 *.py（不递归、不扫整机）。
     * 同步调用方在后台线程调。
     */
    public static List<Result> importDirectory(Context context, File dir) {
        List<Result> results = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) {
            results.add(Result.failure(dir == null ? "" : dir.getAbsolutePath(), "不是有效文件夹"));
            return results;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            results.add(Result.failure(dir.getAbsolutePath(), "文件夹无法读取"));
            return results;
        }
        List<Input> inputs = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile()) continue;
            if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".py")) continue;
            try {
                inputs.add(new Input(file.getName(), readFile(file)));
            } catch (Exception e) {
                results.add(Result.failure(file.getName(), "读取失败：" + e.getMessage()));
            }
        }
        if (inputs.isEmpty() && results.isEmpty()) {
            results.add(Result.failure(dir.getAbsolutePath(), "文件夹里没有 .py 文件"));
            return results;
        }
        results.addAll(importFiles(context, inputs));
        return results;
    }

    /** 已登记在「本地 py 导入」分组里的站（key → 整条 site JSON），供去重与合并。 */
    private static Map<String, JSONObject> existingGroupSites() {
        Map<String, JSONObject> kept = new LinkedHashMap<>();
        try {
            for (SourceActivity.ConfigGroup group : SourceActivity.loadStoredGroups()) {
                if (!GROUP_NAME.equals(group.name)) continue;
                JSONObject root = new JSONObject(group.body);
                JSONArray sites = root.optJSONArray("sites");
                if (sites == null) continue;
                for (int i = 0; i < sites.length(); i++) {
                    JSONObject site = sites.optJSONObject(i);
                    if (site == null) continue;
                    String key = site.optString("key");
                    if (!key.isEmpty()) kept.put(key, site);
                }
            }
        } catch (Exception ignored) {
        }
        return kept;
    }

    /** key 去重：base 空闲直接用；被占时加 _2、_3 后缀；98 个后缀都占满返回 null。 */
    private static String uniqueKey(String base, Map<String, JSONObject> kept, List<JSONObject> fresh) {
        if (!kept.containsKey(base) && !containsKey(fresh, base) && ApiConfig.get().getSource(base) == null) return base;
        for (int i = 2; i < 100; i++) {
            String key = base + "_" + i;
            if (!kept.containsKey(key) && !containsKey(fresh, key) && ApiConfig.get().getSource(key) == null) return key;
        }
        return null;
    }

    private static boolean containsKey(List<JSONObject> sites, String key) {
        for (JSONObject site : sites) {
            if (key.equals(site.optString("key"))) return true;
        }
        return false;
    }

    /** 源文件原样另存到应用私有目录备查（原文件不动）。 */
    private static File saveSourceFile(Context context, String key, String source) {
        try {
            File dir = new File(context.getFilesDir(), IMPORT_DIR);
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = new File(dir, key + ".py");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(source.getBytes(StandardCharsets.UTF_8));
            }
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    /** VodConfig 当时已加载时，把站同步加进内存站表；未加载时跳过（见类注释第 2 点）。 */
    private static void addToVodConfig(String key, String name, String api) {
        try {
            List<Site> sites = VodConfig.get().getSites();
            if (sites == null || sites.isEmpty()) return;
            for (Site site : sites) {
                if (key.equals(site.getKey())) return;
            }
            Site site = new Site();
            site.setKey(key);
            site.setName(name);
            site.setType(3);
            site.setApi(api);
            site.setExt("");
            sites.add(site);
        } catch (Exception ignored) {
        }
    }

    private static String readFile(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int off = 0;
            while (off < bytes.length) {
                int n = in.read(bytes, off, bytes.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(bytes, 0, off, StandardCharsets.UTF_8);
        }
    }
}
