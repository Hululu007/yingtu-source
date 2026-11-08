package com.yingtu.plus.playback;

import android.os.SystemClock;

import com.yingtu.plus.bean.History;
import com.yingtu.plus.player.PlayerManager;
import com.yingtu.plus.util.HistoryHelper;

/**
 * 播放事件收集：把播放进度写回观看历史。
 *
 * VideoActivity 在进度变化、切集、退出时调用。position/duration 来自
 * PlayerManager，写盘走 HistoryHelper.save；进度写盘做 3 秒节流，
 * 退出时立即写一次。
 */
public class PlaybackEventCollector {

    private static volatile PlaybackEventCollector instance;

    private History current;
    private long lastSaveMs;

    private PlaybackEventCollector() {}

    public static PlaybackEventCollector get() {
        if (instance == null) {
            synchronized (PlaybackEventCollector.class) {
                if (instance == null) instance = new PlaybackEventCollector();
            }
        }
        return instance;
    }

    public synchronized void updateHistory(History history) {
        if (history == null) return;
        current = history;
        saveNow(history);
    }

    public synchronized void onProgress(History history, PlayerManager player) {
        if (history == null) return;
        current = history;
        if (player != null) {
            history.position = player.getPosition();
            history.duration = player.getDuration();
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastSaveMs < 3000) return;
        saveNow(history);
    }

    public synchronized void onStop(PlayerManager player) {
        if (current == null) return;
        if (player != null) {
            current.position = player.getPosition();
            current.duration = player.getDuration();
        }
        saveNow(current);
    }

    private void saveNow(History history) {
        try {
            history.updateTime = System.currentTimeMillis();
            HistoryHelper.save(history);
            lastSaveMs = SystemClock.uptimeMillis();
        } catch (Throwable ignored) {
        }
    }
}
