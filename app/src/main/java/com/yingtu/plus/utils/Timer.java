package com.yingtu.plus.utils;

// 按调用点重建：单例计时器；目前唯一调用是 VideoActivity.onDestroy() 的 Timer.get().reset()，用于取消未到期的定时任务。

import android.os.Handler;
import android.os.Looper;

public class Timer {

    private static volatile Timer instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pending;

    private Timer() {
    }

    public static Timer get() {
        if (instance == null) {
            synchronized (Timer.class) {
                if (instance == null) instance = new Timer();
            }
        }
        return instance;
    }

    public void start(long delayMs, Runnable action) {
        reset();
        if (action == null || delayMs < 0) return;
        pending = action;
        handler.postDelayed(pending, delayMs);
    }

    public void reset() {
        if (pending != null) {
            handler.removeCallbacks(pending);
            pending = null;
        }
    }

    public void cancel() {
        reset();
    }

    public boolean isActive() {
        return pending != null;
    }
}
