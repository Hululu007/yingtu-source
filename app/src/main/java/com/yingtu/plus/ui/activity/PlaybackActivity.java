package com.yingtu.plus.ui.activity;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.ui.PlayerView;
import androidx.viewbinding.ViewBinding;

import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.History;
import com.yingtu.plus.bean.Result;
import com.yingtu.plus.player.PlayerManager;
import com.yingtu.plus.player.engine.PlaySpec;
import com.yingtu.plus.player.exo.ExoUtil;
import com.yingtu.plus.service.PlaybackService;
import com.yingtu.plus.ui.custom.CustomSeekView;

/**
 * 播放页基类：VideoActivity 的父类。
 *
 * 对照 webhtv 的 PlaybackActivity 结构、按影图树内现有类型重写：
 * 播放器归本页持有（PlayerManager 由本页创建，回调转给下方钩子），
 * PlaybackService 只保留绑定与上下集回调；影图树内没有 MediaSession，
 * controller() 直接返回底层 media3 Player（有 seekTo(long)，够 VideoActivity
 * 的外部播放器回调用）。
 *
 * BaseActivity 要求实现 getLayoutId()/init()：本页走 ViewBinding，
 * getLayoutId() 返回 0 不铺布局，init() 为空，真正的铺设在
 * onCreate() 里调 getBinding() 后做（此时才有 savedInstanceState 可传给
 * initView）。
 */
public abstract class PlaybackActivity extends BaseActivity implements ServiceConnection {

    private PlaybackService service;
    private PlayerManager playerManager;
    private Player.Listener playerListener;
    private boolean bound;
    private boolean audioOnly;
    private boolean redirect;
    private boolean playbackExiting;
    private boolean stop;
    private boolean lock;

    protected PlaybackService service() {
        return service;
    }

    protected PlayerManager player() {
        return playerManager;
    }

    /** 影图无 MediaSession：返回底层 media3 Player，供外部回调用 seekTo。 */
    protected Player controller() {
        return playerManager == null ? null : playerManager.getPlayer();
    }

    protected boolean isRedirect() {
        return redirect;
    }

    protected void setRedirect(boolean redirect) {
        this.redirect = redirect;
        if (service != null) service.setNavigationCallback(redirect ? null : getNavigationCallback());
    }

    protected boolean isPlaybackExiting() {
        return playbackExiting;
    }

    protected void markPlaybackExiting() {
        this.playbackExiting = true;
    }

    protected void finishPlayback() {
        markPlaybackExiting();
        stopPlayback();
        finish();
    }

    protected void stopPlayback() {
        if (playerManager != null) playerManager.stop();
    }

    protected void updateNavigationKey() {
        if (service != null) service.setNavigationCallback(getNavigationCallback());
    }

    protected boolean isAudioOnly() {
        return audioOnly;
    }

    protected void setAudioOnly(boolean audioOnly) {
        this.audioOnly = audioOnly;
    }

    protected boolean isStop() {
        return stop;
    }

    protected void setStop(boolean stop) {
        this.stop = stop;
    }

    protected boolean isLock() {
        return lock;
    }

    protected void setLock(boolean lock) {
        this.lock = lock;
    }

    protected abstract PlaybackService.NavigationCallback getNavigationCallback();

    protected abstract CustomSeekView getSeekView();

    protected abstract PlayerView getExoView();

    protected abstract ViewBinding getBinding();

    protected abstract String getPlaybackKey();

    protected boolean isOwner() {
        String key = getPlaybackKey();
        return key == null || (service != null && playerManager != null && key.equals(playerManager.getKey()));
    }

    protected boolean isIdle() {
        Player player = controller();
        return player != null && player.getPlaybackState() == Player.STATE_IDLE;
    }

    protected boolean isEnded() {
        Player player = controller();
        return player != null && player.getPlaybackState() == Player.STATE_ENDED;
    }

    protected boolean isBuffering() {
        Player player = controller();
        return player != null && player.getPlaybackState() == Player.STATE_BUFFERING;
    }

    protected boolean isPaused() {
        return !isBuffering() && !isIdle();
    }

    /** 与 History.canSave() 同一口径：有 key 即可存，再叠加不在光盘导航里。 */
    protected boolean canSavePlaybackHistory(History history) {
        return history != null && history.canSave() && !hasDiscNavigationTimeline();
    }

    /** 光盘菜单是否可用：只认 VideoActivity 已在用的 isDiscMenuActive()。 */
    protected boolean hasDiscMenu() {
        return service != null && isOwner() && playerManager != null
                && playerManager.getPlayer() instanceof MpvPlayer mpv && mpv.isDiscMenuActive();
    }

    protected boolean hasDiscNavigationTimeline() {
        return hasDiscMenu();
    }

    /**
     * 光盘菜单控制：2026-11-08 核实，MpvPlayer.sendDiscNav 在影图树内无任何
     * 调用点；nextlib AAR 里没有 MpvPlayer 类，mpv-player 产物本地无盘，
     * build.gradle 也未声明 media3/mpv 依赖，无法证实该方法存在，故保持
     * 空实现、不硬调；有菜单时 hasDiscMenu() 仍为真，入口按钮正常显示。
     */
    protected void showDiscMenuControls() {
    }

    protected void openDiscMenu() {
    }

    protected boolean dispatchDiscMenuTouch(MotionEvent event) {
        return false;
    }

    protected void onServiceConnected() {
    }

    protected void initView(Bundle savedInstanceState) {
    }

    protected void onPrepare() {
    }

    protected void onExoFirstFrame() {
        PlayerView exo = getExoView();
        if (exo == null) return;
        View progress = exo.getRootView().findViewById(R.id.progress);
        if (progress != null) progress.setVisibility(View.GONE);
    }

    protected void onTracksChanged() {
    }

    protected void onTitlesChanged() {
    }

    protected void onPlayerRebuilt() {
    }

    protected void onControllerReady(Player controller) {
    }

    protected void onPlayerPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
    }

    protected void onError(String msg) {
    }

    protected void onReload(String msg) {
        onError(msg);
    }

    protected void onPlayingChanged(boolean isPlaying) {
    }

    protected void onStateChanged(int state) {
    }

    protected void onSizeChanged(VideoSize size) {
    }

    protected void onSurfaceAttached() {
    }

    protected void onReclaim() {
    }

    protected void applyResizeMode(int resizeMode) {
        PlayerView exo = getExoView();
        if (exo != null) exo.setResizeMode(resizeMode);
    }

    protected boolean seekTo(long deltaMs) {
        Player player = controller();
        if (player == null) return false;
        long target = player.getCurrentPosition() + deltaMs;
        if (target < 0) target = 0;
        player.seekTo(target);
        return true;
    }

    protected void startPlayer(String key, Result result, boolean useParse, long timeout, MediaMetadata metadata) {
        if (playerManager == null || result == null) return;
        PlaySpec spec = useParse ? PlaySpec.fromParse(result, key, metadata, true) : PlaySpec.from(result, key, metadata);
        playerManager.start(spec, timeout);
        attachExoPlayer();
        Player controller = controller();
        if (controller != null) onControllerReady(controller);
    }

    private void attachExoPlayer() {
        PlayerView exo = getExoView();
        Player player = controller();
        if (exo == null) return;
        // 建面：布局里 PlayerView 是 surface_type="none"，不调 setRender 就没有输出面，
        // 三路内核都没地方渲染画面。先建面再接播放器（和 webhtv attachSurface 同款顺序）。
        ExoUtil.setPlayerView(exo);
        exo.setPlayer(player);
        if (player != null) {
            attachPlayerListener(player);
            onSurfaceAttached();
        }
    }

    private void attachPlayerListener(Player player) {
        if (playerListener != null) player.removeListener(playerListener);
        playerListener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                onStateChanged(state);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                onPlayingChanged(isPlaying);
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                onPlayerPositionDiscontinuity(oldPosition, newPosition, reason);
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                onSizeChanged(videoSize);
            }

            @Override
            public void onRenderedFirstFrame() {
                onExoFirstFrame();
            }
        };
        player.addListener(playerListener);
    }

    @Override
    protected int getLayoutId() {
        return 0;
    }

    @Override
    protected void init() {
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getBinding().getRoot());
        playerManager = new PlayerManager(new PlayerManager.Callback() {
            @Override
            public void onPrepare() {
                PlaybackActivity.this.onPrepare();
            }

            @Override
            public void onTracksChanged() {
                PlaybackActivity.this.onTracksChanged();
            }

            @Override
            public void onTitlesChanged() {
                PlaybackActivity.this.onTitlesChanged();
            }

            @Override
            public void onError(String msg) {
                PlaybackActivity.this.onError(msg);
            }

            @Override
            public void onReload(String msg) {
                PlaybackActivity.this.onReload(msg);
            }

            @Override
            public void onExoFirstFrame() {
                PlaybackActivity.this.onExoFirstFrame();
            }

            @Override
            public void onPlayerRebuild(Player newPlayer, boolean resetVideoSurface) {
                attachExoPlayer();
                onPlayerRebuilt();
            }
        });
        bound = bindService(new Intent(this, PlaybackService.class), this, BIND_AUTO_CREATE);
        initView(savedInstanceState);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        if (binder instanceof PlaybackService.LocalBinder local) {
            service = local.getService();
            service.setNavigationCallback(getNavigationCallback());
        }
        onServiceConnected();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            try {
                unbindService(this);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        PlayerView exo = null;
        try {
            exo = getExoView();
        } catch (Exception ignored) {
        }
        if (exo != null) exo.setPlayer(null);
        if (playerListener != null && playerManager != null && playerManager.getPlayer() != null) {
            playerManager.getPlayer().removeListener(playerListener);
            playerListener = null;
        }
        if (playerManager != null) {
            playerManager.release();
            playerManager = null;
        }
        service = null;
        super.onDestroy();
    }
}
