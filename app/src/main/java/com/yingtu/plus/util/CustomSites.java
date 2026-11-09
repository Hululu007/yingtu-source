package com.yingtu.plus.util;

import android.text.TextUtils;

import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.App;
import com.yingtu.plus.bean.SourceBean;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定站源 / 常驻站源 / SITES 文件站的存取。
 * 自定站与常驻站以 SourceBean JSON 列表存 Hawk，ApiConfig.parseConfig 结尾
 * 调 applyCustomAndResidentSites() 把它们重新并入站表（换订阅也不丢）。
 * 网址站源添加的站同样进自定站表（来源都是“用户贴地址加进来的站”，
 * 禁用里的“网址站源”位就按这个集合判断）；SITES 文件导入的站 key
 * 另外记一份 SITES_FILE_KEYS，供禁用里的“SITES文件”位判断。
 */
public final class CustomSites {

    private CustomSites() {}

    @SuppressWarnings("unchecked")
    private static List<SourceBean> loadBeans(String key) {
        try {
            String json = Hawk.get(key, "");
            if (TextUtils.isEmpty(json)) return new ArrayList<>();
            List<SourceBean> list = App.gson().fromJson(json,
                    new TypeToken<List<SourceBean>>() {}.getType());
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    private static void saveBeans(String key, List<SourceBean> list) {
        try {
            Hawk.put(key, App.gson().toJson(list == null ? new ArrayList<>() : list));
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> loadKeys(String key) {
        try {
            String json = Hawk.get(key, "");
            if (TextUtils.isEmpty(json)) return new ArrayList<>();
            List<String> list = App.gson().fromJson(json,
                    new TypeToken<List<String>>() {}.getType());
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    private static void saveKeys(String key, List<String> keys) {
        try {
            Hawk.put(key, App.gson().toJson(keys == null ? new ArrayList<>() : keys));
        } catch (Throwable ignored) {
        }
    }

    public static List<SourceBean> getCustomSites() {
        return loadBeans(HawkConfig.CUSTOM_SITES);
    }

    public static List<SourceBean> getResidentSites() {
        return loadBeans(HawkConfig.RESIDENT_SITES);
    }

    public static void saveCustomSites(List<SourceBean> list) {
        saveBeans(HawkConfig.CUSTOM_SITES, list);
        List<String> keys = new ArrayList<>();
        for (SourceBean s : list) if (s != null && !TextUtils.isEmpty(s.getKey())) keys.add(s.getKey());
        saveKeys(HawkConfig.CUSTOM_SITE_KEYS, keys);
    }

    public static void saveResidentSites(List<SourceBean> list) {
        saveBeans(HawkConfig.RESIDENT_SITES, list);
    }

    /** 自定站源：有同 key 的先替换再追加。 */
    public static void addCustomSite(SourceBean site) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        List<SourceBean> list = getCustomSites();
        for (int i = 0; i < list.size(); i++) {
            if (site.getKey().equals(list.get(i).getKey())) {
                list.set(i, site);
                saveCustomSites(list);
                return;
            }
        }
        list.add(site);
        saveCustomSites(list);
    }

    public static void removeCustomSite(String key) {
        if (TextUtils.isEmpty(key)) return;
        List<SourceBean> list = getCustomSites();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (key.equals(list.get(i).getKey())) list.remove(i);
        }
        saveCustomSites(list);
    }

    public static void addResidentSite(SourceBean site) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        List<SourceBean> list = getResidentSites();
        for (SourceBean s : list) if (site.getKey().equals(s.getKey())) return;
        list.add(site);
        saveResidentSites(list);
    }

    public static void removeResidentSite(String key) {
        if (TextUtils.isEmpty(key)) return;
        List<SourceBean> list = getResidentSites();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (key.equals(list.get(i).getKey())) list.remove(i);
        }
        saveResidentSites(list);
    }

    public static boolean isCustomKey(String key) {
        if (TextUtils.isEmpty(key)) return false;
        return loadKeys(HawkConfig.CUSTOM_SITE_KEYS).contains(key);
    }

    public static List<String> getSitesFileKeys() {
        return loadKeys(HawkConfig.SITES_FILE_KEYS);
    }

    public static void addSitesFileKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        List<String> cur = loadKeys(HawkConfig.SITES_FILE_KEYS);
        for (String k : keys) if (!TextUtils.isEmpty(k) && !cur.contains(k)) cur.add(k);
        saveKeys(HawkConfig.SITES_FILE_KEYS, cur);
    }

    public static boolean isSitesFileKey(String key) {
        if (TextUtils.isEmpty(key)) return false;
        return loadKeys(HawkConfig.SITES_FILE_KEYS).contains(key);
    }
}
