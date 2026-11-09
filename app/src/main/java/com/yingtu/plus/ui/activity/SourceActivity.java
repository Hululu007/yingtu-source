package com.yingtu.plus.ui.activity;

import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.BuiltinOverrides;
import com.yingtu.plus.api.SiteHealthChecker;
import com.yingtu.plus.api.SiteHealthResults;
import com.yingtu.plus.api.VodConfigSync;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.ui.adapter.SourceListAdapter;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.util.OkGo;
import com.yingtu.plus.util.SiteFilter;
import com.yingtu.plus.util.SourceMsgGate;
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
 */
public class SourceActivity extends BaseActivity {

    /** 分组记录在 Hawk 里的 key。HawkConfig.java 不在本批可改文件内，暂用字面量。 */
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
    /** 站 key → 含它的配置组（站源短按多仓选用，同 key 在多个组里出现才弹选择）。 */
    private final Map<String, List<ConfigGroup>> groupsByKey = new HashMap<>();
    /** 上次检测结果（key → 结果），行上显示状态；只测当前生效配置的用户站。 */
    private final Map<String, SiteHealthResults.Result> health = new HashMap<>();
    private TextView btnCheck, btnStopCheck, tvCheckProgress;
    private ProgressBar progressCheck;

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
        btnCheck = find(R.id.btnCheck);
        btnStopCheck = find(R.id.btnStopCheck);
        tvCheckProgress = find(R.id.tvCheckProgress);
        progressCheck = find(R.id.progressCheck);
        btnCheck.setOnClickListener(v -> startUserCheck());
        btnStopCheck.setOnClickListener(v -> SiteHealthChecker.get().stop());

        RecyclerView rv = find(R.id.rvSource);
        adapter = new SourceListAdapter();
        adapter.setOnSourceClick(source -> onSourceClicked(source));
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

    /** 检测当前生效配置的用户站（内置站不在这页，去内置区列表页测）。 */
    private void startUserCheck() {
        List<SiteHealthChecker.Target> targets = SiteHealthChecker.get().userTargets();
        if (targets.isEmpty()) {
            Toast.makeText(this, "当前配置没有可检测的用户站", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!SiteHealthChecker.get().start(this, targets, new SiteHealthChecker.Listener() {
            @Override
            public void onProgress(int done, int total, SiteHealthChecker.Target target,
                                   SiteHealthResults.Result result) {
                if (isDestroyed()) return;
                health.put(target.key, result);
                adapter.setHealth(health);
                progressCheck.setMax(total);
                progressCheck.setProgress(done);
                tvCheckProgress.setText(done + "/" + total);
            }

            @Override
            public void onFinished(int done, int total, int[] counts, boolean stopped) {
                if (isDestroyed()) return;
                setChecking(false, total);
                int score = done > 0 ? counts[0] * 100 / done : 0;
                String level = score >= 75 ? "优秀" : score >= 50 ? "良好" : "待关注";
                Toast.makeText(SourceActivity.this,
                        "检测完成" + (stopped ? "（已停止）" : "") + "：正常 " + counts[0]
                                + " · 空 " + counts[1] + " · 超时 " + counts[2]
                                + " · 异常 " + counts[3]
                                + (done > 0 ? " · 健康分 " + score + "（" + level + "）" : ""),
                        Toast.LENGTH_LONG).show();
            }
        })) {
            Toast.makeText(this, "检测已在跑，等它跑完", Toast.LENGTH_SHORT).show();
            return;
        }
        setChecking(true, targets.size());
    }

    private void setChecking(boolean checking, int total) {
        btnCheck.setEnabled(!checking);
        btnStopCheck.setVisibility(checking ? View.VISIBLE : View.GONE);
        progressCheck.setVisibility(checking ? View.VISIBLE : View.GONE);
        if (checking) {
            progressCheck.setMax(total);
            progressCheck.setProgress(0);
            tvCheckProgress.setText("0/" + total);
        } else {
            tvCheckProgress.setText("");
        }
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
        groupsByKey.clear();
        for (ConfigGroup g : stored) {
            List<SourceBean> sites = parseGroupSites(g.body);
            if (sites.isEmpty()) continue;
            // 站源置顶 / 站源禁用（设置 → 首页和搜索）：置顶的排前面，被禁用的来源不列
            SiteFilter.sortPinnedFirst(sites);
            SiteFilter.filterDisabled(sites);
            if (sites.isEmpty()) continue;
            for (SourceBean s : sites) {
                keyToBody.put(s.getKey(), g.body);
                List<ConfigGroup> list = groupsByKey.get(s.getKey());
                if (list == null) {
                    list = new ArrayList<>();
                    groupsByKey.put(s.getKey(), list);
                }
                if (!list.contains(g)) list.add(g);
            }
            groups.add(new SourceListAdapter.Group(g.name, sites));
        }
        // 没有任何分组记录时（全新安装、无配置），退回单列表。
        // 内置站不在这页列（只列用户自有站）；同名 key 被误判内置是 parseConfig 不清空
        // builtinSiteKeys 的旧副作用，这里按现成判定过滤。
        if (groups.isEmpty() && !currentSites.isEmpty()) {
            List<SourceBean> userSites = new ArrayList<>();
            for (SourceBean s : currentSites) {
                if (!ApiConfig.get().isBuiltinSite(s.getKey())) userSites.add(s);
            }
            if (!userSites.isEmpty()) {
                SiteFilter.sortPinnedFirst(userSites);
                SiteFilter.filterDisabled(userSites);
                if (!userSites.isEmpty()) groups.add(new SourceListAdapter.Group("", userSites));
            }
        }
        // 上次检测结果带到行上（只取 user 区的）。
        health.clear();
        for (SourceListAdapter.Group g : groups) {
            for (SourceBean s : g.sites) {
                SiteHealthResults.Result r = SiteHealthResults.get(
                        BuiltinOverrides.ZONE_USER, s.getKey());
                if (r != null) health.put(s.getKey(), r);
            }
        }
        adapter.setHealth(health);
        adapter.submitGroups(groups);
        SourceBean home = ApiConfig.get().getHomeSource();
        if (home != null) adapter.setCurrentKey(home.getKey());
    }

    /** 站源短按多仓选（设置 → 更多设置）：开时同 key 在多个配置组里出现就先选组，关时直接切源。 */
    private void onSourceClicked(SourceBean source) {
        boolean multi = false;
        try {
            multi = Hawk.get(HawkConfig.SOURCE_TAP_MULTI, false);
        } catch (Throwable ignored) {
        }
        if (!multi) {
            switchToSource(source);
            return;
        }
        List<ConfigGroup> groups = groupsByKey.get(source.getKey());
        if (groups == null || groups.size() <= 1) {
            switchToSource(source);
            return;
        }
        String[] names = new String[groups.size()];
        for (int i = 0; i < groups.size(); i++) names[i] = groups.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("选择配置组：" + source.getName())
                .setItems(names, (d, which) -> switchToSourceInGroup(source, groups.get(which)))
                .setNegativeButton("取消", null)
                .show();
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

    /** 多仓选里指定了配置组：先切到该组配置，再切源。 */
    private void switchToSourceInGroup(SourceBean source, ConfigGroup group) {
        try {
            ApiConfig.get().parseConfig(group.body);
            Hawk.put(HawkConfig.API_CONFIG_CACHE, group.body);
        } catch (Throwable t) {
            Toast.makeText(this, "切换失败：该分组配置已损坏", Toast.LENGTH_LONG).show();
            return;
        }
        finishSwitch(source);
    }

    private void finishSwitch(SourceBean source) {
        SourceBean live = ApiConfig.get().getSource(source.getKey());
        if (live == null) live = source;
        ApiConfig.get().setSourceBean(live);
        // J 批：切配置后把新站表同步进 VodConfig（否则内容链路读不到站）
        VodConfigSync.sync();
        adapter.setCurrentKey(live.getKey());
        // 预加载该配置的 spider jar（CSP 站点首次进入会用到），后台执行
        final String spiderJar = ApiConfig.get().getSpider();
        if (spiderJar != null && !spiderJar.isEmpty()) {
            OkGo.io(() -> com.yingtu.plus.api.SpiderManager.get().parseJar(spiderJar));
        }
        SourceMsgGate.info(this, "已切换：" + live.getName());
        // 切源后回首页会自动刷新内容（HomeMainFragment.onVisible 比对 sourceKey）
        finish();
    }

    private void loadConfig(String url) {
        loadConfig(url, false);
    }

    /** fromBackup=true 表示这次拉的是防失接口（不再二次兜底，避免循环）。 */
    private void loadConfig(String url, boolean fromBackup) {
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
                    // 防失接口（高级配置）：主接口拉取失败时，若已设备用地址且与主地址不同，自动改拉备用
                    String backup = "";
                    try {
                        backup = Hawk.get(HawkConfig.BACKUP_API_URL, "");
                    } catch (Throwable ignored) {
                    }
                    if (!fromBackup && !TextUtils.isEmpty(backup) && !backup.trim().equals(url.trim())) {
                        Toast.makeText(SourceActivity.this, "主接口失败，正在尝试防失接口…",
                                Toast.LENGTH_SHORT).show();
                        loadConfig(backup.trim(), true);
                        return;
                    }
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
                        // J 批：订阅加载后把新站表同步进 VodConfig
                        VodConfigSync.sync();
                        // 预加载 spider jar（csp 站点需要），后台执行避免卡 UI
                        final String spiderJar = ApiConfig.get().getSpider();
                        if (spiderJar != null && !spiderJar.isEmpty()) {
                            OkGo.io(() -> com.yingtu.plus.api.SpiderManager.get().parseJar(spiderJar));
                        }
                        refreshList();
                        Toast.makeText(SourceActivity.this,
                                (fromBackup ? "主接口失败，已用防失接口，共 " : "解析成功，共 ")
                                        + ApiConfig.get().getSourceBeanList().size() + " 个源",
                                Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        // 主接口解析失败也试防失接口（地址通但内容不对时）
                        String backup = "";
                        try {
                            backup = Hawk.get(HawkConfig.BACKUP_API_URL, "");
                        } catch (Throwable ignored) {
                        }
                        if (!fromBackup && !TextUtils.isEmpty(backup) && !backup.trim().equals(url.trim())) {
                            Toast.makeText(SourceActivity.this, "主接口解析失败，正在尝试防失接口…",
                                    Toast.LENGTH_SHORT).show();
                            loadConfig(backup.trim(), true);
                            return;
                        }
                        Toast.makeText(SourceActivity.this,
                                "解析失败，请检查地址是否为有效 TVBox 配置",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }
}
