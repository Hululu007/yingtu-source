package com.yingtu.plus.bean;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 节目单频道：老包 assets/epg_data.json 里的一条。
 * 注意：这个文件只有频道名录，没有节目播出时间——字段只有 tvid / epgid /
 * name（逗号分隔的别名）/ status / note / logo，没有任何一档节目的标题与起止时间，
 * 所以节目单界面只能做到“频道名录 + 按别名去直播页找同名频道播”，
 * 做不出“现在播什么、接下来播什么”。节目时间要另外接节目源接口，见 EpgActivity 头注。
 */
public class EpgChannel {

    public String tvid;
    public String epgid;
    public String name;
    public String status;
    public String note;
    public String logo;

    /** name 字段是逗号分隔的别名表，如 CCTV1HD,CCTV1高清,CCTV-1综合,CCTV-1。 */
    public List<String> aliases() {
        List<String> list = new ArrayList<>();
        if (TextUtils.isEmpty(name)) return list;
        for (String part : name.split(",")) {
            String alias = part.trim();
            if (!TextUtils.isEmpty(alias) && !list.contains(alias)) list.add(alias);
        }
        return list;
    }

    public String displayName() {
        if (!TextUtils.isEmpty(note)) return note.trim();
        List<String> aliases = aliases();
        if (!aliases.isEmpty()) return aliases.get(0);
        return TextUtils.isEmpty(epgid) ? "" : epgid;
    }

    public boolean matches(String query) {
        if (TextUtils.isEmpty(query)) return true;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (!TextUtils.isEmpty(epgid) && epgid.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (!TextUtils.isEmpty(note) && note.toLowerCase(Locale.ROOT).contains(q)) return true;
        for (String alias : aliases()) {
            if (alias.toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    /** 直播频道名是否与本条的任一别名相符（节目单点频道 → 直播页找台用）。 */
    public boolean matchesChannelName(String channelName) {
        if (TextUtils.isEmpty(channelName)) return false;
        String target = channelName.trim();
        if (!TextUtils.isEmpty(note) && note.trim().equals(target)) return true;
        for (String alias : aliases()) {
            if (alias.equals(target)) return true;
        }
        return false;
    }
}
