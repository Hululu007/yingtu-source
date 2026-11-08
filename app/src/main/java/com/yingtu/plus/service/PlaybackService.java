package com.yingtu.plus.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.yingtu.plus.player.lyrics.LyricsLine;
import com.yingtu.plus.player.lyrics.LyricsResult;

import java.util.List;

/**
 * 播放服务。
 *
 * VideoActivity 绑定它：通过 NavigationCallback 把耳机/通知栏的上下集、
 * 停止、重播、切音频事件回传给播放页；歌词控制器把桌面歌词的音频内容
 * 与快照推给服务展示。本类只保留调用点用到的接口与回调，播放核心仍在
 * VideoActivity / PlayerManager。
 */
public class PlaybackService extends Service {

    private NavigationCallback navigationCallback;
    private final IBinder binder = new LocalBinder();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /** 外部播放器回放完：走上下集回调（VideoActivity 传给 PlayerHelper 的 onNext）。 */
    public void dispatchNext() {
        if (navigationCallback != null) navigationCallback.onNext();
    }

    public void setNavigationCallback(NavigationCallback callback) {
        this.navigationCallback = callback;
    }

    public NavigationCallback getNavigationCallback() {
        return navigationCallback;
    }

    /** 桌面歌词：当前音频是否为歌词内容。 */
    public void setDesktopLyricsAudioContent(boolean audioContent) {
        // 桌面歌词 UI 由调用方维护，服务侧只保留接口。
    }

    /** 桌面歌词：推送当前行快照。 */
    public void setDesktopLyricsSnapshot(LyricsResult result, List<LyricsLine> lines) {
        // 桌面歌词 UI 由调用方维护，服务侧只保留接口。
    }

    public interface NavigationCallback {

        default void onPrev() {}

        default void onNext() {}

        default void onStop() {}

        default void onReplay() {}

        default void onAudio() {}
    }

    public class LocalBinder extends Binder {

        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }
}
