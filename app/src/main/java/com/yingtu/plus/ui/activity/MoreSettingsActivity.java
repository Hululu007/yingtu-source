package com.yingtu.plus.ui.activity;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.DiagnosticCategories;
import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.setting.PlayerSetting;
import com.yingtu.plus.setting.Setting;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.util.LanSyncHelper;
import com.yingtu.plus.util.VodResultCache;
import com.orhanobut.hawk.Hawk;

/**
 * 更多设置页（旧版“更多设置”弹窗里的项）。
 * 每一行都已接真实生效逻辑；实在做不了的项（耳机按键换曲、Frp、WebDav、XWalkView）
 * 不在本页，原因写在布局底部与回报里。
 */
public class MoreSettingsActivity extends BaseActivity {

    private TextView tvGlide, tvPlayBuffer, tvExoDecode, tvEpisodeCol, tvLyrics, tvHistoryLimit, tvUa,
            tvLogItems, tvCacheRefresh, tvSyncDevice;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_more_settings;
    }

    @Override
    protected void init() {
        find(R.id.ivBack).setOnClickListener(v -> finish());

        bindSwitch(R.id.swMoreLogo, HawkConfig.HOME_LOGO, true);
        bindSwitch(R.id.swMoreLogoRound, HawkConfig.HOME_LOGO_ROUND, true);
        bindSwitch(R.id.swMoreTitleBar, HawkConfig.HOME_TITLE_BAR, true);
        bindSwitch(R.id.swMoreSwipe, HawkConfig.HOME_SWIPE_SWITCH, false);
        bindSwitch(R.id.swMoreAutoPlay, HawkConfig.LIST_AUTO_PLAY, false);
        bindSwitch(R.id.swMoreImmersive, HawkConfig.IMMERSIVE, false);
        bindSwitch(R.id.swMoreTapMulti, HawkConfig.SOURCE_TAP_MULTI, false);
        bindSwitch(R.id.swMoreLogoView, HawkConfig.SOURCE_LOGO_VIEW, true);
        bindSwitch(R.id.swMoreDetailTitle, HawkConfig.DETAIL_VIEW1_TITLE_ONLY, false);
        bindSwitch(R.id.swMoreDlna, HawkConfig.DLNA_ALLOW, false);
        bindSwitch(R.id.swMoreCastLink2, HawkConfig.CAST_LINK2, false);
        bindSwitch(R.id.swMoreP2pExt, HawkConfig.P2P_EXTERNAL_IP, false);
        bindSwitch(R.id.swMoreIjkSub, HawkConfig.IJK_SUBTITLE, true);
        bindSwitch(R.id.swMoreIjkReplay, HawkConfig.IJK_SURFACE_REPLAY, false);
        bindSwitch(R.id.swMoreLiveToVod, HawkConfig.LIVE_TO_VOD, false);
        bindSwitch(R.id.swMoreCacheHome, HawkConfig.VOD_CACHE_HOME, true);
        bindSwitch(R.id.swMoreCacheCate, HawkConfig.VOD_CACHE_CATE, true);
        bindSwitch(R.id.swMoreCacheDetail, HawkConfig.VOD_CACHE_DETAIL, true);
        bindSwitch(R.id.swMoreBoot, HawkConfig.BOOT_START, false);
        bindSwitch(R.id.swMoreFilterSingle, HawkConfig.CATE_FILTER_SINGLE, false);
        bindSwitch(R.id.swMoreHomeNav, HawkConfig.CATE_HOME_NAV, true);
        bindSwitch(R.id.swMoreLongPress, HawkConfig.CATE_LONG_PRESS, true);
        bindSwitch(R.id.swMoreGlobalSniffer, HawkConfig.GLOBAL_SNIFFER, true);
        bindSwitch(R.id.swMoreSrcErr, HawkConfig.SRC_ERR_MSG, true);
        bindSwitch(R.id.swMoreSrcMsg, HawkConfig.SRC_MSG, true);
        bindSwitch(R.id.swMoreStripAd, HawkConfig.STRIP_AD_SEGMENTS, true);
        bindSwitch(R.id.swMoreAutoNext, HawkConfig.AUTO_NEXT_ON_ERROR, false);

        SwitchCompat swTunnel = find(R.id.swMoreTunnel);
        swTunnel.setChecked(PlayerSetting.isTunnel());
        swTunnel.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            PlayerSetting.putTunnel(checked);
            toast(checked ? "EXO隧道模式已开启，下次播放生效" : "EXO隧道模式已关闭");
        });

        tvGlide = find(R.id.tvMoreGlide);
        refreshGlide();
        find(R.id.rowMoreGlide).setOnClickListener(v -> pickGlide());

        tvPlayBuffer = find(R.id.tvMorePlayBuffer);
        refreshPlayBuffer();
        find(R.id.rowMorePlayBuffer).setOnClickListener(v -> pickPlayBuffer());

        tvExoDecode = find(R.id.tvMoreExoDecode);
        refreshExoDecode();
        find(R.id.rowMoreExoDecode).setOnClickListener(v -> pickExoDecode());

        tvEpisodeCol = find(R.id.tvMoreEpisodeCol);
        refreshEpisodeCol();
        find(R.id.rowMoreEpisodeCol).setOnClickListener(v -> pickEpisodeCol());

        tvLyrics = find(R.id.tvMoreLyrics);
        refreshLyrics();
        find(R.id.rowMoreLyrics).setOnClickListener(v -> pickLyrics());

        tvHistoryLimit = find(R.id.tvMoreHistoryLimit);
        refreshHistoryLimit();
        find(R.id.rowMoreHistoryLimit).setOnClickListener(v -> pickHistoryLimit());

        tvUa = find(R.id.tvMoreUa);
        refreshUa();
        find(R.id.rowMoreUa).setOnClickListener(v -> pickUa());

        tvLogItems = find(R.id.tvMoreLogItems);
        refreshLogItems();
        find(R.id.rowMoreLogItems).setOnClickListener(v -> pickLogItems());

        tvCacheRefresh = find(R.id.tvMoreCacheRefresh);
        refreshCacheRefresh();
        find(R.id.rowMoreCacheRefresh).setOnClickListener(v -> pickCacheRefresh());

        tvSyncDevice = find(R.id.tvMoreSyncDevice);
        find(R.id.rowMoreSyncDevice).setOnClickListener(v -> showDeviceSyncDialog());
    }

    private void bindSwitch(int switchId, String key, boolean defValue) {
        SwitchCompat sw = find(switchId);
        sw.setChecked(Hawk.get(key, defValue));
        sw.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> Hawk.put(key, checked));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void pickSingle(String title, String[] opts, int cur, java.util.function.IntConsumer onPick) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(opts, cur, (d, which) -> {
                    onPick.accept(which);
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 图片磁盘缓存策略：Glide OkGlideModule.applyOptions 读它 =====
    private static final String[] GLIDE_OPTS = {"关闭", "自动", "仅缓存原图", "仅缓存缩略"};

    private void refreshGlide() {
        // 默认值 1=自动：与 OkGlideModule.applyOptions 的默认值保持一致，
        // 用户没选过时设置页也显示“自动”，不误导。
        int v = Hawk.get(HawkConfig.GLIDE_CACHE, 1);
        tvGlide.setText(GLIDE_OPTS[v >= 0 && v < GLIDE_OPTS.length ? v : 0]);
    }

    private void pickGlide() {
        pickSingle("图片磁盘缓存策略", GLIDE_OPTS, Hawk.get(HawkConfig.GLIDE_CACHE, 1), which -> {
            Hawk.put(HawkConfig.GLIDE_CACHE, which);
            refreshGlide();
            toast("已保存，重启应用后生效");
        });
    }

    // ===== 播放缓冲时间：ExoUtil 建播放器时按它设起播缓冲 =====
    private void refreshPlayBuffer() {
        int sec = PlayerSetting.getPlayBufferTimeSec();
        tvPlayBuffer.setText(sec == 0 ? "默认" : sec + " 秒");
    }

    private void pickPlayBuffer() {
        String[] opts = {"默认", "1 秒", "2 秒", "3 秒", "5 秒", "8 秒"};
        int[] values = {0, 1, 2, 3, 5, 8};
        int cur = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == PlayerSetting.getPlayBufferTimeSec()) cur = i;
        pickSingle("播放缓冲时间", opts, cur, which -> {
            PlayerSetting.putPlayBufferTimeSec(values[which]);
            refreshPlayBuffer();
        });
    }

    // ===== EXO播放器解码：ExoUtil.buildPlayer 入口按它改 decode =====
    private static final String[] EXO_DECODE_OPTS = {"硬件优先", "软件优先", "自动"};

    private void refreshExoDecode() {
        tvExoDecode.setText(EXO_DECODE_OPTS[PlayerSetting.getExoDecode()]);
    }

    private void pickExoDecode() {
        pickSingle("EXO播放器解码", EXO_DECODE_OPTS, PlayerSetting.getExoDecode(), which -> {
            PlayerSetting.putExoDecode(which);
            refreshExoDecode();
            toast("已保存，下次播放生效");
        });
    }

    // ===== 详情列表最小列数：VideoActivity 选集跨度在标题过长时读它 =====
    private void refreshEpisodeCol() {
        tvEpisodeCol.setText(PlayerSetting.getEpisodeColumn() + " 列");
    }

    private void pickEpisodeCol() {
        String[] opts = {"1 列", "2 列"};
        pickSingle("详情列表最小列数", opts, PlayerSetting.getEpisodeColumn() - 1, which -> {
            PlayerSetting.putEpisodeColumn(which + 1);
            refreshEpisodeCol();
        });
    }

    // ===== 歌词转换显示模式：LyricsOverlayView 读它 =====
    private static final String[] LYRICS_OPTS = {"单行", "双行交替显示", "关闭"};

    private void refreshLyrics() {
        int v = Hawk.get(HawkConfig.LYRICS_MODE, 1);
        tvLyrics.setText(LYRICS_OPTS[v >= 0 && v < LYRICS_OPTS.length ? v : 1]);
    }

    private void pickLyrics() {
        pickSingle("歌词转换显示模式", LYRICS_OPTS, Hawk.get(HawkConfig.LYRICS_MODE, 1), which -> {
            Hawk.put(HawkConfig.LYRICS_MODE, which);
            refreshLyrics();
        });
    }

    // ===== 观看历史记录数：HistoryHelper.save / 两处 saveHistory 按它截断 =====
    private static final String[] HISTORY_OPTS = {"100 | 10", "200 | 20", "300 | 30", "500 | 50"};

    private void refreshHistoryLimit() {
        int v = Hawk.get(HawkConfig.HISTORY_LIMIT, 2);
        tvHistoryLimit.setText(HISTORY_OPTS[v >= 0 && v < HISTORY_OPTS.length ? v : 2]);
    }

    private void pickHistoryLimit() {
        pickSingle("观看历史记录数", HISTORY_OPTS, Hawk.get(HawkConfig.HISTORY_LIMIT, 2), which -> {
            Hawk.put(HawkConfig.HISTORY_LIMIT, which);
            refreshHistoryLimit();
        });
    }

    // ===== 日志输出项目：DebugLogStore 的 6 类开关 + 总开关 =====
    private static final DiagnosticCategories.Category[] LOG_CATS = {
            DiagnosticCategories.Category.PLAYBACK,
            DiagnosticCategories.Category.NETWORK,
            DiagnosticCategories.Category.VIDEO,
            DiagnosticCategories.Category.AUDIO,
            DiagnosticCategories.Category.SUBTITLE,
            DiagnosticCategories.Category.SYSTEM};
    private static final String[] LOG_CAT_NAMES = {"播放流程", "网络请求", "视频画面", "音频输出", "字幕脚本", "内核设备"};

    private void refreshLogItems() {
        if (!DebugLogStore.isEnabled()) {
            tvLogItems.setText("已关闭");
            return;
        }
        int n = 0;
        for (DiagnosticCategories.Category c : LOG_CATS) if (DebugLogStore.categoryEnabled(c)) n++;
        tvLogItems.setText("已开启（" + n + " 项）");
    }

    private void pickLogItems() {
        boolean[] checked = new boolean[LOG_CATS.length];
        for (int i = 0; i < LOG_CATS.length; i++) checked[i] = DebugLogStore.categoryEnabled(LOG_CATS[i]);
        new AlertDialog.Builder(this)
                .setTitle("日志输出项目")
                .setMultiChoiceItems(LOG_CAT_NAMES, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("保存", (d, w) -> {
                    boolean any = false;
                    for (int i = 0; i < LOG_CATS.length; i++) {
                        DebugLogStore.setCategory(LOG_CATS[i], checked[i]);
                        if (checked[i]) any = true;
                    }
                    DebugLogStore.setEnabled(any);
                    refreshLogItems();
                    toast(any ? "日志已开启" : "日志已关闭");
                })
                .setNeutralButton("关闭日志", (d, w) -> {
                    DebugLogStore.setEnabled(false);
                    refreshLogItems();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 后台刷新源数据缓存间隔时间：VodResultCache.scheduleRefresh 按它重取 =====
    private static final String[] CACHE_REFRESH_OPTS = {"关闭", "1 小时", "6 小时", "12 小时", "24 小时"};
    private static final int[] CACHE_REFRESH_VALUES = {0, 1, 6, 12, 24};

    private void refreshCacheRefresh() {
        int hours = Hawk.get(HawkConfig.VOD_CACHE_REFRESH_HOURS, 0);
        int idx = 0;
        for (int i = 0; i < CACHE_REFRESH_VALUES.length; i++) if (CACHE_REFRESH_VALUES[i] == hours) idx = i;
        tvCacheRefresh.setText(CACHE_REFRESH_OPTS[idx]);
    }

    private void pickCacheRefresh() {
        int hours = Hawk.get(HawkConfig.VOD_CACHE_REFRESH_HOURS, 0);
        int cur = 0;
        for (int i = 0; i < CACHE_REFRESH_VALUES.length; i++) if (CACHE_REFRESH_VALUES[i] == hours) cur = i;
        pickSingle("后台刷新源数据缓存间隔时间", CACHE_REFRESH_OPTS, cur, which -> {
            Hawk.put(HawkConfig.VOD_CACHE_REFRESH_HOURS, CACHE_REFRESH_VALUES[which]);
            VodResultCache.scheduleRefresh();
            refreshCacheRefresh();
        });
    }

    // ===== 设备间同步历史播放信息：复用局域网同步（LanSyncHelper），打开时历史预选 =====
    private void showDeviceSyncDialog() {
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
        boolean[] cats = {false, true, false, false};
        cbApi.setOnCheckedChangeListener((b, c) -> cats[0] = c);
        cbHistory.setOnCheckedChangeListener((b, c) -> cats[1] = c);
        cbCollect.setOnCheckedChangeListener((b, c) -> cats[2] = c);
        cbConfig.setOnCheckedChangeListener((b, c) -> cats[3] = c);
        cbApi.setChecked(false);
        cbHistory.setChecked(true);
        cbCollect.setChecked(false);
        cbConfig.setChecked(false);
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
                    (ok, msg) -> runOnUiThread(() -> toast(msg)));
        });
        dialog.show();
    }

    // ===== 默认UA：Setting.getUa() 已被 PlaySpec 默认头读取，存 Prefers "ua" =====
    private void refreshUa() {
        String ua = Setting.getUa();
        tvUa.setText(TextUtils.isEmpty(ua) ? "未设置" : (ua.length() <= 24 ? ua : ua.substring(0, 24) + "…"));
    }

    private void pickUa() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("输入默认 User-Agent，留空为系统默认");
        input.setText(Setting.getUa());
        new AlertDialog.Builder(this)
                .setTitle("默认UA")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    Setting.putUa(input.getText().toString());
                    refreshUa();
                })
                .setNeutralButton("清除", (d, w) -> {
                    Setting.putUa("");
                    refreshUa();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
