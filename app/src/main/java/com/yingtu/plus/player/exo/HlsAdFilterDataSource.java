package com.yingtu.plus.player.exo;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.util.HawkConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 智能去除切片广告（HLS 清单层过滤）：只在 .m3u8 清单上动手脚，其余请求原样透传。
 * 做法：打开清单时把全文读出来，按行删掉广告分片（分片地址含广告特征的，
 * 连同它前面的 #EXTINF 行一起删），再把过滤后的清单交给 Exo 解析。
 * 分片本身不落地、不改写、不重新编号——删行后序列号由 Exo 按清单重算，
 * 不是按字节拼流，所以不会把正常分片错位。特征只认地址里的广告标记
 * （doubleclick / ads / adserver / preroll / midroll 等），不按时长删，
 * 免得把正常短分片误杀。开关关时本类直接返回上游，不读清单。
 */
@OptIn(markerClass = UnstableApi.class)
public final class HlsAdFilterDataSource extends BaseDataSource {

    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstream;

        public Factory(DataSource.Factory upstream) {
            this.upstream = upstream;
        }

        @Override
        public DataSource createDataSource() {
            return new HlsAdFilterDataSource(upstream.createDataSource());
        }
    }

    private final DataSource upstream;
    private DataSpec dataSpec;
    private ByteArrayInputStream filtered;

    public HlsAdFilterDataSource(DataSource upstream) {
        super(false);
        this.upstream = upstream;
    }

    public static boolean isEnabled() {
        try {
            return Hawk.get(HawkConfig.STRIP_AD_SEGMENTS, true);
        } catch (Throwable e) {
            return true;
        }
    }

    @Override
    public void addTransferListener(TransferListener listener) {
        super.addTransferListener(listener);
        upstream.addTransferListener(listener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        transferInitializing(dataSpec);
        Uri uri = dataSpec.uri;
        boolean filter = uri != null
                && uri.toString().toLowerCase(Locale.ROOT).contains(".m3u8");
        if (!filter) {
            long len = upstream.open(dataSpec);
            transferStarted(dataSpec);
            return len;
        }
        upstream.open(dataSpec);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = upstream.read(buf, 0, buf.length)) != C.RESULT_END_OF_INPUT) {
                if (n > 0) out.write(buf, 0, n);
            }
        } finally {
            upstream.close();
        }
        byte[] bytes = filterPlaylist(out.toString(StandardCharsets.UTF_8.name()))
                .getBytes(StandardCharsets.UTF_8);
        filtered = new ByteArrayInputStream(bytes);
        transferStarted(dataSpec);
        return bytes.length;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (filtered != null) {
            int n = filtered.read(buffer, offset, length);
            if (n == -1) return C.RESULT_END_OF_INPUT;
            bytesTransferred(n);
            return n;
        }
        int n = upstream.read(buffer, offset, length);
        if (n > 0) bytesTransferred(n);
        return n;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        try {
            if (filtered != null) filtered.close();
        } catch (Throwable ignored) {
        } finally {
            filtered = null;
            try {
                upstream.close();
            } finally {
                transferEnded();
            }
        }
    }

    /** 广告分片地址特征（小写匹配）。只看分片地址，不看时长。 */
    private static boolean isAdSegment(String uriLine) {
        String u = uriLine.toLowerCase(Locale.ROOT);
        return u.contains("doubleclick")
                || u.contains("googlesyndication")
                || u.contains("googletagservices")
                || u.contains("/ads/")
                || u.contains("://ads.")
                || u.contains("adserver")
                || u.contains("/ad/")
                || u.contains("/gg/")
                || u.contains("://gg.")
                || u.contains("advert")
                || u.contains("preroll")
                || u.contains("midroll")
                || u.contains("sponsor");
    }

    static String filterPlaylist(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        String pendingExtinf = null;
        for (String raw : lines) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith("#EXTINF")) {
                if (pendingExtinf != null) out.append(pendingExtinf).append('\n');
                pendingExtinf = line;
                continue;
            }
            if (!line.isEmpty() && !line.startsWith("#")) {
                if (isAdSegment(line)) {
                    pendingExtinf = null;
                    continue;
                }
                if (pendingExtinf != null) {
                    out.append(pendingExtinf).append('\n');
                    pendingExtinf = null;
                }
                out.append(line).append('\n');
                continue;
            }
            if (pendingExtinf != null) {
                out.append(pendingExtinf).append('\n');
                pendingExtinf = null;
            }
            out.append(line).append('\n');
        }
        if (pendingExtinf != null) out.append(pendingExtinf).append('\n');
        return out.toString();
    }
}
