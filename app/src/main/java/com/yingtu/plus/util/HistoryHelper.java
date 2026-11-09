package com.yingtu.plus.util;

import com.yingtu.plus.bean.History;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * 观看历史存取助手（基于 Hawk 本地持久化）。
 *
 * - save：插入或更新一条记录（按 key 去重，更新进度/线路/时间后置顶）
 * - getAll：按最近观看时间倒序返回
 * - find：按 key 查询（用于详情页/播放页续播）
 * - delete / clear：删除单条 / 清空
 */
public class HistoryHelper {

    private static final int MAX = 200;

    /** 观看历史记录数档位（设置里选）：0=100|10 1=200|20 2=300|30 3=500|50，取前一个数。 */
    public static int getPlayLimit() {
        int idx = 2;
        try {
            idx = Hawk.get(HawkConfig.HISTORY_LIMIT, 2);
        } catch (Throwable ignored) {
        }
        switch (idx) {
            case 0: return 100;
            case 1: return 200;
            case 3: return 500;
            default: return 300;
        }
    }

    /** 搜索历史记录数档位：取后一个数（10/20/30/50）。 */
    public static int getSearchLimit() {
        int idx = 2;
        try {
            idx = Hawk.get(HawkConfig.HISTORY_LIMIT, 2);
        } catch (Throwable ignored) {
        }
        switch (idx) {
            case 0: return 10;
            case 1: return 20;
            case 3: return 50;
            default: return 30;
        }
    }

    private HistoryHelper() {}

    @SuppressWarnings("unchecked")
    public static List<History> getAll() {
        List<History> list = Hawk.get(HawkConfig.PLAY_HISTORY, new ArrayList<>());
        Collections.sort(list, new Comparator<History>() {
            @Override
            public int compare(History a, History b) {
                return Long.compare(b.updateTime, a.updateTime);
            }
        });
        return list;
    }

    public static History find(String key) {
        if (key == null) return null;
        for (History h : getAll()) {
            if (key.equals(h.key)) return h;
        }
        return null;
    }

    public static void save(History history) {
        if (history == null || history.key == null) return;
        history.updateTime = System.currentTimeMillis();
        List<History> list = getAll();
        Iterator<History> it = list.iterator();
        while (it.hasNext()) {
            History h = it.next();
            if (history.key.equals(h.key)) {
                it.remove();
                break;
            }
        }
        list.add(0, history);
        int max = getPlayLimit();
        while (list.size() > max) list.remove(list.size() - 1);
        Hawk.put(HawkConfig.PLAY_HISTORY, list);
    }

    public static void delete(String key) {
        if (key == null) return;
        List<History> list = getAll();
        Iterator<History> it = list.iterator();
        while (it.hasNext()) {
            if (key.equals(it.next().key)) {
                it.remove();
                break;
            }
        }
        Hawk.put(HawkConfig.PLAY_HISTORY, list);
    }

    public static void clear() {
        Hawk.delete(HawkConfig.PLAY_HISTORY);
    }
}
