package com.yingtu.plus.bean;

import java.io.Serializable;

/**
 * Online website entry: a video site the user added by URL only.
 * No spider (jar / py / js / json) is written for it; the browser
 * auto-detects lists and episodes and the player sniffs the stream.
 */
public class OnlineSite implements Serializable {

    /** Work mode: auto-detect list / detail / episode structure. */
    public static final int MODE_AUTO = 0;

    public String id;
    public String name;
    public String url;
    public int mode;
    public boolean pure;
    public long createTime;

    public OnlineSite() {}

    public OnlineSite(String name, String url, int mode, boolean pure) {
        this.id = String.valueOf(System.currentTimeMillis());
        this.name = name;
        this.url = url;
        this.mode = mode;
        this.pure = pure;
        this.createTime = System.currentTimeMillis();
    }

    /** List subtitle. */
    public String subtitle() {
        String base = url == null ? "" : url;
        return pure ? base + " · 纯净" : base;
    }
}
