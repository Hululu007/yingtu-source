package com.yingtu.plus.player;

import android.os.SystemClock;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.DiagnosticEvent;
import com.fongmi.android.tv.player.FelBindProbeControl;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 播放控制侧诊断事件的静态入口：PlayerManager 里只有一处调用，
 * forTrace(traceId, "input.route", "resolved-playback-route", ...)。
 * 事件名与字段都走 DiagnosticEvent 的白名单（"input.route"、
 * inputRoute/source/reason/input/metricScope 均在列），拼好直接交给
 * DebugLogStore；诊断没开时直接跳过。
 */
public final class DiagnosticControls {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static volatile WeakReference<FelBindProbeControl> felProbe = new WeakReference<>(null);

    private DiagnosticControls() {
    }

    public static void registerFelBindProbe(FelBindProbeControl probe) {
        felProbe = new WeakReference<>(probe);
    }

    public static void stopFelBindProbe() {
        FelBindProbeControl probe = felProbe.get();
        if (probe != null) probe.cancel();
    }

    public static void forTrace(String traceId, String event, String association, Consumer<DiagnosticEvent> facts) {
        if (event == null || !DebugLogStore.acceptsEvent(event)) return;
        try {
            DiagnosticEvent diagnostic = new DiagnosticEvent(event, PlaybackTrace.normalize(traceId), "player-controls", 0, 0)
                    .source("controls", "1", "controller", "player-controls", association, null, null,
                            SEQUENCE.incrementAndGet(), SystemClock.elapsedRealtimeNanos());
            if (facts != null) facts.accept(diagnostic);
            DebugLogStore.event(diagnostic);
        } catch (RuntimeException ignored) {
            DebugLogStore.collectorFailure();
        }
    }
}
