package com.yingtu.plus.util;

import com.yingtu.plus.bean.OnlineSite;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Online website storage (Hawk local persistence).
 * Same pattern as WebHomeHelper; entries never touch the 183 builtin stations.
 */
public class OnlineSiteStore {

    private OnlineSiteStore() {}

    public static List<OnlineSite> getAll() {
        List<OnlineSite> list = Hawk.get(HawkConfig.ONLINE_SITES, new ArrayList<>());
        return list == null ? new ArrayList<>() : list;
    }

    /** Add a new site, or update the one with the same id. */
    public static void save(OnlineSite site) {
        if (site == null) return;
        List<OnlineSite> list = getAll();
        for (int i = 0; i < list.size(); i++) {
            OnlineSite cur = list.get(i);
            if (cur != null && site.id != null && site.id.equals(cur.id)) {
                list.set(i, site);
                Hawk.put(HawkConfig.ONLINE_SITES, list);
                return;
            }
        }
        list.add(0, site);
        Hawk.put(HawkConfig.ONLINE_SITES, list);
    }

    public static void delete(String id) {
        if (id == null) return;
        List<OnlineSite> list = getAll();
        Iterator<OnlineSite> it = list.iterator();
        while (it.hasNext()) {
            OnlineSite s = it.next();
            if (s != null && id.equals(s.id)) it.remove();
        }
        Hawk.put(HawkConfig.ONLINE_SITES, list);
    }

    public static OnlineSite find(String id) {
        if (id == null) return null;
        for (OnlineSite s : getAll()) {
            if (s != null && id.equals(s.id)) return s;
        }
        return null;
    }
}
