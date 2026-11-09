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
import com.yingtu.plus.api.BuiltinLiveSources;
import com.yingtu.plus.api.SiteApi;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.LiveChannel;
import com.yingtu.plus.bean.LiveGroup;
import com.yingtu.plus.ui.adapter.LiveChannelAdapter;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.util.SiteFilter;
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
 * 直播源：订阅配置有 lives 地址就用订阅的，没有就用内置 4 源
 * （BuiltinLiveSources：3 个 pingtai 接口现取平台/频道 + 内置 m3u）。
 */
public class LiveActivity extends BaseActivity {

    /** 内置区列表点直播源进来时用：要打开的源 id（xiuguo / maiyoux / bnzb / user_m3u）。 */
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

    private List<BuiltinLiveSources.Source> sources = new ArrayList<>();
    private BuiltinLiveSources.Source currentSource;
    private final List<LiveGroup> groups = new ArrayList<>();
    private List<BuiltinLiveSources.Platform> platforms = new ArrayList<>();
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
        // 接法跟 DetailActivity/DramaPlayerActivity 同款，点它走全屏页 openFullscreen()。
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

        sources = BuiltinLiveSources.getEffectiveSources();
        List<String> names = new ArrayList<>();
        for (BuiltinLiveSources.Source s : sources) names.add(s.name);
        // 内置区列表点进来：直接选中该源（找不到就落第一个）。
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
        } else showEmpty("没有可用的直播源");
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
        return currentSource != null && currentSource.protocol == BuiltinLiveSources.Protocol.PINGTAI;
    }

    private boolean isSpider() {
        return currentSource != null && currentSource.protocol == BuiltinLiveSources.Protocol.SPIDER;
    }

    private void loadSource(BuiltinLiveSources.Source source) {
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
        // 站源禁用-直播接口（设置 → 首页和搜索）：远程直播源被禁用时不加载
        if (source.id != null
                && (source.id.startsWith("inject_live") || source.id.startsWith("config_") || source.id.startsWith("repo_"))
                && SiteFilter.isDisabled(HawkConfig.DISABLE_LIVE)) {
            progress.setVisibility(View.GONE);
            showEmpty("直播接口已在设置里禁用");
            return;
        }
        if (source.protocol == BuiltinLiveSources.Protocol.PINGTAI) {
            BuiltinLiveSources.loadPlatforms(source, new BuiltinLiveSources.OnPlatforms() {
                @Override
                public void onResult(List<BuiltinLiveSources.Platform> list) {
                    if (currentSource != source) return;
                    platforms = list;
                    List<LiveGroup> shells = new ArrayList<>();
                    for (BuiltinLiveSources.Platform p : list) {
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
        } else if (source.protocol == BuiltinLiveSources.Protocol.SPIDER) {
            BuiltinLiveSources.loadSpiderCategories(source, new BuiltinLiveSources.OnPlatforms() {
                @Override
                public void onResult(List<BuiltinLiveSources.Platform> list) {
                    if (currentSource != source) return;
                    platforms = list;
                    List<LiveGroup> shells = new ArrayList<>();
                    for (BuiltinLiveSources.Platform p : list) {
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

    private void loadListSource(BuiltinLiveSources.Source source) {
        if (!TextUtils.isEmpty(source.assetPath)) {
            new Thread(() -> {
                try {
                    String text = BuiltinLiveSources.readAsset(this, source.assetPath);
                    List<LiveGroup> parsed = BuiltinLiveSources.parseListText(source.assetPath, text);
                    OkGo.main(() -> {
                        if (currentSource != source) return;
                        bind(parsed);
                    });
                } catch (Throwable e) {
                    OkGo.main(() -> {
                        if (currentSource != source) return;
                        showEmpty("内置直播列表读取失败：" + e.getMessage());
                    });
                }
            }).start();
            return;
        }
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
                List<LiveGroup> parsed = BuiltinLiveSources.parseListText(url, body);
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
     * pingtai / 蜘蛛源的分组是懒加载（点分组才拉频道），目标分组还没拉到就先切过去触发加载，
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
        if (isPingtai() || isSpider()) {
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
        if (!groupLoaded.get(index) && (isPingtai() || isSpider())) {
            if (isSpider()) loadSpiderGroup(index);
            else loadPingtaiGroup(index);
            return;
        }
        channelAdapter.submit(groups.get(index).channels);
        int selectedInGroup = currentChannel == null ? -1 : groups.get(index).channels.indexOf(currentChannel);
        channelAdapter.setSelected(selectedInGroup);
        if (currentChannel == null && !groups.get(index).channels.isEmpty()) {
            playChannel(groups.get(index).channels.get(0));
        }
        // 分组已加载好：把后面 2 个蜘蛛频道先解析成真地址，点台不用现等。
        preloadSpiderChannels(groups.get(index).channels);
    }

    private void loadPingtaiGroup(int index) {
        progress.setVisibility(View.VISIBLE);
        channelAdapter.submit(new ArrayList<>());
        BuiltinLiveSources.Platform platform = platforms.get(index);
        BuiltinLiveSources.Source src = currentSource;
        BuiltinLiveSources.loadPingtaiChannels(src, platform, new BuiltinLiveSources.OnChannels() {
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
                // 分组刚拉好：把后面 2 个蜘蛛频道先解析成真地址，点台不用现等。
                preloadSpiderChannels(channels);
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

    private void loadSpiderGroup(int index) {
        progress.setVisibility(View.VISIBLE);
        channelAdapter.submit(new ArrayList<>());
        BuiltinLiveSources.Platform platform = platforms.get(index);
        BuiltinLiveSources.Source src = currentSource;
        BuiltinLiveSources.loadSpiderChannels(src, platform, new BuiltinLiveSources.OnChannels() {
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
                // 蜘蛛分组刚拉好：把后面 2 个蜘蛛频道先解析成真地址，点台不用现等。
                preloadSpiderChannels(channels);
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

    /** 蜘蛛频道预解析：分组加载好后，把后面 2 个还是 kaflive:// 标记的频道
     * 先解析成真地址存回频道（点台时 playChannel 直接走真地址，不用现等蜘蛛）。
     * 只做 2 个、不整组扫；已在播/正在解析的跳过；失败静默（点台时按原流程现解析）。 */
    private void preloadSpiderChannels(List<LiveChannel> channels) {
        if (channels == null || channels.isEmpty()) return;
        final BuiltinLiveSources.Source src = currentSource;
        if (src == null) return;
        int done = 0;
        for (final LiveChannel ch : channels) {
            if (done >= 2) break;
            if (ch == null || ch == currentChannel) continue;
            String mark = ch.currentUrl();
            if (TextUtils.isEmpty(mark) || !mark.startsWith(BuiltinLiveSources.SPIDER_MARK_PREFIX)) continue;
            done++;
            BuiltinLiveSources.resolveSpiderPlay(src, mark, new BuiltinLiveSources.OnUrl() {
                @Override
                public void onResult(String url) {
                    if (currentSource != src) return;
                    // 还没被人点过（还是标记）才替换；已解析过的不要反复写。
                    String now = ch.currentUrl();
                    if (!TextUtils.isEmpty(now) && now.startsWith(BuiltinLiveSources.SPIDER_MARK_PREFIX)) {
                        ch.urls.clear();
                        ch.urls.add(url);
                        ch.lineIndex = 0;
                    }
                }

                @Override
                public void onError(String msg) {
                    // 预解析失败静默：点台时按原流程现解析。
                }
            });
        }
    }

    private void playChannel(LiveChannel channel) {
        // 直播短按转点播（设置 → 更多设置）：开时点频道按台名进搜索页找点播（长按始终直接换台）
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
        String first = channel.currentUrl();
        if (first != null && first.startsWith(BuiltinLiveSources.SPIDER_MARK_PREFIX)) {
            resolveSpiderChannel(channel);
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

    /** 蜘蛛型直播：频道地址是标记（kaflive://room/<roomId>），先现解析成真流再播。 */
    private void resolveSpiderChannel(final LiveChannel channel) {
        currentChannel = channel;
        if (currentGroupIndex >= 0 && currentGroupIndex < groups.size()) {
            currentChannelIndex = groups.get(currentGroupIndex).channels.indexOf(channel);
        }
        channelAdapter.setSelected(currentChannelIndex);
        tvCurrent.setText(channel.name);
        tvPlayerTip.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        final BuiltinLiveSources.Source src = currentSource;
        BuiltinLiveSources.resolveSpiderPlay(src, channel.currentUrl(), new BuiltinLiveSources.OnUrl() {
            @Override
            public void onResult(String url) {
                if (currentSource != src || currentChannel != channel) return;
                progress.setVisibility(View.GONE);
                channel.urls.clear();
                channel.urls.add(url);
                channel.lineIndex = 0;
                playCurrentUrl();
            }

            @Override
            public void onError(String msg) {
                if (currentSource != src || currentChannel != channel) return;
                progress.setVisibility(View.GONE);
                tvPlayerTip.setVisibility(View.VISIBLE);
                tvPlayerTip.setText("解析失败：" + msg);
            }
        });
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
            if (TextUtils.isEmpty(url) || url.startsWith(BuiltinLiveSources.SPIDER_MARK_PREFIX)) return;
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
        if (currentChannel.currentUrl().startsWith(BuiltinLiveSources.SPIDER_MARK_PREFIX)) {
            Toast.makeText(this, "正在解析线路，稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
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
