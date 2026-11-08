package com.yingtu.plus.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.bean.Class;
import com.yingtu.plus.bean.Episode;
import com.yingtu.plus.bean.Flag;
import com.yingtu.plus.bean.LiveChannel;
import com.yingtu.plus.bean.LiveGroup;
import com.yingtu.plus.bean.Result;
import com.yingtu.plus.bean.Site;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.bean.Vod;
import com.yingtu.plus.drama.BuiltinDramaConfigs;
import com.yingtu.plus.reader.NovelLoader;
import com.yingtu.plus.reader.ReadingSources;
import com.yingtu.plus.util.OkGo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 站点检测模块：一次跑完远程配置站 + 全部内置站。
 *
 * 检测项集齐（参考仓只看结构、不拷代码；各家借鉴点见规格文档）：
 * - 视频站：jar 诊断（蜘蛛能不能加载、内置 jar 缓存与 MD5、本地 jar 文件头 PK 与大小，
 *   LunaTV 口径）/ 连通性（HEAD/GET 看站活不活、耗时）/ 站点探测（防御等级：
 *   0 直连 / 1 疑似 WAF / 2 疑似盾 / 3 不通 / 4 现代防护，spider-craft 强制 4 级口径）/
 *   首页（走 App 内现有 spider 调用链，取到真条目算通）/ 分类分页（第 1 页 + 第 2 页核对，
 *   第 2 页须出新 id，spider-craft/cn-worker 口径）/
 *   搜索实测（真发关键词验有没有结果，记标题匹配率，LunaTV 口径）/
 *   空结果核对（乱搜一个词应返回空）/ 详情（首条详情能不能取，回显 id 须一致、每条线路
 *   都须有选集，cn-worker/spider-craft 口径）/ 真实播放（取到播放地址后拉分片验真能播：
 *   前 8 个分片里至少 2 个能下、单片 2xx 且 >1KB、TS 头 0x47，tvyuan 口径；
 *   变体按最高 BANDWIDTH 取、剔广告短循环，iptv-api 口径）。
 * - 直播源：频道拉取（平台表/频道表能不能拉）/ 测速（末尾 2 个分片实下，延迟取首字节、
 *   速率按分辨率分档，iptv-api 口径）/ 真实播放（首个频道拉 m3u8 + 分片验真能播）。
 * - 阅读站：首页书单 / 搜索实测（记标题匹配率）/ 空结果核对 / 详情章节。
 *
 * 健康分（LunaTV 口径：成功占比×100，≥75 优秀、≥50 良好、<50 待关注）在跑完的汇总里报。
 * 总判定（只看核心项，参考项只展示）：异常 > 超时 > 空 > 正常；没测过显示待定。
 * 逐个跑（不并行打爆站方），后台线程跑、带进度、能中途停；单站整体 90 秒封顶。
 * 结果记进 SiteHealthResults，下次打开列表直接看上次结果；只展示，不自动删站。
 * 自动屏蔽：内置站失效（异常/超时/空）且总开关开着时，推进隐藏清单（随时能手动再打开）；
 * 用户远程站只展示结果，不自动屏蔽。
 */
public final class SiteHealthChecker {

    /** 目标种类。 */
    public static final int KIND_VIDEO = 0;
    public static final int KIND_LIVE = 1;
    public static final int KIND_READING = 2;

    /** 单站整体封顶（秒）：任一分项 hang 住也不拖垮整轮。 */
    private static final long TARGET_TIMEOUT_SECONDS = 90;
    /** 两站之间停顿（毫秒）：别把站方打爆。 */
    private static final long GAP_MS = 300;
    /** 空结果核对用的乱搜词：正常站应返回空。 */
    private static final String NONSENSE_KEYWORD = "zzqww12345noresult";

    /** 一个检测目标。 */
    public static class Target {
        public final String zone;
        public final String key;
        public final String name;
        public final String api;
        public final int kind;
        public final BuiltinLiveSources.Source liveSource;

        public Target(String zone, String key, String name, String api, int kind,
                      BuiltinLiveSources.Source liveSource) {
            this.zone = zone;
            this.key = key == null ? "" : key;
            this.name = name == null ? "" : name;
            this.api = api == null ? "" : api;
            this.kind = kind;
            this.liveSource = liveSource;
        }

        public String id() {
            return zone + ":" + key;
        }
    }

    /** 跑完一个站的回推。 */
    public interface Listener {
        void onProgress(int done, int total, Target target, SiteHealthResults.Result result);
        void onFinished(int done, int total, int[] counts, boolean stopped);
    }

    private static final SiteHealthChecker INSTANCE = new SiteHealthChecker();

    public static SiteHealthChecker get() {
        return INSTANCE;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService probes = Executors.newFixedThreadPool(2);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean stopRequested;
    private volatile Future<SiteHealthResults.Result> currentProbe;

    private SiteHealthChecker() {
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 停下：当前站跑完手头分项即停（正在等网络的分项由单站封顶兜底）。 */
    public void stop() {
        stopRequested = true;
        Future<SiteHealthResults.Result> f = currentProbe;
        if (f != null) f.cancel(true);
    }

    /**
     * 开始跑一轮。已在跑时返回 false（调用方把按钮置灰即可）。
     * 先补齐内置站进站表并同步 VodConfig，再逐个跑。
     */
    public boolean start(final Context context, final List<Target> targets, final Listener listener) {
        if (targets == null || targets.isEmpty()) return false;
        if (!running.compareAndSet(false, true)) return false;
        stopRequested = false;
        final Context appContext = context == null ? null : context.getApplicationContext();
        worker.execute(() -> {
            try {
                if (appContext != null) {
                    BuiltinConfigs.ensureLoaded(appContext);
                    BuiltinDramaConfigs.ensureLoaded(appContext);
                    VodConfigSync.sync();
                }
                int total = targets.size();
                int done = 0;
                List<SiteHealthResults.Result> outcomes = new ArrayList<>();
                for (Target target : targets) {
                    if (stopRequested) break;
                    SiteHealthResults.Result result = checkOne(appContext, target);
                    outcomes.add(result);
                    done++;
                    SiteHealthResults.putWithItems(target.zone, target.key,
                            result.status, result.detail, result.items);
                    // 自动屏蔽：只对内置站、只在总开关开着时；用户远程站只展示。
                    if (result.isFailed() && !BuiltinOverrides.ZONE_USER.equals(target.zone)
                            && BuiltinOverrides.isAutoBlock()) {
                        BuiltinOverrides.setVisible(target.zone, target.key, false);
                    }
                    final int doneNow = done;
                    final Target t = target;
                    final SiteHealthResults.Result r = result;
                    mainHandler.post(() -> {
                        if (listener != null) listener.onProgress(doneNow, total, t, r);
                    });
                    if (stopRequested) break;
                    try {
                        Thread.sleep(GAP_MS);
                    } catch (InterruptedException ignored) {
                    }
                }
                SiteHealthResults.setLastRun(System.currentTimeMillis());
                int[] counts = SiteHealthResults.summarize(outcomes);
                final int doneFinal = done;
                final boolean stopped = stopRequested;
                mainHandler.post(() -> {
                    if (listener != null) listener.onFinished(doneFinal, total, counts, stopped);
                });
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    /** 全部内置站目标（午夜 + 短剧 + 直播 + 阅读；删掉的排除，隐藏的照常测）。 */
    public static List<Target> builtinTargets(Context context) {
        List<Target> targets = new ArrayList<>();
        if (context != null) {
            BuiltinConfigs.ensureLoaded(context);
            BuiltinDramaConfigs.ensureLoaded(context);
        }
        for (SourceBean s : BuiltinConfigs.getMidnightSitesAll()) {
            targets.add(new Target(BuiltinOverrides.ZONE_MIDNIGHT, s.getKey(), s.getName(),
                    s.getApi(), KIND_VIDEO, null));
        }
        for (SourceBean s : BuiltinDramaConfigs.getDramaSitesAll()) {
            targets.add(new Target(BuiltinOverrides.ZONE_DRAMA, s.getKey(), s.getName(),
                    s.getApi(), KIND_VIDEO, null));
        }
        for (BuiltinLiveSources.Source s : BuiltinLiveSources.getAllBuiltinSources()) {
            targets.add(new Target(BuiltinOverrides.ZONE_LIVE, s.id, s.name, "",
                    KIND_LIVE, s));
        }
        for (ReadingSources.Source s : ReadingSources.all()) {
            if (BuiltinOverrides.isDeleted(BuiltinOverrides.ZONE_READING, s.key)) continue;
            targets.add(new Target(BuiltinOverrides.ZONE_READING, s.key, s.name, s.api,
                    KIND_READING, null));
        }
        return targets;
    }

    /** 当前远程配置的用户站目标（已过滤内置站；只测当前生效的这份配置）。 */
    public static List<Target> userTargets() {
        List<Target> targets = new ArrayList<>();
        for (SourceBean s : ApiConfig.get().getSourceBeanList()) {
            if (ApiConfig.get().isBuiltinSite(s.getKey())) continue;
            targets.add(new Target(BuiltinOverrides.ZONE_USER, s.getKey(), s.getName(),
                    s.getApi(), KIND_VIDEO, null));
        }
        return targets;
    }

    // ===== 单站检测 =====

    private SiteHealthResults.Result checkOne(Context appContext, Target target) {
        currentProbe = probes.submit(() -> checkTarget(appContext, target));
        try {
            return currentProbe.get(TARGET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            currentProbe.cancel(true);
            return new SiteHealthResults.Result(SiteHealthResults.TIMEOUT,
                    System.currentTimeMillis(), "单站检测超时（" + TARGET_TIMEOUT_SECONDS + " 秒）");
        } catch (Exception e) {
            return new SiteHealthResults.Result(SiteHealthResults.ERROR,
                    System.currentTimeMillis(), shortMsg(e));
        } finally {
            currentProbe = null;
        }
    }

    private SiteHealthResults.Result checkTarget(Context appContext, Target target) {
        List<SiteHealthResults.Item> items = new ArrayList<>();
        try {
            if (target.kind == KIND_LIVE) {
                checkLive(appContext, target, items);
            } else if (target.kind == KIND_READING) {
                checkReading(target, items);
            } else {
                checkVideo(target, items);
            }
        } catch (Throwable t) {
            items.add(new SiteHealthResults.Item("检测", SiteHealthResults.ERROR, shortMsg(t), 0));
        }
        String status = overallStatus(items);
        String detail = summarizeDetail(items);
        return new SiteHealthResults.Result(status, System.currentTimeMillis(), detail, items);
    }

    /** 总判定：只看核心项，最重者胜（异常 > 超时 > 空 > 正常），跳过不算。 */
    private static String overallStatus(List<SiteHealthResults.Item> items) {
        int worst = 0; // 0 ok, 1 empty, 2 timeout, 3 error
        for (SiteHealthResults.Item item : items) {
            if (!item.core || SiteHealthResults.SKIP.equals(item.status)) continue;
            int rank = SiteHealthResults.ERROR.equals(item.status) ? 3
                    : SiteHealthResults.TIMEOUT.equals(item.status) ? 2
                    : SiteHealthResults.EMPTY.equals(item.status) ? 1 : 0;
            if (rank > worst) worst = rank;
        }
        if (worst == 3) return SiteHealthResults.ERROR;
        if (worst == 2) return SiteHealthResults.TIMEOUT;
        if (worst == 1) return SiteHealthResults.EMPTY;
        return SiteHealthResults.OK;
    }

    private static String summarizeDetail(List<SiteHealthResults.Item> items) {
        for (SiteHealthResults.Item item : items) {
            if (item.core && SiteHealthResults.ERROR.equals(item.status)) {
                return item.name + "：" + item.detail;
            }
        }
        for (SiteHealthResults.Item item : items) {
            if (item.core && SiteHealthResults.TIMEOUT.equals(item.status)) {
                return item.name + "：超时";
            }
        }
        for (SiteHealthResults.Item item : items) {
            if (item.core && SiteHealthResults.EMPTY.equals(item.status)) {
                return item.name + "：无数据";
            }
        }
        List<String> notes = new ArrayList<>();
        for (SiteHealthResults.Item item : items) {
            if (!item.detail.isEmpty()) notes.add(item.name + " " + item.detail);
        }
        return notes.isEmpty() ? "正常" : TextUtils.join(" · ", notes);
    }

    // ===== 视频站 =====

    private void checkVideo(Target target, List<SiteHealthResults.Item> items) {
        Site site = VodConfig.get().getSite(target.key);
        if (site == null || site.isEmpty()) {
            items.add(new SiteHealthResults.Item("首页", SiteHealthResults.ERROR, "站未同步进站表", 0));
            return;
        }
        long t0;
        // 1. jar 诊断：蜘蛛能不能加载（jar 站验内置 jar 缓存与 MD5）。
        t0 = System.currentTimeMillis();
        if (site.getType() == 3) {
            try {
                Spider spider = site.recent().spider();
                if (spider == null) {
                    items.add(new SiteHealthResults.Item("jar 诊断", SiteHealthResults.ERROR,
                            "蜘蛛加载不到", System.currentTimeMillis() - t0));
                } else if (BuiltinConfigs.JAR_SPEC.equals(site.getJar())
                        || BuiltinConfigs.JAR_NAME.equals(site.getJar())) {
                    boolean cached = com.github.catvod.utils.Util.equals(
                            BuiltinConfigs.JAR_NAME, BuiltinConfigs.JAR_MD5);
                    if (!cached) {
                        items.add(new SiteHealthResults.Item("jar 诊断", SiteHealthResults.ERROR,
                                "内置 jar 缓存缺失或 MD5 不对",
                                System.currentTimeMillis() - t0));
                    } else {
                        // LunaTV 借鉴：本地 jar 也验文件头与大小（Range 前 1KB 的等价做法）。
                        String jarNote = checkLocalJarFile();
                        items.add(new SiteHealthResults.Item("jar 诊断",
                                jarNote.startsWith("坏") ? SiteHealthResults.ERROR
                                        : SiteHealthResults.OK,
                                "内置 jar 缓存与 MD5 正常"
                                        + (jarNote.isEmpty() ? "" : " · " + jarNote),
                                System.currentTimeMillis() - t0));
                    }
                } else {
                    items.add(new SiteHealthResults.Item("jar 诊断", SiteHealthResults.OK,
                            "蜘蛛加载正常", System.currentTimeMillis() - t0));
                }
            } catch (Throwable e) {
                items.add(new SiteHealthResults.Item("jar 诊断", SiteHealthResults.ERROR,
                        shortMsg(e), System.currentTimeMillis() - t0));
            }
        } else {
            items.add(new SiteHealthResults.Item("jar 诊断", SiteHealthResults.SKIP,
                    "非蜘蛛站", 0, false));
        }
        // 2. 连通性：只有 http(s) 接口的站能直接测（参考项）。
        String api = site.getApi();
        String base = httpBase(api);
        t0 = System.currentTimeMillis();
        if (base == null) {
            items.add(new SiteHealthResults.Item("连通性", SiteHealthResults.SKIP,
                    "蜘蛛站无直连地址", 0, false));
        } else {
            try {
                long ms = headOrRange(base);
                items.add(new SiteHealthResults.Item("连通性", SiteHealthResults.OK,
                        ms + "ms", System.currentTimeMillis() - t0, false));
            } catch (java.net.SocketTimeoutException e) {
                items.add(new SiteHealthResults.Item("连通性", SiteHealthResults.TIMEOUT,
                        "连接超时", System.currentTimeMillis() - t0, false));
            } catch (Throwable e) {
                items.add(new SiteHealthResults.Item("连通性", SiteHealthResults.ERROR,
                        shortMsg(e), System.currentTimeMillis() - t0, false));
            }
        }
        // 3. 首页：走 App 内现有 spider 调用链（与 SourceViewModel.getSort 同款）。
        Result home = null;
        t0 = System.currentTimeMillis();
        try {
            home = SiteApi.homeContent(site);
            int count = home == null || home.getList() == null ? 0 : home.getList().size();
            int types = home == null || home.getTypes() == null ? 0 : home.getTypes().size();
            items.add(new SiteHealthResults.Item("首页",
                    count > 0 ? SiteHealthResults.OK : SiteHealthResults.EMPTY,
                    count > 0 ? count + " 条 · " + types + " 个分类" : "无数据",
                    System.currentTimeMillis() - t0));
        } catch (java.net.SocketTimeoutException e) {
            items.add(new SiteHealthResults.Item("首页", SiteHealthResults.TIMEOUT,
                    "超时", System.currentTimeMillis() - t0));
        } catch (Throwable e) {
            items.add(new SiteHealthResults.Item("首页", SiteHealthResults.ERROR,
                    shortMsg(e), System.currentTimeMillis() - t0));
        }
        // 4. 站点探测：防御等级（参考项）。http 站实测响应头/特征；蜘蛛站按首页结果推断。
        t0 = System.currentTimeMillis();
        if (base != null) {
            items.add(probeHttp(base, System.currentTimeMillis() - t0));
        } else {
            SiteHealthResults.Item homeItem = lastItem(items, "首页");
            String grade;
            String note;
            if (homeItem != null && SiteHealthResults.OK.equals(homeItem.status)) {
                grade = "0 级·直连";
                note = "首页直通";
            } else if (homeItem != null && SiteHealthResults.TIMEOUT.equals(homeItem.status)) {
                grade = "3 级·不通";
                note = "首页超时";
            } else if (homeItem != null && isShieldHint(homeItem.detail)) {
                grade = "2 级·疑似盾";
                note = homeItem.detail;
            } else {
                grade = "1 级·疑似墙";
                note = homeItem == null ? "" : homeItem.detail;
            }
            items.add(new SiteHealthResults.Item("站点探测", SiteHealthResults.OK,
                    grade + (note.isEmpty() ? "" : "（" + note + "）"), System.currentTimeMillis() - t0, false));
        }
        if (home == null || home.getList() == null || home.getList().isEmpty()) {
            // 首页没数据：搜索/详情/播放无从下手，后面三项跳过（总判定由首页的“空/异常”定）。
            items.add(new SiteHealthResults.Item("分类分页", SiteHealthResults.SKIP, "首页无数据", 0));
            items.add(new SiteHealthResults.Item("搜索实测", SiteHealthResults.SKIP, "首页无数据", 0));
            items.add(new SiteHealthResults.Item("详情", SiteHealthResults.SKIP, "首页无数据", 0));
            items.add(new SiteHealthResults.Item("真实播放", SiteHealthResults.SKIP, "首页无数据", 0));
            return;
        }
        Vod first = home.getList().get(0);
        String firstId = first == null ? "" : first.getId();
        String firstName = first == null ? "" : first.getName();
        // 5. 分类分页：第 1 页 + 第 2 页核对（spider-craft/cn-worker 口径：
        // 第 2 页必须出新 id，1/2 页 id 交集为 0 才算真分页）。
        t0 = System.currentTimeMillis();
        try {
            List<Class> types = home.getTypes();
            if (types == null || types.isEmpty()) {
                items.add(new SiteHealthResults.Item("分类分页", SiteHealthResults.SKIP,
                        "无分类", 0));
            } else {
                String tid = types.get(0).getTypeId();
                Result p1 = SiteApi.categoryContent(target.key, tid, "1", true, new HashMap<>());
                int c1 = p1 == null || p1.getList() == null ? 0 : p1.getList().size();
                int c2 = -1;
                int overlap = 0;
                try {
                    Result p2 = SiteApi.categoryContent(target.key, tid, "2", true, new HashMap<>());
                    c2 = p2 == null || p2.getList() == null ? 0 : p2.getList().size();
                    if (c1 > 0 && c2 > 0 && p1.getList() != null) {
                        java.util.Set<String> ids1 = new java.util.HashSet<>();
                        for (Vod v : p1.getList()) {
                            if (v != null && !TextUtils.isEmpty(v.getId())) ids1.add(v.getId());
                        }
                        for (Vod v : p2.getList()) {
                            if (v != null && ids1.contains(v.getId())) overlap++;
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (c1 > 0 && c2 > 0 && overlap >= c2) {
                    items.add(new SiteHealthResults.Item("分类分页",
                            SiteHealthResults.EMPTY,
                            "第 2 页无新条目（假分页可疑）",
                            System.currentTimeMillis() - t0));
                } else {
                    items.add(new SiteHealthResults.Item("分类分页",
                            c1 > 0 ? SiteHealthResults.OK : SiteHealthResults.EMPTY,
                            c1 > 0 ? "第 1 页 " + c1 + " 条 · 第 2 页 "
                                    + (c2 < 0 ? "?" : c2 + " 条")
                                    + (c2 > 0 ? " · 重合 " + overlap + " 条" : "")
                                    : "第 1 页无数据",
                            System.currentTimeMillis() - t0));
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            items.add(new SiteHealthResults.Item("分类分页", SiteHealthResults.TIMEOUT,
                    "超时", System.currentTimeMillis() - t0));
        } catch (Throwable e) {
            items.add(new SiteHealthResults.Item("分类分页", SiteHealthResults.ERROR,
                    shortMsg(e), System.currentTimeMillis() - t0));
        }
        // 6. 搜索实测：真发关键词（取首页首条名字前两个字，搜不到就搜“爱”）。
        // LunaTV 借鉴：记标题匹配率（相关率%），比单纯“有结果”更真实。
        String keyword = keywordFrom(firstName);
        t0 = System.currentTimeMillis();
        try {
            Result r = SiteApi.searchContent(site, keyword, false, "1");
            int count = r == null || r.getList() == null ? 0 : r.getList().size();
            int rate = matchRate(r == null ? null : r.getList(), keyword);
            items.add(new SiteHealthResults.Item("搜索实测",
                    count > 0 ? SiteHealthResults.OK : SiteHealthResults.EMPTY,
                    "搜“" + keyword + "”" + (count > 0 ? count + " 条 · 匹配率 " + rate + "%"
                            : "无结果"),
                    System.currentTimeMillis() - t0));
        } catch (java.net.SocketTimeoutException e) {
            items.add(new SiteHealthResults.Item("搜索实测", SiteHealthResults.TIMEOUT,
                    "超时", System.currentTimeMillis() - t0));
        } catch (Throwable e) {
            items.add(new SiteHealthResults.Item("搜索实测", SiteHealthResults.ERROR,
                    shortMsg(e), System.currentTimeMillis() - t0));
        }
        // 7. 空结果核对：乱搜一个词应返回空（参考项：乱给结果只记一笔，不拉垮总判定）。
        t0 = System.currentTimeMillis();
        try {
            Result r = SiteApi.searchContent(site, NONSENSE_KEYWORD, false, "1");
            int count = r == null || r.getList() == null ? 0 : r.getList().size();
            items.add(new SiteHealthResults.Item("空结果核对",
                    count == 0 ? SiteHealthResults.OK : SiteHealthResults.ERROR,
                    count == 0 ? "乱搜返回空，正常" : "乱搜还给 " + count + " 条（可疑）",
                    System.currentTimeMillis() - t0, false));
        } catch (Throwable e) {
            items.add(new SiteHealthResults.Item("空结果核对", SiteHealthResults.SKIP,
                    "搜不动：" + shortMsg(e), System.currentTimeMillis() - t0, false));
        }
        // 8. 详情：首条详情能不能取（cn-worker 口径：回显 id 须一致、线路数 >0；
        // spider-craft 口径：每条线路都须有选集）。
        Result detail = null;
        t0 = System.currentTimeMillis();
        try {
            detail = SiteApi.detailContent(target.key, firstId);
            Vod vod = detail == null ? null : detail.getVod();
            int flags = vod == null || vod.getFlags() == null ? 0 : vod.getFlags().size();
            int emptyFlags = 0;
            if (vod != null && vod.getFlags() != null) {
                for (Flag f : vod.getFlags()) {
                    if (f == null || f.getEpisodes() == null || f.getEpisodes().isEmpty()) {
                        emptyFlags++;
                    }
                }
            }
            boolean idOk = vod == null || TextUtils.isEmpty(firstId)
                    || TextUtils.isEmpty(vod.getId()) || firstId.equals(vod.getId());
            boolean ok = vod != null && !TextUtils.isEmpty(vod.getName()) && flags > 0
                    && emptyFlags == 0 && idOk;
            String note = ok ? vod.getName() + " · " + flags + " 条线路"
                    : !idOk ? "详情回显 id 不一致"
                    : vod == null || TextUtils.isEmpty(vod.getName()) ? "详情取不到"
                    : flags == 0 ? "详情无线路" : emptyFlags + " 条线路无选集";
            items.add(new SiteHealthResults.Item("详情",
                    ok ? SiteHealthResults.OK : SiteHealthResults.EMPTY,
                    note, System.currentTimeMillis() - t0));
        } catch (java.net.SocketTimeoutException e) {
            items.add(new SiteHealthResults.Item("详情", SiteHealthResults.TIMEOUT,
                    "超时", System.currentTimeMillis() - t0));
        } catch (Throwable e) {
            items.add(new SiteHealthResults.Item("详情", SiteHealthResults.ERROR,
                    shortMsg(e), System.currentTimeMillis() - t0));
        }
        // 9. 真实播放：取到播放地址后拉分片验真能播（tvyuan 口径：前 8 个分片里
        // 至少 2 个能下、单片 2xx 且 >1KB、TS 头 0x47 才算通，不只凭 m3u8 拉到就判通）。
        t0 = System.currentTimeMillis();
        try {
            Vod vod = detail == null ? null : detail.getVod();
            Flag flag = firstFlag(vod);
            if (flag == null || flag.getEpisodes() == null || flag.getEpisodes().isEmpty()) {
                items.add(new SiteHealthResults.Item("真实播放", SiteHealthResults.SKIP,
                        "无选集", 0));
            } else {
                Episode ep = flag.getEpisodes().get(0);
                Result play = SiteApi.playerContent(target.key, flag.getFlag(), ep.getUrl());
                String url = play == null ? "" : play.getRealUrl();
                if (TextUtils.isEmpty(url)) {
                    items.add(new SiteHealthResults.Item("真实播放", SiteHealthResults.EMPTY,
                            "没取到播放地址", System.currentTimeMillis() - t0));
                } else if (!url.startsWith("http")) {
                    items.add(new SiteHealthResults.Item("真实播放", SiteHealthResults.SKIP,
                            "非直链（" + schemeOf(url) + "），跳过验证", 0));
                } else {
                    long bytes = verifyMediaUrl(url);
                    items.add(new SiteHealthResults.Item("真实播放",
                            bytes > 0 ? SiteHealthResults.OK : SiteHealthResults.ERROR,
                            bytes > 0 ? "分片可下 · 共 " + bytes + " 字节" : "拉不到分片",
                            System.currentTimeMillis() - t0));
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            items.add(new SiteHealthResults.Item("真实播放", SiteHealthResults.TIMEOUT,
                    "超时", System.currentTimeMillis() - t0));
        } catch (Throwable e) {
            items.add(new SiteHealthResults.Item("真实播放", SiteHealthResults.ERROR,
                    shortMsg(e), System.currentTimeMillis() - t0));
        }
    }

    // ===== 直播源 =====

    private void checkLive(Context appContext, Target target, List<SiteHealthResults.Item> items) {
        BuiltinLiveSources.Source source = target.liveSource;
        if (source == null) {
            items.add(new SiteHealthResults.Item("频道拉取", SiteHealthResults.ERROR, "源配置缺失", 0));
            return;
        }
        // 1. 频道拉取。
        long t0 = System.currentTimeMillis();
        List<LiveChannel> channels = new ArrayList<>();
        try {
            if (source.protocol == BuiltinLiveSources.Protocol.PINGTAI) {
                List<BuiltinLiveSources.Platform> platforms = loadPlatformsSync(source, 20000);
                if (platforms == null) {
                    items.add(new SiteHealthResults.Item("频道拉取", SiteHealthResults.TIMEOUT,
                            "平台表超时", System.currentTimeMillis() - t0));
                } else if (platforms.isEmpty()) {
                    items.add(new SiteHealthResults.Item("频道拉取", SiteHealthResults.EMPTY,
                            "平台表为空", System.currentTimeMillis() - t0));
                } else {
                    channels = loadChannelsSync(source, platforms.get(0), 20000);
                    items.add(new SiteHealthResults.Item("频道拉取",
                            channels.isEmpty() ? SiteHealthResults.EMPTY : SiteHealthResults.OK,
                            platforms.size() + " 个平台 · 首平台 " + channels.size() + " 个频道",
                            System.currentTimeMillis() - t0));
                }
            } else {
                String text = BuiltinLiveSources.readAsset(appContext, source.assetPath);
                List<LiveGroup> groups = BuiltinLiveSources.parseListText(source.assetPath, text);
                for (LiveGroup g : groups) {
                    if (g.channels != null) channels.addAll(g.channels);
                }
                items.add(new SiteHealthResults.Item("频道拉取",
                        channels.isEmpty() ? SiteHealthResults.EMPTY : SiteHealthResults.OK,
                        groups.size() + " 组 · " + channels.size() + " 个频道",
                        System.currentTimeMillis() - t0));
            }
        } catch (java.net.SocketTimeoutException e) {
            items.add(new SiteHealthResults.Item("频道拉取", SiteHealthResults.TIMEOUT,
                    "超时", System.currentTimeMillis() - t0));
        } catch (Throwable e) {
            items.add(new SiteHealthResults.Item("频道拉取", SiteHealthResults.ERROR,
                    shortMsg(e), System.currentTimeMillis() - t0));
        }
        if (channels.isEmpty()) {
            items.add(new SiteHealthResults.Item("测速", SiteHealthResults.SKIP, "无频道", 0));
            items.add(new SiteHealthResults.Item("真实播放", SiteHealthResults.SKIP, "无频道", 0));
            return;
        }
        // 2. 测速：取前 3 个频道测首字节与分片速度。
        t0 = System.currentTimeMillis();
        List<Long> speeds = new ArrayList<>();
        String speedNote = "";
        int tried = 0;
        for (LiveChannel ch : channels) {
            if (tried >= 3) break;
            String url = firstChannelUrl(ch);
            if (TextUtils.isEmpty(url) || !url.startsWith("http")) continue;
            tried++;
            try {
                long kbps = measureStreamSpeed(url);
                if (kbps > 0) speeds.add(kbps);
            } catch (Throwable ignored) {
            }
        }
        if (speeds.isEmpty()) {
            items.add(new SiteHealthResults.Item("测速", SiteHealthResults.ERROR,
                    "3 个频道都测不到速度", System.currentTimeMillis() - t0));
        } else {
            speeds.sort(Long::compareTo);
            long median = speeds.get(speeds.size() / 2);
            speedNote = median + " KB/s（" + speedGrade(median, lastStreamResolution) + "）"
                    + (lastStreamResolution.isEmpty() ? ""
                    : " · " + lastStreamResolution);
            items.add(new SiteHealthResults.Item("测速", SiteHealthResults.OK,
                    "测了 " + speeds.size() + " 个频道 · 中位 " + speedNote,
                    System.currentTimeMillis() - t0));
        }
        // 3. 真实播放：首个频道拉 m3u8 + 首分片验真能播。
        t0 = System.currentTimeMillis();
        try {
            String url = firstChannelUrl(channels.get(0));
            if (TextUtils.isEmpty(url)) {
                items.add(new SiteHealthResults.Item("真实播放", SiteHealthResults.EMPTY,
                        "首频道无地址", System.currentTimeMillis() - t0));
            } else if (!url.startsWith("http")) {
                items.add(new SiteHealthResults.Item("真实播放", SiteHealthResults.SKIP,
                        "非直链（" + schemeOf(url) + "），跳过验证", 0));
            } else {
                long bytes = verifyMediaUrl(url);
                items.add(new SiteHealthResults.Item("真实播放",
                        bytes > 0 ? SiteHealthResults.OK : SiteHealthResults.ERROR,
                        bytes > 0 ? "分片可下 · 共 " + bytes + " 字节" : "拉不到分片",
                        System.currentTimeMillis() - t0));
            }
        } catch (Throwable e) {
            items.add(new SiteHealthResults.Item("真实播放", SiteHealthResults.ERROR,
                    shortMsg(e), System.currentTimeMillis() - t0));
        }
    }

    // ===== 阅读站 =====

    private void checkReading(Target target, List<SiteHealthResults.Item> items) {
        long t0;
        List<NovelLoader.Book> books = null;
        Spider spider = null;
        // 1. 首页书单。
        t0 = System.currentTimeMillis();
        try {
            spider = NovelLoader.getSpider(target.key, target.api, "");
            books = NovelLoader.homeBooks(spider);
            int count = books == null ? 0 : books.size();
            items.add(new SiteHealthResults.Item("首页",
                    count > 0 ? SiteHealthResults.OK : SiteHealthResults.EMPTY,
                    count > 0 ? count + " 本" : "首页无书",
                    System.currentTimeMillis() - t0));
        } catch (java.net.SocketTimeoutException e) {
            items.add(new SiteHealthResults.Item("首页", SiteHealthResults.TIMEOUT,
                    "超时", System.currentTimeMillis() - t0));
        } catch (Throwable e) {
            items.add(new SiteHealthResults.Item("首页", SiteHealthResults.ERROR,
                    shortMsg(e), System.currentTimeMillis() - t0));
        }
        if (books == null || books.isEmpty() || spider == null) {
            items.add(new SiteHealthResults.Item("搜索实测", SiteHealthResults.SKIP, "首页无书", 0));
            items.add(new SiteHealthResults.Item("详情章节", SiteHealthResults.SKIP, "首页无书", 0));
            return;
        }
        NovelLoader.Book first = books.get(0);
        // 2. 搜索实测：取首本书名头两个字真搜（LunaTV 借鉴：记标题匹配率）。
        String keyword = keywordFrom(first.title);
        t0 = System.currentTimeMillis();
        try {
            List<NovelLoader.Book> found = NovelLoader.searchBooks(spider, keyword);
            int count = found == null ? 0 : found.size();
            int rate = matchRateBooks(found, keyword);
            items.add(new SiteHealthResults.Item("搜索实测",
                    count > 0 ? SiteHealthResults.OK : SiteHealthResults.EMPTY,
                    "搜“" + keyword + "”" + (count > 0 ? count + " 本 · 匹配率 " + rate + "%"
                            : "无结果"),
                    System.currentTimeMillis() - t0));
        } catch (java.net.SocketTimeoutException e) {
            items.add(new SiteHealthResults.Item("搜索实测", SiteHealthResults.TIMEOUT,
                    "超时", System.currentTimeMillis() - t0));
        } catch (Throwable e) {
            items.add(new SiteHealthResults.Item("搜索实测", SiteHealthResults.ERROR,
                    shortMsg(e), System.currentTimeMillis() - t0));
        }
        // 3. 空结果核对（参考项）。
        t0 = System.currentTimeMillis();
        try {
            List<NovelLoader.Book> found = NovelLoader.searchBooks(spider, NONSENSE_KEYWORD);
            int count = found == null ? 0 : found.size();
            items.add(new SiteHealthResults.Item("空结果核对",
                    count == 0 ? SiteHealthResults.OK : SiteHealthResults.ERROR,
                    count == 0 ? "乱搜返回空，正常" : "乱搜还给 " + count + " 本（可疑）",
                    System.currentTimeMillis() - t0, false));
        } catch (Throwable e) {
            items.add(new SiteHealthResults.Item("空结果核对", SiteHealthResults.SKIP,
                    "搜不动：" + shortMsg(e), System.currentTimeMillis() - t0, false));
        }
        // 4. 详情章节：首本详情取章节。
        t0 = System.currentTimeMillis();
        try {
            NovelLoader.BookDetail detail = NovelLoader.bookDetail(spider, first.id);
            int count = detail == null || detail.chapters == null ? 0 : detail.chapters.size();
            items.add(new SiteHealthResults.Item("详情章节",
                    count > 0 ? SiteHealthResults.OK : SiteHealthResults.EMPTY,
                    count > 0 ? first.title + " · " + count + " 章" : "详情无章节",
                    System.currentTimeMillis() - t0));
        } catch (java.net.SocketTimeoutException e) {
            items.add(new SiteHealthResults.Item("详情章节", SiteHealthResults.TIMEOUT,
                    "超时", System.currentTimeMillis() - t0));
        } catch (Throwable e) {
            items.add(new SiteHealthResults.Item("详情章节", SiteHealthResults.ERROR,
                    shortMsg(e), System.currentTimeMillis() - t0));
        }
    }

    // ===== 小工具 =====

    /** 取 http(s) 基址：api 不是网址（蜘蛛类名）返回 null。 */
    private static String httpBase(String api) {
        if (TextUtils.isEmpty(api)) return null;
        String a = api.trim();
        if (!a.startsWith("http://") && !a.startsWith("https://")) return null;
        int cut = a.indexOf('/', 8);
        return cut > 0 ? a.substring(0, cut) : a;
    }

    /** HEAD 不通就退回 Range GET，只求一个存活证明 + 耗时。 */
    private long headOrRange(String url) throws IOException {
        Request head = new Request.Builder().url(url).head()
                .header("User-Agent", "Mozilla/5.0").build();
        long t0 = System.currentTimeMillis();
        try (Response r = OkGo.client().newCall(head).execute()) {
            if (r.code() < 500) return System.currentTimeMillis() - t0;
        } catch (IOException e) {
            // 有些站禁 HEAD，往下走 GET。
        }
        Request get = new Request.Builder().url(url).header("Range", "bytes=0-0")
                .header("User-Agent", "Mozilla/5.0").build();
        try (Response r = OkGo.client().newCall(get).execute()) {
            if (r.code() >= 500) throw new IOException("HTTP " + r.code());
            return System.currentTimeMillis() - t0;
        }
    }

    /** 站点探测：实测响应头与特征，分 0 直连 / 1 疑似 WAF / 2 疑似盾 / 3 不通（参考项）。 */
    private SiteHealthResults.Item probeHttp(String base, long ms) {
        try {
            Request req = new Request.Builder().url(base)
                    .header("User-Agent", "Mozilla/5.0").build();
            try (Response r = OkGo.client().newCall(req).execute()) {
                String cfRay = r.header("cf-ray");
                String server = r.header("server");
                String body = "";
                if (r.body() != null) {
                    String text = r.body().string();
                    body = text.length() > 4096 ? text.substring(0, 4096) : text;
                }
                int code = r.code();
                if (code == 403 || code == 503 || isShieldHint(body) || cfRay != null && code >= 400) {
                    return new SiteHealthResults.Item("站点探测", SiteHealthResults.OK,
                            "2 级·疑似盾（HTTP " + code + "）", ms, false);
                }
                // spider-craft 借鉴：现代特征（验证组件/现代框架）任一命中强制 4 级。
                if (isModernHint(body)) {
                    return new SiteHealthResults.Item("站点探测", SiteHealthResults.OK,
                            "4 级·现代防护（HTTP " + code + "）", ms, false);
                }
                if (cfRay != null || (server != null && server.toLowerCase().contains("cloudflare"))) {
                    return new SiteHealthResults.Item("站点探测", SiteHealthResults.OK,
                            "1 级·疑似 WAF（CF 在前，通）", ms, false);
                }
                if (code >= 400) {
                    return new SiteHealthResults.Item("站点探测", SiteHealthResults.OK,
                            "3 级·不通（HTTP " + code + "）", ms, false);
                }
                return new SiteHealthResults.Item("站点探测", SiteHealthResults.OK,
                        "0 级·直连（HTTP " + code + "）", ms, false);
            }
        } catch (java.net.SocketTimeoutException e) {
            return new SiteHealthResults.Item("站点探测", SiteHealthResults.OK,
                    "3 级·不通（超时）", ms, false);
        } catch (Throwable e) {
            return new SiteHealthResults.Item("站点探测", SiteHealthResults.OK,
                    "3 级·不通（" + shortMsg(e) + "）", ms, false);
        }
    }

    private static boolean isShieldHint(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String t = text.toLowerCase();
        return t.contains("just a moment") || t.contains("attention required")
                || t.contains("checking your browser") || t.contains("cf-challenge")
                || t.contains("cloudflare") && t.contains("challenge")
                || t.contains("人机验证") || t.contains("安全验证");
    }

    /** 现代防护特征：验证组件或现代前端框架（spider-craft：任一命中强制 4 级）。 */
    private static boolean isModernHint(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String t = text.toLowerCase();
        return t.contains("turnstile") || t.contains("hcaptcha") || t.contains("recaptcha")
                || t.contains("cf-challenge") || t.contains("__next_data__");
    }

    /**
     * 真实播放验证（tvyuan + iptv-api + spider-craft probe_media 口径）：
     * m3u8 先解析主清单（有变体时按最高 BANDWIDTH 取）、验 #EXTM3U、
     * 剔广告短循环（#EXT-X-ENDLIST 且总时长 ≤90 秒），再下前 8 个分片——
     * 够 3 个成功即停，至少 2 个成功（单片 2xx 且 >1KB、TS 头 0x47）才算通；
     * 直链 Range 取头，mp4 验 ftyp、flv 验 FLV 头。
     * 返回成功分片字节总数，0 表示拉不到。调用方保证 url 以 http 开头。
     */
    private long verifyMediaUrl(String url) throws IOException {
        if (url.contains(".m3u8")) {
            Playlist pl = resolvePlaylist(url);
            if (pl == null || pl.text == null || !pl.text.contains("#EXTM3U")) return 0;
            if (isShortLoopAd(pl.text)) {
                throw new IOException("疑似广告/无信号短循环");
            }
            List<String> segs = segmentUrls(pl.url, pl.text, 8);
            if (segs.isEmpty()) return 0;
            long total = 0;
            int good = 0;
            for (String seg : segs) {
                if (good >= 3) break;
                long n = verifySegment(seg);
                if (n > 0) {
                    good++;
                    total += n;
                }
            }
            return good >= 2 ? total : 0;
        }
        byte[] head = rangeBytesFull(url, 4095);
        if (head == null || head.length == 0) return 0;
        String lower = url.toLowerCase();
        if (lower.contains(".mp4") && !containsFtyp(head)) return 0;
        if (lower.contains(".flv") && !startsWith(head, "FLV")) return 0;
        return head.length;
    }

    /** 下一个分片：2xx 且 >1KB 才算成功；TS 分片验同步头 0x47。失败返回 0（不抛，换下一个）。 */
    private long verifySegment(String segUrl) {
        try {
            byte[] buf = rangeBytesFull(segUrl, 262143);
            if (buf == null || buf.length <= 1000) return 0;
            String lower = segUrl.toLowerCase();
            if (lower.contains(".ts") && (buf[0] & 0xFF) != 0x47) return 0;
            return buf.length;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 取一段字节（Range），2xx/206 才算数；返回 null 表示拉不到。 */
    private byte[] rangeBytesFull(String url, int bytes) throws IOException {
        Request req = new Request.Builder().url(url)
                .header("Range", "bytes=0-" + bytes)
                .header("User-Agent", "Mozilla/5.0").build();
        try (Response r = OkGo.client().newCall(req).execute()) {
            if (r.body() == null || r.code() >= 400) return null;
            return r.body().bytes();
        }
    }

    private long rangeBytes(String url, int bytes) throws IOException {
        byte[] buf = rangeBytesFull(url, bytes);
        return buf == null ? 0 : buf.length;
    }

    private static boolean containsFtyp(byte[] buf) {
        if (buf == null || buf.length < 8) return false;
        for (int i = 0; i + 4 <= buf.length && i < 16; i++) {
            if (buf[i] == 'f' && buf[i + 1] == 't' && buf[i + 2] == 'y' && buf[i + 3] == 'p') {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] buf, String prefix) {
        if (buf == null || buf.length < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (buf[i] != (byte) prefix.charAt(i)) return false;
        }
        return true;
    }

    /** 一份播放清单：最终媒体清单地址 + 文本 + 分辨率（变体行里取）。 */
    private static class Playlist {
        final String url;
        final String text;
        final String resolution;

        Playlist(String url, String text, String resolution) {
            this.url = url;
            this.text = text;
            this.resolution = resolution == null ? "" : resolution;
        }
    }

    /**
     * 解析 m3u8：主清单有 #EXT-X-STREAM-INF 变体时按最高 BANDWIDTH 取一档
     * （tvyuan 取第一行的改法：按带宽取最高码率），再拉媒体清单。
     */
    private Playlist resolvePlaylist(String url) throws IOException {
        String text = fetchText(url);
        if (text == null) return null;
        if (!text.contains("#EXT-X-STREAM-INF")) {
            return new Playlist(url, text, parseResolution(text));
        }
        String bestUrl = null;
        String bestRes = "";
        long bestBw = -1;
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue;
            long bw = parseLongAttr(line, "BANDWIDTH");
            String res = parseStringAttr(line, "RESOLUTION");
            String variant = null;
            for (int j = i + 1; j < lines.length; j++) {
                String s = lines[j].trim();
                if (s.isEmpty()) continue;
                if (s.startsWith("#")) continue;
                variant = s;
                break;
            }
            if (variant != null && bw >= bestBw) {
                bestBw = bw;
                bestUrl = resolveUrl(url, variant);
                bestRes = res;
            }
        }
        if (bestUrl == null) return new Playlist(url, text, "");
        String media = fetchText(bestUrl);
        if (media == null) return null;
        return new Playlist(bestUrl, media, bestRes);
    }

    private String fetchText(String url) throws IOException {
        Request req = new Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0").build();
        try (Response r = OkGo.client().newCall(req).execute()) {
            if (r.body() == null || r.code() >= 400) return null;
            return r.body().string();
        }
    }

    /** 清单里取分片地址（相对地址按清单地址拼），最多 max 条。 */
    private static List<String> segmentUrls(String playlistUrl, String playlist, int max) {
        List<String> out = new ArrayList<>();
        for (String line : playlist.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            out.add(resolveUrl(playlistUrl, s));
            if (out.size() >= max) break;
        }
        return out;
    }

    private static String resolveUrl(String base, String ref) {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        if (ref.startsWith("/")) {
            int cut = base.indexOf('/', 8);
            return (cut > 0 ? base.substring(0, cut) : base) + ref;
        }
        int slash = base.lastIndexOf('/');
        return (slash >= 0 ? base.substring(0, slash + 1) : base) + ref;
    }

    /** 广告/无信号短循环（iptv-api 口径）：已播完且总时长 ≤90 秒。 */
    private static boolean isShortLoopAd(String playlist) {
        if (!playlist.contains("#EXT-X-ENDLIST")) return false;
        double total = 0;
        for (String line : playlist.split("\n")) {
            String s = line.trim();
            if (s.startsWith("#EXTINF:")) {
                try {
                    String num = s.substring(8).split(",")[0].trim();
                    total += Double.parseDouble(num);
                } catch (Throwable ignored) {
                }
            }
        }
        return total > 0 && total <= 90;
    }

    private static String parseResolution(String playlist) {
        for (String line : playlist.split("\n")) {
            String s = line.trim();
            if (s.startsWith("#EXT-X-STREAM-INF")) {
                return parseStringAttr(s, "RESOLUTION");
            }
        }
        return "";
    }

    private static long parseLongAttr(String line, String key) {
        try {
            String v = parseStringAttr(line, key);
            return v.isEmpty() ? -1 : Long.parseLong(v);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String parseStringAttr(String line, String key) {
        int i = line.indexOf(key + "=");
        if (i < 0) return "";
        int start = i + key.length() + 1;
        int end = line.indexOf(',', start);
        if (end < 0) end = line.length();
        String v = line.substring(start, end).trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    /**
     * 直播测速（iptv-api 口径）：m3u8 取末尾 2 个分片实下，延迟取首字节、速率取实下字节/时间；
     * 单源上限 256KB。返回 KB/s，0 表示测不到；分辨率记进 lastStreamResolution 供展示。
     */
    private String lastStreamResolution = "";

    private long measureStreamSpeed(String url) throws IOException {
        long t0 = System.currentTimeMillis();
        long bytes;
        if (url.contains(".m3u8")) {
            Playlist pl = resolvePlaylist(url);
            if (pl == null || pl.text == null || !pl.text.contains("#EXTM3U")) return 0;
            lastStreamResolution = pl.resolution;
            if (isShortLoopAd(pl.text)) return 0;
            List<String> segs = segmentUrls(pl.url, pl.text, 8);
            if (segs.isEmpty()) return 0;
            bytes = 0;
            int from = Math.max(0, segs.size() - 2);
            for (int i = from; i < segs.size(); i++) {
                bytes += rangeBytes(segs.get(i), 65535);
            }
        } else {
            bytes = rangeBytes(url, 65535);
        }
        long ms = System.currentTimeMillis() - t0;
        if (bytes <= 0 || ms <= 0) return 0;
        return bytes * 1000 / ms / 1024;
    }

    /**
     * 测速分级（iptv-api 口径：速率按分辨率分档，720p≥0.2 / 1080p≥0.5 / 4K≥1.0 MiB/s；
     * 分辨率未知时沿用旧口径 快≥500、中≥100 KB/s）。
     */
    private static String speedGrade(long kbps, String resolution) {
        int height = 0;
        if (resolution != null && resolution.contains("x")) {
            try {
                height = Integer.parseInt(resolution.split("x")[1]);
            } catch (Throwable ignored) {
            }
        }
        long fast = 500;
        if (height >= 2160) fast = 1024;
        else if (height >= 1080) fast = 512;
        else if (height >= 720) fast = 205;
        if (kbps >= fast) return "快";
        if (kbps >= 100) return "中";
        return "慢";
    }

    /** 本地 jar 文件头与大小（LunaTV 口径：前 1KB 验 PK 头、<1KB 判无效、<50KB 判可疑）。 */
    private static String checkLocalJarFile() {
        try {
            java.io.File f = com.github.catvod.utils.Path.jar(BuiltinConfigs.JAR_NAME);
            if (f == null || !f.exists()) return "坏：本地 jar 文件不存在";
            if (f.length() < 1000) return "坏：jar 文件过小（" + f.length() + " 字节）";
            byte[] head = new byte[2];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                if (in.read(head) < 2) return "坏：jar 文件读不到头";
            }
            if (head[0] != 'P' || head[1] != 'K') return "坏：jar 文件头不是 ZIP";
            if (f.length() < 50 * 1024) return "jar 偏小（" + (f.length() / 1024) + "KB），可疑";
            return "";
        } catch (Throwable e) {
            return "";
        }
    }

    /** 搜索标题匹配率（LunaTV 口径：结果标题含关键词的占比）。 */
    private static int matchRate(List<Vod> list, String keyword) {
        if (list == null || list.isEmpty() || TextUtils.isEmpty(keyword)) return 0;
        String k = keyword.toLowerCase();
        int hit = 0;
        for (Vod v : list) {
            if (v != null && v.getName() != null && v.getName().toLowerCase().contains(k)) hit++;
        }
        return hit * 100 / list.size();
    }

    private static int matchRateBooks(List<NovelLoader.Book> list, String keyword) {
        if (list == null || list.isEmpty() || TextUtils.isEmpty(keyword)) return 0;
        String k = keyword.toLowerCase();
        int hit = 0;
        for (NovelLoader.Book b : list) {
            if (b != null && b.title != null && b.title.toLowerCase().contains(k)) hit++;
        }
        return hit * 100 / list.size();
    }

    /** 把 pingtai 的回调式拉平台表转成同步等（latch + 超时）。 */
    private List<BuiltinLiveSources.Platform> loadPlatformsSync(
            BuiltinLiveSources.Source source, long timeoutMs) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<BuiltinLiveSources.Platform>> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        BuiltinLiveSources.loadPlatforms(source, new BuiltinLiveSources.OnPlatforms() {
            @Override
            public void onResult(List<BuiltinLiveSources.Platform> platforms) {
                result.set(platforms);
                latch.countDown();
            }

            @Override
            public void onError(String msg) {
                error.set(msg);
                latch.countDown();
            }
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null;
        if (result.get() != null) return result.get();
        throw new IOException(error.get() == null ? "平台表拉取失败" : error.get());
    }

    private List<LiveChannel> loadChannelsSync(BuiltinLiveSources.Source source,
                                               BuiltinLiveSources.Platform platform,
                                               long timeoutMs) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<LiveChannel>> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        BuiltinLiveSources.loadPingtaiChannels(source, platform, new BuiltinLiveSources.OnChannels() {
            @Override
            public void onResult(List<LiveChannel> channels) {
                result.set(channels);
                latch.countDown();
            }

            @Override
            public void onError(String msg) {
                error.set(msg);
                latch.countDown();
            }
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new java.net.SocketTimeoutException("频道表超时");
        }
        if (result.get() != null) return result.get();
        throw new IOException(error.get() == null ? "频道表拉取失败" : error.get());
    }

    private static String firstChannelUrl(LiveChannel ch) {
        if (ch == null || ch.urls == null || ch.urls.isEmpty()) return "";
        return ch.urls.get(0);
    }

    private static Flag firstFlag(Vod vod) {
        if (vod == null || vod.getFlags() == null) return null;
        for (Flag f : vod.getFlags()) {
            if (f != null && f.getEpisodes() != null && !f.getEpisodes().isEmpty()) return f;
        }
        return null;
    }

    private static SiteHealthResults.Item lastItem(List<SiteHealthResults.Item> items, String name) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (name.equals(items.get(i).name)) return items.get(i);
        }
        return null;
    }

    /** 搜索关键词：取名字头两个字（太长反而搜不到）；取不到就搜“爱”。 */
    private static String keywordFrom(String name) {
        if (!TextUtils.isEmpty(name)) {
            String k = name.trim();
            if (k.length() >= 2) return k.substring(0, 2);
            if (k.length() == 1) return k;
        }
        return "爱";
    }

    private static String schemeOf(String url) {
        int i = url.indexOf("://");
        return i > 0 ? url.substring(0, i) : "未知协议";
    }

    private static String shortMsg(Throwable e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        if (TextUtils.isEmpty(m)) return e.getClass().getSimpleName();
        if (m.length() > 60) m = m.substring(0, 60) + "…";
        return m;
    }
}
