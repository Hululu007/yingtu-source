package com.yingtu.plus.web.ext;

import com.google.gson.JsonElement;

/**
 * WebHome 扩展源注册表。
 *
 * 订阅配置里的 webHomeExtensions 段经 VodConfig.setGlobalSources 记在这里，
 * 供 WebHome 页加载扩展；Backup 恢复后调 clear() 丢弃旧数据并重新加载。
 * 本类只做登记与清理，不解析扩展内容。
 */
public class WebHomeExtensionRegistry {

    private static volatile WebHomeExtensionRegistry instance;

    private JsonElement sources;
    private String baseUrl;

    private WebHomeExtensionRegistry() {}

    public static WebHomeExtensionRegistry get() {
        if (instance == null) {
            synchronized (WebHomeExtensionRegistry.class) {
                if (instance == null) instance = new WebHomeExtensionRegistry();
            }
        }
        return instance;
    }

    public synchronized void setGlobalSources(JsonElement sources, String baseUrl) {
        this.sources = sources;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
    }

    public synchronized JsonElement getSources() {
        return sources;
    }

    public synchronized String getBaseUrl() {
        return baseUrl == null ? "" : baseUrl;
    }

    public synchronized void clear() {
        sources = null;
        baseUrl = "";
    }
}
