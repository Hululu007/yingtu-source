package com.yingtu.plus.web;

import android.webkit.WebView;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

/**
 * document-start 脚本注册（反射版）。
 *
 * 真的 document-start 注入要 androidx.webkit:webkit（WebViewCompat.addDocumentStartJavaScript，
 * WebViewFeature.DOCUMENT_START_SCRIPT），上游 app/build.gradle 里没有这个依赖、且构建配置不在本次
 * 移植范围内，所以这里用反射调用：包存在时行为与直引完全一致；包不存在时返回 null，调用方
 * （HomeWebController）按既有降级链把 document-start 扩展改在 document-end 时补注，
 * 注入本身不断。想让 document-start 真生效，只需由构建方在依赖里加 androidx.webkit:webkit。
 */
public final class DocumentStartScripts {

    private DocumentStartScripts() {
    }

    public static boolean isSupported() {
        try {
            Class<?> feature = Class.forName("androidx.webkit.WebViewFeature");
            Method isSupported = feature.getMethod("isFeatureSupported", String.class);
            Object result = isSupported.invoke(null, "DOCUMENT_START_SCRIPT");
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 注册成功返回一个可 remove() 的句柄对象，不支持时返回 null。 */
    public static Object add(WebView webView, String script) {
        try {
            Class<?> compat = Class.forName("androidx.webkit.WebViewCompat");
            Method add = compat.getMethod("addDocumentStartJavaScript", WebView.class, String.class, Set.class);
            return add.invoke(null, webView, script, Collections.singleton("*"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void remove(Object handler) {
        if (handler == null) return;
        try {
            Method remove = handler.getClass().getMethod("remove");
            remove.invoke(handler);
        } catch (Throwable ignored) {
        }
    }
}
