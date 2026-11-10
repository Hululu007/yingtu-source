package com.yingtu.plus.ui.activity;

import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;

import com.yingtu.plus.BuildConfig;
import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.VodConfigSync;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.setting.BackgroundPlaybackPolicy;
import com.yingtu.plus.setting.PlayerSetting;
import com.yingtu.plus.bean.ParseBean;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.player.exo.MediaSourceFactory;
import com.yingtu.plus.util.CacheCleaner;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.util.LanSyncHelper;
import com.yingtu.plus.util.SecureDns;
import com.yingtu.plus.util.SiteFilter;
import com.yingtu.plus.util.WallpaperFx;
import com.yingtu.plus.util.WallpaperHelper;
import com.yingtu.plus.util.ZoneSites;
import com.orhanobut.hawk.Hawk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 设置页。分区卡片式布局，参考用户提供的 UI：
 *   数据接口 / 首页和搜索 / 播放设置 / 系统设置 + 底部功能按钮。
 *
 * 可用项已接真实逻辑（接口地址、首页推荐源名、默认解析、深色模式、
 * 网格列数、清缓存、关于、检测更新）；展示性项提供取值切换对话框。
 */
public class SettingActivity extends BaseActivity {

    private TextView tvApiUrl, tvParseName, tvHomeRec, tvGridColumns,
            tvOrientation, tvStartSource, tvCacheHint, tvImportLocalJson, tvBuiltinZone,
            tvApiCache, tvSearchShow, tvProgressTag, tvRender, tvScale,
            tvHomeSourceCols, tvSearchSite, tvBufferTimeout, tvIjkBuffer, tvIjkDecode,
            tvStartPage, tvSecureDns, tvWallpaperFx, tvCateViewType, tvCateViewCols,
            tvDetailStyle, tvSkipAd, tvAutoFloat, tvSnifferView, tvCacheLocation;

    /** 系统文件选择器：选手机里的 .json 配置文件（字段初始化时注册，必须在 onCreate 之前）。 */
    private final ActivityResultLauncher<String> pickJsonLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onJsonPicked);

    @Override
    protected int getLayoutId() {
        return R.layout.activity_setting;
    }

    @Override
    protected void init() {
        find(R.id.ivBack).setOnClickListener(v -> finish());

        bindDataSection();
        bindHomeSection();
        bindPlaySection();
        bindSystemSection();
        bindBottom();

        ((TextView) find(R.id.tvVersion)).setText("影图+ v" + BuildConfig.VERSION_NAME);
    }

    // ===== 数据接口 =====
    private void bindDataSection() {
        tvApiUrl = find(R.id.tvApiUrl);
        refreshApiUrl();
        find(R.id.rowApiUrl).setOnClickListener(v ->
                startActivity(new Intent(this, SourceActivity.class)));
        find(R.id.btnApiSetting).setOnClickListener(v ->
                startActivity(new Intent(this, SourceActivity.class)));
        find(R.id.btnApiAdvanced).setOnClickListener(v ->
                startActivity(new Intent(this, AdvancedConfigActivity.class)));
        find(R.id.btnApiMore).setOnClickListener(v ->
                startActivity(new Intent(this, MoreSettingsActivity.class)));
        find(R.id.rowInject).setOnClickListener(v -> showInjectDialog());
        tvApiCache = find(R.id.tvApiCache);
        refreshApiCache();
        find(R.id.rowApiCache).setOnClickListener(v -> pickApiCache());
        find(R.id.rowApiDownload).setOnClickListener(v -> downloadCurrentConfig());
        find(R.id.rowInjectDownload).setOnClickListener(v -> downloadInject());
        find(R.id.rowSyncData).setOnClickListener(v -> showSyncDialog());
        tvBuiltinZone = find(R.id.tvBuiltinZone);
        refreshBuiltinZoneLabel();
        find(R.id.rowBuiltinZone).setOnClickListener(v ->
                startActivity(new Intent(this, BuiltinZoneActivity.class)));
        find(R.id.rowOneKeyPy).setOnClickListener(v ->
                startActivity(new Intent(this, OneKeyPyActivity.class)));
        tvImportLocalJson = find(R.id.tvImportLocalJson);
        refreshImportLabel();
        find(R.id.rowImportLocalJson).setOnClickListener(v -> pickJsonLauncher.launch("*/*"));
    }

    private void refreshApiCache() {
        if (tvApiCache == null) return;
        tvApiCache.setText(Hawk.get(HawkConfig.API_CACHE_ENABLED, true)
                ? getString(R.string.val_on) : getString(R.string.val_off));
    }

    /** 接口缓存：开时启动读本地已缓存的订阅配置，关时每次现取（下次启动生效）。 */
    private void pickApiCache() {
        String[] opts = {getString(R.string.val_off), getString(R.string.val_on)};
        int cur = Hawk.get(HawkConfig.API_CACHE_ENABLED, true) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle(R.string.row_api_cache)
                .setSingleChoiceItems(opts, cur, (d, which) -> {
                    Hawk.put(HawkConfig.API_CACHE_ENABLED, which == 1);
                    refreshApiCache();
                    d.dismiss();
                    toast(which == 1 ? "接口缓存已开启" : "接口缓存已关闭，下次启动不再读本地缓存");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 接口下载：把当前接口地址的配置写到下载目录。 */
    private void downloadCurrentConfig() {
        String body = Hawk.get(HawkConfig.API_CONFIG_CACHE, "");
        if (body == null || body.isEmpty()) {
            toast("当前没有已加载的接口配置，先去站源管理加载订阅");
            return;
        }
        downloadTextFile("影图+接口配置.json", body);
    }

    /** 注入下载：把注入地址的内容拉下来写到下载目录。 */
    private void downloadInject() {
        String url = com.yingtu.plus.util.InjectHelper.getUrl(this);
        if (url == null || url.isEmpty()) {
            toast("注入地址为空，先在注入地址里填一个直播源地址");
            return;
        }
        toast("正在下载注入内容…");
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                String body = readText(conn.getInputStream());
                runOnUiThread(() -> downloadTextFile("影图+注入下载.txt", body));
            } catch (Throwable e) {
                runOnUiThread(() -> toast("注入下载失败：" + e.getMessage()));
            }
        }).start();
    }

    private String readText(InputStream in) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** 写文本文件到系统下载目录（Android 10+ 走 MediaStore，以下走 Download 文件夹）。 */
    private void downloadTextFile(String fileName, String content) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    toast("写文件失败：拿不到下载目录");
                    return;
                }
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("打不开输出流");
                    out.write(content.getBytes("UTF-8"));
                }
                toast("已保存到下载目录：" + fileName);
            } else {
                java.io.File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                java.io.File file = new java.io.File(dir, fileName);
                try (OutputStream out = new java.io.FileOutputStream(file)) {
                    out.write(content.getBytes("UTF-8"));
                }
                toast("已保存到：" + file.getAbsolutePath());
            }
        } catch (Throwable e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    private void refreshApiUrl() {
        String url = Hawk.get(HawkConfig.API_URL, "");
        tvApiUrl.setText(url.isEmpty() ? getString(R.string.val_not_set) : shorten(url));
    }

    /** 内置站点行：总数从现有数据里读（午夜 + 短剧 + 直播，删掉的不算）。 */
    private void refreshBuiltinZoneLabel() {
        if (tvBuiltinZone == null) return;
        com.yingtu.plus.api.BuiltinConfigs.ensureLoaded(this);
        com.yingtu.plus.drama.BuiltinDramaConfigs.ensureLoaded(this);
        int total = com.yingtu.plus.api.BuiltinConfigs.getMidnightSitesAll().size()
                + com.yingtu.plus.drama.BuiltinDramaConfigs.getDramaSitesAll().size()
                + com.yingtu.plus.api.BuiltinLiveSources.getAllBuiltinSources().size();
        tvBuiltinZone.setText(total + " 个站");
    }

    // ===== 首页和搜索 =====
    private void bindHomeSection() {
        tvStartSource = find(R.id.tvStartSource);
        tvHomeRec = find(R.id.tvHomeRec);
        refreshHomeRec();

        find(R.id.btnSiteSource).setOnClickListener(v -> showSiteSourceDialog());
        find(R.id.btnSourcePin).setOnClickListener(v -> showSourcePinDialog());
        find(R.id.btnSourceDisable).setOnClickListener(v -> showSourceDisableDialog());
        find(R.id.rowStartSource).setOnClickListener(v -> showStartupSitesDialog());
        find(R.id.rowHomeRec).setOnClickListener(v -> pickHomeRec());
        tvSearchShow = find(R.id.tvSearchShow);
        refreshSearchShow();
        find(R.id.rowSearchShow).setOnClickListener(v -> pickSearchShow());
        tvHomeSourceCols = find(R.id.tvHomeSourceCols);
        refreshHomeSourceCols();
        find(R.id.rowHomeSourceCols).setOnClickListener(v -> pickHomeSourceCols());
        tvSearchSite = find(R.id.tvSearchSite);
        refreshSearchSite();
        find(R.id.rowSearchSite).setOnClickListener(v -> pickSearchSite());
        refreshStartSource();
    }

    // ===== 搜索展示（前=搜索页，后=聚搜页）=====
    private static final String[] SEARCH_SHOW_OPTS = {
            "文字|文字", "文字|图片", "文字|图文", "图片|文字", "图片|图片", "图片|图文"
    };

    private void refreshSearchShow() {
        if (tvSearchShow == null) return;
        int v = Hawk.get(HawkConfig.SEARCH_SHOW, 1);
        tvSearchShow.setText(SEARCH_SHOW_OPTS[v >= 0 && v < SEARCH_SHOW_OPTS.length ? v : 1]);
    }

    private void pickSearchShow() {
        int cur = Hawk.get(HawkConfig.SEARCH_SHOW, 1);
        new AlertDialog.Builder(this)
                .setTitle("请选择搜索视图(搜索 | 聚搜)")
                .setSingleChoiceItems(SEARCH_SHOW_OPTS, cur, (d, which) -> {
                    Hawk.put(HawkConfig.SEARCH_SHOW, which);
                    refreshSearchShow();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 首页选源列数（默认 3 列） =====
    private void refreshHomeSourceCols() {
        if (tvHomeSourceCols == null) return;
        tvHomeSourceCols.setText(Hawk.get(HawkConfig.HOME_SOURCE_COLUMNS, 3) + " 列");
    }

    private void pickHomeSourceCols() {
        String[] opts = {"1 列", "2 列", "3 列"};
        int cur = Hawk.get(HawkConfig.HOME_SOURCE_COLUMNS, 3) - 1;
        new AlertDialog.Builder(this)
                .setTitle("首页选源列数")
                .setSingleChoiceItems(opts, cur, (d, which) -> {
                    Hawk.put(HawkConfig.HOME_SOURCE_COLUMNS, which + 1);
                    refreshHomeSourceCols();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 搜索站点 =====
    private static final String[] SEARCH_SITE_OPTS = {"全部", "白名单", "黑名单", "当前源", "选择源", "名称包含"};

    private void refreshSearchSite() {
        if (tvSearchSite == null) return;
        tvSearchSite.setText(SEARCH_SITE_OPTS[SiteFilter.getSearchMode()]);
    }

    private void pickSearchSite() {
        int cur = SiteFilter.getSearchMode();
        new AlertDialog.Builder(this)
                .setTitle("请选择搜索的站点名单")
                .setSingleChoiceItems(SEARCH_SITE_OPTS, cur, (d, which) -> {
                    d.dismiss();
                    if (which == 1 || which == 2 || which == 4) pickSearchSiteList(which);
                    else if (which == 5) pickSearchSiteName();
                    else {
                        Hawk.put(HawkConfig.SEARCH_SITE_MODE, 0);
                        refreshSearchSite();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 白名单 / 黑名单 / 选择源：站源多选（复选 + 全选/全清/反选）。 */
    private void pickSearchSiteList(int mode) {
        List<SourceBean> sites = allUserSites();
        if (sites.isEmpty()) {
            toast("当前没有可选站源");
            return;
        }
        String key = mode == 1 ? HawkConfig.SEARCH_SITE_WHITELIST
                : mode == 2 ? HawkConfig.SEARCH_SITE_BLACKLIST : HawkConfig.SEARCH_SITE_PICKED;
        Set<String> selected = new HashSet<>(SiteFilter.getStringList(key));
        String[] names = new String[sites.size()];
        boolean[] checked = new boolean[sites.size()];
        for (int i = 0; i < sites.size(); i++) {
            names[i] = sites.get(i).getName();
            checked[i] = selected.contains(sites.get(i).getKey());
        }
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_site_multi, null);
        android.widget.ListView listView = view.findViewById(R.id.listSites);
        listView.setChoiceMode(android.widget.ListView.CHOICE_MODE_MULTIPLE);
        listView.setAdapter(new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, names));
        for (int i = 0; i < checked.length; i++) listView.setItemChecked(i, checked[i]);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(mode == 1 ? "搜索白名单" : mode == 2 ? "搜索黑名单" : "选择搜索的源")
                .setView(view)
                .setPositiveButton("保存", (d, w) -> {
                    List<String> keys = new ArrayList<>();
                    for (int i = 0; i < sites.size(); i++) {
                        if (listView.isItemChecked(i)) keys.add(sites.get(i).getKey());
                    }
                    SiteFilter.setStringList(key, keys);
                    Hawk.put(HawkConfig.SEARCH_SITE_MODE, mode);
                    refreshSearchSite();
                    toast("已保存 " + keys.size() + " 个站");
                })
                .setNegativeButton("取消", null)
                .create();
        view.findViewById(R.id.btnSelectAll).setOnClickListener(v -> {
            for (int i = 0; i < sites.size(); i++) listView.setItemChecked(i, true);
        });
        view.findViewById(R.id.btnSelectNone).setOnClickListener(v -> {
            for (int i = 0; i < sites.size(); i++) listView.setItemChecked(i, false);
        });
        view.findViewById(R.id.btnSelectInvert).setOnClickListener(v -> {
            for (int i = 0; i < sites.size(); i++) listView.setItemChecked(i, !listView.isItemChecked(i));
        });
        view.findViewById(R.id.btnBack).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /** 名称包含：按站名子串过滤聚搜站表。 */
    private void pickSearchSiteName() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("输入站名包含的文字");
        input.setText(Hawk.get(HawkConfig.SEARCH_SITE_NAME_FILTER, ""));
        new AlertDialog.Builder(this)
                .setTitle("按名称包含搜索")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    Hawk.put(HawkConfig.SEARCH_SITE_NAME_FILTER, input.getText().toString().trim());
                    Hawk.put(HawkConfig.SEARCH_SITE_MODE, 5);
                    refreshSearchSite();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 当前配置的用户站 + 内置站（去重，供置顶/禁用/搜索名单多选共用）。 */
    private List<SourceBean> allUserSites() {
        List<SourceBean> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            for (SourceBean s : ApiConfig.get().getSourceBeanList()) {
                if (s != null && s.getKey() != null && seen.add(s.getKey())) out.add(s);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ===== 站源置顶：站源多选，置顶的在站源列表里排前面 =====
    private void showSourcePinDialog() {
        List<SourceBean> sites = allUserSites();
        if (sites.isEmpty()) {
            toast("当前没有可选站源");
            return;
        }
        Set<String> pinned = new HashSet<>(SiteFilter.getPinned());
        String[] names = new String[sites.size()];
        for (int i = 0; i < sites.size(); i++) names[i] = sites.get(i).getName();
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_site_multi, null);
        android.widget.ListView listView = view.findViewById(R.id.listSites);
        listView.setChoiceMode(android.widget.ListView.CHOICE_MODE_MULTIPLE);
        listView.setAdapter(new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, names));
        for (int i = 0; i < sites.size(); i++) listView.setItemChecked(i, pinned.contains(sites.get(i).getKey()));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("站源置顶")
                .setView(view)
                .setPositiveButton("保存", (d, w) -> {
                    List<String> keys = new ArrayList<>();
                    for (int i = 0; i < sites.size(); i++) {
                        if (listView.isItemChecked(i)) keys.add(sites.get(i).getKey());
                    }
                    SiteFilter.setPinned(keys);
                    toast(keys.isEmpty() ? "已取消全部置顶" : "已置顶 " + keys.size() + " 个站");
                })
                .setNegativeButton("取消", null)
                .create();
        view.findViewById(R.id.btnSelectAll).setOnClickListener(v -> {
            for (int i = 0; i < sites.size(); i++) listView.setItemChecked(i, true);
        });
        view.findViewById(R.id.btnSelectNone).setOnClickListener(v -> {
            for (int i = 0; i < sites.size(); i++) listView.setItemChecked(i, false);
        });
        view.findViewById(R.id.btnSelectInvert).setOnClickListener(v -> {
            for (int i = 0; i < sites.size(); i++) listView.setItemChecked(i, !listView.isItemChecked(i));
        });
        view.findViewById(R.id.btnBack).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    // ===== 站源禁用：8 项（SITES文件=导入的 SITES 文件站，网址站源=自定/网址添加的站）=====
    private void showSourceDisableDialog() {
        String[] names = {"网络接口", "直播接口", "内部配置", "JS文件", "DRPY文件", "JSON文件", "SITES文件", "网址站源"};
        int[] bits = {HawkConfig.DISABLE_NET, HawkConfig.DISABLE_LIVE, HawkConfig.DISABLE_BUILTIN,
                HawkConfig.DISABLE_JS, HawkConfig.DISABLE_DRPY, HawkConfig.DISABLE_JSON,
                HawkConfig.DISABLE_SITES_FILE, HawkConfig.DISABLE_URL_SITE};
        boolean[] checked = new boolean[names.length];
        int mask = SiteFilter.getDisabledMask();
        for (int i = 0; i < bits.length; i++) checked[i] = (mask & bits[i]) != 0;
        new AlertDialog.Builder(this)
                .setTitle("站源禁用")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("保存", (d, w) -> {
                    int m = 0;
                    for (int i = 0; i < bits.length; i++) if (checked[i]) m |= bits[i];
                    SiteFilter.setDisabledMask(m);
                    toast(m == 0 ? "已取消全部禁用" : "禁用已保存，返回列表即生效");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 站点来源：只读，当前站源的来源构成 =====
    private void showSiteSourceDialog() {
        int remote = 0, builtin = 0, local = 0;
        try {
            for (SourceBean s : ApiConfig.get().getSourceBeanList()) {
                if (ApiConfig.get().isBuiltinSite(s.getKey())) builtin++;
                else remote++;
            }
        } catch (Throwable ignored) {
        }
        try {
            for (SourceActivity.ConfigGroup g : SourceActivity.loadStoredGroups()) {
                if (g.name != null && g.name.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) local++;
            }
        } catch (Throwable ignored) {
        }
        int live = 0;
        try {
            live = com.yingtu.plus.api.BuiltinLiveSources.getAllBuiltinSources().size();
        } catch (Throwable ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle("站点来源")
                .setMessage("远程接口站源：" + remote + " 个\n内置站源：" + builtin + " 个\n"
                        + "本地导入配置组：" + local + " 个\n内置直播源：" + live + " 个")
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 启动站源行的值：已设几个区（0 个显示“未设置”）。 */
    private void refreshStartSource() {
        if (tvStartSource == null) return;
        int count = 0;
        if (!ZoneSites.getStartupSiteKey(ZoneSites.ZONE_MOVIE).isEmpty()) count++;
        if (!ZoneSites.getStartupSiteKey(ZoneSites.ZONE_DRAMA).isEmpty()) count++;
        if (!ZoneSites.getStartupSiteKey(ZoneSites.ZONE_MIDNIGHT).isEmpty()) count++;
        tvStartSource.setText(count == 0 ? "未设置" : "已设 " + count + " 个区");
    }

    /** 启动站源（三区独立）：点开一个三行弹窗，每行显示该区当前设的启动站；点一行选该区的站。 */
    private void showStartupSitesDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_startup_sites, null);
        TextView tvMovie = view.findViewById(R.id.tvStartupMovie);
        TextView tvDrama = view.findViewById(R.id.tvStartupDrama);
        TextView tvMidnight = view.findViewById(R.id.tvStartupMidnight);
        Runnable refreshRows = () -> {
            tvMovie.setText(ZoneSites.getStartupSiteName(ZoneSites.ZONE_MOVIE));
            tvDrama.setText(ZoneSites.getStartupSiteName(ZoneSites.ZONE_DRAMA));
            tvMidnight.setText(ZoneSites.getStartupSiteName(ZoneSites.ZONE_MIDNIGHT));
        };
        refreshRows.run();
        view.findViewById(R.id.rowStartupMovie).setOnClickListener(v ->
                pickStartupSite(ZoneSites.ZONE_MOVIE, "影视", refreshRows));
        view.findViewById(R.id.rowStartupDrama).setOnClickListener(v ->
                pickStartupSite(ZoneSites.ZONE_DRAMA, "短剧", refreshRows));
        view.findViewById(R.id.rowStartupMidnight).setOnClickListener(v ->
                pickStartupSite(ZoneSites.ZONE_MIDNIGHT, "午夜", refreshRows));
        androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("启动站源")
                        .setView(view)
                        .setPositiveButton("完成", null)
                        .create();
        dialog.setOnDismissListener(d -> refreshStartSource());
        dialog.show();
    }

    /** 选某区的启动站：单选列表，首项“不设置”用于清除；三个区各存各的键。 */
    private void pickStartupSite(String zone, String zoneName, Runnable refreshRows) {
        List<SourceBean> sites = ZoneSites.getZoneSites(zone);
        if (sites == null || sites.isEmpty()) {
            Toast.makeText(this, zoneName + "区暂无可选站点", Toast.LENGTH_SHORT).show();
            return;
        }
        String current = ZoneSites.getStartupSiteKey(zone);
        String[] names = new String[sites.size() + 1];
        names[0] = "不设置";
        int checked = 0;
        for (int i = 0; i < sites.size(); i++) {
            names[i + 1] = sites.get(i).getName();
            if (sites.get(i).getKey().equals(current)) checked = i + 1;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(zoneName + "区启动站")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    ZoneSites.setStartupSiteKey(zone, which == 0 ? "" : sites.get(which - 1).getKey());
                    d.dismiss();
                    if (refreshRows != null) refreshRows.run();
                    refreshStartSource();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshHomeRec() {
        tvHomeRec.setText(Hawk.get(HawkConfig.HOME_REC, "站点推荐"));
    }

    private void pickHomeRec() {
        String[] opts = {"豆瓣热播", "站源推荐"};
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.row_home_rec)
                .setItems(opts, (d, which) -> {
                    Hawk.put(HawkConfig.HOME_REC, opts[which]);
                    refreshHomeRec();
                })
                .show();
    }

    // ===== 播放设置 =====
    private void bindPlaySection() {
        tvParseName = find(R.id.tvParseName);
        refreshParseName();
        find(R.id.rowParse).setOnClickListener(v -> showParsePicker());

        refreshCoreName();
        tvDetailStyle = find(R.id.tvDetailStyle);
        refreshDetailStyle();
        find(R.id.rowDetailStyle).setOnClickListener(v -> pickDetailStyle());
        tvProgressTag = find(R.id.tvProgressTag);
        refreshProgressTag();
        find(R.id.rowProgressTag).setOnClickListener(v -> pickProgressTag());
        find(R.id.rowPlayer).setOnClickListener(v -> showCorePicker());
        tvRender = find(R.id.tvRender);
        refreshRender();
        find(R.id.rowRender).setOnClickListener(v -> pickRender());
        tvScale = find(R.id.tvScale);
        refreshScale();
        find(R.id.rowScale).setOnClickListener(v -> pickScale());
        tvSkipAd = find(R.id.tvSkipAd);
        refreshSkipAd();
        find(R.id.rowSkipAd).setOnClickListener(v -> pickSkipAd());
        tvSnifferView = find(R.id.tvSnifferView);
        refreshSnifferView();
        find(R.id.rowSnifferView).setOnClickListener(v -> pickSnifferView());
        tvAutoFloat = find(R.id.tvAutoFloat);
        refreshAutoFloat();
        find(R.id.rowAutoFloat).setOnClickListener(v -> pickAutoFloat());
        tvBufferTimeout = find(R.id.tvBufferTimeout);
        refreshBufferTimeout();
        find(R.id.rowBufferTimeout).setOnClickListener(v -> pickBufferTimeout());
        tvIjkBuffer = find(R.id.tvIjkBuffer);
        refreshIjkBuffer();
        find(R.id.rowIjkBuffer).setOnClickListener(v -> pickIjkBuffer());
        tvIjkDecode = find(R.id.tvIjkDecode);
        refreshIjkDecode();
        find(R.id.rowIjkDecode).setOnClickListener(v -> pickIjkDecode());
        tvCacheLocation = find(R.id.tvCacheLocation);
        refreshCacheLocation();
        find(R.id.rowCacheLocation).setOnClickListener(v -> pickCacheLocation());
    }

    private static final String[] DETAIL_STYLE_OPTS = {"影音图片", "简洁文字"};

    private void refreshDetailStyle() {
        if (tvDetailStyle == null) return;
        int v = Hawk.get(HawkConfig.DETAIL_STYLE, 0);
        tvDetailStyle.setText(DETAIL_STYLE_OPTS[v >= 0 && v < DETAIL_STYLE_OPTS.length ? v : 0]);
    }

    /** 详情页样式：影音图片=现行（海报毛玻璃+剧照），简洁文字=只显标题行、藏简介与剧照（DetailActivity 读它）。 */
    private void pickDetailStyle() {
        int cur = Hawk.get(HawkConfig.DETAIL_STYLE, 0);
        new AlertDialog.Builder(this)
                .setTitle("详情页样式")
                .setSingleChoiceItems(DETAIL_STYLE_OPTS, cur, (d, which) -> {
                    Hawk.put(HawkConfig.DETAIL_STYLE, which);
                    refreshDetailStyle();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshSkipAd() {
        if (tvSkipAd == null) return;
        tvSkipAd.setText(Hawk.get(HawkConfig.SKIP_ANTI_LEECH, true)
                ? getString(R.string.val_on) : getString(R.string.val_off));
    }

    /** 解析跳过防盗：开时解析到直链后跳过二次 WebView 嗅探校验直接放行（ParseJob 读它），关时保持原流程。 */
    private void pickSkipAd() {
        String[] opts = {getString(R.string.val_on), getString(R.string.val_off)};
        int cur = Hawk.get(HawkConfig.SKIP_ANTI_LEECH, true) ? 0 : 1;
        new AlertDialog.Builder(this)
                .setTitle("解析跳过防盗")
                .setSingleChoiceItems(opts, cur, (d, which) -> {
                    Hawk.put(HawkConfig.SKIP_ANTI_LEECH, which == 0);
                    refreshSkipAd();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshAutoFloat() {
        if (tvAutoFloat == null) return;
        tvAutoFloat.setText(Hawk.get(HawkConfig.AUTO_FLOAT, false)
                ? getString(R.string.val_on) : getString(R.string.val_off));
    }

    /** 自动悬浮：开时切后台自动进画中画（VideoActivity.onUserLeaveHint 读它），关时不自动进。 */
    private void pickAutoFloat() {
        String[] opts = {getString(R.string.val_on), getString(R.string.val_off)};
        int cur = Hawk.get(HawkConfig.AUTO_FLOAT, false) ? 0 : 1;
        new AlertDialog.Builder(this)
                .setTitle("自动悬浮")
                .setSingleChoiceItems(opts, cur, (d, which) -> {
                    Hawk.put(HawkConfig.AUTO_FLOAT, which == 0);
                    refreshAutoFloat();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshSnifferView() {
        if (tvSnifferView == null) return;
        tvSnifferView.setText(Hawk.get(HawkConfig.SNIFFER_VIEW, 0) == 1 ? "XWalkView" : "系统自带");
    }

    /** 嗅探View：系统自带=现行 WebView 嗅探（ParseJob）；XWalkView 需另下几十 MB Crosswalk 运行时，
     * 未接入——点了只给说明、不切换、不存值，绝不做成点了没反应的假选项。 */
    private void pickSnifferView() {
        String[] opts = {"系统自带", "XWalkView"};
        int cur = Hawk.get(HawkConfig.SNIFFER_VIEW, 0);
        new AlertDialog.Builder(this)
                .setTitle("嗅探View")
                .setSingleChoiceItems(opts, cur, (d, which) -> {
                    d.dismiss();
                    if (which == 1) {
                        new AlertDialog.Builder(this)
                                .setTitle("XWalkView 未接入")
                                .setMessage("XWalkView 需要另外下载几十 MB 的 Crosswalk 运行时，本应用未接入，无法切换。嗅探仍使用系统自带 WebView。")
                                .setPositiveButton("知道了", null)
                                .show();
                        return;
                    }
                    Hawk.put(HawkConfig.SNIFFER_VIEW, 0);
                    refreshSnifferView();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshCacheLocation() {
        if (tvCacheLocation == null) return;
        int v = Hawk.get(HawkConfig.CACHE_LOCATION, 0);
        java.io.File dir = v == 1 ? externalExoCacheDir() : com.github.catvod.utils.Path.exoCache();
        String free = freeSpaceText(dir);
        tvCacheLocation.setText((v == 1 ? "外部存储" : "默认") + (free.isEmpty() ? "" : " | " + free));
    }

    /** 外置缓存目录下的 exo 目录；取不到外置缓存时返回 null。 */
    private java.io.File externalExoCacheDir() {
        try {
            java.io.File ext = getExternalCacheDir();
            if (ext == null) return null;
            return new java.io.File(ext, "exo");
        } catch (Throwable e) {
            return null;
        }
    }

    /** 目录剩余空间文本（GB/MB），取不到返回空串。 */
    private static String freeSpaceText(java.io.File dir) {
        if (dir == null) return "";
        try {
            android.os.StatFs sf = new android.os.StatFs(dir.getAbsolutePath());
            long bytes = sf.getAvailableBytes();
            if (bytes >= 1024L * 1024 * 1024) {
                return String.format(java.util.Locale.ROOT, "%.1f GB 可用", bytes / 1073741824.0);
            }
            return (bytes / 1024 / 1024) + " MB 可用";
        } catch (Throwable e) {
            return "";
        }
    }

    /** 播放缓存位置：默认=内置存储（Path.exoCache），外部存储=外置缓存目录下的 exo；
     * 切换后释放旧 Exo 缓存、下次在新目录重建；Glide 图片缓存目录随初始化固定，重启应用后跟随。 */
    private void pickCacheLocation() {
        java.io.File internalDir = com.github.catvod.utils.Path.exoCache();
        java.io.File externalDir = externalExoCacheDir();
        String internalFree = freeSpaceText(internalDir);
        String externalFree = freeSpaceText(externalDir);
        String[] opts = {
                "默认（内置存储" + (internalFree.isEmpty() ? "" : "，" + internalFree) + "）",
                "外部存储" + (externalDir == null ? "（不可用）" : "，" + externalFree)};
        int cur = Hawk.get(HawkConfig.CACHE_LOCATION, 0);
        new AlertDialog.Builder(this)
                .setTitle("播放缓存位置")
                .setSingleChoiceItems(opts, cur, (d, which) -> {
                    if (which == 1 && externalDir == null) {
                        toast("外部存储不可用");
                        return;
                    }
                    Hawk.put(HawkConfig.CACHE_LOCATION, which);
                    MediaSourceFactory.resetCacheForLocation();
                    refreshCacheLocation();
                    d.dismiss();
                    toast("已切换到" + (which == 1 ? "外部存储" : "默认") + "，图片缓存重启应用后跟随");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshProgressTag() {
        if (tvProgressTag == null) return;
        tvProgressTag.setText(Hawk.get(HawkConfig.PROGRESS_TAG, 0) == 1 ? "唯一ID" : "名称");
    }

    /** 进度标识：名称=历史列表显片名+进度，唯一ID=显 vod_id+进度（HistoryAdapter 读它）。 */
    private void pickProgressTag() {
        String[] opts = {"唯一ID", "名称"};
        int cur = Hawk.get(HawkConfig.PROGRESS_TAG, 0);
        new AlertDialog.Builder(this)
                .setTitle("请选择视频播放参数的标识方式")
                .setSingleChoiceItems(opts, cur, (d, which) -> {
                    Hawk.put(HawkConfig.PROGRESS_TAG, which);
                    refreshProgressTag();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshRender() {
        if (tvRender == null) return;
        tvRender.setText(PlayerSetting.getRender() == PlayerSetting.RENDER_TEXTURE ? "TextureView" : "SurfaceView");
    }

    /** 渲染方式：ExoUtil.setPlayerView 读 PlayerSetting.getRender()，下次建播放器生效。 */
    private void pickRender() {
        String[] opts = {"TextureView", "SurfaceView"};
        int cur = PlayerSetting.getRender() == PlayerSetting.RENDER_TEXTURE ? 0 : 1;
        new AlertDialog.Builder(this)
                .setTitle("请选择默认渲染方式")
                .setSingleChoiceItems(opts, cur, (d, which) -> {
                    PlayerSetting.putRender(which == 0 ? PlayerSetting.RENDER_TEXTURE : PlayerSetting.RENDER_SURFACE);
                    refreshRender();
                    d.dismiss();
                    toast("已设为 " + opts[which] + "，下次播放生效");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static final String[] SCALE_OPTS = {"适应", "16:9", "4:3", "填充", "原始", "裁剪"};

    private void refreshScale() {
        if (tvScale == null) return;
        tvScale.setText(SCALE_OPTS[PlayerSetting.getScale()]);
    }

    /** 画面缩放：存 scale 索引，PlaybackActivity.applyResizeMode 按 scaleToResizeMode 换算成
     * media3 ResizeMode（适应/原始都是 FIT，16:9/4:3 是定宽/定高，填充是 FILL，裁剪是 ZOOM）。 */
    private void pickScale() {
        int cur = PlayerSetting.getScale();
        new AlertDialog.Builder(this)
                .setTitle("请选择默认画面缩放")
                .setSingleChoiceItems(SCALE_OPTS, cur, (d, which) -> {
                    PlayerSetting.putScale(which);
                    refreshScale();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshBufferTimeout() {
        if (tvBufferTimeout == null) return;
        int sec = PlayerSetting.getBufferTimeoutSec();
        tvBufferTimeout.setText(sec == 0 ? getString(R.string.val_off) : sec + " 秒");
    }

    /** 缓冲超时时间：关闭=默认（主路 8 秒、详情内联 15/30 秒），选 N 秒=连接与读取超时都取 N 秒。 */
    private void pickBufferTimeout() {
        String[] opts = new String[21];
        opts[0] = getString(R.string.val_off);
        for (int i = 1; i <= 20; i++) opts[i] = i + " 秒";
        int cur = PlayerSetting.getBufferTimeoutSec();
        new AlertDialog.Builder(this)
                .setTitle("请选择播放缓冲超时时间")
                .setSingleChoiceItems(opts, cur, (d, which) -> {
                    PlayerSetting.putBufferTimeoutSec(which);
                    refreshBufferTimeout();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshIjkBuffer() {
        if (tvIjkBuffer == null) return;
        int bytes = PlayerSetting.getBuffer();
        tvIjkBuffer.setText(bytes <= 0 ? getString(R.string.val_off) : (bytes / 1024 / 1024) + "MB");
    }

    /** IJK边播边存：存入 PlayerSetting buffer，IjkSimplePlayer 与 ExoUtil 建缓冲时读它。 */
    private void pickIjkBuffer() {
        String[] opts = new String[18];
        opts[0] = getString(R.string.val_off);
        for (int i = 1; i <= 17; i++) opts[i] = (i * 20) + "MB";
        int bytes = PlayerSetting.getBuffer();
        int cur = bytes <= 0 ? 0 : Math.min(17, bytes / 1024 / 1024 / 20);
        new AlertDialog.Builder(this)
                .setTitle("请选择IJK边播边存缓存大小")
                .setSingleChoiceItems(opts, cur, (d, which) -> {
                    PlayerSetting.putBuffer(which * 20 * 1024 * 1024);
                    refreshIjkBuffer();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshIjkDecode() {
        if (tvIjkDecode == null) return;
        tvIjkDecode.setText(PlayerSetting.getIjkDecode() == 0 ? "软解码" : "硬解码");
    }

    /** IJK解码：下次建 IJK 内核时按它起（PlayerManager 起引擎时读 getIjkDecode）。 */
    private void pickIjkDecode() {
        String[] opts = {"硬解码", "软解码"};
        int cur = PlayerSetting.getIjkDecode() == 0 ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("IJK解码")
                .setSingleChoiceItems(opts, cur, (d, which) -> {
                    PlayerSetting.putIjkDecode(which == 0 ? 1 : 0);
                    refreshIjkDecode();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshCoreName() {
        TextView tv = find(R.id.tvPlayer);
        if (tv != null) tv.setText(com.yingtu.plus.player.core.PlayerFactory.coreName(
                com.yingtu.plus.player.core.PlayerFactory.currentCore()));
    }

    private void showCorePicker() {
        final String[] names = {"ExoPlayer（默认·兼容好）", "IJK（ffmpeg 软解）", "MPV（疑难格式）"};
        int cur = com.yingtu.plus.player.core.PlayerFactory.currentCore();
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("默认播放器内核")
                .setSingleChoiceItems(names, cur, (d, which) -> {
                    com.yingtu.plus.player.core.PlayerFactory.setCore(which);
                    refreshCoreName();
                    d.dismiss();
                    toast("已设为 " + com.yingtu.plus.player.core.PlayerFactory.coreName(which)
                            + "，下次播放生效（播放页右上角也可临时切换）");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshParseName() {
        ParseBean p = ApiConfig.get().getDefaultParse();
        tvParseName.setText(p != null ? p.getName() : getString(R.string.parse_none));
    }

    private void showParsePicker() {
        List<ParseBean> parses = ApiConfig.get().getParseBeanList();
        if (parses.isEmpty()) {
            toast("当前源未配置解析接口");
            return;
        }
        String[] names = new String[parses.size()];
        int checked = -1;
        for (int i = 0; i < parses.size(); i++) {
            names[i] = parses.get(i).getName();
            if (parses.get(i).isSelected()) checked = i;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.setting_default_parse)
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    ApiConfig.get().setDefaultParse(parses.get(which));
                    refreshParseName();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 系统设置 =====
    private void bindSystemSection() {
        tvOrientation = find(R.id.tvOrientation);
        tvGridColumns = find(R.id.tvGridColumns);
        refreshOrientation();
        refreshGridColumns();

        SwitchCompat swDark = find(R.id.swDark);
        swDark.setChecked(Hawk.get(HawkConfig.DARK_MODE, true));
        swDark.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            Hawk.put(HawkConfig.DARK_MODE, checked);
            AppCompatDelegate.setDefaultNightMode(checked
                    ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_NO);
        });

        find(R.id.rowOrientation).setOnClickListener(v -> pickOrientation());
        tvStartPage = find(R.id.tvStartPage);
        refreshStartPage();
        find(R.id.rowStartPage).setOnClickListener(v -> pickStartPage());
        tvSecureDns = find(R.id.tvSecureDns);
        refreshSecureDns();
        find(R.id.rowSecureDns).setOnClickListener(v -> pickSecureDns());
        tvWallpaperFx = find(R.id.tvWallpaperFx);
        refreshWallpaperFx();
        find(R.id.rowWallpaperFx).setOnClickListener(v -> pickWallpaperFx());
        tvCateViewType = find(R.id.tvCateViewType);
        refreshCateViewType();
        find(R.id.rowCateViewType).setOnClickListener(v -> pickCateViewType());
        tvCateViewCols = find(R.id.tvCateViewCols);
        refreshCateViewCols();
        find(R.id.rowCateViewCols).setOnClickListener(v -> pickCateViewCols());
        SwitchCompat swAutoClear = find(R.id.swAutoClear);
        swAutoClear.setChecked(Hawk.get(HawkConfig.AUTO_CLEAR_PLAY_CACHE, true));
        swAutoClear.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            Hawk.put(HawkConfig.AUTO_CLEAR_PLAY_CACHE, checked);
            toast(checked ? "自清播放缓存已开启" : "自清播放缓存已关闭");
        });
        find(R.id.rowGridColumns).setOnClickListener(v -> pickGridColumns());

        find(R.id.rowClearCache).setOnClickListener(v -> {
            // 真清播放缓存（Glide 图片缓存 + 两个 Exo 缓存目录），不清站源与历史
            CacheCleaner.clearPlayCache(SettingActivity.this);
            ((TextView) find(R.id.tvCacheHint)).setText(R.string.val_on);
            toast("已清理播放缓存");
        });

        // 短剧预加载开关：播当前集时提前解析后面 5 集的流地址（只解析地址，不下载视频字节），默认开。
        SwitchCompat swDramaPreload = find(R.id.swDramaPreload);
        swDramaPreload.setChecked(Hawk.get(HawkConfig.DRAMA_PRELOAD, true));
        swDramaPreload.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            Hawk.put(HawkConfig.DRAMA_PRELOAD, checked);
            toast(checked ? "短剧预加载已开启" : "短剧预加载已关闭");
        });

        // 后台播放开关：开着时切后台音频继续播（前台服务保活 + 通知栏控制），默认关。
        SwitchCompat swBackgroundPlay = find(R.id.swBackgroundPlay);
        swBackgroundPlay.setChecked(BackgroundPlaybackPolicy.isEnabled());
        swBackgroundPlay.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            BackgroundPlaybackPolicy.putEnabled(checked);
            toast(checked ? "后台播放已开启" : "后台播放已关闭");
        });

        bindWallpaperSection();
    }

    // ===== 默认启动 =====
    private static final String[] START_PAGE_OPTS = {"首页", "直播", "文件", "历史", "收藏", "最近"};

    private void refreshStartPage() {
        if (tvStartPage == null) return;
        int v = Hawk.get(HawkConfig.DEFAULT_START_PAGE, 0);
        tvStartPage.setText(START_PAGE_OPTS[v >= 0 && v < START_PAGE_OPTS.length ? v : 0]);
    }

    /** 默认启动：FirstActivity 启动 1.2 秒后按它进对应区（最近=历史页）。 */
    private void pickStartPage() {
        int cur = Hawk.get(HawkConfig.DEFAULT_START_PAGE, 0);
        new AlertDialog.Builder(this)
                .setTitle("请选择默认启动项目")
                .setSingleChoiceItems(START_PAGE_OPTS, cur, (d, which) -> {
                    Hawk.put(HawkConfig.DEFAULT_START_PAGE, which);
                    refreshStartPage();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 安全DNS：选了立即应用到 catvod OkHttp 的 DoH（SecureDns），下次请求即生效 =====
    private void refreshSecureDns() {
        if (tvSecureDns == null) return;
        tvSecureDns.setText(SecureDns.nameOf(SecureDns.get()));
    }

    private void pickSecureDns() {
        int cur = SecureDns.get();
        new AlertDialog.Builder(this)
                .setTitle("请选择安全DNS")
                .setSingleChoiceItems(SecureDns.NAMES, cur, (d, which) -> {
                    Hawk.put(HawkConfig.SECURE_DNS, which);
                    SecureDns.apply(which);
                    refreshSecureDns();
                    d.dismiss();
                    toast(which == 0 ? "安全DNS已关闭，走系统 DNS" : "安全DNS已切到" + SecureDns.NAMES[which]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 壁纸虚化|亮度：两个滑杆，存 Hawk，FirstActivity/HomeActivity 设背景时应用 =====
    private void refreshWallpaperFx() {
        if (tvWallpaperFx == null) return;
        tvWallpaperFx.setText(WallpaperFx.getBlur() + " | " + WallpaperFx.getBrightness());
    }

    private void pickWallpaperFx() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_wallpaper_fx, null);
        TextView tvBlur = view.findViewById(R.id.tvBlurValue);
        TextView tvBright = view.findViewById(R.id.tvBrightValue);
        SeekBar sbBlur = view.findViewById(R.id.sbBlur);
        SeekBar sbBright = view.findViewById(R.id.sbBright);
        sbBlur.setMax(25);
        sbBlur.setProgress(WallpaperFx.getBlur());
        sbBright.setMax(200);
        sbBright.setProgress(WallpaperFx.getBrightness() + 100);
        Runnable refresh = () -> {
            tvBlur.setText("虚化：" + sbBlur.getProgress());
            tvBright.setText("亮度：" + (sbBright.getProgress() - 100));
        };
        refresh.run();
        sbBlur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { refresh.run(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sbBright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { refresh.run(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("壁纸虚化|亮度")
                .setView(view)
                .setPositiveButton("保存", (d, w) -> {
                    Hawk.put(HawkConfig.WALLPAPER_BLUR, sbBlur.getProgress());
                    Hawk.put(HawkConfig.WALLPAPER_BRIGHTNESS, sbBright.getProgress() - 100);
                    refreshWallpaperFx();
                    toast("已保存，下次启动壁纸生效");
                })
                .setNegativeButton("取消", null)
                .create();
        // 系统壁纸：内置可选图（默认渐变 / 森林日落 artwork.webp）；网络壁纸：相册选图（WallpaperActivity）
        view.findViewById(R.id.btnSysWallpaper).setOnClickListener(v -> {
            dialog.dismiss();
            showSystemWallpaperPicker();
        });
        view.findViewById(R.id.btnNetWallpaper).setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, WallpaperActivity.class));
        });
        dialog.show();
    }

    /** 系统壁纸选择器：默认渐变=恢复默认（清空自定义壁纸），森林日落=内置 artwork.webp（资源 URI，ContentResolver 可读）。 */
    private void showSystemWallpaperPicker() {
        int cur = -1;
        try {
            Uri uri = WallpaperHelper.getWallpaperUri(this);
            if (uri == null) cur = 0;
            else if ("android.resource".equals(uri.getScheme()) && uri.toString().contains("artwork")) cur = 1;
        } catch (Throwable ignored) {
        }
        String[] opts = {"默认渐变", "森林日落"};
        new AlertDialog.Builder(this)
                .setTitle("系统壁纸")
                .setSingleChoiceItems(opts, cur, (d, which) -> {
                    d.dismiss();
                    if (which == 0) {
                        WallpaperHelper.resetWallpaper(this);
                        toast("已恢复默认渐变壁纸");
                    } else {
                        WallpaperHelper.setWallpaper(this, Uri.parse("android.resource://" + getPackageName() + "/" + R.drawable.artwork));
                        toast("已设为森林日落壁纸");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 分类视图类型 / 列数：首页分类列表（HomeMainFragment）读它切换布局与列数 =====
    private static final String[] CATE_VIEW_OPTS = {"普通视图1", "文字视图", "详情视图1", "详情视图2", "普通视图2"};
    private static final String[] CATE_COLS_OPTS = {
            "方案1：2列|1列|1列|1列", "方案2：3列|2列|2列|2列", "方案3：3列|2列|3列|2列"};

    private void refreshCateViewType() {
        if (tvCateViewType == null) return;
        int v = Hawk.get(HawkConfig.CATE_VIEW_TYPE, 0);
        tvCateViewType.setText(CATE_VIEW_OPTS[v >= 0 && v < CATE_VIEW_OPTS.length ? v : 0]);
    }

    private void pickCateViewType() {
        int cur = Hawk.get(HawkConfig.CATE_VIEW_TYPE, 0);
        new AlertDialog.Builder(this)
                .setTitle("请选择分类视图类型")
                .setSingleChoiceItems(CATE_VIEW_OPTS, cur, (d, which) -> {
                    Hawk.put(HawkConfig.CATE_VIEW_TYPE, which);
                    refreshCateViewType();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshCateViewCols() {
        if (tvCateViewCols == null) return;
        int v = Hawk.get(HawkConfig.CATE_VIEW_COLUMNS, 1);
        tvCateViewCols.setText("方案" + (v + 1));
    }

    private void pickCateViewCols() {
        int cur = Hawk.get(HawkConfig.CATE_VIEW_COLUMNS, 1);
        new AlertDialog.Builder(this)
                .setTitle("分类视图[竖图|横图|文字|详情]列数")
                .setSingleChoiceItems(CATE_COLS_OPTS, cur, (d, which) -> {
                    Hawk.put(HawkConfig.CATE_VIEW_COLUMNS, which);
                    refreshCateViewCols();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 影图间同步数据（局域网最小闭环，见 LanSyncHelper）=====
    private void showSyncDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_sync_data, null);
        android.widget.EditText etAddr = view.findViewById(R.id.etSyncAddr);
        android.widget.CheckBox cbApi = view.findViewById(R.id.cbSyncApi);
        android.widget.CheckBox cbHistory = view.findViewById(R.id.cbSyncHistory);
        android.widget.CheckBox cbCollect = view.findViewById(R.id.cbSyncCollect);
        android.widget.CheckBox cbConfig = view.findViewById(R.id.cbSyncConfig);
        TextView tvDevices = view.findViewById(R.id.tvSyncDevices);
        TextView tvLocal = view.findViewById(R.id.tvSyncLocal);
        LanSyncHelper.startServer();
        String ip = LanSyncHelper.localIp();
        tvLocal.setText(ip.isEmpty() ? "本机同步服务已启动（端口 8686），本机 IP 未取到"
                : "本机地址：" + ip + ":" + LanSyncHelper.PORT + "（对方填这个地址拉取）");
        boolean[] cats = {true, true, true, true};
        cbApi.setOnCheckedChangeListener((b, c) -> cats[0] = c);
        cbHistory.setOnCheckedChangeListener((b, c) -> cats[1] = c);
        cbCollect.setOnCheckedChangeListener((b, c) -> cats[2] = c);
        cbConfig.setOnCheckedChangeListener((b, c) -> cats[3] = c);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("影图间同步数据")
                .setView(view)
                .setNegativeButton("关闭", null)
                .create();
        view.findViewById(R.id.btnSyncRefresh).setOnClickListener(v -> {
            LanSyncHelper.startServer();
            toast("本机同步服务已启动");
        });
        view.findViewById(R.id.btnSyncScan).setOnClickListener(v -> {
            tvDevices.setText("正在扫描同网段…");
            LanSyncHelper.scan(addresses -> runOnUiThread(() -> {
                if (addresses.isEmpty()) {
                    tvDevices.setText("没扫到设备：确认对方也开了同步服务且在同一 WiFi");
                } else {
                    tvDevices.setText("扫到 " + addresses.size() + " 台：" + android.text.TextUtils.join("、", addresses));
                    etAddr.setText(addresses.get(0));
                }
            }));
        });
        view.findViewById(R.id.btnSyncPush).setOnClickListener(v -> {
            LanSyncHelper.push(etAddr.getText().toString(), cats[0], cats[1], cats[2], cats[3],
                    (ok, msg) -> runOnUiThread(() -> toast(msg)));
        });
        view.findViewById(R.id.btnSyncPull).setOnClickListener(v -> {
            LanSyncHelper.pull(etAddr.getText().toString(), cats[0], cats[1], cats[2], cats[3],
                    (ok, msg) -> runOnUiThread(() -> {
                        toast(msg);
                        if (ok) {
                            refreshApiUrl();
                            refreshHomeRec();
                        }
                    }));
        });
        dialog.show();
    }

    private void refreshOrientation() {
        tvOrientation.setText(PlayerSetting.isLandscapeOrientation() ? getString(R.string.val_landscape) : getString(R.string.val_portrait));
    }

    private void pickOrientation() {
        String[] opts = {getString(R.string.val_portrait), getString(R.string.val_landscape)};
        int checked = PlayerSetting.isLandscapeOrientation() ? 1 : 0;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.row_orientation)
                .setSingleChoiceItems(opts, checked, (d, which) -> {
                    PlayerSetting.putOrientation(which);
                    refreshOrientation();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshGridColumns() {
        int cols = Hawk.get(HawkConfig.GRID_COLUMNS, 3);
        tvGridColumns.setText(cols + " 列");
    }

    private void pickGridColumns() {
        String[] opts = {"3 列", "4 列", "5 列"};
        int cur = Hawk.get(HawkConfig.GRID_COLUMNS, 3);
        int checked = cur - 3;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.row_grid_columns)
                .setSingleChoiceItems(opts, checked, (d, which) -> {
                    Hawk.put(HawkConfig.GRID_COLUMNS, which + 3);
                    refreshGridColumns();
                    d.dismiss();
                })
                .show();
    }

    // ===== 底部按钮 =====
    private void bindBottom() {
        find(R.id.btnBackup).setOnClickListener(v -> doBackup());
        find(R.id.btnClearCache).setOnClickListener(v -> {
            Hawk.delete(HawkConfig.PLAY_HISTORY);
            Hawk.delete(HawkConfig.SEARCH_HISTORY);
            toast("缓存已清除");
        });
        find(R.id.btnUpdate).setOnClickListener(v -> checkUpdate());
        find(R.id.btnAbout).setOnClickListener(v -> showAbout());
    }

    /** 数据备份：Hawk 全部配置 + 当前接口配置导出成一个 JSON 写到下载目录。 */
    private void doBackup() {
        new AlertDialog.Builder(this)
                .setTitle("数据备份")
                .setMessage("把全部设置与当前接口配置导出成一个 JSON 文件，保存到下载目录？")
                .setPositiveButton("备份", (d, w) -> {
                    try {
                        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
                        root.addProperty("app", "影图+");
                        root.addProperty("version", BuildConfig.VERSION_NAME);
                        root.addProperty("api_url", Hawk.get(HawkConfig.API_URL, ""));
                        root.addProperty("api_config", Hawk.get(HawkConfig.API_CONFIG_CACHE, ""));
                        com.google.gson.JsonObject cfg = new com.google.gson.JsonObject();
                        cfg.addProperty("home_rec", Hawk.get(HawkConfig.HOME_REC, "豆瓣热播"));
                        cfg.addProperty("grid_columns", Hawk.get(HawkConfig.GRID_COLUMNS, 3));
                        cfg.addProperty("dark_mode", Hawk.get(HawkConfig.DARK_MODE, true));
                        cfg.addProperty("default_start_page", Hawk.get(HawkConfig.DEFAULT_START_PAGE, 0));
                        cfg.addProperty("secure_dns", Hawk.get(HawkConfig.SECURE_DNS, 0));
                        cfg.addProperty("search_show", Hawk.get(HawkConfig.SEARCH_SHOW, 1));
                        cfg.addProperty("progress_tag", Hawk.get(HawkConfig.PROGRESS_TAG, 0));
                        root.add("settings", cfg);
                        downloadTextFile("影图+备份.json", new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root));
                    } catch (Throwable e) {
                        toast("备份失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 检测更新：读远端版本号对比。当前没有可用的更新源（旧的两个公开镜像仓已随账号被封而消失），
     * 有更新源地址时才会真的去请求；没配就如实报，不假装已是最新。 */
    private void checkUpdate() {
        String updateUrl = Hawk.get("update_url", "");
        if (updateUrl == null || updateUrl.isEmpty()) {
            toast("未配置更新源，当前版本 v" + BuildConfig.VERSION_NAME);
            return;
        }
        toast("正在检查更新…");
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(updateUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                String body = readText(conn.getInputStream());
                String remote = new com.google.gson.JsonParser().parse(body)
                        .getAsJsonObject().get("version").getAsString();
                runOnUiThread(() -> {
                    if (BuildConfig.VERSION_NAME.equals(remote)) {
                        toast("当前已是最新版本 v" + BuildConfig.VERSION_NAME);
                    } else {
                        toast("发现新版本 v" + remote + "，当前 v" + BuildConfig.VERSION_NAME);
                    }
                });
            } catch (Throwable e) {
                runOnUiThread(() -> toast("检查更新失败：" + e.getMessage()));
            }
        }).start();
    }

    private void showAbout() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("关于 影图+")
                .setMessage("影图+ 是一个开源 TVBox 风格的影视聚合播放器。\n\n" +
                        "版本：" + BuildConfig.VERSION_NAME + "\n" +
                        "分类筛选 + 站源抽屉切换（对齐原版交互）；CatVod Spider 运行时 + WebHome\n" +
                        "可切换播放内核：ExoPlayer / IJK / MPV\n\n" +
                        "本项目仅供学习与交流，所有视频内容来自用户自行配置的第三方源，" +
                        "开发者不提供、不存储任何影视资源。")
                .setPositiveButton("知道了", null)
                .show();
    }

    // ===== 注入地址 =====
    private void showInjectDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("输入直播源地址（m3u / txt / url）");
        input.setText(com.yingtu.plus.util.InjectHelper.getUrl(this));
        input.setSingleLine(false);
        input.setMinLines(1);
        input.setMaxLines(3);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("自定义直播源（注入地址）")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    com.yingtu.plus.util.InjectHelper.setUrl(this, input.getText().toString().trim());
                    refreshInjectLabel();
                    toast("注入地址已保存，放映页将使用此源");
                })
                .setNeutralButton("清除", (d, w) -> {
                    com.yingtu.plus.util.InjectHelper.setUrl(this, "");
                    refreshInjectLabel();
                    toast("已清除注入地址，使用订阅内置直播源");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshInjectLabel() {
        TextView tv = find(R.id.tvInject);
        if (tv == null) return;
        String url = com.yingtu.plus.util.InjectHelper.getUrl(this);
        tv.setText((url == null || url.isEmpty()) ? getString(R.string.val_off) : shorten(url));
    }

    // ===== 壁纸 =====
    private void bindWallpaperSection() {
        TextView tvWallpaper = find(R.id.tvWallpaper);
        refreshWallpaperLabel(tvWallpaper);
        find(R.id.rowWallpaper).setOnClickListener(v ->
                startActivity(new Intent(this, WallpaperActivity.class)));
    }

    private void refreshWallpaperLabel(TextView tv) {
        tv.setText(com.yingtu.plus.util.WallpaperHelper.hasCustomWallpaper(this)
                ? "自定义" : "默认");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshApiUrl();
        refreshParseName();
        refreshImportLabel();
        refreshBuiltinZoneLabel();
        refreshStartSource();
        refreshApiCache();
        refreshSearchShow();
        refreshHomeSourceCols();
        refreshSearchSite();
        refreshProgressTag();
        refreshDetailStyle();
        refreshSkipAd();
        refreshSnifferView();
        refreshAutoFloat();
        refreshCacheLocation();
        refreshRender();
        refreshScale();
        refreshBufferTimeout();
        refreshIjkBuffer();
        refreshIjkDecode();
        refreshStartPage();
        refreshSecureDns();
        refreshWallpaperFx();
        refreshCateViewType();
        refreshCateViewCols();
        TextView tv = find(R.id.tvWallpaper);
        if (tv != null) refreshWallpaperLabel(tv);
    }

    // ===== 导入本地 JSON 文件 =====
    // 链路：系统文件选择器 → 读文件 → ApiConfig.parseConfig（与订阅地址同一条解析链路）。
    private void onJsonPicked(Uri uri) {
        if (uri == null) return; // 用户取消选择
        String fileName = queryDisplayName(uri);
        if (fileName != null && !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
            toast("请选择 .json 文件");
            return;
        }
        String body;
        try {
            body = readText(uri);
        } catch (Exception e) {
            toast("文件读取失败：" + e.getMessage());
            return;
        }
        if (body == null || body.trim().isEmpty()) {
            toast("文件读取失败：文件为空");
            return;
        }
        try {
            ApiConfig.get().parseConfig(body);
        } catch (Throwable t) {
            toast("JSON 解析失败：不是有效的 TVBox 配置（" + t.getMessage() + "）");
            return;
        }
        List<SourceBean> sites = ApiConfig.get().getSourceBeanList();
        if (sites.isEmpty()) {
            toast("JSON 解析失败：配置里没有站点（sites 为空）");
            return;
        }
        Hawk.put(HawkConfig.API_CONFIG_CACHE, body);
        String name = fileName != null ? fileName : "本地 JSON 文件";
        SourceActivity.recordConfigGroup(name, body);
        // J 批：本地 JSON 导入后把新站表同步进 VodConfig
        VodConfigSync.sync();
        refreshImportLabel();
        toast("导入成功，共 " + sites.size() + " 个源");
    }

    private String readText(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("无法打开文件");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 显示最近一次导入的本地文件名；没有则显示“未导入”。订阅地址加载的组名是 URL，不算。 */
    private void refreshImportLabel() {
        if (tvImportLocalJson == null) return;
        String label = "未导入";
        List<SourceActivity.ConfigGroup> groups = SourceActivity.loadStoredGroups();
        for (int i = groups.size() - 1; i >= 0; i--) {
            String name = groups.get(i).name;
            if (name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                    && !name.toLowerCase(java.util.Locale.ROOT).startsWith("http")) {
                label = name;
                break;
            }
        }
        tvImportLocalJson.setText(label);
    }

    // ===== 工具 =====
    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static String shorten(String url) {
        if (url.length() <= 34) return url;
        return url.substring(0, 16) + "…" + url.substring(url.length() - 16);
    }
}
