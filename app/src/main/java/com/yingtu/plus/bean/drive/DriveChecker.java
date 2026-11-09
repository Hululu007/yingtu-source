package com.yingtu.plus.bean.drive;

import android.text.TextUtils;

import com.yingtu.plus.util.OkGo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 分享链接检测：建在 bean/drive 四个数据类之上（DriveCheckItem / DriveCheckRequest /
 * DriveCheckResponse / DriveCheckResult）。
 *
 * 只做轻量可达性检测：对链接发一次 GET（跟随跳转），按 HTTP 状态给结论，不破解提取码、
 * 不解析网盘页面、不取真实文件地址。检测不到就是检测不到，不编结果。
 * 提取码只随请求带在 URL 参数里，不存盘。
 */
public class DriveChecker {

    public interface Callback {
        void onResult(DriveCheckResponse response);
    }

    public static void check(final DriveCheckRequest request, final Callback callback) {
        final List<DriveCheckItem> items = request == null ? Collections.emptyList() : request.getItems();
        OkGo.io(() -> {
            List<DriveCheckResult> results = new ArrayList<>();
            for (DriveCheckItem item : items) {
                results.add(checkOne(item, request == null ? null : request.getViewToken()));
            }
            final DriveCheckResponse response = new DriveCheckResponse(results);
            OkGo.main(() -> callback.onResult(response));
        });
    }

    private static DriveCheckResult checkOne(DriveCheckItem item, String viewToken) {
        long now = System.currentTimeMillis();
        String type = item == null ? "" : item.getDiskType();
        String url = item == null ? "" : item.getUrl();
        if (TextUtils.isEmpty(url)) {
            return new DriveCheckResult(type, url, url, "error", false, now, 0, "链接为空");
        }
        String normalized = normalize(url, item.getPassword());
        try {
            Request request = new Request.Builder()
                    .url(normalized)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                    .build();
            try (Response response = OkGo.client().newCall(request).execute()) {
                int code = response.code();
                String finalUrl = response.request() != null && response.request().url() != null
                        ? response.request().url().toString() : normalized;
                if (code >= 200 && code < 400) {
                    return new DriveCheckResult(type, url, finalUrl, "reachable", false, now, 0,
                            "可达 · HTTP " + code + "（只代表链接能打开，不代表能取到文件）");
                }
                return new DriveCheckResult(type, url, finalUrl, "unreachable", false, now, 0,
                        "打不开 · HTTP " + code);
            }
        } catch (IOException e) {
            return new DriveCheckResult(type, url, normalized, "error", false, now, 0,
                    "请求失败：" + e.getMessage());
        } catch (Throwable e) {
            return new DriveCheckResult(type, url, normalized, "error", false, now, 0,
                    "检测异常：" + e.getMessage());
        }
    }

    /** 提取码拼到链接上（阿里/123/移动云盘分享链接都认 pwd 参数形式）；没有就不拼。 */
    private static String normalize(String url, String password) {
        if (TextUtils.isEmpty(password)) return url;
        if (url.contains("pwd=")) return url;
        return url + (url.contains("?") ? "&" : "?") + "pwd=" + password;
    }
}
