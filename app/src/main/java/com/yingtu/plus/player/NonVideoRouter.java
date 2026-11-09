package com.yingtu.plus.player;

import android.app.Activity;
import android.text.TextUtils;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.yingtu.plus.App;
import com.yingtu.plus.utils.Notify;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 非视频内容分流路由：novel://（小说）与 pics://（图片 / 漫画）。
 * 只认最终播放地址的 scheme，不认选集名。
 * 协议见 ~/workspace/tvbox_nonvideo_format.md：
 * 小说详情出“阅读$novel://{title,content}”JSON，图片出“查看$pics://{url1&&url2...}”，
 * playerContent 对两者 parse=0 原样透传，壳侧按 scheme 分流，不走视频引擎。
 */
public final class NonVideoRouter {

    public static final String SCHEME_NOVEL = "novel://";
    public static final String SCHEME_PICS = "pics://";

    public enum Kind {
        NONE, NOVEL, PICS
    }

    /** 小说解析结果：标题 + 正文。 */
    public static final class NovelData {
        public final String title;
        public final String content;

        public NovelData(String title, String content) {
            this.title = title == null ? "" : title;
            this.content = content == null ? "" : content;
        }
    }

    private NonVideoRouter() {
    }

    /** 按最终播放地址的 scheme 判型。 */
    public static Kind kind(String url) {
        if (TextUtils.isEmpty(url)) return Kind.NONE;
        String value = url.trim();
        if (startsWithIgnoreCase(value, SCHEME_NOVEL)) return Kind.NOVEL;
        if (startsWithIgnoreCase(value, SCHEME_PICS)) return Kind.PICS;
        return Kind.NONE;
    }

    public static boolean isNonVideo(String url) {
        return kind(url) != Kind.NONE;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.length() >= prefix.length() && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static String payload(String url, String scheme) {
        return url.trim().substring(scheme.length());
    }

    /**
     * 解析 novel:// 载荷：{"title","content"}。
     * 蜘蛛侧可能直接拼原样 JSON，也可能整段 URL 编码过：先按原样解析 JSON，
     * 失败再 URL 解码一次后解析。URLDecoder 会把 + 转空格，只在原样解析失败时才用，
     * 避免误伤正文里的 + 号。
     */
    public static NovelData parseNovel(String url) {
        if (kind(url) != Kind.NOVEL) return null;
        String raw = payload(url, SCHEME_NOVEL).trim();
        if (TextUtils.isEmpty(raw)) return null;
        NovelData data = parseNovelJson(raw);
        if (data != null) return data;
        try {
            String decoded = URLDecoder.decode(raw, "UTF-8");
            if (!TextUtils.equals(decoded, raw)) return parseNovelJson(decoded.trim());
        } catch (Exception ignored) {
        }
        return null;
    }

    private static NovelData parseNovelJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            String content = obj.optString("content", "");
            if (TextUtils.isEmpty(content)) return null;
            return new NovelData(obj.optString("title", ""), content);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 解析 pics:// 载荷：多张图用 && 拼接。
     * 单张地址保持原样返回，@Headers= / @Cookie= / @Referer= / @User-Agent= 这类
     * 后缀与代理转换由 ImgUtil.getUrl() 在加载时处理，与午夜区封面同一条加载路。
     */
    public static List<String> parsePics(String url) {
        List<String> images = new ArrayList<>();
        if (kind(url) != Kind.PICS) return images;
        String raw = payload(url, SCHEME_PICS).trim();
        if (TextUtils.isEmpty(raw)) return images;
        for (String part : raw.split("&&")) {
            String item = part == null ? "" : part.trim();
            if (!TextUtils.isEmpty(item)) images.add(item);
        }
        return images;
    }

    /**
     * 在当前 Activity 上弹出对应的查看器。返回 true 表示已接管（查看器已排队弹出，
     * 或内容本身解析失败已提示），调用方不要再走视频链路；返回 false 表示当前
     * 拿不到可挂 DialogFragment 的 Activity，调用方自行报错。
     * 用 App.activity() 取当前 Activity：PlayerManager 手里没有 Activity，
     * 而分流钩子只在播放页（VideoActivity 在前台）触发，取到的就是播放页。
     */
    public static boolean tryShow(String url, String fallbackTitle) {
        Activity activity = App.activity();
        if (!(activity instanceof FragmentActivity)) return false;
        FragmentActivity host = (FragmentActivity) activity;
        if (host.isFinishing() || host.isDestroyed()) return false;
        App.post(() -> showNow(host, url, fallbackTitle));
        return true;
    }

    private static void showNow(FragmentActivity host, String url, String fallbackTitle) {
        if (host.isFinishing() || host.isDestroyed()) return;
        try {
            if (host.getSupportFragmentManager().isStateSaved()) return;
            Kind kind = kind(url);
            if (kind == Kind.NOVEL) {
                NovelData data = parseNovel(url);
                if (data == null) {
                    Notify.show("小说内容解析失败");
                    return;
                }
                for (Fragment f : host.getSupportFragmentManager().getFragments()) {
                    if (f instanceof NovelViewer) return;
                }
                NovelViewer.show(host, data, fallbackTitle);
            } else if (kind == Kind.PICS) {
                List<String> images = parsePics(url);
                if (images.isEmpty()) {
                    Notify.show("没有可显示的图片");
                    return;
                }
                for (Fragment f : host.getSupportFragmentManager().getFragments()) {
                    if (f instanceof PicsViewer) return;
                }
                PicsViewer.show(host, images, fallbackTitle);
            }
        } catch (Throwable e) {
            App.logCaught(e);
            Notify.show("打开失败，请重试");
        }
    }
}
