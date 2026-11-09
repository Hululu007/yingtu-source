package com.yingtu.plus.bean;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.yingtu.plus.App;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 节目单频道名录：读包内 assets/epg_data.json（从老包反编译 assets 里原样拷过来，
 * 是数据文件、不是代码）。顶层 {"epgs": [...]}，共 534 条，每条只有
 * tvid / epgid / name（逗号分隔的别名）/ status / note / logo，
 * 没有任何一档节目的标题与起止时间。节目时间不走这个文件，走 XMLTV 节目源
 * （LiveEpgSetting 的 live_epg_url，见 EpgActivity），用这里的 epgid 当 tvg-id 去对。
 * 读不到或格式对不上时返回空表 + 记错误，调用方如实显示“数据读不到”，不编假节目单。
 */
public class EpgDirectory {

    private static class Root {
        @SerializedName("epgs")
        List<EpgChannel> epgs;
    }

    private static List<EpgChannel> cached;
    private static String lastError;

    public static String getLastError() {
        return lastError;
    }

    public static List<EpgChannel> load(Context context) {
        if (cached != null) return cached;
        lastError = null;
        List<EpgChannel> list = new ArrayList<>();
        try {
            InputStream in = context.getAssets().open("epg_data.json");
            byte[] bytes = new byte[in.available()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            in.close();
            String json = new String(bytes, 0, read, StandardCharsets.UTF_8);
            Root root = new Gson().fromJson(json, Root.class);
            if (root == null || root.epgs == null) {
                lastError = "epg_data.json 里没有 epgs 数组";
            } else {
                list.addAll(root.epgs);
            }
        } catch (Throwable e) {
            lastError = "epg_data.json 读取失败：" + e.getMessage();
            App.logCaught(e);
        }
        cached = list;
        return cached;
    }
}
