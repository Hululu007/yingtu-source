package com.yingtu.plus.util;

import android.text.TextUtils;

import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.App;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合规则：把多个站的首页推荐拼成一个混合首页。
 * 一条规则 = 名称 + 成员站 key 列表，存 Hawk MIX_RULES（每条“名称|key1,key2”）；
 * MIX_ACTIVE 存当前激活的规则名，空 = 不启用。首页加载时若有激活规则，
 * SourceViewModel.getSortMixed() 逐站取首页推荐并去重合并，分类 Tab 隐藏
 * （各站分类 id 会撞，不做跨站分类合并）。
 */
public final class MixRules {

    private MixRules() {}

    public static final class Rule {
        public final String name;
        public final List<String> keys;

        public Rule(String name, List<String> keys) {
            this.name = name;
            this.keys = keys == null ? new ArrayList<>() : keys;
        }

        public String encode() {
            StringBuilder sb = new StringBuilder(name).append('|');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(keys.get(i));
            }
            return sb.toString();
        }

        public static Rule decode(String line) {
            if (TextUtils.isEmpty(line)) return null;
            int sep = line.indexOf('|');
            if (sep <= 0) return null;
            String name = line.substring(0, sep).trim();
            String rest = line.substring(sep + 1).trim();
            List<String> keys = new ArrayList<>();
            if (!rest.isEmpty()) {
                for (String k : rest.split(",")) {
                    k = k.trim();
                    if (!k.isEmpty()) keys.add(k);
                }
            }
            if (name.isEmpty() || keys.size() < 2) return null;
            return new Rule(name, keys);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Rule> getRules() {
        List<Rule> out = new ArrayList<>();
        try {
            String json = Hawk.get(HawkConfig.MIX_RULES, "");
            if (TextUtils.isEmpty(json)) return out;
            List<String> lines = App.gson().fromJson(json,
                    new TypeToken<List<String>>() {}.getType());
            if (lines == null) return out;
            for (String line : lines) {
                Rule r = Rule.decode(line);
                if (r != null) out.add(r);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void saveRules(List<Rule> rules) {
        try {
            List<String> lines = new ArrayList<>();
            for (Rule r : rules) lines.add(r.encode());
            Hawk.put(HawkConfig.MIX_RULES, App.gson().toJson(lines));
        } catch (Throwable ignored) {
        }
    }

    public static void addRule(Rule rule) {
        if (rule == null) return;
        List<Rule> rules = getRules();
        for (int i = 0; i < rules.size(); i++) {
            if (rule.name.equals(rules.get(i).name)) {
                rules.set(i, rule);
                saveRules(rules);
                return;
            }
        }
        rules.add(rule);
        saveRules(rules);
    }

    public static void removeRule(String name) {
        if (TextUtils.isEmpty(name)) return;
        List<Rule> rules = getRules();
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (name.equals(rules.get(i).name)) rules.remove(i);
        }
        saveRules(rules);
        if (name.equals(getActiveName())) setActiveName("");
    }

    public static String getActiveName() {
        try {
            String v = Hawk.get(HawkConfig.MIX_ACTIVE, "");
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    public static void setActiveName(String name) {
        try {
            Hawk.put(HawkConfig.MIX_ACTIVE, name == null ? "" : name);
        } catch (Throwable ignored) {
        }
    }

    /** 当前激活的规则（未激活或规则已删返回 null）。 */
    public static Rule getActiveRule() {
        String name = getActiveName();
        if (TextUtils.isEmpty(name)) return null;
        for (Rule r : getRules()) if (name.equals(r.name)) return r;
        return null;
    }
}
