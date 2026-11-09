package com.yingtu.plus.util;

import android.text.TextUtils;

import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.BuiltinConfigs;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.drama.BuiltinDramaConfigs;
import com.yingtu.plus.drama.DramaSites;

import java.util.ArrayList;
import java.util.List;

/**
 * 各区站点独立登记：影视 / 短剧 / 午夜三区各记各的当前站与启动站。
 *
 * 背景：原来全 App 只有一个全局当前源（ApiConfig.setSourceBean），
 * 午夜区切个站，顶栏和影视区跟着变；短剧区同理。改成每区一个 Hawk 键，
 * 弹窗里切站只写本区的键，其他区不动；顶栏按当前所在区显示站名。
 * 全局当前源仍保留，只作为“最后用的那个站”的共享写口（内嵌的
 * HomeMainFragment 靠它取数），各区每次进 tab 时按自己的键重新断言。
 */
public class ZoneSites {

    public static final String ZONE_MOVIE = "movie";
    public static final String ZONE_DRAMA = "drama";
    public static final String ZONE_MIDNIGHT = "midnight";

    private static String currentKey(String zone) {
        if (ZONE_DRAMA.equals(zone)) return HawkConfig.ZONE_SITE_DRAMA;
        if (ZONE_MIDNIGHT.equals(zone)) return HawkConfig.ZONE_SITE_MIDNIGHT;
        return HawkConfig.ZONE_SITE_MOVIE;
    }

    private static String startupKey(String zone) {
        if (ZONE_DRAMA.equals(zone)) return HawkConfig.STARTUP_SITE_DRAMA;
        if (ZONE_MIDNIGHT.equals(zone)) return HawkConfig.STARTUP_SITE_MIDNIGHT;
        return HawkConfig.STARTUP_SITE_MOVIE;
    }

    /** 该区当前站的 key，空字符串 = 没选过。 */
    public static String getCurrentSiteKey(String zone) {
        return Hawk.get(currentKey(zone), "");
    }

    public static void setCurrentSiteKey(String zone, String key) {
        Hawk.put(currentKey(zone), key == null ? "" : key);
    }

    /** 该区设好的启动站 key，空字符串 = 未设。 */
    public static String getStartupSiteKey(String zone) {
        return Hawk.get(startupKey(zone), "");
    }

    public static void setStartupSiteKey(String zone, String key) {
        Hawk.put(startupKey(zone), key == null ? "" : key);
    }

    /**
     * 冷启动时把各区设好的启动站写进当前站（一个进程只跑一次，
     * 避免旋转重建把使用中切好的站顶回启动站）。
     * 另外做一次老数据认领：以前只有一个全局当前源，若某区还没记过站，
     * 且全局当前源正好属于该区，就认领成该区的当前站，老用户升级后不断档。
     */
    private static boolean startupApplied = false;

    public static void applyStartupSites() {
        if (startupApplied) return;
        startupApplied = true;
        SourceBean home = ApiConfig.get().getHomeSource();
        if (home != null) {
            String homeKey = home.getKey();
            if (TextUtils.isEmpty(getCurrentSiteKey(ZONE_MOVIE))
                    && isZoneSite(ZONE_MOVIE, homeKey)) {
                setCurrentSiteKey(ZONE_MOVIE, homeKey);
            }
            if (TextUtils.isEmpty(getCurrentSiteKey(ZONE_DRAMA))
                    && isZoneSite(ZONE_DRAMA, homeKey)) {
                setCurrentSiteKey(ZONE_DRAMA, homeKey);
            }
            if (TextUtils.isEmpty(getCurrentSiteKey(ZONE_MIDNIGHT))
                    && isZoneSite(ZONE_MIDNIGHT, homeKey)) {
                setCurrentSiteKey(ZONE_MIDNIGHT, homeKey);
            }
        }
        for (String zone : new String[]{ZONE_MOVIE, ZONE_DRAMA, ZONE_MIDNIGHT}) {
            String startup = getStartupSiteKey(zone);
            if (!TextUtils.isEmpty(startup)) setCurrentSiteKey(zone, startup);
        }
    }

    /** 该区当前站的显示名；没选站返回 null（调用方显示“请选择源”）。 */
    public static String getCurrentSiteName(String zone) {
        String key = getCurrentSiteKey(zone);
        if (TextUtils.isEmpty(key)) return null;
        if (ZONE_DRAMA.equals(zone) && DramaSites.ORIGINAL_KEY.equals(key)) return "番茄短剧";
        SourceBean s = ApiConfig.get().getSource(key);
        return s == null ? null : s.getName();
    }

    /** 该区启动站的显示名；未设返回“未设置”。 */
    public static String getStartupSiteName(String zone) {
        String key = getStartupSiteKey(zone);
        if (TextUtils.isEmpty(key)) return "未设置";
        if (ZONE_DRAMA.equals(zone) && DramaSites.ORIGINAL_KEY.equals(key)) return "番茄短剧";
        SourceBean s = ApiConfig.get().getSource(key);
        return s == null ? "未设置" : s.getName();
    }

    /**
     * 该区可选的站列表（顶栏弹窗与启动站选择共用）：
     * 午夜 = 内置午夜站（健康屏蔽与覆盖清单已生效）；
     * 短剧 = 内置短剧站 + 首位的“番茄短剧”流；
     * 影视 = 用户自己导入的站（内置站不进影视区，老口径不动）。
     */
    public static List<SourceBean> getZoneSites(String zone) {
        if (ZONE_DRAMA.equals(zone)) {
            List<SourceBean> list = BuiltinDramaConfigs.getDramaSites();
            SourceBean original = new SourceBean();
            original.setKey(DramaSites.ORIGINAL_KEY);
            original.setName("番茄短剧");
            list.add(0, original);
            return list;
        }
        if (ZONE_MIDNIGHT.equals(zone)) {
            return BuiltinConfigs.getMidnightSites();
        }
        List<SourceBean> list = new ArrayList<>();
        for (SourceBean s : ApiConfig.get().getSourceBeanList()) {
            if (!ApiConfig.get().isBuiltinSite(s.getKey())) list.add(s);
        }
        return list;
    }

    /** 该 key 是否属于这个区（影视区 = 非内置站）。 */
    public static boolean isZoneSite(String zone, String key) {
        if (TextUtils.isEmpty(key)) return false;
        if (ZONE_DRAMA.equals(zone)) {
            return DramaSites.ORIGINAL_KEY.equals(key) || BuiltinDramaConfigs.isDramaSite(key);
        }
        if (ZONE_MIDNIGHT.equals(zone)) {
            return BuiltinConfigs.isMidnightSite(key);
        }
        return !ApiConfig.get().isBuiltinSite(key);
    }
}
