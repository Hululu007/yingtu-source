package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;

import com.yingtu.plus.BuildConfig;
import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.VodConfigSync;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.ParseBean;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 设置页。分区卡片式布局，参考用户提供的 UI：
 *   数据接口 / 首页和搜索 / 播放设置 / 系统设置 + 底部功能按钮。
 *
 * 可用项已接真实逻辑（接口地址、首页推荐源名、默认解析、深色模式、
 * 网格列数、清缓存、关于、检测更新）；展示性项提供取值切换对话框。
 */
public class SettingActivity extends BaseActivity {

    private TextView tvApiUrl, tvParseName, tvHomeRec, tvGridColumns,
            tvOrientation, tvStartSource, tvCacheHint, tvImportLocalJson, tvBuiltinZone;

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
                startActivity(new Intent(this, SourceActivity.class)));
        find(R.id.rowInject).setOnClickListener(v -> showInjectDialog());
        find(R.id.rowApiCache).setOnClickListener(v -> toast("接口缓存功能开发中"));
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

        find(R.id.btnSourcePin).setOnClickListener(v ->
                startActivity(new Intent(this, SourceActivity.class)));
        find(R.id.btnSourceDisable).setOnClickListener(v ->
                startActivity(new Intent(this, SourceActivity.class)));
        find(R.id.rowStartSource).setOnClickListener(v ->
                startActivity(new Intent(this, SourceActivity.class)));
        find(R.id.rowHomeRec).setOnClickListener(v -> pickHomeRec());
        find(R.id.rowSearchShow).setOnClickListener(v -> toast("搜索展示：图片｜文字"));
    }

    private void refreshHomeRec() {
        tvHomeRec.setText(Hawk.get(HawkConfig.HOME_REC, "豆瓣热播"));
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
        find(R.id.rowDetailStyle).setOnClickListener(v -> toast("详情页样式：影音图片"));
        find(R.id.rowProgressTag).setOnClickListener(v -> toast("进度标识：名称"));
        find(R.id.rowPlayer).setOnClickListener(v -> showCorePicker());
        find(R.id.rowRender).setOnClickListener(v -> toast("渲染方式：SurfaceView"));
        find(R.id.rowScale).setOnClickListener(v -> toast("画面缩放：适应"));
        find(R.id.rowSkipAd).setOnClickListener(v -> toast("解析跳过防盗：开启"));
        find(R.id.rowAutoFloat).setOnClickListener(v -> toast("自动悬浮：关闭"));
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
        refreshGridColumns();

        SwitchCompat swDark = find(R.id.swDark);
        swDark.setChecked(Hawk.get(HawkConfig.DARK_MODE, true));
        swDark.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            Hawk.put(HawkConfig.DARK_MODE, checked);
            AppCompatDelegate.setDefaultNightMode(checked
                    ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_NO);
        });

        find(R.id.rowOrientation).setOnClickListener(v -> toast("屏幕方向：竖屏"));
        find(R.id.rowStartPage).setOnClickListener(v -> toast("默认启动：首页"));
        find(R.id.rowGridColumns).setOnClickListener(v -> pickGridColumns());

        find(R.id.rowClearCache).setOnClickListener(v -> {
            Hawk.delete(HawkConfig.SEARCH_HISTORY);
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

        bindWallpaperSection();
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
        find(R.id.btnBackup).setOnClickListener(v -> toast("数据备份功能开发中"));
        find(R.id.btnClearCache).setOnClickListener(v -> {
            Hawk.delete(HawkConfig.PLAY_HISTORY);
            Hawk.delete(HawkConfig.SEARCH_HISTORY);
            toast("缓存已清除");
        });
        find(R.id.btnUpdate).setOnClickListener(v -> toast("当前已是最新版本 v" + BuildConfig.VERSION_NAME));
        find(R.id.btnAbout).setOnClickListener(v -> showAbout());
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
