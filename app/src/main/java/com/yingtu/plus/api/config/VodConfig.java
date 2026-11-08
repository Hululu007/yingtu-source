package com.yingtu.plus.api.config;

import android.text.TextUtils;

import com.yingtu.plus.App;
import com.yingtu.plus.api.CspWarmup;
import com.yingtu.plus.api.Decoder;
import com.yingtu.plus.api.loader.BaseLoader;
import com.yingtu.plus.bean.Config;
import com.yingtu.plus.bean.Depot;
import com.yingtu.plus.bean.Parse;
import com.yingtu.plus.bean.Rule;
import com.yingtu.plus.bean.Site;
import com.yingtu.plus.event.ConfigEvent;
import com.yingtu.plus.event.RefreshEvent;
import com.yingtu.plus.impl.Callback;
import com.yingtu.plus.setting.CustomCspSetting;
import com.yingtu.plus.utils.UrlUtil;
import com.yingtu.plus.web.ext.WebHomeExtensionRegistry;
import com.github.catvod.bean.Doh;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class VodConfig extends BaseConfig {

    private static final String TAG = VodConfig.class.getSimpleName();

    private Site home;
    private String wall;
    private Parse parse;
    private List<Doh> doh;
    private List<Rule> rules;
    private List<Site> sites;
    private List<String> ads;
    private List<String> flags;
    private List<Parse> parses;

    public static VodConfig get() {
        return Loader.INSTANCE;
    }

    public static int getCid() {
        return get().getConfig().getId();
    }

    public static String getUrl() {
        return get().getConfig().getUrl();
    }

    public static String getDesc() {
        return get().getConfig().getDesc();
    }

    public static int getHomeIndex() {
        return get().getSites().indexOf(get().getHome());
    }

    public static boolean hasParse() {
        return !get().getParses().isEmpty();
    }

    public static void load(Config config, Callback callback) {
        get().clear().config(config).load(callback);
    }

    public VodConfig init() {
        return config(Config.vod());
    }

    public VodConfig config(Config config) {
        this.config = config;
        return this;
    }

    public VodConfig clear() {
        ads = null;
        doh = null;
        home = null;
        wall = null;
        parse = null;
        sites = null;
        flags = null;
        rules = null;
        parses = null;
        WebHomeExtensionRegistry.get().setGlobalSources(null, "");
        BaseLoader.get().clear();
        RuleConfig.get().invalidate();
        return this;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected Config defaultConfig() {
        return Config.vod();
    }

    @Override
    protected void postEvent() {
        super.postEvent();
        ConfigEvent.vod();
    }

    @Override
    protected void load(Config config) throws Throwable {
        String json = Decoder.getJson(UrlUtil.convert(config.getUrl()), TAG);
        checkJson(config, Json.parse(json).getAsJsonObject());
    }

    @Override
    protected boolean isLoaded() {
        return !getSites().isEmpty();
    }

    @Override
    protected void beforeLoad() {
        CspWarmup.reset();
    }

    @Override
    protected void onLoadSuccess() {
        CspWarmup.schedule("vod-config-loaded");
    }

    private void checkJson(Config config, JsonObject object) throws Throwable {
        if (object.has("msg")) {
            throw new Exception(object.get("msg").getAsString());
        } else if (object.has("urls")) {
            parseDepot(config, object);
        } else {
            parseConfig(config, object);
        }
    }

    private void parseDepot(Config config, JsonObject object) throws Throwable {
        List<Depot> items = Depot.arrayFrom(object.getAsJsonArray("urls").toString());
        List<Config> configs = new ArrayList<>();
        for (Depot item : items) configs.add(Config.find(item, VOD));
        if (configs.isEmpty()) throw new Exception("Depot urls is empty");
        load(this.config = configs.get(0));
        Config.delete(config.getUrl());
    }

    private void parseConfig(Config config, JsonObject object) {
        CustomCspSetting.inject(object);
        initList(object);
        initLive(config, object);
        initWall(config, object);
        initSite(config, object);
        initParse(config, object);
        WebHomeExtensionRegistry.get().setGlobalSources(object.get("webHomeExtensions"), config.getUrl());
        config.setLogo(Json.safeString(object, "logo"));
        config.setNotice(Json.safeString(object, "notice"));
        config.setDanmaku(Json.safeString(object, "danmaku"));
    }

    private void initList(JsonObject object) {
        setHeaders(Header.arrayFrom(fetchArray(object, "headers")));
        setProxy(Proxy.arrayFrom(fetchArray(object, "proxy")));
        setRules(Rule.arrayFrom(fetchArray(object, "rules")));
        setDoh(Doh.arrayFrom(fetchArray(object, "doh")));
        setFlags(Json.safeListString(object, "flags"));
        setHosts(Json.safeListString(object, "hosts"));
        setAds(Json.safeListString(object, "ads"));
    }

    private void initLive(Config config, JsonObject object) {
        if (Json.isEmpty(object, "lives")) return;
        Config temp = Config.find(config, LIVE).save();
        boolean sync = LiveConfig.get().needSync(config.getUrl());
        if (sync) LiveConfig.get().config(temp.update()).parse(object);
    }

    private void initWall(Config config, JsonObject object) {
        if (Json.isEmpty(object, "wallpaper")) return;
        this.wall = Json.safeString(object, "wallpaper");
        Config temp = Config.find(wall, config.getName(), WALL).save();
        boolean sync = WallConfig.get().needSync(wall);
        if (sync) WallConfig.get().config(temp.update());
    }

    private void initSite(Config config, JsonObject object) {
        String spider = Json.safeString(object, "spider");
        BaseLoader.get().parseJar(spider, true);
        setSites(Json.safeListElement(object, "sites").stream().map(e -> Site.objectFrom(e, spider)).distinct().collect(Collectors.toCollection(ArrayList::new)));
        Map<String, Site> items = Site.findAll().stream().collect(Collectors.toMap(Site::getKey, Function.identity()));
        getSites().forEach(site -> site.sync(items.get(site.getKey())));
        CustomCspSetting.Result custom = CustomCspSetting.inject(getSites());
        Site home = !custom.home().isEmpty() ? custom.home() : getSites().stream().filter(item -> item.getKey().equals(config.getHome())).findFirst().orElse(getSites().isEmpty() ? new Site() : getSites().get(0));
        setHome(config, home, false);
    }

    private void initParse(Config config, JsonObject object) {
        setParses(Json.safeListElement(object, "parses").stream().map(Parse::objectFrom).distinct().collect(Collectors.toCollection(ArrayList::new)));
        setParse(config, getParses().isEmpty() ? new Parse() : getParses().stream().filter(item -> item.getName().equals(config.getParse())).findFirst().orElse(getParses().get(0)), false);
    }

    public List<Site> getSites() {
        return sites == null ? Collections.emptyList() : sites;
    }

    private void setSites(List<Site> sites) {
        this.sites = sites;
    }

    public List<Parse> getParses() {
        return parses == null ? Collections.emptyList() : parses;
    }

    private void setParses(List<Parse> parses) {
        if (!parses.isEmpty()) parses.add(0, Parse.god());
        this.parses = parses;
    }

    public List<Doh> getDoh() {
        List<Doh> items = Doh.get(App.get());
        if (doh == null) return items;
        items.removeAll(doh);
        items.addAll(doh);
        return items;
    }

    private void setDoh(List<Doh> doh) {
        this.doh = doh;
    }

    public List<Rule> getRules() {
        return rules == null ? Collections.emptyList() : rules;
    }

    private void setRules(List<Rule> rules) {
        this.rules = rules;
        RuleConfig.get().invalidate();
    }

    public List<Parse> getParses(int type) {
        return getParses().stream().filter(item -> item.getType() == type).toList();
    }

    public List<Parse> getParses(int type, String flag) {
        List<Parse> items = getParses(type);
        List<Parse> filter = items.stream().filter(item -> item.getExt().getFlag().contains(flag)).toList();
        return filter.isEmpty() ? items : filter;
    }

    public List<String> getFlags() {
        return flags == null ? Collections.emptyList() : flags;
    }

    private void setFlags(List<String> flags) {
        this.flags = flags;
    }

    public List<String> getAds() {
        return ads == null ? Collections.emptyList() : ads;
    }

    private void setAds(List<String> ads) {
        this.ads = ads;
        RuleConfig.get().invalidate();
    }

    public Parse getParse() {
        return parse == null ? new Parse() : parse;
    }

    public void setParse(Parse parse) {
        setParse(getConfig(), parse, true);
    }

    public Site getHome() {
        return home == null ? new Site() : home;
    }

    public void setHome(Site site) {
        setHome(getConfig(), site, true);
        RefreshEvent.home();
    }

    public String getWall() {
        return TextUtils.isEmpty(wall) ? "" : wall;
    }

    public Parse getParse(String name) {
        return getParses().stream().filter(item -> item.getName().equals(name)).findFirst().orElse(new Parse());
    }

    public Site getSite(String key) {
        return getSites().stream().filter(item -> item.getKey().equals(key)).findFirst().orElse(new Site());
    }

    /**
     * J 批：把一份现成的站表整体装进 VodConfig（内存同步，不联网）。
     * 与 VodConfig.load 的 JSON 加载路径等价的收尾：去重、预加载配置级
     * spider jar（JarLoader 缓存命中即直接加载，不下载）、按库里存过的
     * 站设置同步 searchable/changeable、按 homeKey 选 home 站、走
     * onLoadSuccess（CspWarmup 预热，内部按配置去重）并 postEvent
     * （ConfigEvent.vod）。整体替换，不叠加；表空直接返回，不清空旧表。
     * 不调 clear()：不同步 parses/flags 等字段，也就不清 BaseLoader，
     * 避免异步 clear 与紧接着的 parseJar 互相竞态。
     */
    public void replaceSites(List<Site> sites, String spider, String homeKey) {
        if (sites == null || sites.isEmpty()) return;
        try {
            List<Site> items = sites.stream().distinct().collect(Collectors.toCollection(ArrayList::new));
            if (items.isEmpty()) return;
            if (!TextUtils.isEmpty(spider)) BaseLoader.get().parseJar(spider, true);
            Map<String, Site> db = Site.findAll().stream().collect(Collectors.toMap(Site::getKey, Function.identity(), (a, b) -> a));
            items.forEach(site -> site.sync(db.get(site.getKey())));
            setSites(items);
            Site home = items.stream().filter(item -> item.getKey().equals(homeKey)).findFirst().orElse(items.get(0));
            setHome(getConfig(), home, false);
            onLoadSuccess();
            postEvent();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * N 批：把一份现成的解析器表整体装进 VodConfig（内存同步，不联网）。
     * 与 initParse 的 JSON 加载路径等价：去重（按名，Parse.equals 只比名）、
     * 非空表前置 Parse.god()（setParses 原实现不动）、按
     * preferredName 选当前解析器（无匹配时取表首位即 god，与 initParse 的
     * orElse(get(0)) 一致）、同步各条目的 selected 状态。不调 clear()。
     * null 直接返回（不动旧表）；空表整体替换——空表即配置里没有 parses 段，
     * 旧解析器不应残留，hasParse() 随之变 false，ParseAdapter 随之变空。
     * 与公开 setParse(Parse) 不打架：当前解析器只在这里选一次，私有重载
     * save=false 不写库；用户在播放页点的解析器存在 Config.parse 里，
     * 调用方把该名字当 preferredName 传进来即可保留。
     */
    public void replaceParses(List<Parse> parses, String preferredName) {
        if (parses == null) return;
        try {
            List<Parse> items = parses.stream().distinct().collect(Collectors.toCollection(ArrayList::new));
            setParses(items);
            Parse current;
            if (getParses().isEmpty()) current = new Parse();
            else if (!TextUtils.isEmpty(preferredName)) current = getParses().stream().filter(item -> item.getName().equals(preferredName)).findFirst().orElse(getParses().get(0));
            else current = getParses().get(0);
            setParse(getConfig(), current, false);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * N 批：flags 段整体替换。null 不动旧值；空表清空（配置里没有该段即无，
     * 旧 flags 不应残留，否则 Result.shouldUseParse() 会继续命中旧 flag）。
     * 不调 clear()，不发事件。
     */
    public void replaceFlags(List<String> flags) {
        if (flags == null) return;
        try {
            setFlags(new ArrayList<>(flags));
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * N 批：rules 段整体替换。null 不动旧值；空表清空（配置里没有该段即无）。
     * 经私有 setRules 走 RuleConfig.invalidate()，嗅探下次取规则时重新合并。
     * 不调 clear()，不发事件。
     */
    public void replaceRules(List<Rule> rules) {
        if (rules == null) return;
        try {
            setRules(new ArrayList<>(rules));
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * N 批：ads 段整体替换。null 不动旧值；空表清空（配置里没有该段即无）。
     * 经私有 setAds 走 RuleConfig.invalidate()。不调 clear()，不发事件。
     */
    public void replaceAds(List<String> ads) {
        if (ads == null) return;
        try {
            setAds(new ArrayList<>(ads));
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * N 批：doh 段整体替换。null 不动旧值；空表清空（配置里没有该段即无，
     * getDoh() 随之只回 App 内置的默认 Doh）。不调 clear()，不发事件。
     */
    public void replaceDoh(List<Doh> doh) {
        if (doh == null) return;
        try {
            setDoh(new ArrayList<>(doh));
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void setParse(Config config, Parse parse, boolean save) {
        this.parse = parse;
        this.parse.setSelected(true);
        config.setParse(parse.getName());
        getParses().forEach(item -> item.setSelected(parse));
        if (save) config.save();
    }

    private void setHome(Config config, Site site, boolean save) {
        home = site;
        home.setSelected(true);
        config.setHome(home.getKey());
        if (save) config.save();
        getSites().forEach(item -> item.setSelected(home));
    }

    private static class Loader {
        static volatile VodConfig INSTANCE = new VodConfig();
    }
}
