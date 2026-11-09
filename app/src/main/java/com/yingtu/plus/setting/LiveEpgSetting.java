package com.yingtu.plus.setting;

import com.yingtu.plus.bean.Channel;
import com.yingtu.plus.util.Prefers;
import java.util.List;
import java.util.ArrayList;

public class LiveEpgSetting {
    private static final String KEY_URL = "live_epg_url";
    private static final String KEY_HISTORY = "live_epg_history";
    private static final int MAX_HISTORY = 20;

    public static String getUrl() { return Prefers.getString(KEY_URL, ""); }
    public static void putUrl(String url) { Prefers.put(KEY_URL, url); }
    
    public static List<String> getHistory() {
        String s = Prefers.getString(KEY_HISTORY, null);
        if (s == null) return new ArrayList<>();
        return new ArrayList<>(List.of(s.split("\n")));
    }
    
    public static void addHistory(String url) {
        List<String> list = getHistory();
        list.remove(url);
        list.add(0, url);
        while (list.size() > MAX_HISTORY) list.remove(list.size() - 1);
        Prefers.put(KEY_HISTORY, String.join("\n", list));
    }
    
    public static void removeHistory(String url) {
        List<String> list = getHistory();
        list.remove(url);
        Prefers.put(KEY_HISTORY, String.join("\n", list));
    }
    
    public static void clearHistory() { Prefers.remove(KEY_HISTORY); }
    
    /**
     * 第九批第三路接线：之前这里是空实现，配了节目源地址也不会生效。
     * 现在返回已配的 XMLTV 地址（默认空，没配就返回空表、不拉节目），
     * LiveApi.parseXml / Channel.live 走这条把地址挂到频道上。
     */
    public static List<String> getXmlUrls(Object live) {
        String url = getEffectiveUrl(live);
        if (url == null || url.isEmpty()) return new ArrayList<>();
        List<String> urls = new ArrayList<>();
        urls.add(url);
        return urls;
    }

    public static String getEffectiveUrl(Object live) {
        return getUrl();
    }

    public static void apply(Object live) {}

    /** 频道本身没配 epg 地址时，把设置里的 XMLTV 地址挂上去；频道自带地址的不动。 */
    public static void apply(Object live, Object channel) {
        if (!(channel instanceof Channel)) return;
        Channel item = (Channel) channel;
        String url = getEffectiveUrl(live);
        if (url == null || url.isEmpty()) return;
        if (item.getEpg() == null || item.getEpg().isEmpty()) item.setEpg(url);
    }
}
