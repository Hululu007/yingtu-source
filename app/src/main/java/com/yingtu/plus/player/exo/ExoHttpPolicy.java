package com.yingtu.plus.player.exo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import java.util.HashMap;
import java.util.Map;

/**
 * 播放链路统一的 HTTP 超时与重试策略（黄瓜密钥修法第 1 件）。
 *
 * <p>根因：黄瓜流主机拉 AES-128 密钥时间歇性 403/超时，单请求约 8 秒，刚好撞上
 * {@link DefaultHttpDataSource} 默认的 8 秒连接/读取超时。主路（VideoActivity/
 * PlaybackActivity，经 MediaSourceFactory + OkHttp）本来就是 30 秒，不受影响；
 * 详情页内联播放（DetailActivity）与通用内核（ExoPlayerCore）用的是
 * {@code new DefaultHttpDataSource.Factory()}，全是 8 秒默认——这里统一抬高。
 *
 * <p>403 本就是可重试错误（分叉版 DefaultLoadErrorHandlingPolicy：403 不在不可重试
 * 之列，默认最少重试 3 次，退避 min((errorCount-1)*1000, 5000)）；这里把次数与退避
 * 写成显式值（3 次，退避 1 秒/2 秒/4 秒，上限 5 秒），不再依赖默认。
 * 最坏情况：单次加载 4 次尝试 x 30 秒读超时 + 退避 7 秒，约 127 秒才报播放失败；
 * 403 是立即返回的，实际重试在几秒内完成。
 */
@OptIn(markerClass = UnstableApi.class)
public final class ExoHttpPolicy {

    /** 连接超时：建连 + TLS 握手，8 秒实测已到边缘，放到 15 秒。 */
    public static final int CONNECT_TIMEOUT_MS = 15 * 1000;

    /** 读取超时：单次读不到数据就断，与主路 OkHttp.player() 的 30 秒看齐。 */
    public static final int READ_TIMEOUT_MS = 30 * 1000;

    /** 可重试加载（清单/密钥/分片 403、超时、5xx 等）最少重试次数：3 次，共 4 次尝试。 */
    public static final int MIN_LOADABLE_RETRY_COUNT = 3;

    private ExoHttpPolicy() {}

    /** 带统一超时 + 指定请求头的 HTTP 数据源工厂（User-Agent 单独设，其余进默认头）。 */
    @NonNull
    public static DefaultHttpDataSource.Factory newHttpDataSourceFactory(
            @Nullable Map<String, String> headers) {
        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(READ_TIMEOUT_MS);
        if (headers == null || headers.isEmpty()) return factory;
        String userAgent = null;
        Map<String, String> extra = new HashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            if ("User-Agent".equalsIgnoreCase(entry.getKey())) userAgent = entry.getValue();
            else extra.put(entry.getKey(), entry.getValue());
        }
        if (userAgent != null) factory.setUserAgent(userAgent);
        if (!extra.isEmpty()) factory.setDefaultRequestProperties(extra);
        return factory;
    }

    /** 显式重试策略：最少重试 3 次；不可重试的错（解析错、文件不存在等）原样不重试。 */
    @NonNull
    public static LoadErrorHandlingPolicy newRetryPolicy() {
        return new DefaultLoadErrorHandlingPolicy(MIN_LOADABLE_RETRY_COUNT) {
            @Override
            public long getRetryDelayMsFor(LoadErrorInfo loadErrorInfo) {
                long base = super.getRetryDelayMsFor(loadErrorInfo);
                if (base == C.TIME_UNSET) return C.TIME_UNSET;
                int shift = Math.min(Math.max(loadErrorInfo.errorCount - 1, 0), 2);
                return Math.min(1000L << shift, 5000L);
            }
        };
    }

    /** 带显式重试策略的媒体源工厂（供直接建 DefaultMediaSourceFactory 的播放路复用）。 */
    @NonNull
    public static DefaultMediaSourceFactory newMediaSourceFactory(
            @NonNull DataSource.Factory dataSourceFactory) {
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(dataSourceFactory);
        factory.setLoadErrorHandlingPolicy(newRetryPolicy());
        return factory;
    }
}
