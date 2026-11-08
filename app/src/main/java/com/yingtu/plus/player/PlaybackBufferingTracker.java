package com.yingtu.plus.player;

public final class PlaybackBufferingTracker {

    private boolean buffering;
    private Phase phase;
    private long startedAtMs;
    private int rebufferCount;
    private long rebufferTotalMs;

    public Event update(boolean isBuffering, boolean startupComplete, long nowMs, int playbackState, long positionMs, long forwardBufferedMs, boolean loading) {
        long now = Math.max(0, nowMs);
        long position = Math.max(0, positionMs);
        long forward = Math.max(0, forwardBufferedMs);
        if (isBuffering) {
            if (buffering) return null;
            buffering = true;
            phase = startupComplete ? Phase.REBUFFER : Phase.STARTUP;
            startedAtMs = now;
            if (phase == Phase.REBUFFER) rebufferCount++;
            return new Event(Type.START, phase, 0, rebufferCount, rebufferTotalMs, position, forward, loading, playbackState);
        }
        if (!buffering) return null;
        long durationMs = Math.max(0, now - startedAtMs);
        if (phase == Phase.REBUFFER) rebufferTotalMs += durationMs;
        Event event = new Event(Type.END, phase, durationMs, rebufferCount, rebufferTotalMs, position, forward, loading, playbackState);
        buffering = false;
        phase = null;
        startedAtMs = 0;
        return event;
    }

    public void reset() {
        buffering = false;
        phase = null;
        startedAtMs = 0;
        rebufferCount = 0;
        rebufferTotalMs = 0;
    }

    public int getRebufferCount() {
        return rebufferCount;
    }

    public boolean isBuffering() {
        return buffering;
    }

    public long getRebufferTotalMs(long nowMs) {
        if (!buffering || phase != Phase.REBUFFER) return rebufferTotalMs;
        long runningMs = Math.max(0, Math.max(0, nowMs) - startedAtMs);
        return rebufferTotalMs > Long.MAX_VALUE - runningMs ? Long.MAX_VALUE : rebufferTotalMs + runningMs;
    }

    public enum Type {
        START("start"),
        END("end");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Phase {
        STARTUP("startup"),
        REBUFFER("rebuffer");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Event(Type type, Phase phase, long durationMs, int rebufferCount, long rebufferTotalMs, long positionMs, long forwardBufferedMs, boolean loading, int playbackState) {
    }
}
