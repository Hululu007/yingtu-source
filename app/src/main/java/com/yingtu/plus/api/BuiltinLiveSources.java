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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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
 * 回退规则：订阅配置里有 lives 地址时以外加载的为准（每个地址成一个列表源），
 * 没有时用内置 4 源。ApiConfig.java 不动，回退逻辑全在这里。
 */
public class BuiltinLiveSources {

    public enum Protocol {
        PINGTAI,
        LIST
    }

    public static class Source {
        public final String id;
        public final String name;
        public final Protocol protocol;
        public final List<String> endpoints;
        public final String assetPath;

        public Source(String id, String name, Protocol protocol, List<String> endpoints, String assetPath) {
            this.id = id;
            this.name = name;
            this.protocol = protocol;
            this.endpoints = endpoints;
            this.assetPath = assetPath;
        }

        public Source(String id, String name, Protocol protocol, List<String> endpoints) {
            this(id, name, protocol, endpoints, null);
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
     * 生效的直播源：订阅配置有 lives 地址就用订阅的（一个地址一个列表源），
     * 没有就用内置 4 源。内置源走覆盖清单：隐藏/删掉的不出列表，改过名的显示新名。
     */
    public static List<Source> getEffectiveSources() {
        List<String> configured = ApiConfig.get().getLiveUrls();
        if (configured != null && !configured.isEmpty()) {
            List<Source> sources = new ArrayList<>();
            for (int i = 0; i < configured.size(); i++) {
                String url = configured.get(i);
                String name = configured.size() == 1 ? "订阅直播" : "订阅直播" + (i + 1);
                sources.add(new Source("config_" + i, name, Protocol.LIST, urls(url)));
            }
            return sources;
        }
        return visibleBuiltinSources();
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
}
