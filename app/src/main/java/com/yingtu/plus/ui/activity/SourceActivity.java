package com.yingtu.plus.ui.activity;

import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.SpiderManager;
import com.yingtu.plus.api.VodConfigSync;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.ui.adapter.SourceListAdapter;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.util.OkGo;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 视频源管理页：
 *  - 顶部输入 TVBox 订阅地址 → 拉取解析 → 保存
 *  - 下方源列表按接口配置分组，组头可点折叠/展开，组内站点三列网格；点击站点切源
 *
 * 分组说明：ApiConfig.parseConfig 每次加载都会清空旧站，所以多配置分组
 * 不能只靠 ApiConfig。本页把每次成功加载的配置原文（name=来源，body=配置 JSON）
 * 追加存进 Hawk（GROUP_STORE_KEY），列表按这份分组记录展示；点旧分组里的站时，
 * 先把该组配置原文重新 parseConfig 恢复成当前配置，再切源。
 * PySiteImporter 导入的 py 站记在「本地 py 导入」分组里，与订阅分组同一套机制。
 *
 * 注意：导入后不自动跳到本页（与 dm17 一致），用户须自己进视频源页才能看到新组；
 * 点组内站触发 switchToSource 后，站表经 VodConfigSync 同步进 VodConfig 才能播。
 */
public class SourceActivity extends BaseActivity {

    /** 分组记录在 Hawk 里的 key。 */
    public static final String GROUP_STORE_KEY = "api_config_groups";

    /** 一次加载的配置：name 为来源（订阅地址/文件名），body 为配置 JSON 原文。 */
    public static class ConfigGroup {
        public final String name;
        public final String body;

        public ConfigGroup(String name, String body) {
            this.name = name == null ? "" : name;
            this.body = body == null ? "" : body;
        }
    }

    /** 记录一次成功加载的配置。同一份内容（body 相同）只留一组并更新来源名；同名则覆盖内容。 */
    public static void recordConfigGroup(String name, String body) {
        if (body == null || body.isEmpty()) return;
        if (name == null || name.isEmpty()) name = "配置 1";
        List<ConfigGroup> groups = loadStoredGroups();
        boolean done = false;
        for (int i = 0; i < groups.size(); i++) {
            ConfigGroup g = groups.get(i);
            if (g.body.equals(body)) {
                groups.set(i, new ConfigGroup(name, body));
                done = true;
                break;
            }
        }
        if (!done) {
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).name.equals(name)) {
                    groups.set(i, new ConfigGroup(name, body));
                    done = true;
                    break;
                }
            }
        }
        if (!done) groups.add(new ConfigGroup(name, body));
        saveStoredGroups(groups);
    }

    /** 读出已存的全部分组记录（按加载顺序）。 */
    public static List<ConfigGroup> loadStoredGroups() {
        List<ConfigGroup> groups = new ArrayList<>();
        try {
            String raw = Hawk.get(GROUP_STORE_KEY, "");
            if (TextUtils.isEmpty(raw)) return groups;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                groups.add(new ConfigGroup(o.optString("name"), o.optString("body")));
            }
        } catch (Throwable ignored) {
        }
        return groups;
    }

    private static void saveStoredGroups(List<ConfigGroup> groups) {
        try {
            JSONArray arr = new JSONArray();
            for (ConfigGroup g : groups) {
                JSONObject o = new JSONObject();
                o.put("name", g.name);
                o.put("body", g.body);
                arr.put(o);
            }
            Hawk.put(GROUP_STORE_KEY, arr.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 只为展示解析一组配置里的站点（key/name/type/api），不经过 ApiConfig，不影响当前配置。 */
    public static List<SourceBean> parseGroupSites(String body) {
        List<SourceBean> sites = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("sites")) return sites;
            JsonArray arr = root.getAsJsonArray("sites");
            for (JsonElement e : arr) {
                JsonObject obj = e.getAsJsonObject();
                SourceBean sb = new SourceBean();
                sb.setKey(optString(obj, "key"));
                sb.setName(optString(obj, "name"));
                sb.setType(optInt(obj, "type"));
                sb.setApi(optString(obj, "api"));
                sites.add(sb);
            }
        } catch (Throwable ignored) {
        }
        return sites;
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static int optInt(JsonObject obj, String key) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private EditText etApi;
    private SourceListAdapter adapter;
    private View progress;
    /** 站点 key → 所属分组的配置原文；点站时用它把该组恢复成当前配置。 */
    private final Map<String, String> keyToBody = new HashMap<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_source;
    }

    @Override
    protected void init() {
        etApi = find(R.id.etApi);
        progress = find(R.id.progress);
        etApi.setText(Hawk.get(HawkConfig.API_URL, ""));

        find(R.id.ivBack).setOnClickListener(v -> finish());
        find(R.id.btnLoad).setOnClickListener(v -> loadConfig(etApi.getText().toString()));

        RecyclerView rv = find(R.id.rvSource);
        adapter = new SourceListAdapter();
        adapter.setOnSourceClick(source -> switchToSource(source));
        GridLayoutManager glm = new GridLayoutManager(this, 3);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.isGroupPosition(position) ? 3 : 1;
            }
        });
        rv.setLayoutManager(glm);
        rv.setAdapter(adapter);

        refreshList();
    }

    private void refreshList() {
        List<ConfigGroup> stored = loadStoredGroups();
        // 当前配置若不在分组记录里（本功能上线前加载的、或被清过分组记录），补一组进来
        String currentBody = Hawk.get(HawkConfig.API_CONFIG_CACHE, "");
        List<SourceBean> currentSites = ApiConfig.get().getSourceBeanList();
        if (!currentSites.isEmpty() && !TextUtils.isEmpty(currentBody)) {
            boolean found = false;
            for (ConfigGroup g : stored) {
                if (g.body.equals(currentBody)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                String name = Hawk.get(HawkConfig.API_URL, "");
                if (TextUtils.isEmpty(name)) name = "配置 " + (stored.size() + 1);
                stored.add(new ConfigGroup(name, currentBody));
                saveStoredGroups(stored);
            }
        }

        List<SourceListAdapter.Group> groups = new ArrayList<>();
        keyToBody.clear();
        for (ConfigGroup g : stored) {
            List<SourceBean> sites = parseGroupSites(g.body);
            if (sites.isEmpty()) continue;
            for (SourceBean s : sites) {
                keyToBody.put(s.getKey(), g.body);
            }
            groups.add(new SourceListAdapter.Group(g.name, sites));
        }
        // 没有任何分组记录时（全新安装、无配置），退回单列表。
        if (groups.isEmpty() && !currentSites.isEmpty()) {
            groups.add(new SourceListAdapter.Group("", currentSites));
        }
        adapter.submitGroups(groups);
        SourceBean home = ApiConfig.get().getHomeSource();
        if (home != null) adapter.setCurrentKey(home.getKey());
    }

    /** 切源：先把该站所属分组的配置恢复成当前配置，再切换（否则站不在 ApiConfig 里，播不了）。 */
    private void switchToSource(SourceBean source) {
        String body = keyToBody.get(source.getKey());
        if (!TextUtils.isEmpty(body)) {
            try {
                ApiConfig.get().parseConfig(body);
                Hawk.put(HawkConfig.API_CONFIG_CACHE, body);
            } catch (Throwable t) {
                Toast.makeText(this, "切换失败：该分组配置已损坏", Toast.LENGTH_LONG).show();
                return;
            }
        }
        finishSwitch(source);
    }

    private void finishSwitch(SourceBean source) {
        SourceBean live = ApiConfig.get().getSource(source.getKey());
        if (live == null) live = source;
        ApiConfig.get().setSourceBean(live);
        // 切配置后把新站表同步进 VodConfig（否则内容链路读不到站）
        VodConfigSync.sync();
        adapter.setCurrentKey(live.getKey());
        // 预加载该配置的 spider jar（CSP 站点首次进入会用到），后台执行
        final String spiderJar = ApiConfig.get().getSpider();
        if (spiderJar != null && !spiderJar.isEmpty()) {
            OkGo.io(() -> SpiderManager.get().parseJar(spiderJar));
        }
        Toast.makeText(this, "已切换：" + live.getName(), Toast.LENGTH_SHORT).show();
        // 切源后回首页会自动刷新内容（HomeMainFragment.onVisible 比对 sourceKey）
        finish();
    }

    private void loadConfig(String url) {
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, "请输入订阅地址", Toast.LENGTH_SHORT).show();
            return;
        }
        progress.setVisibility(View.VISIBLE);
        Request request = new Request.Builder().url(url.trim()).build();
        OkGo.client().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                OkGo.main(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(SourceActivity.this, "拉取失败：" + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                OkGo.main(() -> {
                    progress.setVisibility(View.GONE);
                    try {
                        ApiConfig.get().parseConfig(body);
                        Hawk.put(HawkConfig.API_URL, url.trim());
                        Hawk.put(HawkConfig.API_CONFIG_CACHE, body);
                        recordConfigGroup(url.trim(), body);
                        // 订阅加载后把新站表同步进 VodConfig
                        VodConfigSync.sync();
                        // 预加载 spider jar（csp 站点需要），后台执行避免卡 UI
                        final String spiderJar = ApiConfig.get().getSpider();
                        if (spiderJar != null && !spiderJar.isEmpty()) {
                            OkGo.io(() -> SpiderManager.get().parseJar(spiderJar));
                        }
                        refreshList();
                        Toast.makeText(SourceActivity.this,
                                "解析成功，共 " + ApiConfig.get().getSourceBeanList().size() + " 个源",
                                Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        Toast.makeText(SourceActivity.this,
                                "解析失败，请检查地址是否为有效 TVBox 配置",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }
}
