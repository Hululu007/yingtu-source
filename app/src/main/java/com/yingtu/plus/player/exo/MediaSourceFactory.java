package com.yingtu.plus.player.exo;

import static androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PriorityTaskManager;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;

import com.yingtu.plus.App;
import com.yingtu.plus.player.exo.ass.AssFontSet;
import com.yingtu.plus.player.exo.ass.ExoAssSession;
import com.yingtu.plus.player.cache.DiskCacheCapacityPolicy;
import com.yingtu.plus.setting.PlaybackPerformanceSetting;
import com.yingtu.plus.setting.PlayerSetting;
import com.yingtu.plus.setting.PreloadSetting;
import com.yingtu.plus.utils.FileUtil;
import com.yingtu.plus.utils.UrlUtil;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.util.HawkConfig;

import java.io.File;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MediaSourceFactory implements MediaSource.Factory {

    private static final String CONCAT_SOURCE_SEPARATOR = "***";
    private static final String CONCAT_SOURCE_SEPARATOR_REGEX = "\\*\\*\\*";
    private static final String CONCAT_DURATION_SEPARATOR = "|||";
    private static final String CONCAT_DURATION_SEPARATOR_REGEX = "\\|\\|\\|";
    private static final PriorityTaskManager PLAYBACK_PRIORITY_MANAGER = new PriorityTaskManager();
    private static final CacheCapacityState CACHE_CAPACITY_STATE = new CacheCapacityState();

    private static StandaloneDatabaseProvider databaseProvider;
    private static Cache cache;

    private final DefaultMediaSourceFactory defaultMediaSourceFactory;
    private OkHttpDataSource.Factory httpDataSourceFactory;
    private DataSource.Factory dataSourceFactory;
    private ExtractorsFactory extractorsFactory;
    @Nullable private final ExoDolbyVisionPlaybackState dolbyVisionPlaybackState;
    @Nullable private final ExoAssSession assSession;

    public MediaSourceFactory() {
        this(null);
    }

    MediaSourceFactory(
            @Nullable ExoDolbyVisionPlaybackState dolbyVisionPlaybackState) {
        this(dolbyVisionPlaybackState, null);
    }

    MediaSourceFactory(@Nullable ExoDolbyVisionPlaybackState dolbyVisionPlaybackState,
            @Nullable ExoAssSession assSession) {
        this.dolbyVisionPlaybackState = dolbyVisionPlaybackState;
        this.assSession = assSession;
        defaultMediaSourceFactory = new DefaultMediaSourceFactory(getDataSourceFactory(), getExtractorsFactory());
        // 黄瓜密钥修法：重试策略写成显式值（3 次，退避 1/2/4 秒），不再只依赖默认。
        defaultMediaSourceFactory.setLoadErrorHandlingPolicy(ExoHttpPolicy.newRetryPolicy());
        defaultMediaSourceFactory.setLoadOnlySelectedTracks(PlaybackPerformanceSetting.isLoadOnlySelectedTracksEnabled());
    }

    static DataSource.Factory createUpstreamDataSourceFactory(Map<String, String> headers) {
        OkHttpDataSource.Factory factory = new OkHttpDataSource.Factory(OkHttp.player());
        applyHeaders(factory, headers);
        DataSource.Factory upstream = new DefaultDataSource.Factory(App.get(), factory);
        DataSource.Factory recovered = new HttpEofRecoveryDataSource.Factory(upstream);
        return new PriorityTaskDataSource.Factory(recovered, PLAYBACK_PRIORITY_MANAGER, C.PRIORITY_PLAYBACK_PRELOAD, true);
    }

    static synchronized Cache getCache() {
        if (cache != null) return cache;
        File dir = resolveCacheDir();
        DiskCacheCapacityPolicy.Decision decision = resolveCapacity(dir, FileUtil.getDirectorySize(dir));
        long capacityBytes = initialCapacityBytes(decision);
        Cache created = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(capacityBytes), getDatabaseProvider());
        cache = created;
        CACHE_CAPACITY_STATE.recordCreated(capacityBytes);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-cache", "created capacityBytes=%d policy=%s existingBytes=%d availableBytes=%d reserveBytes=%d", capacityBytes, decision.state(), decision.existingCacheBytes(), decision.availableStorageBytes(), decision.reserveBytes());
        return created;
    }

    public static synchronized void acquireCacheSession() {
        DiskCacheCapacityPolicy.Decision decision = refreshPendingCacheCapacity();
        if (isReliable(decision) && CACHE_CAPACITY_STATE.canReleasePending()) rebuildCacheLocked("next-player-session");
        CACHE_CAPACITY_STATE.acquireSession();
    }

    public static synchronized void releaseCacheSession() {
        CACHE_CAPACITY_STATE.releaseSession();
        DiskCacheCapacityPolicy.Decision decision = refreshPendingCacheCapacity();
        if (isReliable(decision) && CACHE_CAPACITY_STATE.canReleasePending()) rebuildCacheLocked("last-player-release");
    }

    /** 播放缓存目录：默认内置存储（Path.exoCache()），选“外部存储”时用外置缓存目录下的 exo。 */
    public static File resolveCacheDir() {
        try {
            if (Hawk.get(HawkConfig.CACHE_LOCATION, 0) == 1) {
                File ext = App.get().getExternalCacheDir();
                if (ext != null) return new File(ext, "exo");
            }
        } catch (Throwable ignored) {
        }
        return Path.exoCache();
    }

    /** 缓存位置切换后调用：释放旧目录缓存，下次取缓存时在新目录重建（旧目录文件保留）。 */
    public static synchronized void resetCacheForLocation() {
        releaseCacheLocked("cache-location-changed");
    }

    private static StandaloneDatabaseProvider getDatabaseProvider() {
        if (databaseProvider == null) databaseProvider = new StandaloneDatabaseProvider(App.get());
        return databaseProvider;
    }

    private static DiskCacheCapacityPolicy.Decision resolveCapacity(File dir, long existingCacheBytes) {
        FileUtil.StorageSpace storage = FileUtil.getStorageSpace(dir);
        return DiskCacheCapacityPolicy.resolve(storage.available(), PreloadSetting.getPreloadSizeBytes(PlayerSetting.EXO), existingCacheBytes, storage.availableBytes(), storage.totalBytes());
    }

    private static long initialCapacityBytes(DiskCacheCapacityPolicy.Decision decision) {
        if (!isReliable(decision)) return decision.existingCacheBytes();
        return decision.effectiveCapacityBytes();
    }

    static synchronized long getCacheCapacityBytes() {
        DiskCacheCapacityPolicy.Decision decision = refreshPendingCacheCapacity();
        return cache == null ? initialCapacityBytes(decision) : CACHE_CAPACITY_STATE.actualCapacityBytes();
    }

    static synchronized long getPendingCacheCapacityBytes() {
        refreshPendingCacheCapacity();
        return CACHE_CAPACITY_STATE.pendingCapacityBytes();
    }

    static synchronized ExoCacheWritePolicy.Decision getCacheWriteDecision() {
        DiskCacheCapacityPolicy.Decision capacity = refreshPendingCacheCapacity();
        long actualCapacityBytes = cache == null ? 0 : CACHE_CAPACITY_STATE.actualCapacityBytes();
        return ExoCacheWritePolicy.resolve(capacity, actualCapacityBytes);
    }

    private static DiskCacheCapacityPolicy.Decision refreshPendingCacheCapacity() {
        File dir = Path.exoCache();
        long existingCacheBytes = cache == null ? FileUtil.getDirectorySize(dir) : cache.getCacheSpace();
        DiskCacheCapacityPolicy.Decision decision = resolveCapacity(dir, existingCacheBytes);
        if (isReliable(decision)) CACHE_CAPACITY_STATE.report(decision.effectiveCapacityBytes());
        return decision;
    }

    private static boolean isReliable(DiskCacheCapacityPolicy.Decision decision) {
        return decision.state() != DiskCacheCapacityPolicy.State.UNAVAILABLE;
    }

    private static void rebuildCacheLocked(String reason) {
        if (!releaseCacheLocked(reason)) return;
        try {
            getCache();
        } catch (RuntimeException e) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-cache", "rebuild-failed reason=%s error=%s", reason, e.getClass().getSimpleName());
        }
    }

    private static boolean releaseCacheLocked(String reason) {
        if (cache == null) return false;
        Cache releasing = cache;
        long actual = CACHE_CAPACITY_STATE.actualCapacityBytes();
        long pending = CACHE_CAPACITY_STATE.pendingCapacityBytes();
        try {
            releasing.release();
            cache = null;
            CACHE_CAPACITY_STATE.recordReleased();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-cache", "released reason=%s actualCapacityBytes=%d pendingCapacityBytes=%d activeSessions=%d", reason, actual, pending, CACHE_CAPACITY_STATE.activeSessions());
            return true;
        } catch (RuntimeException e) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-cache", "release-failed reason=%s error=%s activeSessions=%d", reason, e.getClass().getSimpleName(), CACHE_CAPACITY_STATE.activeSessions());
            return false;
        }
    }

    static boolean isConcatenatingUrl(String url) {
        return url != null && url.contains(CONCAT_SOURCE_SEPARATOR) && url.contains(CONCAT_DURATION_SEPARATOR);
    }

    @NonNull
    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
        return this;
    }

    @NonNull
    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
        // 原来是空实现：外层传进来的策略被直接丢掉，工厂一直用默认策略；现在透传下去。
        defaultMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
        return this;
    }

    @NonNull
    @Override
    public @C.ContentType int[] getSupportedTypes() {
        return defaultMediaSourceFactory.getSupportedTypes();
    }

    @NonNull
    @Override
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
        applyHeaders(getHttpDataSourceFactory(), ExoUtil.extractHeaders(mediaItem));
        String url = mediaItem.requestMetadata.mediaUri != null ? mediaItem.requestMetadata.mediaUri.toString() : "";
        AssFontSet fonts = assSession == null ? null : assSession.beginMediaFonts();
        if (isConcatenatingUrl(url)) return createConcatenatingMediaSource(mediaItem, url);
        if (fonts != null || mediaItem.localConfiguration != null && mediaItem.localConfiguration.tag instanceof com.yingtu.plus.player.PlaybackDiagnosticCollector.Context) {
            // Bind the collection to this source's extractors. Old loaders cannot populate a new video.
            // Container ASS needs its attachments even when no external subtitle was configured.
            return new DefaultMediaSourceFactory(getDataSourceFactory(), Media3DiagnosticBridge.extractors(buildExtractorsFactory(fonts), mediaItem))
                    .setLoadErrorHandlingPolicy(ExoHttpPolicy.newRetryPolicy())
                    .setLoadOnlySelectedTracks(PlaybackPerformanceSetting.isLoadOnlySelectedTracksEnabled())
                    .createMediaSource(mediaItem);
        }
        else return defaultMediaSourceFactory.createMediaSource(mediaItem);
    }

    private MediaSource createConcatenatingMediaSource(MediaItem mediaItem, String url) {
        ConcatenatingMediaSource2.Builder builder = new ConcatenatingMediaSource2.Builder();
        DefaultMediaSourceFactory sourceFactory = new DefaultMediaSourceFactory(getDataSourceFactory(),
                Media3DiagnosticBridge.extractors(getExtractorsFactory(), mediaItem))
                .setLoadErrorHandlingPolicy(ExoHttpPolicy.newRetryPolicy())
                .setLoadOnlySelectedTracks(PlaybackPerformanceSetting.isLoadOnlySelectedTracksEnabled());
        for (String split : url.split(CONCAT_SOURCE_SEPARATOR_REGEX)) {
            String[] info = split.split(CONCAT_DURATION_SEPARATOR_REGEX);
            if (info.length >= 2) builder.add(sourceFactory.createMediaSource(mediaItem.buildUpon().setUri(UrlUtil.uri(info[0])).build()), Long.parseLong(info[1]));
        }
        return builder.build();
    }

    private ExtractorsFactory getExtractorsFactory() {
        if (extractorsFactory == null) extractorsFactory = buildExtractorsFactory(null);
        return extractorsFactory;
    }

    private ExtractorsFactory buildExtractorsFactory(@Nullable AssFontSet fonts) {
            ExtractorsFactory defaults = new DefaultExtractorsFactory()
                    .setTsExtractorFlags(FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                    .setTsExtractorTimestampSearchBytes(
                            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10);
            ExtractorsFactory withApe = new ExtractorsFactory() {
                @Override
                public androidx.media3.extractor.Extractor[] createExtractors() {
                    return prependApe(defaults.createExtractors());
                }

                @Override
                public androidx.media3.extractor.Extractor[] createExtractors(
                        Uri uri, Map<String, List<String>> responseHeaders) {
                    return prependApe(defaults.createExtractors(uri, responseHeaders));
                }
            };
            return new DolbyVisionP81ExtractorsFactory(withApe, dolbyVisionPlaybackState, fonts);
    }

    private static androidx.media3.extractor.Extractor[] prependApe(
            androidx.media3.extractor.Extractor[] defaults) {
        androidx.media3.extractor.Extractor[] extractors =
                new androidx.media3.extractor.Extractor[defaults.length + 1];
        extractors[0] = new ApeExtractor();
        System.arraycopy(defaults, 0, extractors, 1, defaults.length);
        return extractors;
    }

    private DataSource.Factory getDataSourceFactory() {
        if (dataSourceFactory == null) {
            DataSource.Factory http = getHttpDataSourceFactory();
            // 缓冲超时：主路 OkHttpDataSource 的连接/读取超时也跟设置走（关闭=默认 8/8 秒，
            // ExoHttpPolicy 那路是 15/30 秒默认值，两路各按各的默认，只在用户选了秒数时统一改）。
            int timeoutSec = PlayerSetting.getBufferTimeoutSec();
            if (timeoutSec > 0 && http instanceof OkHttpDataSource.Factory okFactory) {
                okFactory.setConnectTimeoutMs(timeoutSec * 1000).setReadTimeoutMs(timeoutSec * 1000);
            }
            // 智能去除切片广告：开关开时在清单层过滤广告分片（见 HlsAdFilterDataSource）。
            if (HlsAdFilterDataSource.isEnabled()) http = new HlsAdFilterDataSource.Factory(http);
            DataSource.Factory cacheDataSource = getCacheDataSource(new DefaultDataSource.Factory(App.get(), http));
            DataSource.Factory trackedDataSource = new PlaybackBytePositionDataSource.Factory(cacheDataSource);
            dataSourceFactory = new PriorityTaskDataSource.Factory(trackedDataSource, PLAYBACK_PRIORITY_MANAGER, C.PRIORITY_PLAYBACK, false);
        }
        return dataSourceFactory;
    }

    private CacheDataSource.Factory getCacheDataSource(DataSource.Factory upstreamFactory) {
        return new CacheDataSource.Factory()
                .setCache(getCache())
                .setUpstreamDataSourceFactory(new HttpEofRecoveryDataSource.Factory(upstreamFactory))
                .setCacheWriteDataSinkFactory(null)
                .setEventListener(PlaybackCacheMetrics.listener())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    private OkHttpDataSource.Factory getHttpDataSourceFactory() {
        if (httpDataSourceFactory == null) httpDataSourceFactory = new OkHttpDataSource.Factory(OkHttp.player());
        return httpDataSourceFactory;
    }

    private static void applyHeaders(OkHttpDataSource.Factory factory, Map<String, String> headers) {
        Map<String, String> sanitized = sanitizeHeaders(headers);
        String userAgent = removeUserAgentHeader(sanitized);
        factory.setUserAgent(userAgent).setDefaultRequestProperties(sanitized);
    }

    static Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        if (headers == null || headers.isEmpty()) return sanitized;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = entry.getKey().trim();
            if (key.isEmpty()) continue;
            sanitized.put(key, entry.getValue().trim());
        }
        return sanitized;
    }

    static String removeUserAgentHeader(Map<String, String> headers) {
        String userAgent = null;
        Iterator<Map.Entry<String, String>> iterator = headers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, String> entry = iterator.next();
            if (!"User-Agent".equalsIgnoreCase(entry.getKey())) continue;
            String value = entry.getValue().trim();
            if (!value.isEmpty()) userAgent = value;
            iterator.remove();
        }
        return userAgent;
    }
}
