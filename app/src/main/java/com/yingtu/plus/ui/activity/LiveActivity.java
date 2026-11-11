package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.R;
import com.yingtu.plus.api.LiveSources;
import com.yingtu.plus.api.SiteApi;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.LiveChannel;
import com.yingtu.plus.bean.LiveGroup;
import com.yingtu.plus.ui.adapter.LiveChannelAdapter;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.ui.adapter.LiveGroupAdapter;
import com.yingtu.plus.util.OkGo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 直播页：FongMiTV 式竖屏，上半是内嵌播放器（直接播当前频道），
 * 下方是分组 + 频道列表，切频道不跳页、就在本页换流。
 *
 * 布局结构参考渔老 webhtv 的 activity_live.xml（上播放器 9 份、下列表 14 份），
 * 代码自己写，不搬原代码。播放器是树里现成的 media3 ExoPlayer 直播
 * （VideoActivity 是 6619 行的点播详情播放页，没法拆成内嵌组件，
 * 复用不了才走这条路）；全屏按钮跳现有 VideoActivity。
 *
 * 直播源：本树不内置任何直播源。生效的源只来自用户自己提供的三处
 * （LiveSources：注入直播地址、订阅配置 lives、直播仓库 LIVE_REPO）。
 * 三处都空时显示“没有可用的直播源”空态，不闪退；“能播”要用户提供源后才成立。
 *
 * 与 dm17 的区别（剥离）：
 * - 删内置 4 源与 BuiltinOverrides 覆盖清单，LiveSources 只合用户提供的源；
 * - 删蜘蛛型直播（本树没有蜘蛛型直播的蜘蛛类与 jar）；
 * - 删站源禁用-直播接口检查（上游没有 SiteFilter/设置开关，本树也不设）；
 * - 修 dm17 的一个隐患：LIST 源进 showGroup 时 groupLoaded 是空表，
 *   原样 groupLoaded.get(index) 会 IndexOutOfBounds 闪退，这里先判长度再取。
 */
public class LiveActivity extends BaseActivity {

    /** 节目单点频道进来时用：要打开的源 id（inject_live / config_xxx / repo_xxx）。 */
    public static final String EXTRA_SOURCE_ID = "source_id";
    /** 节目单点频道进来时用：要找的频道名（节目单名录里的 note）。 */
    public static final String EXTRA_CHANNEL_NAME = "channel_name";
    /** 节目单点频道进来时用：频道别名表，逗号分隔（名录 name 字段原样）。 */
    public static final String EXTRA_CHANNEL_ALIASES = "channel_aliases";

    private PlayerView playerView;
    private ExoPlayer player;
    private ProgressBar progress;
    private ProgressBar playerProgress;
    private TextView tvEmpty;
    private TextView tvPlayerTip;
    private TextView tvCurrent;
    private LiveGroupAdapter groupAdapter;
    private LiveChannelAdapter channelAdapter;

    private List<LiveSources.Source> sources = new ArrayList<>();
    private LiveSources.Source currentSource;
    private final List<LiveGroup> groups = new ArrayList<>();
    private List<LiveSources.Platform> platforms = new ArrayList<>();
    private final List<Boolean> groupLoaded = new ArrayList<>();
    private LiveChannel currentChannel;
    private int currentGroupIndex = -1;
    private int currentChannelIndex = -1;
    private boolean sourceSpinnerReady = false;
    /** 下一频道列表轻探的节流：同一地址 60 秒内不重复探。 */
    private String lastWarmedUrl;
    private long lastWarmedAt;
    /** 节目单带过来的待找频道别名：频道列表一加载好就按别名找台，找到就播，找不到如实提示。 */
    private final List<String> pendingAliases = new ArrayList<>();
    private boolean pendingToastShown = false;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_live;
    }

    @Override
    protected void init() {
        progress = find(R.id.progress);
        playerProgress = find(R.id.playerProgress);
        tvEmpty = find(R.id.tvEmpty);
        tvPlayerTip = find(R.id.tvPlayerTip);
        tvCurrent = find(R.id.tvCurrent);
        find(R.id.ivBack).setOnClickListener(v -> finish());
        find(R.id.btnLine).setOnClickListener(v -> switchLine());
        find(R.id.btnFull).setOnClickListener(v -> openFullscreen());
        find(R.id.btnEpg).setOnClickListener(v -> startActivity(new Intent(this, EpgActivity.class)));
        // 节目单点频道进来：把别名记下来，等频道列表加载好再找台。
        String aliasesExtra = getIntent().getStringExtra(EXTRA_CHANNEL_ALIASES);
        if (!TextUtils.isEmpty(aliasesExtra)) {
            for (String alias : aliasesExtra.split(",")) {
                String a = alias.trim();
                if (!TextUtils.isEmpty(a) && !pendingAliases.contains(a)) pendingAliases.add(a);
            }
        }
        String nameExtra = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        if (!TextUtils.isEmpty(nameExtra) && !pendingAliases.contains(nameExtra.trim())) {
            pendingAliases.add(0, nameExtra.trim());
        }

        playerView = find(R.id.exo);
        player = new ExoPlayer.Builder(this).build();
        player.setPlayWhenReady(true);
        playerView.setPlayer(player);
        playerView.setKeepScreenOn(true);
        // PlayerView 自带全屏按钮：没接监听就不显示/不响应（media3 只有接了监听才亮按钮）；
        // 点它走全屏页 openFullscreen()。
        playerView.setFullscreenButtonClickListener(isFullscreen -> openFullscreen());
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                playerProgress.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY) tvPlayerTip.setVisibility(View.GONE);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                playerProgress.setVisibility(View.GONE);
                if (!tryNextLine()) {
                    tvPlayerTip.setVisibility(View.VISIBLE);
                    tvPlayerTip.setText("播放失败，请换频道或线路");
                }
            }
        });

        RecyclerView rvGroup = find(R.id.rvGroup);
        rvGroup.setLayoutManager(new LinearLayoutManager(this));
        groupAdapter = new LiveGroupAdapter();
        groupAdapter.setOnGroupSelected(this::showGroup);
        rvGroup.setAdapter(groupAdapter);

        RecyclerView rvChannel = find(R.id.rvChannel);
        rvChannel.setLayoutManager(new LinearLayoutManager(this));
        channelAdapter = new LiveChannelAdapter();
        channelAdapter.setOnChannelClick(this::playChannel);
        channelAdapter.setOnChannelLongClick(this::playChannelDirect);
        rvChannel.setAdapter(channelAdapter);

        sources = LiveSources.getEffectiveSources();
        List<String> names = new ArrayList<>();
        for (LiveSources.Source s : sources) names.add(s.name);
        // 节目单点进来：直接选中该源（找不到就落第一个）。
        int startIndex = 0;
        String wantId = getIntent().getStringExtra(EXTRA_SOURCE_ID);
        if (wantId != null) {
            for (int i = 0; i < sources.size(); i++) {
                if (sources.get(i).id.equals(wantId)) {
                    startIndex = i;
                    break;
                }
            }
        }
        Spinner spSource = find(R.id.spSource);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        // 下拉项跟主题一个颜色（渐变末尾色），不再用系统黑底项
        spinnerAdapter.setDropDownViewResource(R.layout.item_live_source_dropdown);
        spSource.setAdapter(spinnerAdapter);
        spSource.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!sourceSpinnerReady) {
                    sourceSpinnerReady = true;
                    return;
                }
                loadSource(sources.get(position));
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        if (!sources.isEmpty()) {
            spSource.setSelection(startIndex);
            loadSource(sources.get(startIndex));
        } else showEmpty("没有可用的直播源\n请先提供直播源：订阅配置里加 lives 列表地址，或填注入直播地址");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null && currentChannel != null) player.setPlayWhenReady(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.setPlayWhenReady(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private boolean isPingtai() {
        return currentSource != null && currentSource.protocol == LiveSources.Protocol.PINGTAI;
    }

    private void loadSource(LiveSources.Source source) {
        currentSource = source;
        stopPlayback();
        groups.clear();
        platforms = new ArrayList<>();
        groupLoaded.clear();
        currentGroupIndex = -1;
        currentChannelIndex = -1;
        currentChannel = null;
        tvCurrent.setText(source.name);
        groupAdapter.submit(groups);
        channelAdapter.submit(new ArrayList<>());
        progress.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        if (source.protocol == LiveSources.Protocol.PINGTAI) {
            LiveSources.loadPlatforms(source, new LiveSources.OnPlatforms() {
                @Override
                public void onResult(List<LiveSources.Platform> list) {
                    if (currentSource != source) return;
                    platforms = list;
                    List<LiveGroup> shells = new ArrayList<>();
                    for (LiveSources.Platform p : list) {
                        shells.add(new LiveGroup(p.name));
                        groupLoaded.add(false);
                    }
                    bind(shells);
                }

                @Override
                public void onError(String msg) {
                    if (currentSource != source) return;
                    showEmpty(msg);
                }
            });
        } else {
            loadListSource(source);
        }
    }

    private void loadListSource(LiveSources.Source source) {
        if (source.endpoints.isEmpty()) {
            showEmpty("该直播源没有可用地址");
            return;
        }
        String url = source.endpoints.get(0);
        Request request = new Request.Builder().url(url).build();
        OkGo.client().newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                OkGo.main(() -> {
                    if (currentSource != source) return;
                    showEmpty("直播列表加载失败：" + e.getMessage());
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                List<LiveGroup> parsed = LiveSources.parseListText(url, body);
                OkGo.main(() -> {
                    if (currentSource != source) return;
                    bind(parsed);
                });
            }
        });
    }

    private void bind(List<LiveGroup> parsed) {
        progress.setVisibility(View.GONE);
        groups.clear();
        groups.addAll(parsed);
        if (groups.isEmpty()) {
            showEmpty("直播列表为空或格式不支持");
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        groupAdapter.submit(groups);
        showGroup(0);
        tryPlayPendingChannel();
    }

    /**
     * 节目单带过来的待找频道：按别名在已加载的分组里找。
     * 先精确匹配（别名 == 频道名），再包含匹配。找到就切分组播它；
     * pingtai 源的分组是懒加载（点分组才拉频道），目标分组还没拉到就先切过去触发加载，
     * 等 onResult 回来再找一次；全加载完还找不到就如实提示并清掉待找。
     */
    private void tryPlayPendingChannel() {
        if (pendingAliases.isEmpty() || groups.isEmpty()) return;
        for (int gi = 0; gi < groups.size(); gi++) {
            for (LiveChannel channel : groups.get(gi).channels) {
                for (String alias : pendingAliases) {
                    if (alias.equals(channel.name)) {
                        playPendingFound(gi, channel);
                        return;
                    }
                }
            }
        }
        for (int gi = 0; gi < groups.size(); gi++) {
            for (LiveChannel channel : groups.get(gi).channels) {
                for (String alias : pendingAliases) {
                    if (channel.name.contains(alias) || alias.contains(channel.name)) {
                        playPendingFound(gi, channel);
                        return;
                    }
                }
            }
        }
        if (isPingtai()) {
            for (int i = 0; i < groupLoaded.size(); i++) {
                if (!groupLoaded.get(i)) {
                    if (!pendingToastShown) {
                        pendingToastShown = true;
                        Toast.makeText(this, "节目单频道正在加载，稍后在列表里找", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
            }
        }
        pendingAliases.clear();
        Toast.makeText(this, "当前直播源里没有这个频道", Toast.LENGTH_SHORT).show();
    }

    private void playPendingFound(int groupIndex, LiveChannel channel) {
        pendingAliases.clear();
        showGroup(groupIndex);
        playChannel(channel);
    }

    private void showGroup(int index) {
        if (index < 0 || index >= groups.size()) return;
        currentGroupIndex = index;
        groupAdapter.setSelected(index);
        // 注意：LIST 源的 groupLoaded 是空表（只有 PINGTAI 源才逐组记加载态），
        // 必须先判长度再取，否则 IndexOutOfBounds 闪退（dm17 原样取会闪退，见头注）。
        if (index < groupLoaded.size() && !groupLoaded.get(index) && isPingtai()) {
            loadPingtaiGroup(index);
            return;
        }
        channelAdapter.submit(groups.get(index).channels);
        int selectedInGroup = currentChannel == null ? -1 : groups.get(index).channels.indexOf(currentChannel);
        channelAdapter.setSelected(selectedInGroup);
        if (currentChannel == null && !groups.get(index).channels.isEmpty()) {
            playChannel(groups.get(index).channels.get(0));
        }
    }

    private void loadPingtaiGroup(int index) {
        progress.setVisibility(View.VISIBLE);
        channelAdapter.submit(new ArrayList<>());
        LiveSources.Platform platform = platforms.get(index);
        LiveSources.Source src = currentSource;
        LiveSources.loadPingtaiChannels(src, platform, new LiveSources.OnChannels() {
            @Override
            public void onResult(List<LiveChannel> channels) {
                if (currentSource != src || index >= groups.size() || index >= groupLoaded.size()) return;
                progress.setVisibility(View.GONE);
                groupLoaded.set(index, true);
                groups.get(index).channels.clear();
                groups.get(index).channels.addAll(channels);
                if (currentGroupIndex != index) return;
                channelAdapter.submit(groups.get(index).channels);
                channelAdapter.setSelected(groups.get(index).channels.indexOf(currentChannel));
                if (currentChannel == null && !channels.isEmpty()) playChannel(channels.get(0));
                tryPlayPendingChannel();
            }

            @Override
            public void onError(String msg) {
                if (currentSource != src) return;
                progress.setVisibility(View.GONE);
                Toast.makeText(LiveActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void playChannel(LiveChannel channel) {
        // 直播短按转点播（Hawk LIVE_TO_VOD，默认关；本树没有设置开关，默认即关）：
        // 开时点频道按台名进搜索页找点播（长按始终直接换台）
        boolean toVod = false;
        try {
            toVod = Hawk.get(HawkConfig.LIVE_TO_VOD, false);
        } catch (Throwable ignored) {
        }
        if (toVod && channel.name != null && !channel.name.isEmpty()) {
            Intent i = new Intent(this, SearchActivity.class);
            i.putExtra("keyword", channel.name);
            startActivity(i);
            return;
        }
        playChannelDirect(channel);
    }

    /** 直接换台（短按转点播关时走这里，长按始终走这里）。 */
    private void playChannelDirect(LiveChannel channel) {
        if (channel == null || channel.urls.isEmpty()) {
            Toast.makeText(this, "该频道没有可播地址", Toast.LENGTH_SHORT).show();
            return;
        }
        currentChannel = channel;
        if (currentGroupIndex >= 0 && currentGroupIndex < groups.size()) {
            currentChannelIndex = groups.get(currentGroupIndex).channels.indexOf(channel);
        }
        channelAdapter.setSelected(currentChannelIndex);
        tvCurrent.setText(channel.name);
        tvPlayerTip.setVisibility(View.GONE);
        playCurrentUrl();
    }

    private void playCurrentUrl() {
        if (player == null || currentChannel == null) return;
        String url = currentChannel.currentUrl();
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, "该频道没有可播地址", Toast.LENGTH_SHORT).show();
            return;
        }
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory();
        if (!TextUtils.isEmpty(currentChannel.ua)) httpFactory.setUserAgent(currentChannel.ua);
        // ExoPlayer 实例没有换源工厂的方法（那是 Builder 的方法）：
        // 用该工厂直接造出 MediaSource 再交给已建好的 player。
        MediaSource mediaSource = new DefaultMediaSourceFactory(httpFactory).createMediaSource(MediaItem.fromUri(url));
        player.setMediaSource(mediaSource);
        player.prepare();
        player.play();
        // 本台开播后，顺带轻探下一台的播放列表（只取头段、只 1 个台、60 秒内不重复探），
        // 把 DNS/握手先热起来，切台时少等一截；失败静默。
        warmNextChannelPlaylist();
    }

    /** 下一频道列表轻探：后台 GET 一下 m3u8/直链的头段就关掉，不下整段。 */
    private void warmNextChannelPlaylist() {
        try {
            if (currentGroupIndex < 0 || currentGroupIndex >= groups.size()) return;
            List<LiveChannel> list = groups.get(currentGroupIndex).channels;
            int next = currentChannelIndex + 1;
            if (next < 0 || next >= list.size()) return;
            LiveChannel ch = list.get(next);
            if (ch == null) return;
            final String url = ch.currentUrl();
            if (TextUtils.isEmpty(url)) return;
            long now = System.currentTimeMillis();
            if (TextUtils.equals(url, lastWarmedUrl) && now - lastWarmedAt < 60 * 1000L) return;
            lastWarmedUrl = url;
            lastWarmedAt = now;
            Request request = new Request.Builder().url(url).header("Range", "bytes=0-0").build();
            OkGo.client().newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    // 轻探失败静默：切台时按原流程现取。
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.body() != null) response.body().close();
                    } catch (Exception ignored) {
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    /** 播放失败时先试下一条线路，返回是否还有线路可试。 */
    private boolean tryNextLine() {
        if (currentChannel == null || !currentChannel.hasMultiLine()) return false;
        if (currentChannel.lineIndex + 1 >= currentChannel.urls.size()) return false;
        currentChannel.lineIndex++;
        tvPlayerTip.setVisibility(View.GONE);
        playCurrentUrl();
        return true;
    }

    private void switchLine() {
        if (currentChannel == null || !currentChannel.hasMultiLine()) {
            Toast.makeText(this, "当前频道只有一条线路", Toast.LENGTH_SHORT).show();
            return;
        }
        currentChannel.lineIndex = (currentChannel.lineIndex + 1) % currentChannel.urls.size();
        Toast.makeText(this, "切换到线路 " + (currentChannel.lineIndex + 1), Toast.LENGTH_SHORT).show();
        playCurrentUrl();
    }

    private void openFullscreen() {
        if (currentChannel == null || TextUtils.isEmpty(currentChannel.currentUrl())) return;
        VideoActivity.start(this, SiteApi.PUSH, currentChannel.currentUrl(), currentChannel.name);
    }

    private void stopPlayback() {
        if (player != null) player.stop();
        tvPlayerTip.setVisibility(View.GONE);
        playerProgress.setVisibility(View.GONE);
    }

    private void showEmpty(String msg) {
        progress.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText(msg);
    }
}
