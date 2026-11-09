package com.yingtu.plus.util;

import android.os.Handler;
import android.os.Looper;

import com.github.catvod.crawler.SpiderDebug;
import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.api.SiteApi;
import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.bean.Result;
import com.yingtu.plus.bean.Site;
import com.yingtu.plus.bean.SourceBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 源数据内存缓存：首页推荐（HOME）/ 分类列表（CATE）/ 视频详情（DETAIL）三类。
 * 开关各走 Hawk（VOD_CACHE_HOME / VOD_CACHE_CATE / VOD_CACHE_DETAIL），关时不读不写；
 * 切源时清对应站的条目；后台按 VOD_CACHE_REFRESH_HOURS 定时重取已缓存的条目。
 * 单条存活 120 分钟、最多 128 条（LRU），只缓存成功取到的结果。
 * 慢网调大理由：站列表取一次要好几秒，30 分钟/64 条在多站切换时刚够就淘汰，
 * 用户来回切站就反复现取；120 分钟/128 条仍只是几 MB 内存，列表过期风险可接受
 * （视频站列表小时级才变，且用户下拉/重进可手动刷新）。
 * 注意：这是纯内存缓存，重启 App 即失；磁盘持久化见下方 refreshAll 注释（未做）。
 */
public final class VodResultCache {

    public enum Kind { HOME, CATE, DETAIL }

    private static final long TTL_MS = 120 * 60 * 1000L;
    private static final int MAX_ENTRIES = 128;

    private static final class Entry {
        final Kind kind;
        final Result result;
        final long time;
        final String siteKey;
        final String tid;
        final String page;
        final HashMap<String, String> extend;
        final String id;

        Entry(Kind kind, Result result, String siteKey, String tid, String page,
              HashMap<String, String> extend, String id) {
            this.kind = kind;
            this.result = result;
            this.time = System.currentTimeMillis();
            this.siteKey = siteKey;
            this.tid = tid;
            this.page = page;
            this.extend = extend;
            this.id = id;
        }

        boolean expired() {
            return System.currentTimeMillis() - time > TTL_MS;
        }
    }

    private static final Map<String, Entry> CACHE = new LinkedHashMap<String, Entry>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, VodResultCache.Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private static volatile boolean schedulerStarted;

    private VodResultCache() {}

    public static boolean enabled(Kind kind) {
        try {
            switch (kind) {
                case HOME: return Hawk.get(HawkConfig.VOD_CACHE_HOME, true);
                case CATE: return Hawk.get(HawkConfig.VOD_CACHE_CATE, true);
                case DETAIL: return Hawk.get(HawkConfig.VOD_CACHE_DETAIL, true);
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static String homeKey(String siteKey) {
        return "home:" + siteKey;
    }

    public static String cateKey(String key, String tid, String page, HashMap<String, String> extend) {
        String ext = "";
        if (extend != null && !extend.isEmpty()) {
            ext = new java.util.TreeMap<>(extend).toString();
        }
        return "cate:" + key + ":" + tid + ":" + page + ":" + ext;
    }

    public static String detailKey(String key, String id) {
        return "detail:" + key + ":" + id;
    }

    public static synchronized Result get(Kind kind, String cacheKey) {
        if (!enabled(kind)) return null;
        Entry e = CACHE.get(cacheKey);
        if (e == null || e.expired()) {
            if (e != null) CACHE.remove(cacheKey);
            return null;
        }
        return e.result;
    }

    public static synchronized void put(Kind kind, String cacheKey, Result result,
                                        String siteKey, String tid, String page,
                                        HashMap<String, String> extend, String id) {
        if (!enabled(kind) || result == null) return;
        CACHE.put(cacheKey, new Entry(kind, result, siteKey, tid, page, extend, id));
    }

    public static synchronized void clear() {
        CACHE.clear();
    }

    /** 切源/换配置时清掉该站相关的条目，其余站的缓存保留。 */
    public static synchronized void clearSite(String siteKey) {
        if (siteKey == null || siteKey.isEmpty()) return;
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Entry> e : CACHE.entrySet()) {
            if (siteKey.equals(e.getValue().siteKey)) dead.add(e.getKey());
        }
        for (String k : dead) CACHE.remove(k);
    }

    public static synchronized int size() {
        return CACHE.size();
    }

    // ===== 后台刷新：App.onCreate 里调一次即可 =====

    public static void scheduleRefresh() {
        if (schedulerStarted) return;
        schedulerStarted = true;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                try {
                    maybeRefresh();
                } catch (Throwable ignored) {
                }
                handler.postDelayed(this, 15 * 60 * 1000L);
            }
        };
        handler.postDelayed(tick, 15 * 60 * 1000L);
    }

    private static void maybeRefresh() {
        int hours;
        try {
            hours = Hawk.get(HawkConfig.VOD_CACHE_REFRESH_HOURS, 0);
        } catch (Throwable e) {
            return;
        }
        if (hours <= 0) return;
        long last;
        try {
            last = Hawk.get(HawkConfig.VOD_CACHE_REFRESH_LAST, 0L);
        } catch (Throwable e) {
            return;
        }
        if (System.currentTimeMillis() - last < hours * 3600 * 1000L) return;
        try {
            Hawk.put(HawkConfig.VOD_CACHE_REFRESH_LAST, System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
        refreshAll();
    }

    /** 后台线程重取全部未过期条目，失败的条目保留旧值（不删缓存、不弹错）。
     * 已过期的条目不刷：get() 本来就把它当不存在，按需重取即可，后台刷它
     * 是白白烧慢网流量。默认刷新间隔为 0=关闭（MoreSettingsActivity），
     * 慢网下建议保持关闭或选 24 小时，别让后台刷新抢前台浏览的带宽。 */
    public static void refreshAll() {
        final List<Entry> snapshot;
        synchronized (VodResultCache.class) {
            snapshot = new ArrayList<>();
            for (Entry e : CACHE.values()) if (!e.expired()) snapshot.add(e);
        }
        if (snapshot.isEmpty()) return;
        new Thread(() -> {
            for (Entry e : snapshot) {
                try {
                    Result fresh = refetch(e);
                    if (fresh == null) continue;
                    synchronized (VodResultCache.class) {
                        Entry cur = CACHE.get(keyOf(e));
                        if (cur == e) {
                            CACHE.put(keyOf(e), new Entry(e.kind, fresh, e.siteKey, e.tid, e.page, e.extend, e.id));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (SpiderDebug.isEnabled()) SpiderDebug.log("vod-cache", "background refresh done entries=%d", snapshot.size());
        }).start();
    }

    private static String keyOf(Entry e) {
        switch (e.kind) {
            case HOME: return homeKey(e.siteKey);
            case CATE: return cateKey(e.siteKey, e.tid, e.page, e.extend);
            default: return detailKey(e.siteKey, e.id);
        }
    }

    private static Result refetch(Entry e) throws Exception {
        switch (e.kind) {
            case HOME: {
                SourceBean bean = null;
                try {
                    bean = com.yingtu.plus.api.ApiConfig.get().getSource(e.siteKey);
                } catch (Throwable ignored) {
                }
                if (bean == null) return null;
                Site site = VodConfig.get().getSite(e.siteKey);
                if (site == null) return null;
                return SiteApi.homeContent(site);
            }
            case CATE:
                return SiteApi.categoryContent(e.siteKey, e.tid, e.page, true, e.extend == null ? new HashMap<>() : new HashMap<>(e.extend));
            default:
                return SiteApi.detailContent(e.siteKey, e.id);
        }
    }
}
