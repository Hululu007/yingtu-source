package com.yingtu.plus.bean;

// 按调用点重建：投屏用的视频数据类；字段按 VideoActivity.onCast() 的构造调用 CastVideo(name, url, position, headers) 确定。

import java.io.Serializable;
import java.util.Map;

public class CastVideo implements Serializable {

    public final String name;
    public final String url;
    public final long position;
    public final Map<String, String> headers;

    public CastVideo(String name, String url, long position, Map<String, String> headers) {
        this.name = name;
        this.url = url;
        this.position = position;
        this.headers = headers;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public long getPosition() {
        return position;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}
