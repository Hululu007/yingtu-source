package com.yingtu.plus.api;

import android.text.TextUtils;

import com.github.catvod.bean.Doh;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.App;
import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.bean.Parse;
import com.yingtu.plus.bean.ParseBean;
import com.yingtu.plus.bean.Rule;
import com.yingtu.plus.bean.Site;
import com.yingtu.plus.bean.SnifferRule;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.utils.UrlUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * ApiConfig 站表 → VodConfig 站表的内存同步器（J 批建，N 批补五段）。
 *
 * 背景：VodConfig 自带的 load(Config, Callback) 是从 Config 表里的订阅 URL
 * 联网拉配置 JSON；但本树里没有任何地方往 type=0 的 Config 行写过 URL
 * （订阅地址只存在 Hawk 的 API_URL，配置正文只存在 Hawk 的 API_CONFIG_CACHE，
 * 本地文件导入的配置连 URL 都没有），且本地 Server 全部回 404，
 * file:// / assets:// 的地址也拉不到。所以启动时直接调 VodConfig.load
 * 走不通，只能把 ApiConfig 已经解析好的站表同步进 VodConfig 的内存站表。
 *
 * 同步内容与 VodConfig.initSite 的 JSON 解析保持一致：key/name/type/api/
 * ext/jar/playUrl/searchable/quickSearch，空 jar 补配置级 spider，api/ext
 * 过 UrlUtil.convert，站名过 trans，当前源（ApiConfig.getHomeSource）
 * 记为 VodConfig 的 home 站。jar 站的取流不走下载：VodConfig.replaceSites
 * 里调 BaseLoader.parseJar(spider, true)，JarLoader 命中 H 批拷进缓存的
 * jar（Path.jar 位置 + MD5 校验）即直接加载。
 *
 * N 批：同步再补 parses/flags/rules/ads/doh 五段（J 批只同步了站表 + home
 * 站，parses 为空时 Result.shouldUseParse() 恒 false、ParseAdapter 为空，
 * 需解析器的站走不了 parse 路线）。五段与站表同一次 sync() 调用，顺序是
 * 先五段、后站表——replaceSites 末尾调 onLoadSuccess + postEvent，五段
 * 必须在事件发出前就位。调用点不用新增，沿用已有的 6 处。
 *
 * 五段来源：
 * parses/rules 用 ApiConfig 已解析好的 parseBeanList/snifferRules；
 * flags/ads/doh 用 Hawk API_CONFIG_CACHE 的原文 JSON（ApiConfig 没解析
 * 它们；缓存与 ApiConfig 解析用的正文是同一份，每次 parseConfig 后调用方
 * 都 Hawk.put 过：SourceActivity.switchToSource/loadConfig、
 * SettingActivity 本地 JSON 导入）。内置午夜配置 builtin_midnight.json
 * 只有 spider/sites 两段，不贡献五段。
 *
 * 空段策略：配置里没有某段即无，传空表清空旧值（订阅切换后旧解析器/
 * 旧 flag 不应残留；与 VodConfig.load 的 JSON 路径一致，缺段即空表）。
 * 只有调用方传 null 才不动旧值（本文件内永不传 null）。
 *
 * 转换损耗（已核实）：ParseBean 只有 name/type/url 三段，配置里 parses
 * 条目如带 ext（flag/header）会丢——VodConfig.getParses(type, flag)
 * 的 flag 过滤随之退回整 type 表；Rule 无 setter，与 SnifferRule 字段名
 * 一一对应（name/hosts/regex/script/exclude），走 App.gson() 往返逐个转。
 * ApiConfig 的 parseConfig 只收 hosts 非空的 rule，本同步沿用该口径。
 *
 * 当前解析器选择：优先保留 VodConfig 自己的已存选择（播放页点的，存在
 * Config.parse 里），其次用 ApiConfig 的默认解析器（设置页点的，存在
 * Hawk DEFAULT_PARSE），两个名字在新表里都对不上时由 replaceParses
 * 取表首位（即 Parse.god()，与 initParse 一致）。与公开 setParse(Parse)
 * 不打架：两边各管各的 selected（Parse 与 ParseBean 是两套对象）。
 *
 * 全部 try 包住：失败只打日志，不崩启动、不崩切源。
 */
public final class VodConfigSync {

    private VodConfigSync() {
    }

    /** 把当前 ApiConfig 的站表 + 五段同步进 VodConfig；五段先行，站表随后。 */
    public static void sync() {
        sync(true);
    }

    /**
     * 启动时调 sync(false)：只同步站表，不预加载 jar。
     * jar 预加载（DexClassLoader 建类加载器 + Init + YouTube 探活联网）冷启动
     * 要几秒，放主线程会拖慢启动；跳过后 jar 在第一次用蜘蛛时由
     * JarLoader.getSpider() 按需加载（那路走后台线程），站表本身不受影响。
     * 切源等其他调用点沿用 sync()（jar 已在内存里时 parseJar 直接返回，不耗时）。
     */
    public static void sync(boolean preloadJar) {
        try {
            syncMeta();
            syncSites(preloadJar);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /** 五段同步：parses/rules 走 ApiConfig 已解析的表，flags/ads/doh 走缓存原文 JSON。 */
    private static void syncMeta() {
        ApiConfig api = ApiConfig.get();
        // parses：ParseBean（name/type/url）逐个转 VodConfig 的 Parse。
        List<Parse> parses = new ArrayList<>();
        for (ParseBean pb : api.getParseBeanList()) {
            if (pb == null) continue;
            Parse parse = new Parse();
            parse.setName(pb.getName());
            parse.setType(pb.getType());
            parse.setUrl(pb.getUrl());
            parses.add(parse);
        }
        String preferred = VodConfig.get().getConfig().getParse();
        if (TextUtils.isEmpty(preferred) || noneNamed(parses, preferred)) {
            ParseBean def = api.getDefaultParse();
            preferred = def == null ? "" : def.getName();
            if (!TextUtils.isEmpty(preferred) && noneNamed(parses, preferred)) preferred = "";
        }
        VodConfig.get().replaceParses(parses, preferred);
        // rules：SnifferRule 走 Gson 往返转 Rule（字段名一一对应）。
        List<Rule> rules = new ArrayList<>();
        for (SnifferRule sr : api.getSnifferRules()) {
            if (sr == null) continue;
            try {
                Rule rule = App.gson().fromJson(App.gson().toJson(sr), Rule.class);
                if (rule != null) rules.add(rule);
            } catch (Throwable ignored) {
            }
        }
        VodConfig.get().replaceRules(rules);
        // flags/ads/doh：从缓存原文 JSON 里取；缓存空/损坏时按“配置里没有该段”处理，传空表清空旧值。
        // 三段各包各的 try，一段格式坏了不连累另外两段。
        List<String> flags = new ArrayList<>();
        List<String> ads = new ArrayList<>();
        List<Doh> doh = new ArrayList<>();
        JsonObject root = null;
        try {
            String cached = Hawk.get(HawkConfig.API_CONFIG_CACHE, "");
            if (!TextUtils.isEmpty(cached)) root = JsonParser.parseString(cached).getAsJsonObject();
        } catch (Throwable ignored) {
        }
        if (root != null) {
            try {
                flags = Json.safeListString(root, "flags");
            } catch (Throwable ignored) {
            }
            try {
                ads = Json.safeListString(root, "ads");
            } catch (Throwable ignored) {
            }
            try {
                if (root.has("doh")) doh = Doh.arrayFrom(root.get("doh"));
            } catch (Throwable ignored) {
            }
        }
        VodConfig.get().replaceFlags(flags);
        VodConfig.get().replaceAds(ads);
        VodConfig.get().replaceDoh(doh);
    }

    private static boolean noneNamed(List<Parse> parses, String name) {
        for (Parse item : parses) if (item.getName().equals(name)) return false;
        return true;
    }

    /** 站表同步（J 批原样）：表空时什么都不做（不清空已有站表）。 */
    private static void syncSites(boolean preloadJar) {
        try {
            List<SourceBean> sources = ApiConfig.get().getSourceBeanList();
            if (sources == null || sources.isEmpty()) return;
            String spider = ApiConfig.get().getSpider();
            List<Site> sites = new ArrayList<>();
            for (SourceBean sb : sources) {
                if (sb == null || TextUtils.isEmpty(sb.getKey())) continue;
                Site site = new Site();
                site.setKey(sb.getKey());
                site.setName(sb.getName() == null ? "" : sb.getName());
                site.setType(sb.getType());
                site.setApi(UrlUtil.convert(sb.getApi() == null ? "" : sb.getApi()));
                site.setExt(UrlUtil.convert(sb.getExt() == null ? "" : sb.getExt()));
                site.setJar(TextUtils.isEmpty(sb.getJar()) ? spider : sb.getJar());
                site.setPlayUrl(sb.getPlayerUrl() == null ? "" : sb.getPlayerUrl());
                site.setSearchable(sb.getSearchable());
                site.setQuickSearch(sb.getQuickSearch());
                sites.add(site.trans());
            }
            if (sites.isEmpty()) return;
            SourceBean home = ApiConfig.get().getHomeSource();
            VodConfig.get().replaceSites(sites, spider, home == null ? "" : home.getKey(), preloadJar);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
