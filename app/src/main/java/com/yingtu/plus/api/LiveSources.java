package com.yingtu.plus.api;

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 直播源（用户提供，不内置任何站）。
 *
 * 生效的源只来自三处，全是用户自己的：
 * 1. 注入直播地址（InjectHelper 的 inject_url，用户在别处填的自定义 m3u 地址）；
 * 2. 订阅配置 lives（ApiConfig.LiveEntry，type=0 的 m3u/txt 列表地址进，type=3 蜘蛛型跳过——
 *    本树没有蜘蛛型直播的蜘蛛类，接了也播不了，不如不进）；
 * 3. 直播仓库（Hawk LIVE_REPO，每行“名称,列表链接”，用户自己加的）。
 *
 * 协议：
 * - LIST：m3u/txt 列表，走 LiveParser 解析出分组/频道；
 * - PINGTAI：平台接口（{endpoint}/json.txt 取平台表，再按平台取频道）。
 *   加载机制保留（loadPlatforms/loadPingtaiChannels），但当前三处来源都只产 LIST 源，
 *   PINGTAI 分支是休眠的——以后接了能产 PINGTAI 源的入口再启用，不用重写加载器。
 *
 * 与 dm17 的 BuiltinLiveSources 的区别（剥离）：
 * - 删内置 4 源（秀果/欲火聚合/百年直播 3 个 pingtai 硬编码接口 + 内置 m3u）；
 * - 删 BuiltinOverrides 覆盖清单（隐藏/改名/删除）；
 * - 删蜘蛛型直播（SPIDER 协议、kaflive:// 标记、BuiltinConfigs.JAR_SPEC 取蜘蛛）；
 * - 删 assets 读内置 m3u（readAsset / assetPath）。
 */
public class LiveSources {

    public enum Protocol {
        PINGTAI,
        LIST
    }

    public static class Source {
        public final String id;
        public final String name;
        public final Protocol protocol;
        public final List<String> endpoints;

        public Source(String id, String name, Protocol protocol, List<String> endpoints) {
            this.id = id;
            this.name = name;
            this.protocol = protocol;
            this.endpoints = endpoints == null ? new ArrayList<String>() : endpoints;
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

    private static List<String> urls(String... items) {
        List<String> list = new ArrayList<>();
        for (String item : items) list.add(item);
        return list;
    }

    /**
     * 生效的直播源：注入地址 + 订阅配置 lives + 直播仓库，三处合并、去重。
     * 没有任何内置源；三处都空时返回空表，调用方（LiveActivity）显示空态，不闪退。
     */
    public static List<Source> getEffectiveSources() {
        List<Source> result = new ArrayList<>();
        addRemoteSources(result);
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

    /** 把订阅配置的 lives 与注入地址合并进列表（去重）。 */
    private static void addRemoteSources(List<Source> result) {
        // 注入地址：用户自定义的 m3u，当作一条源（名叫“注入直播”），插在最前
        String inject = ApiConfig.get().getInjectLiveUrl();
        if (!TextUtils.isEmpty(inject)) {
            addRemoteSource(result, "inject_live", "注入直播", inject);
        }
        // 配置 lives：type=0（m3u 列表）进；type=3（蜘蛛型、无 url）跳过，
        // 本树没有蜘蛛型直播的蜘蛛类，接了也播不了
        for (ApiConfig.LiveEntry e : ApiConfig.get().getLiveEntries()) {
            if (e.type == 3) continue;
            if (TextUtils.isEmpty(e.url)) continue;
            String name = TextUtils.isEmpty(e.name) ? e.url : e.name;
            addRemoteSource(result, "config_" + name, name, e.url);
        }
    }

    /** 追加一条远程源：按名/地址去重。 */
    private static void addRemoteSource(List<Source> result, String id, String name, String url) {
        for (Source s : result) {
            if (s.name.equals(name)) return;
            if (s.protocol == Protocol.LIST && s.endpoints.contains(url)) return;
        }
        result.add(new Source(id, name, Protocol.LIST, urls(url)));
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
}
