package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.BuiltinConfigs;
import com.yingtu.plus.api.BuiltinLiveSources;
import com.yingtu.plus.api.BuiltinOverrides;
import com.yingtu.plus.api.SiteHealthChecker;
import com.yingtu.plus.api.SiteHealthResults;
import com.yingtu.plus.api.VodConfigSync;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.drama.BuiltinDramaConfigs;
import com.yingtu.plus.reader.ReadingHomeActivity;
import com.yingtu.plus.reader.ReadingSources;
import com.yingtu.plus.ui.adapter.BuiltinZoneAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置区列表页（设置页“内置站点”入口进来）。
 *
 * 专门列全部内置站（午夜 + 短剧 + 直播 + 阅读，数量从现有数据里读，不硬编码），
 * 与影视区分开。分区展示，组头带数量；点站名进该站（复用各分区现成的进站链路）。
 *
 * 管理能力（覆盖清单 Hawk 持久化，重启仍有效；不动 assets 原文件）：
 * - 显示开关：每站行上直接带开关（照火蚁TV源显示列表），关掉的站分区 tab 与各站列表
 *   里都不出现；检测与更新不受影响，随时能再打开。本页是管理页，隐藏的站仍列在这里。
 * - 全部显示：一键把隐藏的站全开回来（删掉的不恢复）。
 * - 长按菜单：改名 / 删除 / 检测这个站。改名后分区 tab 与本页同步显示新名；
 *   删除从列表移除，卸载重装才恢复。
 *
 * 检测：本页“检测全部”跑全部内置站；跑完失效站按自动屏蔽开关处理；
 * 上次结果直接显示，没测过显示“待定”；超过 24 小时未检测且定时复查开着时，
 * 打开本页自动重测一轮。
 */
public class BuiltinZoneActivity extends BaseActivity {

    private BuiltinZoneAdapter adapter;
    private TextView tvTotal, tvProgress, btnCheck, btnStop;
    private ProgressBar progressCheck;
    private SwitchCompat swAutoBlock, swAutoRecheck;
    private boolean autoRecheckFired;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_builtin_zone;
    }

    @Override
    protected void init() {
        find(R.id.ivBack).setOnClickListener(v -> finish());
        tvTotal = find(R.id.tvTotal);
        tvProgress = find(R.id.tvProgress);
        btnCheck = find(R.id.btnCheck);
        btnStop = find(R.id.btnStop);
        progressCheck = find(R.id.progressCheck);
        swAutoBlock = find(R.id.swAutoBlock);
        swAutoRecheck = find(R.id.swAutoRecheck);

        RecyclerView rv = find(R.id.rvBuiltin);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BuiltinZoneAdapter();
        adapter.setOnItemClick(this::onItemClick);
        adapter.setOnItemLongClick(this::onItemLongClick);
        adapter.setOnVisibilityChanged(this::onVisibilityChanged);
        rv.setAdapter(adapter);

        find(R.id.btnShowAll).setOnClickListener(v -> {
            BuiltinOverrides.showAll();
            rebuildGroups();
            toast("已全部显示");
        });
        btnCheck.setOnClickListener(v -> startFullCheck());
        btnStop.setOnClickListener(v -> SiteHealthChecker.get().stop());

        swAutoBlock.setChecked(BuiltinOverrides.isAutoBlock());
        swAutoBlock.setOnCheckedChangeListener((b, checked) ->
                BuiltinOverrides.setAutoBlock(checked));
        swAutoRecheck.setChecked(BuiltinOverrides.isAutoRecheck());
        swAutoRecheck.setOnCheckedChangeListener((b, checked) ->
                BuiltinOverrides.setAutoRecheck(checked));

        rebuildGroups();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildGroups();
        // 定时复查：超过间隔没测过，打开本页自动重测一轮（每进一次页只触发一次）。
        if (!autoRecheckFired && BuiltinOverrides.isAutoRecheck()
                && !SiteHealthChecker.get().isRunning()) {
            long last = SiteHealthResults.lastRun();
            long dueMs = (long) BuiltinOverrides.recheckHours() * 3600_000L;
            if (last == 0 || System.currentTimeMillis() - last > dueMs) {
                autoRecheckFired = true;
                toast("上次检测已超过 " + BuiltinOverrides.recheckHours()
                        + " 小时，正在自动重测");
                startFullCheck();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 检测在后台继续跑（结果照常存盘）；回调里以 isDestroyed 守住，不碰已销毁的界面。
    }

    // ===== 列表构建 =====

    /** 从现有加载器读数（先补齐内置站进站表），数量不硬编码。 */
    private void rebuildGroups() {
        BuiltinConfigs.ensureLoaded(this);
        BuiltinDramaConfigs.ensureLoaded(this);
        VodConfigSync.sync();
        List<BuiltinZoneAdapter.Group> groups = new ArrayList<>();
        groups.add(new BuiltinZoneAdapter.Group("午夜区", midnightItems()));
        groups.add(new BuiltinZoneAdapter.Group("短剧区", dramaItems()));
        groups.add(new BuiltinZoneAdapter.Group("直播", liveItems()));
        groups.add(new BuiltinZoneAdapter.Group("阅读", readingItems()));
        adapter.submitGroups(groups);
        int total = 0;
        for (BuiltinZoneAdapter.Group g : groups) total += g.items.size();
        tvTotal.setText(total + " 个站");
    }

    private List<BuiltinZoneAdapter.Item> midnightItems() {
        List<BuiltinZoneAdapter.Item> items = new ArrayList<>();
        for (SourceBean s : BuiltinConfigs.getMidnightSitesAll()) {
            String kind = !TextUtils.isEmpty(s.getJar()) ? "jar站"
                    : (s.getApi() != null && s.getApi().endsWith(".py") ? "py站" : "蜘蛛站");
            items.add(new BuiltinZoneAdapter.Item(BuiltinOverrides.ZONE_MIDNIGHT, s.getKey(),
                    s.getName(), s.getKey() + " · " + kind,
                    BuiltinOverrides.isVisible(BuiltinOverrides.ZONE_MIDNIGHT, s.getKey()),
                    SiteHealthResults.get(BuiltinOverrides.ZONE_MIDNIGHT, s.getKey())));
        }
        return items;
    }

    private List<BuiltinZoneAdapter.Item> dramaItems() {
        List<BuiltinZoneAdapter.Item> items = new ArrayList<>();
        for (SourceBean s : BuiltinDramaConfigs.getDramaSitesAll()) {
            String kind = !TextUtils.isEmpty(s.getJar()) ? "jar站"
                    : (s.getApi() != null && s.getApi().endsWith(".py") ? "py站" : "蜘蛛站");
            items.add(new BuiltinZoneAdapter.Item(BuiltinOverrides.ZONE_DRAMA, s.getKey(),
                    s.getName(), s.getKey() + " · " + kind,
                    BuiltinOverrides.isVisible(BuiltinOverrides.ZONE_DRAMA, s.getKey()),
                    SiteHealthResults.get(BuiltinOverrides.ZONE_DRAMA, s.getKey())));
        }
        return items;
    }

    private List<BuiltinZoneAdapter.Item> liveItems() {
        List<BuiltinZoneAdapter.Item> items = new ArrayList<>();
        for (BuiltinLiveSources.Source s : BuiltinLiveSources.getAllBuiltinSources()) {
            String kind = s.protocol == BuiltinLiveSources.Protocol.PINGTAI ? "pingtai" : "m3u 列表";
            items.add(new BuiltinZoneAdapter.Item(BuiltinOverrides.ZONE_LIVE, s.id,
                    s.name, s.id + " · " + kind,
                    BuiltinOverrides.isVisible(BuiltinOverrides.ZONE_LIVE, s.id),
                    SiteHealthResults.get(BuiltinOverrides.ZONE_LIVE, s.id)));
        }
        return items;
    }

    private List<BuiltinZoneAdapter.Item> readingItems() {
        List<BuiltinZoneAdapter.Item> items = new ArrayList<>();
        for (ReadingSources.Source s : ReadingSources.all()) {
            if (BuiltinOverrides.isDeleted(BuiltinOverrides.ZONE_READING, s.key)) continue;
            String kind = ReadingSources.KIND_COMICS.equals(s.kind) ? "漫画"
                    : ReadingSources.KIND_AUDIO.equals(s.kind) ? "听书" : "小说";
            String name = BuiltinOverrides.displayName(BuiltinOverrides.ZONE_READING,
                    s.key, s.name);
            items.add(new BuiltinZoneAdapter.Item(BuiltinOverrides.ZONE_READING, s.key,
                    name, s.key + " · " + kind,
                    BuiltinOverrides.isVisible(BuiltinOverrides.ZONE_READING, s.key),
                    SiteHealthResults.get(BuiltinOverrides.ZONE_READING, s.key)));
        }
        return items;
    }

    // ===== 点站进站（复用各分区现成的链路） =====

    private void onItemClick(BuiltinZoneAdapter.Item item) {
        if (BuiltinOverrides.ZONE_MIDNIGHT.equals(item.zone)
                || BuiltinOverrides.ZONE_DRAMA.equals(item.zone)) {
            SourceBean bean = ApiConfig.get().getSource(item.key);
            if (bean == null) {
                toast("该站暂未加载，稍后再试");
                return;
            }
            ApiConfig.get().setSourceBean(bean);
            VodConfigSync.sync();
            Intent intent = new Intent(this, HomeActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra(HomeActivity.EXTRA_NAV_TAB,
                    BuiltinOverrides.ZONE_MIDNIGHT.equals(item.zone)
                            ? HomeActivity.NAV_MIDNIGHT : HomeActivity.NAV_DRAMA);
            intent.putExtra(HomeActivity.EXTRA_SITE_KEY, item.key);
            startActivity(intent);
            finish();
        } else if (BuiltinOverrides.ZONE_LIVE.equals(item.zone)) {
            Intent intent = new Intent(this, LiveActivity.class);
            intent.putExtra(LiveActivity.EXTRA_SOURCE_ID, item.key);
            startActivity(intent);
        } else if (BuiltinOverrides.ZONE_READING.equals(item.zone)) {
            ReadingSources.Source source = ReadingSources.byKey(item.key);
            if (source == null) {
                toast("该站暂未加载，稍后再试");
                return;
            }
            Intent intent = new Intent(this, ReadingHomeActivity.class);
            intent.putExtra(ReadingHomeActivity.EXTRA_KIND, source.kind);
            intent.putExtra(ReadingHomeActivity.EXTRA_SOURCE_KEY, item.key);
            startActivity(intent);
        }
    }

    // ===== 显示开关 / 长按菜单 =====

    private void onVisibilityChanged(BuiltinZoneAdapter.Item item, boolean visible) {
        BuiltinOverrides.setVisible(item.zone, item.key, visible);
        adapter.refreshGroup(item);
    }

    private void onItemLongClick(BuiltinZoneAdapter.Item item) {
        String[] options = {"改名", "删除", "检测这个站"};
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(options, (d, which) -> {
                    if (which == 0) showRenameDialog(item);
                    else if (which == 1) showDeleteDialog(item);
                    else checkSingle(item);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRenameDialog(BuiltinZoneAdapter.Item item) {
        EditText input = new EditText(this);
        input.setText(item.name);
        input.setSingleLine(true);
        input.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("改名：" + item.name)
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    BuiltinOverrides.rename(item.zone, item.key,
                            input.getText().toString());
                    rebuildGroups();
                    toast("已改名");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteDialog(BuiltinZoneAdapter.Item item) {
        new AlertDialog.Builder(this)
                .setTitle("删除 " + item.name + "？")
                .setMessage("只从列表移除，不删站文件；卸载重装才恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    BuiltinOverrides.delete(item.zone, item.key);
                    rebuildGroups();
                    toast("已从列表移除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 检测 =====

    private void startFullCheck() {
        List<SiteHealthChecker.Target> targets = collectTargets();
        if (targets.isEmpty()) {
            toast("没有可检测的站");
            return;
        }
        if (!SiteHealthChecker.get().start(this, targets, fullListener)) {
            toast("检测已在跑，等它跑完");
            return;
        }
        setChecking(true, targets.size());
    }

    private void checkSingle(BuiltinZoneAdapter.Item item) {
        if (SiteHealthChecker.get().isRunning()) {
            toast("检测已在跑，等它跑完再测单个");
            return;
        }
        List<SiteHealthChecker.Target> targets = new ArrayList<>();
        targets.add(targetFor(item));
        if (!SiteHealthChecker.get().start(this, targets, singleListener(item))) {
            toast("检测已在跑，等它跑完");
            return;
        }
        setChecking(true, 1);
    }

    private List<SiteHealthChecker.Target> collectTargets() {
        List<SiteHealthChecker.Target> targets = new ArrayList<>();
        for (BuiltinZoneAdapter.Group g : adapter.getGroups()) {
            for (BuiltinZoneAdapter.Item item : g.items) {
                targets.add(targetFor(item));
            }
        }
        return targets;
    }

    private SiteHealthChecker.Target targetFor(BuiltinZoneAdapter.Item item) {
        int kind;
        String api = "";
        BuiltinLiveSources.Source live = null;
        if (BuiltinOverrides.ZONE_LIVE.equals(item.zone)) {
            kind = SiteHealthChecker.KIND_LIVE;
            for (BuiltinLiveSources.Source s : BuiltinLiveSources.getAllBuiltinSources()) {
                if (s.id.equals(item.key)) {
                    live = s;
                    break;
                }
            }
        } else if (BuiltinOverrides.ZONE_READING.equals(item.zone)) {
            kind = SiteHealthChecker.KIND_READING;
            ReadingSources.Source s = ReadingSources.byKey(item.key);
            if (s != null) api = s.api;
        } else {
            kind = SiteHealthChecker.KIND_VIDEO;
            SourceBean bean = ApiConfig.get().getSource(item.key);
            if (bean != null) api = bean.getApi();
        }
        return new SiteHealthChecker.Target(item.zone, item.key, item.name, api, kind, live);
    }

    private final SiteHealthChecker.Listener fullListener = new SiteHealthChecker.Listener() {
        @Override
        public void onProgress(int done, int total, SiteHealthChecker.Target target,
                               SiteHealthResults.Result result) {
            if (isDestroyed()) return;
            adapter.updateHealth(target.zone, target.key, result);
            syncItemVisibility(target.zone, target.key);
            progressCheck.setMax(total);
            progressCheck.setProgress(done);
            tvProgress.setText(done + "/" + total);
        }

        @Override
        public void onFinished(int done, int total, int[] counts, boolean stopped) {
            if (isDestroyed()) return;
            setChecking(false, total);
            toast("检测完成" + (stopped ? "（已停止）" : "") + "：正常 " + counts[0]
                    + " · 空 " + counts[1] + " · 超时 " + counts[2] + " · 异常 " + counts[3]
                    + healthScore(counts, done));
            rebuildGroups();
        }
    };

    private SiteHealthChecker.Listener singleListener(BuiltinZoneAdapter.Item item) {
        return new SiteHealthChecker.Listener() {
            @Override
            public void onProgress(int done, int total, SiteHealthChecker.Target target,
                                   SiteHealthResults.Result result) {
                if (isDestroyed()) return;
                adapter.updateHealth(target.zone, target.key, result);
                syncItemVisibility(target.zone, target.key);
            }

            @Override
            public void onFinished(int done, int total, int[] counts, boolean stopped) {
                if (isDestroyed()) return;
                setChecking(false, total);
                SiteHealthResults.Result r = SiteHealthResults.get(item.zone, item.key);
                toast(item.name + "：" + (r == null ? "待定" : SiteHealthResults.label(r.status))
                        + (r != null && !r.detail.isEmpty() ? "（" + r.detail + "）" : ""));
                rebuildGroups();
            }
        };
    }

    /** 自动屏蔽改了开关状态后，把行上的开关同步过来。 */
    private void syncItemVisibility(String zone, String key) {
        boolean visible = BuiltinOverrides.isVisible(zone, key);
        for (BuiltinZoneAdapter.Group g : adapter.getGroups()) {
            for (BuiltinZoneAdapter.Item item : g.items) {
                if (item.zone.equals(zone) && item.key.equals(key)) item.visible = visible;
            }
        }
    }

    private void setChecking(boolean checking, int total) {
        btnCheck.setEnabled(!checking);
        btnStop.setVisibility(checking ? android.view.View.VISIBLE : android.view.View.GONE);
        progressCheck.setVisibility(checking ? android.view.View.VISIBLE : android.view.View.GONE);
        if (checking) {
            progressCheck.setMax(total);
            progressCheck.setProgress(0);
            tvProgress.setText("0/" + total);
        } else {
            tvProgress.setText("");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 健康分（LunaTV 口径：成功占比×100，≥75 优秀、≥50 良好、<50 待关注）。 */
    private static String healthScore(int[] counts, int done) {
        if (done <= 0) return "";
        int score = counts[0] * 100 / done;
        String level = score >= 75 ? "优秀" : score >= 50 ? "良好" : "待关注";
        return " · 健康分 " + score + "（" + level + "）";
    }
}
