package com.fongmi.android.tv.player;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.DiagnosticEvent;
import com.yingtu.plus.player.PlaybackDiagnosticCollector;

import java.util.Objects;

// Rewritten from the call-site contract and the diagnostic event schema; the
// observation policy (report only after three identical samples spanning at
// least fifteen seconds, never request recovery or change playback) and the
// emitted event fields are preserved so existing diagnostics keep working.
public final class OutputProgressDiagnostic {

    private static final int STALL_SAMPLES = 3;
    private static final long STALL_WINDOW_MS = 15_000L;
    private static final String GATE_REASON = "output-metric-unavailable";
    private static final String STALL_REASON = "no-output-progress-observed";

    private final boolean video;
    private PlaybackDiagnosticCollector.Context owner;
    private long epoch;
    private long capture = -1;
    private double lastValue = Double.NaN;
    private long stalledSince;
    private int sameCount;
    private boolean stallReported;
    private String activeGate;

    public OutputProgressDiagnostic(boolean video) {
        this.video = video;
    }

    public void sample(PlaybackDiagnosticCollector log, PlaybackDiagnosticCollector.Context context,
                       long currentEpoch, long nowMs, double progress, String gate, String metric) {
        if (!acceptsEvents() || context == null) {
            lastValue = Double.NaN;
            activeGate = null;
            return;
        }
        if (owner != context || epoch != currentEpoch || capture != DebugLogStore.captureGeneration()) {
            owner = context;
            epoch = currentEpoch;
            capture = DebugLogStore.captureGeneration();
            lastValue = Double.NaN;
            activeGate = null;
            stallReported = false;
        }
        if (gate == null && !Double.isFinite(progress)) gate = GATE_REASON;
        if (gate != null) {
            reportGate(log, context, gate, metric);
            return;
        }
        activeGate = null;
        if (!Double.isFinite(lastValue) || Double.compare(progress, lastValue) != 0) {
            lastValue = progress;
            stalledSince = nowMs;
            sameCount = 0;
            stallReported = false;
            return;
        }
        sameCount++;
        long stalledFor = nowMs - stalledSince;
        if (stallReported || sameCount < STALL_SAMPLES || stalledFor < STALL_WINDOW_MS) return;
        stallReported = true;
        reportStall(log, context, currentEpoch, stalledFor, progress, metric);
    }

    private boolean acceptsEvents() {
        return DebugLogStore.acceptsEvent(video ? "video.output.summary" : "audio.output.playhead");
    }

    private void reportGate(PlaybackDiagnosticCollector log, PlaybackDiagnosticCollector.Context context,
                            String gate, String metric) {
        lastValue = Double.NaN;
        sameCount = 0;
        stallReported = false;
        if (!Objects.equals(activeGate, gate)) {
            log.emit(context, "diag.observation", "output-progress-gate", "sample-owner", e -> e
                    .observed("video", video)
                    .observed("phase", "gated")
                    .observed("reason", gate)
                    .observed("metricScope", metric));
        }
        activeGate = gate;
    }

    private void reportStall(PlaybackDiagnosticCollector log, PlaybackDiagnosticCollector.Context context,
                             long currentEpoch, long stalledFor, double progress, String metric) {
        log.emit(context, "diag.observation", "output-progress-samples", "sample-owner", e -> e.inferred()
                .observed("video", video)
                .observed("phase", "observation")
                .observed("reason", STALL_REASON)
                .observed("windowMs", stalledFor)
                .observed("count", sameCount)
                .observed("value", progress)
                .observed("epoch", currentEpoch)
                .observed("metricScope", metric)
                .unknown(video ? "physicalVideo" : "audibility", DiagnosticEvent.Status.NOT_OBSERVABLE)
                .observed("nextStep", "correlate input, buffering, surface/route and user symptom; this is not a decoder failure")
                .pin(log.instanceId() + (video ? "-video-observation" : "-audio-observation")));
    }
}
