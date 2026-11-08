package com.yingtu.plus.player;

public final class PlaybackProfileAbCoordinator {

    private static final PlaybackProfileAbCoordinator PROCESS =
            new PlaybackProfileAbCoordinator();

    private PlaybackAutoContext.SessionToken session =
            PlaybackAutoContext.SessionToken.none();
    private PlaybackProfileAbPolicy.Arm arm;
    private long experimentGeneration;
    private boolean active;

    private PlaybackProfileAbCoordinator() {
    }

    public static PlaybackProfileAbCoordinator process() {
        return PROCESS;
    }

    public synchronized boolean beginSession(
            PlaybackAutoContext.SessionToken session,
            StartConfig config,
            long startedAtElapsedMs) {
        if (active) invalidateLocked(InvalidationReason.REPLACED);
        clearState();
        StartConfig safe = config == null ? StartConfig.disabled() : config;
        this.session = session == null
                ? PlaybackAutoContext.SessionToken.none() : session;
        this.arm = safe.arm();
        this.experimentGeneration = safe.experimentGeneration();
        this.active = safe.allowed() && this.session.active();
        return this.active;
    }

    public synchronized boolean observe(
            PlaybackAutoContext.SessionToken session,
            RuntimeInput input,
            long sampledAtElapsedMs) {
        if (!isCurrent(session)) return false;
        RuntimeInput safe = input == null ? RuntimeInput.disabled() : input;
        if (!safe.allowed()) {
            invalidateLocked(InvalidationReason.POLICY_DISABLED);
            return false;
        }
        if (safe.experimentGeneration() != experimentGeneration) {
            invalidateLocked(InvalidationReason.GENERATION_CHANGED);
            return false;
        }
        return active;
    }

    public synchronized boolean endSession(
            PlaybackAutoContext.SessionToken session,
            EndConfig config,
            long endedAtElapsedMs,
            long nowEpochMs) {
        if (!isCurrent(session)) return false;
        EndConfig safe = config == null ? EndConfig.disabled() : config;
        if (!safe.allowed()) {
            invalidateLocked(InvalidationReason.POLICY_DISABLED);
            return false;
        }
        if (safe.experimentGeneration() != experimentGeneration) {
            invalidateLocked(InvalidationReason.GENERATION_CHANGED);
            return false;
        }
        boolean wasActive = active;
        clearState();
        return wasActive;
    }

    public synchronized void invalidate(
            PlaybackAutoContext.SessionToken session,
            InvalidationReason reason) {
        if (!isCurrent(session)) return;
        invalidateLocked(reason);
    }

    private boolean isCurrent(PlaybackAutoContext.SessionToken candidate) {
        return candidate != null && session.active() && session.equals(candidate);
    }

    private void invalidateLocked(InvalidationReason reason) {
        clearState();
    }

    private void clearState() {
        session = PlaybackAutoContext.SessionToken.none();
        arm = null;
        experimentGeneration = 0;
        active = false;
    }

    public enum InvalidationReason {
        NONE("none"),
        REPLACED("replaced"),
        POLICY_DISABLED("policy-disabled"),
        GENERATION_CHANGED("generation-changed"),
        SPEED_RESCUE_CONFOUND("speed-rescue-confound"),
        USER_SEEK("user-seek"),
        PAUSED("paused"),
        USER_SPEED("user-speed"),
        UNKNOWN("unknown");

        private final String label;

        InvalidationReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record StartConfig(
            boolean allowed,
            PlaybackProfileAbPolicy.Arm arm,
            String deviceDigest,
            long experimentGeneration,
            boolean normalPlaybackSpeed) {

        public StartConfig {
            deviceDigest = deviceDigest == null ? "" : deviceDigest;
        }

        public static StartConfig disabled() {
            return new StartConfig(false, null, "", 0, false);
        }
    }

    public record RuntimeInput(
            boolean allowed,
            PlaybackProfileAbPolicy.Arm arm,
            long experimentGeneration,
            PlaybackAutoContext context,
            PlaybackTelemetry.RuntimeObservation observation,
            boolean playbackIntended,
            boolean frameSchedulingExperimentActive,
            boolean temporarySpeedRescueActive) {

        public static RuntimeInput disabled() {
            return new RuntimeInput(
                    false,
                    null,
                    0,
                    null,
                    PlaybackTelemetry.RuntimeObservation.unknown(),
                    false,
                    false,
                    false);
        }
    }

    public record EndConfig(
            boolean allowed,
            PlaybackProfileAbPolicy.Arm arm,
            long experimentGeneration,
            String reason) {

        public EndConfig {
            reason = PlaybackTelemetry.safeLabel(reason);
        }

        public static EndConfig disabled() {
            return new EndConfig(false, null, 0, "disabled");
        }
    }
}
