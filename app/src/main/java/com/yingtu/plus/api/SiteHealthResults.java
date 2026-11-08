package com.yingtu.plus.api;

import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 站点检测结果存盘（Hawk 持久化）：上次跑完的结果记下来，下次打开列表
 * 直接看上次结果，不用每次重跑。
 *
 * 注意：不要与 setting/SiteHealthStore 混淆——那是树里原有的空桩
 * （recordSearch/recordPlay/recordDetail/flush 全是空实现，无界面、无真实记录），
 * 本类是本次检测功能真正的结果存盘。
 *
 * key = 分区:key（如 midnight:hmav、user:xigua），值 = {s:状态, t:检测时间毫秒, d:备注}。
 * 状态：ok 正常 / empty 空 / timeout 超时 / error 异常。
 */
public final class SiteHealthResults {

    /** Hawk 键（HawkConfig.java 不在本批可改文件内，用字面量）。 */
    private static final String STORE_KEY = "site_health_results";

    public static final String OK = "ok";
    public static final String EMPTY = "empty";
    public static final String TIMEOUT = "timeout";
    public static final String ERROR = "error";

    /** 一条结果：状态 + 检测时间 + 备注（条数/错误原因）。 */
    public static class Result {
        public final String status;
        public final long time;
        public final String detail;
        /** 分项明细（连通性/首页/搜索/详情/播放…），没有表示老版本只记了总状态。 */
        public final List<Item> items;

        public Result(String status, long time, String detail) {
            this(status, time, detail, null);
        }

        public Result(String status, long time, String detail, List<Item> items) {
            this.status = status == null ? ERROR : status;
            this.time = time;
            this.detail = detail == null ? "" : detail;
            this.items = items == null ? new ArrayList<>() : items;
        }

        /** 是否失效（自动屏蔽只认这三种：异常/超时/空）。 */
        public boolean isFailed() {
            return ERROR.equals(status) || TIMEOUT.equals(status) || EMPTY.equals(status);
        }
    }

    /** 一个检测分项：名 + 状态 + 备注 + 耗时毫秒。状态与总状态同一套（ok/empty/timeout/error），另有 skip 跳过。
     * core=true 是核心项（参与总判定：首页/分类/搜索/详情/播放/频道/测速），
     * core=false 是参考项（连通性/站点探测/空结果核对：只展示，不单独拉垮总判定）。 */
    public static class Item {
        public final String name;
        public final String status;
        public final String detail;
        public final long ms;
        public final boolean core;

        public Item(String name, String status, String detail, long ms) {
            this(name, status, detail, ms, true);
        }

        public Item(String name, String status, String detail, long ms, boolean core) {
            this.name = name == null ? "" : name;
            this.status = status == null ? ERROR : status;
            this.detail = detail == null ? "" : detail;
            this.ms = ms;
            this.core = core;
        }
    }

    /** 分项跳过（该站不适用这一项，如蜘蛛站没有 http 接口可做连通性）。 */
    public static final String SKIP = "skip";
    /** 待定：还没检测过（列表里没结果的站显示这个）。 */
    public static final String PENDING = "pending";

    private SiteHealthResults() {
    }

    /** 状态中文名（列表里直接显示）。 */
    public static String label(String status) {
        if (OK.equals(status)) return "正常";
        if (EMPTY.equals(status)) return "空";
        if (TIMEOUT.equals(status)) return "超时";
        if (PENDING.equals(status)) return "待定";
        return "异常";
    }

    /** 读一条结果，没有返回 null。 */
    public static synchronized Result get(String zone, String key) {
        return getById(zone + ":" + (key == null ? "" : key));
    }

    public static synchronized Result getById(String id) {
        try {
            String raw = Hawk.get(STORE_KEY, "");
            if (raw == null || raw.isEmpty()) return null;
            JSONObject root = new JSONObject(raw);
            if (!root.has(id)) return null;
            JSONObject o = root.getJSONObject(id);
            return new Result(o.optString("s", ERROR), o.optLong("t", 0), o.optString("d", ""),
                    parseItems(o.optJSONArray("items")));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<Item> parseItems(org.json.JSONArray arr) {
        List<Item> items = new ArrayList<>();
        if (arr == null) return items;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            items.add(new Item(o.optString("n", ""), o.optString("s", ERROR),
                    o.optString("d", ""), o.optLong("ms", 0), o.optBoolean("c", true)));
        }
        return items;
    }

    /** 记一条结果（检测跑完一个记一个，停下重进也不丢）。 */
    public static synchronized void put(String zone, String key, String status, String detail) {
        putWithItems(zone, key, status, detail, null);
    }

    /** 记一条带分项明细的结果。 */
    public static synchronized void putWithItems(String zone, String key, String status,
                                                 String detail, List<Item> items) {
        putByIdWithItems(zone + ":" + (key == null ? "" : key), status, detail, items);
    }

    public static synchronized void putById(String id, String status, String detail) {
        putByIdWithItems(id, status, detail, null);
    }

    public static synchronized void putByIdWithItems(String id, String status, String detail,
                                                     List<Item> items) {
        try {
            String raw = Hawk.get(STORE_KEY, "");
            JSONObject root = (raw == null || raw.isEmpty()) ? new JSONObject() : new JSONObject(raw);
            JSONObject o = new JSONObject();
            o.put("s", status);
            o.put("t", System.currentTimeMillis());
            o.put("d", detail == null ? "" : detail);
            if (items != null && !items.isEmpty()) {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (Item item : items) {
                    JSONObject jo = new JSONObject();
                    jo.put("n", item.name);
                    jo.put("s", item.status);
                    jo.put("d", item.detail);
                    jo.put("ms", item.ms);
                    jo.put("c", item.core);
                    arr.put(jo);
                }
                o.put("items", arr);
            }
            root.put(id, o);
            Hawk.put(STORE_KEY, root.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 上次整轮检测跑完的时间（毫秒），没有返回 0。定时复查按它算过期。 */
    public static synchronized long lastRun() {
        try {
            return Hawk.get("site_health_last_run", 0L);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static synchronized void setLastRun(long time) {
        try {
            Hawk.put("site_health_last_run", time);
        } catch (Throwable ignored) {
        }
    }

    /** 全部结果的副本（id → 结果），供列表一次取完。 */
    public static synchronized Map<String, Result> all() {
        Map<String, Result> map = new HashMap<>();
        try {
            String raw = Hawk.get(STORE_KEY, "");
            if (raw == null || raw.isEmpty()) return map;
            JSONObject root = new JSONObject(raw);
            Iterator<String> it = root.keys();
            while (it.hasNext()) {
                String id = it.next();
                JSONObject o = root.optJSONObject(id);
                if (o != null) {
                    map.put(id, new Result(o.optString("s", ERROR), o.optLong("t", 0),
                            o.optString("d", ""), parseItems(o.optJSONArray("items"))));
                }
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    /** 汇总：四种状态各几站（检测跑完报数用）。 */
    public static int[] summarize(List<Result> results) {
        int[] counts = new int[4]; // ok / empty / timeout / error
        if (results == null) return counts;
        for (Result r : results) {
            if (r == null) continue;
            if (OK.equals(r.status)) counts[0]++;
            else if (EMPTY.equals(r.status)) counts[1]++;
            else if (TIMEOUT.equals(r.status)) counts[2]++;
            else counts[3]++;
        }
        return counts;
    }
}
