package com.yingtu.plus.utils;

import android.net.Uri;

import com.yingtu.plus.server.Server;
import com.yingtu.plus.web.WebServer;
import com.github.catvod.utils.UriUtil;
import com.google.common.net.HttpHeaders;

public class UrlUtil {

    public static Uri uri(String url) {
        return Uri.parse(url.trim().replace("\\", ""));
    }

    public static String scheme(String url) {
        return url == null ? "" : scheme(Uri.parse(url));
    }

    public static String scheme(Uri uri) {
        String scheme = uri.getScheme();
        return scheme == null ? "" : scheme.toLowerCase().trim();
    }

    public static String host(String url) {
        return url == null ? "" : host(Uri.parse(url));
    }

    public static String host(Uri uri) {
        String host = uri.getHost();
        return host == null ? "" : host.toLowerCase().trim();
    }

    public static String path(String url) {
        return url == null ? "" : path(Uri.parse(url));
    }

    public static String path(Uri uri) {
        String path = uri.getLastPathSegment();
        return path == null ? "" : path.trim();
    }

    public static String resolve(String baseUri, String referenceUri) {
        return UriUtil.resolve(baseUri, referenceUri);
    }

    public static String convert(String url) {
        String scheme = scheme(url);
        if ("proxy".equals(scheme)) {
            // proxy:// 必须经本地代理解：真正的 /proxy 路由在 WebServer（调蜘蛛 localProxy），
            // Server 是只回 404 的桩，拼它的地址图永远黑。WebServer 未启动时原样返回。
            String base = WebServer.get().getAddress("/proxy?");
            return base.isEmpty() ? url : url.replace(scheme + "://", base);
        }
        String path = null;
        if ("assets".equals(scheme)) path = "/";
        else if ("file".equals(scheme)) path = "/file/";
        return path != null ? url.replace(scheme + "://", Server.get().getAddress(path)) : url;
    }

    public static String getName(String url) {
        Uri uri = Uri.parse(url);
        String path = path(uri);
        String host = host(uri);
        return !path.isEmpty() ? path : !host.isEmpty() ? host : url;
    }

    public static String fixHeader(String key) {
        if (HttpHeaders.USER_AGENT.equalsIgnoreCase(key)) return HttpHeaders.USER_AGENT;
        if (HttpHeaders.REFERER.equalsIgnoreCase(key)) return HttpHeaders.REFERER;
        if (HttpHeaders.COOKIE.equalsIgnoreCase(key)) return HttpHeaders.COOKIE;
        return key;
    }
}
