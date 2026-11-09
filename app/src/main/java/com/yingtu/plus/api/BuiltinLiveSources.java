package com.yingtu.plus.api;

import android.content.Context;
import android.text.TextUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yingtu.plus.api.parser.LiveParser;
import com.yingtu.plus.bean.Channel;
import com.yingtu.plus.bean.Group;
import com.yingtu.plus.bean.Live;
import com.yingtu.plus.bean.LiveChannel;
import com.yingtu.plus.bean.LiveGroup;
import com.yingtu.plus.util.OkGo;

import com.github.catvod.crawler.Spider;
import com.yingtu.plus.api.BuiltinConfigs;
import com.yingtu.plus.api.loader.BaseLoader;
import com.yingtu.plus.bean.Class;
import com.yingtu.plus.bean.Result;
import com.yingtu.plus.bean.Vod;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 内置直播源（不依赖订阅也能看直播）。
 *
 * 4 个源与修仙版 / 火蚁TV 一致：
 * - 3 个 pingtai 源：接口现取平台表（{endpoint}/json.txt 的 pingtai 数组），
 *   再按平台现取频道（{endpoint}/{address} 的 zhubo/list/data 数组），频道不写死；
 * - 1 个列表源：assets/live/user_m3u.m3u（用户提供的 m3u），走 LiveParser 解析。
 *
 * 合并规则：内置可见 4 源 + 订阅配置 lives 并存、互不顶掉。
 * 远程用配置里的真名；type=0（m3u 列表）进；type=3（蜘蛛型、无 url）只有
 * 树里有对应蜘蛛类的才进（SPIDER_APIS 白名单，目前只有咖啡体育 csp_KafLiveAmns），
 * 其余 type=3 仍跳过；同名或同地址去重；
 * 覆盖清单（隐藏/删除/改名）对内置与远程都生效。
 */
public class BuiltinLiveSources {

    public enum Protocol {
        PINGTAI,
        LIST,
        SPIDER
    }

    /** 有对应蜘蛛类的 type=3 直播 api 白名单（只认这几个，别的 type=3 仍跳过）。 */
    private static final java.util.Set<String> SPIDER_APIS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "csp_KafLiveAmns"
    ));

    /** 蜘蛛型直播的频道标记前缀：点频道时按此前缀拦截、现解析真流。 */
    public static final String SPIDER_MARK_PREFIX = "kaflive://room/";

    public static class Source {
        public final String id;
        public final String name;
        public final Protocol protocol;
        public final List<String> endpoints;
        public final String assetPath;
        public final String spiderApi;

        public Source(String id, String name, Protocol protocol, List<String> endpoints, String assetPath) {
            this(id, name, protocol, endpoints, assetPath, null);
        }

        public Source(String id, String name, Protocol protocol, List<String> endpoints) {
            this(id, name, protocol, endpoints, null, null);
        }

        public Source(String id, String name, Protocol protocol, List<String> endpoints, String assetPath, String spiderApi) {
            this.id = id;
            this.name = name;
            this.protocol = protocol;
            this.endpoints = endpoints == null ? new ArrayList<String>() : endpoints;
            this.assetPath = assetPath;
            this.spiderApi = spiderApi;
        }
    }

    /** pingtai 平台（json.txt 里的一行：address 是频道接口名，title 是平台名）。 */
    public static class Platform {
        public final String id;
        public final String name;
        public final String logo;
        public final int count;
        public final String endpoint;

        public Platform(String id, String name, String logo, int count, String endpoint) {
            this.id = id;
            this.name = name;
            this.logo = logo;
            this.count = count;
            this.endpoint = endpoint;
        }
    }

    public interface OnPlatforms {
        void onResult(List<Platform> platforms);
        void onError(String msg);
    }

    public interface OnChannels {
        void onResult(List<LiveChannel> channels);
        void onError(String msg);
    }

    public interface OnUrl {
        void onResult(String url);
        void onError(String msg);
    }

    private static List<String> urls(String... items) {
        List<String> list = new ArrayList<>();
        for (String item : items) list.add(item);
        return list;
    }

    /** 内置 4 源：显示名带 🔞 前缀，与火蚁TV一致。 */
    public static final List<Source> BUILTIN = new ArrayList<>();

    static {
        BUILTIN.add(new Source("xiuguo", "🔞秀果", Protocol.PINGTAI, urls(
                "http://api.vipmisss.com:81/xcdsw",
                "http://api.hclyz.com:81/mf")));
        BUILTIN.add(new Source("maiyoux", "🔞欲火聚合", Protocol.PINGTAI, urls(
                "http://api.maiyoux.com:81/mf",
                "http://api.vipmisss.com:81/xcdsw")));
        BUILTIN.add(new Source("bnzb", "🔞百年直播", Protocol.PINGTAI, urls(
                "http://api.vipmisss.com:81/xcdsw")));
        BUILTIN.add(new Source("user_m3u", "🔞成人点播", Protocol.LIST,
                new ArrayList<>(), "live/user_m3u.m3u"));
    }

    /**
     * 生效的直播源：内置可见源 + 订阅配置 lives 合并，两者并存、互不顶掉。
     * 顺序：内置可见源在前，远程 lives 在后（注入地址插在远程最前）。
     * 远程用配置里的真名；type=0（m3u 列表）进；type=3（蜘蛛型、无 url）只有
     * 白名单里的 api 才进（目前只有咖啡体育 csp_KafLiveAmns），其余跳过；
     * 同名或同地址去重；覆盖清单对内置与远程都生效。
     */
    public static List<Source> getEffectiveSources() {
        List<Source> result = new ArrayList<>();
        // 1. 内置可见源（覆盖清单：隐藏/删掉的不出，改过名的显示新名）
        result.addAll(visibleBuiltinSources());
        // 2. 远程 lives（配置里的真名 + url + type）与注入地址
        addRemoteSources(result);
        // 3. 直播仓库（高级配置里用户自己加的“名称,列表链接”，按名/地址去重）
        addRepoSources(result);
        return result;
    }

    /** 直播仓库：Hawk LIVE_REPO 每行“名称,列表链接”，并进生效源列表。 */
    private static void addRepoSources(List<Source> result) {
        List<String> lines;
        try {
            lines = com.orhanobut.hawk.Hawk.get(
                    com.yingtu.plus.util.HawkConfig.LIVE_REPO, new ArrayList<String>());
        } catch (Throwable e) {
            return;
        }
        if (lines == null) return;
        for (String line : lines) {
            if (line == null) continue;
            String[] parts = line.split(",", 2);
            if (parts.length < 2) continue;
            String name = parts[0].trim();
            String url = parts[1].trim();
            if (name.isEmpty() || url.isEmpty()) continue;
            addRemoteSource(result, "repo_" + name, name, url);
        }
    }

    /** 把订阅配置的 lives 与注入地址合并进列表（内置已在前，远程追加，去重，覆盖清单生效）。 */
    private static void addRemoteSources(List<Source> result) {
        // 注入地址：用户自定义的 m3u，当作一条远程源（名叫“注入直播”），插在远程最前
        String inject = ApiConfig.get().getInjectLiveUrl();
        if (!TextUtils.isEmpty(inject)) {
            addRemoteSource(result, "inject_live", "注入直播", inject);
        }
        // 配置 lives：type=0（m3u 列表）进；type=3（蜘蛛型、无 url）只有白名单
        // 里的 api 才进（目前只有咖啡体育 csp_KafLiveAmns），其余 type=3 跳过
        for (ApiConfig.LiveEntry e : ApiConfig.get().getLiveEntries()) {
            if (e.type == 3) {
                if (e.api != null && SPIDER_APIS.contains(e.api)) {
                    String name = TextUtils.isEmpty(e.name) ? e.api : e.name;
                    addRemoteSpiderSource(result, "config_" + name, name, e.api);
                }
                continue;
            }
            if (TextUtils.isEmpty(e.url)) {
                continue;
            }
            String name = TextUtils.isEmpty(e.name) ? e.url : e.name;
            addRemoteSource(result, "config_" + name, name, e.url);
        }
    }

    /** 远程 type=3 蜘蛛型直播源（按名去重；同名再出现就跳过，不重复加）。 */
    private static void addRemoteSpiderSource(List<Source> result, String id, String name, String spiderApi) {
        for (Source s : result) {
            if (s.name != null && s.name.equals(name)) {
                return;
            }
        }
        result.add(new Source(id, name, Protocol.SPIDER, new ArrayList<String>(), null, spiderApi));
    }

    /** 追加一条远程源：先过覆盖清单（隐藏/删掉的不出，改过名的显示新名），再按名/地址去重。 */
    private static void addRemoteSource(List<Source> result, String id, String name, String url) {
        if (!com.yingtu.plus.api.BuiltinOverrides.isVisible(
                com.yingtu.plus.api.BuiltinOverrides.ZONE_LIVE, id)) return;
        String display = com.yingtu.plus.api.BuiltinOverrides.displayName(
                com.yingtu.plus.api.BuiltinOverrides.ZONE_LIVE, id, name);
        for (Source s : result) {
            if (s.name.equals(display)) return;
            if (s.protocol == Protocol.LIST && s.endpoints.contains(url)) return;
        }
        result.add(new Source(id, display, Protocol.LIST, urls(url)));
    }

    /** 可见的内置直播源（隐藏/删掉的排除，改过名的显示新名），供直播页用。 */
    public static List<Source> visibleBuiltinSources() {
        List<Source> result = new ArrayList<>();
        for (Source s : BUILTIN) {
            if (!com.yingtu.plus.api.BuiltinOverrides.isVisible(
                    com.yingtu.plus.api.BuiltinOverrides.ZONE_LIVE, s.id)) continue;
            result.add(renamedCopy(s));
        }
        return result;
    }

    /** 全部内置直播源（不含删掉的，含隐藏的），供内置区列表管理页用。 */
    public static List<Source> getAllBuiltinSources() {
        List<Source> result = new ArrayList<>();
        for (Source s : BUILTIN) {
            if (com.yingtu.plus.api.BuiltinOverrides.isDeleted(
                    com.yingtu.plus.api.BuiltinOverrides.ZONE_LIVE, s.id)) continue;
            result.add(renamedCopy(s));
        }
        return result;
    }

    /** 拷一份 Source，把显示名换成覆盖清单里的新名（原 BUILTIN 列表不动）。 */
    private static Source renamedCopy(Source s) {
        String name = com.yingtu.plus.api.BuiltinOverrides.displayName(
                com.yingtu.plus.api.BuiltinOverrides.ZONE_LIVE, s.id, s.name);
        return new Source(s.id, name, s.protocol, s.endpoints, s.assetPath);
    }

    /** 把 LiveParser 解析出的 Group/Channel 转成 LiveGroup/LiveChannel。 */
    public static List<LiveGroup> toGroups(Live live) {
        List<LiveGroup> parsed = new ArrayList<>();
        for (Group group : live.getGroups()) {
            LiveGroup lg = new LiveGroup(group.getName());
            for (Channel channel : group.getChannel()) {
                LiveChannel lc = new LiveChannel(channel.getName());
                lc.logo = channel.getLogo();
                lc.ua = channel.getUa();
                lc.urls.addAll(channel.getUrls());
                lc.lineIndex = channel.getIndex();
                lg.channels.add(lc);
            }
            parsed.add(lg);
        }
        return parsed;
    }

    /** 解析 m3u/txt 文本（列表源用，LiveParser.text 入口）。 */
    public static List<LiveGroup> parseListText(String url, String text) {
        Live live = new Live("直播", url);
        LiveParser.text(live, text);
        return toGroups(live);
    }

    /** 读 assets 文件为字符串。 */
    public static String readAsset(Context context, String assetPath) throws IOException {
        InputStream in = context.getAssets().open(assetPath);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    /** 拉 pingtai 平台表：并行取全部镜像，按 address 合并去重，计数取最大的镜像。 */
    public static void loadPlatforms(Source source, OnPlatforms cb) {
        Map<String, Platform> merged = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        AtomicInteger pending = new AtomicInteger(source.endpoints.size());
        if (source.endpoints.isEmpty()) {
            cb.onError("该直播源没有可用接口");
            return;
        }
        for (String endpoint : source.endpoints) {
            Request request = new Request.Builder().url(endpoint + "/json.txt").build();
            OkGo.client().newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    synchronized (failures) {
                        failures.add(endpoint + ": " + e.getMessage());
                    }
                    finishOne();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String body = response.body() != null ? response.body().string() : "";
                    try {
                        for (Platform p : parsePlatforms(endpoint, body)) {
                            synchronized (merged) {
                                Platform old = merged.get(p.id);
                                if (old == null || p.count > old.count) merged.put(p.id, p);
                            }
                        }
                    } catch (Throwable t) {
                        synchronized (failures) {
                            failures.add(endpoint + ": 解析失败");
                        }
                    }
                    finishOne();
                }

                private void finishOne() {
                    if (pending.decrementAndGet() == 0) {
                        List<Platform> all = new ArrayList<>(merged.values());
                        List<Platform> result = new ArrayList<>();
                        for (Platform p : all) if (p.count > 0) result.add(p);
                        if (result.isEmpty()) result = all;
                        if (result.isEmpty()) {
                            OkGo.main(() -> cb.onError(failures.isEmpty() ? "该直播源没有可用分类" : "该直播源暂时不可用：" + failures.get(0)));
                        } else {
                            List<Platform> done = result;
                            OkGo.main(() -> cb.onResult(done));
                        }
                    }
                }
            });
        }
    }

    private static List<Platform> parsePlatforms(String endpoint, String body) {
        List<Platform> list = new ArrayList<>();
        JsonElement root = JsonParser.parseString(body);
        JsonElement raw = root.isJsonObject() ? root.getAsJsonObject().get("pingtai") : root;
        if (raw == null || !raw.isJsonArray()) return list;
        for (JsonElement e : raw.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject obj = e.getAsJsonObject();
            String id = opt(obj, "address");
            String name = opt(obj, "title");
            if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) continue;
            int count = 0;
            try {
                count = Integer.parseInt(opt(obj, "Number"));
            } catch (Throwable ignored) {
            }
            list.add(new Platform(id, name, opt(obj, "xinimg"), count, endpoint));
        }
        return list;
    }

    /** 拉某平台的频道：先用该平台所属镜像，失败再依次试同源其他镜像。 */
    public static void loadPingtaiChannels(Source source, Platform platform, OnChannels cb) {
        List<String> candidates = new ArrayList<>();
        if (!TextUtils.isEmpty(platform.endpoint)) candidates.add(platform.endpoint);
        for (String endpoint : source.endpoints) {
            if (!candidates.contains(endpoint)) candidates.add(endpoint);
        }
        loadPingtaiChannelsFrom(source, platform, candidates, 0, null, cb);
    }

    private static void loadPingtaiChannelsFrom(Source source, Platform platform, List<String> candidates, int index, String lastError, OnChannels cb) {
        if (index >= candidates.size()) {
            String msg = lastError == null ? "该分类暂时没有频道" : lastError;
            OkGo.main(() -> cb.onError(msg));
            return;
        }
        String endpoint = candidates.get(index);
        Request request = new Request.Builder().url(endpoint + "/" + platform.id).build();
        OkGo.client().newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                loadPingtaiChannelsFrom(source, platform, candidates, index + 1, e.getMessage(), cb);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                try {
                    List<LiveChannel> channels = parsePingtaiChannels(body);
                    if (channels.isEmpty()) throw new IllegalStateException("该分类暂时没有频道");
                    OkGo.main(() -> cb.onResult(channels));
                } catch (Throwable t) {
                    loadPingtaiChannelsFrom(source, platform, candidates, index + 1, t.getMessage(), cb);
                }
            }
        });
    }

    private static List<LiveChannel> parsePingtaiChannels(String body) {
        List<LiveChannel> reliable = new ArrayList<>();
        List<LiveChannel> fallback = new ArrayList<>();
        JsonElement root = JsonParser.parseString(body);
        JsonElement rows = root;
        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            rows = obj.has("zhubo") ? obj.get("zhubo") : obj.has("list") ? obj.get("list") : obj.get("data");
        }
        if (rows == null || !rows.isJsonArray()) return reliable;
        List<String> seen = new ArrayList<>();
        for (JsonElement e : rows.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject obj = e.getAsJsonObject();
            String name = opt(obj, "title");
            String url = opt(obj, "address");
            if (TextUtils.isEmpty(name) || TextUtils.isEmpty(url) || !url.contains("://")) continue;
            if (seen.contains(name)) continue;
            seen.add(name);
            LiveChannel c = new LiveChannel(name);
            c.logo = opt(obj, "img");
            c.urls.add(url);
            if (url.startsWith("rtmp")) fallback.add(c);
            else reliable.add(c);
        }
        reliable.addAll(fallback);
        return reliable;
    }

    private static String opt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        try {
            return obj.get(key).getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ============ 蜘蛛型直播（type=3，如咖啡体育） ============

    /** 从内置 jar 里拿蜘蛛实例（与内置 jar 站同一条路：jar 规格走 BuiltinConfigs.JAR_SPEC）。 */
    private static Spider getSpider(Source source) {
        if (source == null || TextUtils.isEmpty(source.spiderApi)) return null;
        try {
            return BaseLoader.get().getSpider("kaflive", source.spiderApi, "", BuiltinConfigs.JAR_SPEC);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 蜘蛛型直播：取分类（蜘蛛 homeContent 的 class 列表）作直播区分组。 */
    public static void loadSpiderCategories(final Source source, final OnPlatforms cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Spider spider = getSpider(source);
                    if (spider == null) {
                        OkGo.main(new Runnable() {
                            @Override
                            public void run() {
                                cb.onError("蜘蛛加载失败");
                            }
                        });
                        return;
                    }
                    Result result = Result.fromJson(spider.homeContent(true));
                    List<Class> types = result.getTypes();
                    final List<Platform> platforms = new ArrayList<>();
                    if (types != null) {
                        for (Class t : types) {
                            if (t == null || TextUtils.isEmpty(t.getTypeId())) continue;
                            platforms.add(new Platform(t.getTypeId(), t.getTypeName(), "", 0, ""));
                        }
                    }
                    OkGo.main(new Runnable() {
                        @Override
                        public void run() {
                            if (platforms.isEmpty()) cb.onError("分类为空");
                            else cb.onResult(platforms);
                        }
                    });
                } catch (final Throwable e) {
                    OkGo.main(new Runnable() {
                        @Override
                        public void run() {
                            cb.onError(e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /** 蜘蛛型直播：取某分类下的频道（蜘蛛 categoryContent 的 Vod 列表转频道）。 */
    public static void loadSpiderChannels(final Source source, final Platform platform, final OnChannels cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Spider spider = getSpider(source);
                    if (spider == null) {
                        OkGo.main(new Runnable() {
                            @Override
                            public void run() {
                                cb.onError("蜘蛛加载失败");
                            }
                        });
                        return;
                    }
                    Result result = Result.fromJson(spider.categoryContent(platform.id, "1", false, new HashMap<String, String>()));
                    List<Vod> vods = result.getList();
                    final List<LiveChannel> channels = new ArrayList<>();
                    if (vods != null) {
                        for (Vod v : vods) {
                            if (v == null || TextUtils.isEmpty(v.getId())) continue;
                            LiveChannel c = new LiveChannel(v.getName());
                            c.logo = v.getPic();
                            c.urls.add(SPIDER_MARK_PREFIX + v.getId());
                            channels.add(c);
                        }
                    }
                    OkGo.main(new Runnable() {
                        @Override
                        public void run() {
                            if (channels.isEmpty()) cb.onError("频道为空");
                            else cb.onResult(channels);
                        }
                    });
                } catch (final Throwable e) {
                    OkGo.main(new Runnable() {
                        @Override
                        public void run() {
                            cb.onError(e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /** 蜘蛛型直播：把频道标记（kaflive://room/<roomId>）现解析成真播放地址。 */
    public static void resolveSpiderPlay(final Source source, final String mark, final OnUrl cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String roomId = mark == null ? "" : mark;
                    if (roomId.startsWith(SPIDER_MARK_PREFIX)) {
                        roomId = roomId.substring(SPIDER_MARK_PREFIX.length());
                    }
                    if (TextUtils.isEmpty(roomId)) {
                        OkGo.main(new Runnable() {
                            @Override
                            public void run() {
                                cb.onError("房间号为空");
                            }
                        });
                        return;
                    }
                    Spider spider = getSpider(source);
                    if (spider == null) {
                        OkGo.main(new Runnable() {
                            @Override
                            public void run() {
                                cb.onError("蜘蛛加载失败");
                            }
                        });
                        return;
                    }
                    Result result = Result.fromJson(spider.playerContent("在线播放", roomId, new ArrayList<String>()));
                    final String url = result == null ? "" : result.getRealUrl();
                    OkGo.main(new Runnable() {
                        @Override
                        public void run() {
                            if (TextUtils.isEmpty(url)) cb.onError("取流地址为空");
                            else cb.onResult(url);
                        }
                    });
                } catch (final Throwable e) {
                    OkGo.main(new Runnable() {
                        @Override
                        public void run() {
                            cb.onError(e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }
}
