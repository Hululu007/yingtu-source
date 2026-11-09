package com.yingtu.plus.util;

import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.bean.SourceBean;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 站源置顶 / 站源禁用 / 搜索站点范围：三处列表（站源管理页、首页选源弹窗、聚搜）
 * 共用的过滤与排序。类型口径按 SourceBean.type：0=xml，1=json，3=drpy/js。
 * JS 与 DRPY 在咱们树里同属 type 3，分不开，两项共用同一过滤（回报里已写明）；
 * SITES文件与网址站源在树里没有可区分的来源，不提供（回报里已写明）。
 */
public final class SiteFilter {

    private SiteFilter() {}

    // ===== 置顶 =====

    @SuppressWarnings("unchecked")
    public static List<String> getPinned() {
        try {
            List<String> list = Hawk.get(HawkConfig.SOURCE_PINNED, new ArrayList<String>());
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    public static void setPinned(List<String> keys) {
        try {
            Hawk.put(HawkConfig.SOURCE_PINNED, keys == null ? new ArrayList<String>() : keys);
        } catch (Throwable ignored) {
        }
    }

    /** 置顶的按置顶顺序排前面，其余保持原顺序（稳定排序）。 */
    public static void sortPinnedFirst(List<SourceBean> sites) {
        if (sites == null || sites.size() < 2) return;
        List<String> pinned = getPinned();
        if (pinned.isEmpty()) return;
        sites.sort((a, b) -> {
            int ia = pinned.indexOf(a.getKey());
            int ib = pinned.indexOf(b.getKey());
            if (ia == -1 && ib == -1) return 0;
            if (ia == -1) return 1;
            if (ib == -1) return -1;
            return Integer.compare(ia, ib);
        });
    }

    // ===== 禁用 =====

    public static int getDisabledMask() {
        try {
            return Hawk.get(HawkConfig.SOURCE_DISABLED, 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    public static void setDisabledMask(int mask) {
        try {
            Hawk.put(HawkConfig.SOURCE_DISABLED, mask);
        } catch (Throwable ignored) {
        }
    }

    public static boolean isDisabled(int bit) {
        return (getDisabledMask() & bit) != 0;
    }

    /** 该站是否被禁用规则拦住（只看来源类型，不拦当前正在播的站，调用方自行决定）。 */
    public static boolean isSiteDisabled(SourceBean s) {
        if (s == null) return false;
        int mask = getDisabledMask();
        if (mask == 0) return false;
        boolean builtin = false;
        try {
            builtin = ApiConfig.get().isBuiltinSite(s.getKey());
        } catch (Throwable ignored) {
        }
        if ((mask & HawkConfig.DISABLE_BUILTIN) != 0 && builtin) return true;
        if ((mask & HawkConfig.DISABLE_NET) != 0 && !builtin) return true;
        if ((mask & HawkConfig.DISABLE_JS) != 0 && s.getType() == 3) return true;
        if ((mask & HawkConfig.DISABLE_DRPY) != 0 && s.getType() == 3) return true;
        if ((mask & HawkConfig.DISABLE_JSON) != 0 && s.getType() == 1) return true;
        if ((mask & HawkConfig.DISABLE_SITES_FILE) != 0 && CustomSites.isSitesFileKey(s.getKey())) return true;
        if ((mask & HawkConfig.DISABLE_URL_SITE) != 0 && CustomSites.isCustomKey(s.getKey())) return true;
        return false;
    }

    /** 按 key 查（聚搜的 Site 只有 key 时用 ApiConfig 反查类型）。 */
    public static boolean isKeyDisabled(String key) {
        if (key == null || getDisabledMask() == 0) return false;
        try {
            SourceBean s = ApiConfig.get().getSource(key);
            if (s != null) return isSiteDisabled(s);
            boolean builtin = ApiConfig.get().isBuiltinSite(key);
            int mask = getDisabledMask();
            if ((mask & HawkConfig.DISABLE_BUILTIN) != 0 && builtin) return true;
            if ((mask & HawkConfig.DISABLE_NET) != 0 && !builtin) return true;
            if ((mask & HawkConfig.DISABLE_SITES_FILE) != 0 && CustomSites.isSitesFileKey(key)) return true;
            if ((mask & HawkConfig.DISABLE_URL_SITE) != 0 && CustomSites.isCustomKey(key)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void filterDisabled(List<SourceBean> sites) {
        if (sites == null || getDisabledMask() == 0) return;
        Iterator<SourceBean> it = sites.iterator();
        while (it.hasNext()) if (isSiteDisabled(it.next())) it.remove();
    }

    // ===== 搜索站点范围 =====

    @SuppressWarnings("unchecked")
    public static List<String> getStringList(String key) {
        try {
            List<String> list = Hawk.get(key, new ArrayList<String>());
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    public static void setStringList(String key, List<String> values) {
        try {
            Hawk.put(key, values == null ? new ArrayList<String>() : values);
        } catch (Throwable ignored) {
        }
    }

    public static int getSearchMode() {
        int m;
        try {
            m = Hawk.get(HawkConfig.SEARCH_SITE_MODE, 0);
        } catch (Throwable e) {
            return 0;
        }
        return m >= 0 && m <= 5 ? m : 0;
    }

    /** 聚搜站表按搜索范围过滤（调用方已按 searchable 滤过一遍，这里只做范围）。 */
    public static List<com.yingtu.plus.bean.Site> filterSearchSites(
            List<com.yingtu.plus.bean.Site> all, String homeKey) {
        if (all == null) return new ArrayList<>();
        int mode = getSearchMode();
        if (mode == 0) return new ArrayList<>(all);
        List<com.yingtu.plus.bean.Site> out = new ArrayList<>();
        Set<String> white = new HashSet<>(getStringList(HawkConfig.SEARCH_SITE_WHITELIST));
        Set<String> black = new HashSet<>(getStringList(HawkConfig.SEARCH_SITE_BLACKLIST));
        Set<String> picked = new HashSet<>(getStringList(HawkConfig.SEARCH_SITE_PICKED));
        String nameFilter = "";
        try {
            nameFilter = Hawk.get(HawkConfig.SEARCH_SITE_NAME_FILTER, "");
        } catch (Throwable ignored) {
        }
        for (com.yingtu.plus.bean.Site s : all) {
            if (s == null || s.getKey() == null) continue;
            switch (mode) {
                case 1:
                    if (white.contains(s.getKey())) out.add(s);
                    break;
                case 2:
                    if (!black.contains(s.getKey())) out.add(s);
                    break;
                case 3:
                    if (homeKey != null && homeKey.equals(s.getKey())) out.add(s);
                    break;
                case 4:
                    if (picked.contains(s.getKey())) out.add(s);
                    break;
                case 5:
                    if (nameFilter == null || nameFilter.isEmpty()
                            || (s.getName() != null && s.getName().contains(nameFilter))) out.add(s);
                    break;
                default:
                    out.add(s);
                    break;
            }
        }
        return out;
    }
}
