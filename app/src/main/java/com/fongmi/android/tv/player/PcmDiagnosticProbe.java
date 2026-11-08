package com.fongmi.android.tv.player;

import androidx.media3.common.C;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.DiagnosticCapture;
import com.github.catvod.crawler.diagnostics.DiagnosticCategories;
import com.yingtu.plus.player.PlaybackDiagnosticCollector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Rewritten from the call-site contract and the diagnostic event schema; the
// sampling policy (at most three windows per capture, 4096 frames each, at
// least one second apart, statistics only, raw samples never retained) and
// the emitted event fields are preserved so existing diagnostics keep working.
public final class PcmDiagnosticProbe {

    private static final int MAX_WINDOWS_PER_CAPTURE = 3;
    private static final int MAX_FRAMES_PER_WINDOW = 4096;
    private static final long MIN_GAP_NS = 1_000_000_000L;
    private static final int MAX_CHANNELS = 32;
    private static final double CLIP_LEVEL = 0.9999;

    private String activeCapture;
    private int probesTaken;
    private long earliestNextNs;

    // Per-channel accumulators for one window.
    private static final class ChannelStats {
        double sumSquares;
        double peak;
        int zeros;
        int clipped;
        int nonFinite;
    }

    public void sample(PlaybackDiagnosticCollector log, PlaybackDiagnosticCollector.Context owner,
                       ByteBuffer data, int start, int end, int encoding, int channels, String checkpoint, boolean protectedMedia) {
        if (protectedMedia || owner == null || !acceptsAudio() || channels < 1 || channels > MAX_CHANNELS || end <= start) return;
        DiagnosticCapture.Session capture = DiagnosticCapture.current(owner.trace(), owner.generation(), owner.attempt());
        if (capture == null || !capture.instance().equals(log.instanceId())) return;
        if (!capture.id().equals(activeCapture)) {
            activeCapture = capture.id();
            probesTaken = 0;
            earliestNextNs = 0;
        }
        if (probesTaken >= MAX_WINDOWS_PER_CAPTURE || System.nanoTime() < earliestNextNs) return;
        int bytesPerSample = bytesPerSample(encoding);
        if (bytesPerSample == 0) return;
        int frames = Math.min(MAX_FRAMES_PER_WINDOW, (end - start) / (bytesPerSample * channels));
        if (frames == 0) return;
        int probeIndex = probesTaken++;
        earliestNextNs = System.nanoTime() + MIN_GAP_NS;
        ChannelStats[] stats = collect(data, start, frames, encoding, channels);
        publish(log, owner, capture.id(), probeIndex, checkpoint, encoding, channels, frames, stats);
    }

    private boolean acceptsAudio() {
        return DebugLogStore.categoryEnabled(DiagnosticCategories.Category.AUDIO);
    }

    private int bytesPerSample(int encoding) {
        switch (encoding) {
            case C.ENCODING_PCM_8BIT: return 1;
            case C.ENCODING_PCM_16BIT: return 2;
            case C.ENCODING_PCM_24BIT: return 3;
            case C.ENCODING_PCM_32BIT:
            case C.ENCODING_PCM_FLOAT: return 4;
            default: return 0;
        }
    }

    private ChannelStats[] collect(ByteBuffer data, int start, int frames, int encoding, int channels) {
        ChannelStats[] stats = new ChannelStats[channels];
        for (int i = 0; i < channels; i++) stats[i] = new ChannelStats();
        ByteBuffer view = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        view.position(start);
        for (int frame = 0; frame < frames; frame++) {
            for (int channel = 0; channel < channels; channel++) {
                double value = readSample(view, encoding);
                ChannelStats s = stats[channel];
                if (!Double.isFinite(value)) {
                    s.nonFinite++;
                    continue;
                }
                double magnitude = Math.abs(value);
                s.sumSquares += value * value;
                if (magnitude > s.peak) s.peak = magnitude;
                if (value == 0) s.zeros++;
                if (magnitude >= CLIP_LEVEL) s.clipped++;
            }
        }
        return stats;
    }

    private double readSample(ByteBuffer view, int encoding) {
        switch (encoding) {
            case C.ENCODING_PCM_8BIT:
                return ((view.get() & 0xFF) - 128) / 128.0;
            case C.ENCODING_PCM_16BIT:
                return view.getShort() / 32768.0;
            case C.ENCODING_PCM_24BIT:
                return ((view.get() & 0xFF) | ((view.get() & 0xFF) << 8) | (view.get() << 16)) / 8388608.0;
            case C.ENCODING_PCM_32BIT:
                return view.getInt() / 2147483648.0;
            default:
                return view.getFloat();
        }
    }

    private void publish(PlaybackDiagnosticCollector log, PlaybackDiagnosticCollector.Context owner,
                         String captureId, int probeIndex, String checkpoint, int encoding,
                         int channels, int frames, ChannelStats[] stats) {
        for (int channel = 0; channel < channels; channel++) {
            final int c = channel;
            final ChannelStats s = stats[c];
            log.emit(owner, "audio.pcm-probe", checkpoint, "capture-owner", e -> e
                    .observed("captureId", captureId)
                    .observed("probeIndex", probeIndex)
                    .observed("channel", c)
                    .observed("channels", channels)
                    .observed("encoding", encoding)
                    .observed("frames", frames)
                    .observed("rms", Math.sqrt(s.sumSquares / frames))
                    .observed("peak", s.peak)
                    .observed("zeroRatio", (double) s.zeros / frames)
                    .observed("clipped", s.clipped)
                    .observed("nonFinite", s.nonFinite)
                    .observed("metricScope", "bounded PCM statistics; no raw samples retained"));
        }
    }
}
