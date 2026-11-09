// 全播放预加载：详情页打开、切集、切台时，后台先把播放地址解析好、
// 磁力把 TorrServer 暖好，点播放时直接出画面，不用现取现等。
// 只做两件事：解析地址 + 暖好磁力服务；不下载整集视频字节。
// 节制：同时最多缓存 3 条解析结果、每条 5 分钟过期（签名地址会过期，
// 黑料的 auth_key 约 7 分钟失效）；预加载任务单线程串行跑，不并行
// burst（慢速 VPN 下并行会全灭）；失败全静默，只记调试日志，不弹错、
// 不阻塞正常点播——点播时缓存没命中就按原流程现取。
package com.yingtu.plus.player;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.yingtu.plus.api.SiteApi;
import com.yingtu.plus.bean.Result;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlaybackPreload {

    private static final String TAG = "preload";
    /** 解析结果最多留几条：当前集 + 下一集 + 再下一路，够用就好。 */
    private static final int MAX_ENTRIES = 3;
    /** 解析结果保质期：签名地址会过期，5 分钟后作废、点播时重取。 */
    private static final long TTL_MS = 5 * 60 * 1000L;

    /** 单线程串行跑预加载：慢网下逐个来，不并行 burst。 */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static final Map<String, Entry> CACHE = new LinkedHashMap<String, Entry>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private static final Set<String> MAGNET_IN_FLIGHT = new HashSet<>();

    private static class Entry {
        final Result result;
        final long fetchedAt;

        Entry(Result result, long fetchedAt) {
            this.result = result;
            this.fetchedAt = fetchedAt;
        }

        boolean fresh() {
            return System.currentTimeMillis() - fetchedAt < TTL_MS;
        }
    }

    public static boolean isMagnet(String url) {
        return url != null && url.startsWith("magnet:");
    }

    private static String key(String siteKey, String flag, String episodeUrl) {
        return (siteKey == null ? "" : siteKey) + "\n" + (flag == null ? "" : flag) + "\n" + (episodeUrl == null ? "" : episodeUrl);
    }

    /**
     * 后台解析一集的播放地址并缓存；解析出来是磁力就顺手把 TorrServer 暖好。
     * 已缓存未过期、或正在解析同一集时直接跳过。失败静默（只记调试日志）。
     */
    public static void preload(final String siteKey, final String flag, final String episodeUrl) {
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(episodeUrl)) return;
        // 磁力直链不用走蜘蛛解析，直接暖 TorrServer。
        if (isMagnet(episodeUrl)) {
            preloadMagnet(episodeUrl);
            return;
        }
        final String k = key(siteKey, flag, episodeUrl);
        synchronized (CACHE) {
            Entry e = CACHE.get(k);
            if (e != null && e.fresh()) return;
            if (!IN_FLIGHT.add(k)) return;
        }
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Result result = SiteApi.playerContent(siteKey, flag, episodeUrl);
                    String realUrl = result == null ? "" : result.getRealUrl();
                    if (!TextUtils.isEmpty(realUrl)) {
                        // 蜘蛛解析出来是磁力：地址先缓存，TorrServer 也顺手暖好，
                        // 点播时播放器管线（Source.fetch）直接用暖好的服务，不用现等。
                        if (isMagnet(realUrl)) {
                            preloadMagnet(realUrl);
                        }
                        synchronized (CACHE) {
                            CACHE.put(k, new Entry(result, System.currentTimeMillis()));
                        }
                        SpiderDebug.log(TAG, "preloaded key=%s flag=%s url=%s", siteKey, flag, episodeUrl);
                    }
                } catch (Throwable e) {
                    // 预加载失败静默：点播时按原流程现取，不弹错。
                    SpiderDebug.log(TAG, "preload failed key=%s flag=%s err=%s", siteKey, flag, e.getMessage());
                } finally {
                    synchronized (CACHE) {
                        IN_FLIGHT.remove(k);
                    }
                }
            }
        });
    }

    /**
     * 取出缓存的解析结果（一次性的，取走即删，免得过期签名地址被反复用）。
     * 没命中、或已过期返回 null，调用方按原流程现取。
     */
    public static Result take(String siteKey, String flag, String episodeUrl) {
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(episodeUrl)) return null;
        String k = key(siteKey, flag, episodeUrl);
        synchronized (CACHE) {
            Entry e = CACHE.remove(k);
            if (e == null || !e.fresh()) return null;
            return e.result;
        }
    }

    /**
     * 调用方自己解析完后，把结果放进共享缓存（详情页预加载就是这么做的：
     * 解析一次，详情页内联播与跳播放页兜底两条路都能复用，不用各取一次）。
     * 只有真地址非空的结果才存。
     */
    public static void put(String siteKey, String flag, String episodeUrl, Result result) {
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(episodeUrl) || result == null) return;
        if (TextUtils.isEmpty(result.getRealUrl())) return;
        synchronized (CACHE) {
            CACHE.put(key(siteKey, flag, episodeUrl), new Entry(result, System.currentTimeMillis()));
        }
    }

    /**
     * 磁力预加载：先把 TorrServer 服务拉起来（首次要下载几十 MB 二进制，
     * 这就是点播磁力时最耗的那一段），再把种子加进去取元数据、探一下首字节。
     * 播的时候 TorrServerExtractor.fetch 直接复用暖好的服务与种子，不用现等。
     * 同一条磁力只暖一次；失败静默。
     */
    public static void preloadMagnet(final String magnet) {
        if (TextUtils.isEmpty(magnet)) return;
        synchronized (CACHE) {
            if (!MAGNET_IN_FLIGHT.add(magnet)) return;
        }
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    TorrServerManager server = TorrServerManager.get();
                    server.ensureStarted();
                    TorrServerManager.Torrent torrent = server.addTorrent(magnet);
                    if (torrent != null) {
                        TorrServerManager.FileEntry best = torrent.bestFile();
                        if (best != null) {
                            String hash = torrent.hash != null ? torrent.hash : TorrServerManager.extractHash(magnet);
                            if (hash != null) {
                                // 轻探首字节：让 TorrServer 现在就开始连 peer、下首分片。
                                try {
                                    server.probe(server.playUrl(hash, best.id));
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                    SpiderDebug.log(TAG, "magnet preloaded hash=%s", TorrServerManager.extractHash(magnet));
                } catch (Throwable e) {
                    SpiderDebug.log(TAG, "magnet preload failed err=%s", e.getMessage());
                } finally {
                    synchronized (CACHE) {
                        MAGNET_IN_FLIGHT.remove(magnet);
                    }
                }
            }
        });
    }

    /** 清掉全部缓存的解析结果（切站、退出播放页时调一下也行，不调也会自然过期）。 */
    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    // 仅供测试/诊断：当前缓存了几条。
    public static int cachedCount() {
        synchronized (CACHE) {
            int n = 0;
            Iterator<Entry> it = CACHE.values().iterator();
            while (it.hasNext()) {
                if (it.next().fresh()) n++;
            }
            return n;
        }
    }
}
