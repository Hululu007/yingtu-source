package com.yingtu.plus.ui.activity;

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

import com.yingtu.plus.R;
import com.yingtu.plus.api.BuiltinLiveSources;
import com.yingtu.plus.api.SiteApi;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.LiveChannel;
import com.yingtu.plus.bean.LiveGroup;
import com.yingtu.plus.ui.adapter.LiveChannelAdapter;
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

        playerView = find(R.id.exo);
        player = new ExoPlayer.Builder(this).build();
        player.setPlayWhenReady(true);
        playerView.setPlayer(player);
        playerView.setKeepScreenOn(true);
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
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
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
    }

    private void showGroup(int index) {
        if (index < 0 || index >= groups.size()) return;
        currentGroupIndex = index;
        groupAdapter.setSelected(index);
        if (isPingtai() && !groupLoaded.get(index)) {
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
        if (channel.urls.isEmpty()) return;
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
        if (TextUtils.isEmpty(url)) return;
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory();
        if (!TextUtils.isEmpty(currentChannel.ua)) httpFactory.setUserAgent(currentChannel.ua);
        // ExoPlayer 实例没有换源工厂的方法（那是 Builder 的方法）：
        // 用该工厂直接造出 MediaSource 再交给已建好的 player。
        MediaSource mediaSource = new DefaultMediaSourceFactory(httpFactory).createMediaSource(MediaItem.fromUri(url));
        player.setMediaSource(mediaSource);
        player.prepare();
        player.play();
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
