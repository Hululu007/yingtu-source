// 按调用点重建，行为取最直白的合理实现。
package com.yingtu.plus.player;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PlaybackMemoryCoordinator {

    private static final long SNAPSHOT_TTL_MS = 60_000;
    private static final long PRESSURE_TTL_MS = 60_000;
    private static final long PSS_TTL_MS = 10 * 60_000;
    private static final int API_RUNNING_TRIM_DEPRECATED = 34;
    private static final int TRIM_MEMORY_RUNNING_MODERATE = 5;
    private static final int TRIM_MEMORY_RUNNING_LOW = 10;
    private static final int TRIM_MEMORY_RUNNING_CRITICAL = 15;

    private static final PlaybackMemoryCoordinator PROCESS = new PlaybackMemoryCoordinator(PlaybackAutoContextStore.process());

    private final PlaybackAutoContextStore store;
    private final AtomicReference<PlaybackAutoContext.SessionToken> activeSession;
    private final CopyOnWriteArrayList<ListenerRegistration> listeners;

    public PlaybackMemoryCoordinator(PlaybackAutoContextStore store) {
        this.store = Objects.requireNonNull(store);
        this.activeSession = new AtomicReference<>(PlaybackAutoContext.SessionToken.none());
        this.listeners = new CopyOnWriteArrayList<>();
    }

    public static PlaybackMemoryCoordinator process() {
        return PROCESS;
    }

    public boolean beginSession(PlaybackAutoContext.SessionToken session) {
        if (session == null || !session.active() || !session.equals(store.snapshot().session())) return false;
        activeSession.set(session);
        return true;
    }

    public boolean endSession(PlaybackAutoContext.SessionToken session) {
        if (session == null || !session.active()) return false;
        return activeSession.compareAndSet(session, PlaybackAutoContext.SessionToken.none());
    }

    public PlaybackAutoContext.SessionToken activeSession() {
        return activeSession.get();
    }

    public boolean publish(PlaybackAutoContext.SessionToken session,
                           PlaybackAutoContext.MemorySnapshot snapshot,
                           Long diagnosticPssBytes,
                           int sdkInt,
                           long publishedAtElapsedMs) {
        if (session == null || !session.equals(activeSession.get())) return false;
        long publishedAt = Math.max(0, publishedAtElapsedMs);
        Evaluation result = evaluate(snapshot, sdkInt);
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressureFact = result.known()
                ? PlaybackAutoContext.Fact.withTtl(result.pressure(), result.source(), result.confidence(),
                publishedAt, PRESSURE_TTL_MS)
                : null;
        PlaybackAutoContext.Confidence snapshotConfidence = snapshotConfidence(snapshot);
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> snapshotFact = snapshot != null
                && snapshot.hasEvidence() && snapshotConfidence != PlaybackAutoContext.Confidence.UNKNOWN
                ? PlaybackAutoContext.Fact.withTtl(snapshot, snapshotSource(snapshot),
                snapshotConfidence, publishedAt, SNAPSHOT_TTL_MS)
                : null;
        PlaybackAutoContext.Fact<Long> pssFact = validBytes(diagnosticPssBytes)
                ? PlaybackAutoContext.Fact.withTtl(diagnosticPssBytes, PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.LOW, publishedAt, PSS_TTL_MS)
                : null;
        if (pressureFact == null && snapshotFact == null && pssFact == null) return false;
        if (!store.publishMemoryFacts(session, pressureFact, snapshotFact, pssFact, publishedAt)) return false;
        if (!session.equals(activeSession.get())) return false;
        PlaybackAutoContext current = store.snapshot();
        if (!session.equals(current.session())) return false;
        Update update = new Update(session,
                current.device().memoryPressure(),
                current.device().memorySnapshot(),
                current.device().diagnosticPssBytes(),
                result.reason(),
                publishedAt);
        notifyListeners(update);
        return true;
    }

    public Registration addListener(Listener listener) {
        ListenerRegistration registration = new ListenerRegistration(Objects.requireNonNull(listener));
        listeners.add(registration);
        return registration;
    }

    int listenerCount() {
        return listeners.size();
    }

    private void notifyListeners(Update update) {
        for (ListenerRegistration registration : listeners) {
            if (registration.closed.get()) continue;
            try {
                registration.listener.onMemoryUpdate(update);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean validBytes(Long value) {
        return value != null && value >= 0;
    }

    private static PlaybackAutoContext.ValueSource snapshotSource(PlaybackAutoContext.MemorySnapshot snapshot) {
        return switch (snapshot.trigger()) {
            case TRIM_MEMORY, LOW_MEMORY -> PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK;
            default -> PlaybackAutoContext.ValueSource.SYSTEM_API;
        };
    }

    private static Evaluation evaluate(PlaybackAutoContext.MemorySnapshot snapshot, int sdkInt) {
        if (snapshot == null) return Evaluation.unknown();
        if (snapshot.trigger() == PlaybackAutoContext.MemoryTrigger.LOW_MEMORY) {
            return new Evaluation(PlaybackAutoContext.MemoryPressure.CRITICAL,
                    PlaybackAutoContext.Confidence.HIGH, Reason.LOW_MEMORY_CALLBACK, true);
        }
        if (Boolean.TRUE.equals(snapshot.systemLowMemory())) {
            return new Evaluation(PlaybackAutoContext.MemoryPressure.CRITICAL,
                    PlaybackAutoContext.Confidence.HIGH, Reason.SYSTEM_LOW_MEMORY, false);
        }
        if (atOrBelowThreshold(snapshot.systemAvailableBytes(), snapshot.systemThresholdBytes())) {
            return new Evaluation(PlaybackAutoContext.MemoryPressure.CRITICAL,
                    PlaybackAutoContext.Confidence.HIGH, Reason.SYSTEM_THRESHOLD, false);
        }
        if (Long.valueOf(0).equals(snapshot.javaHeapHeadroomBytes())) {
            return new Evaluation(PlaybackAutoContext.MemoryPressure.CRITICAL,
                    PlaybackAutoContext.Confidence.HIGH, Reason.JAVA_HEAP_EXHAUSTED, false);
        }
        if (sdkInt < API_RUNNING_TRIM_DEPRECATED && snapshot.lastTrimLevel() != null) {
            return switch (snapshot.lastTrimLevel()) {
                case TRIM_MEMORY_RUNNING_CRITICAL -> new Evaluation(PlaybackAutoContext.MemoryPressure.CRITICAL,
                        PlaybackAutoContext.Confidence.MEDIUM, Reason.LEGACY_RUNNING_CRITICAL, true);
                case TRIM_MEMORY_RUNNING_LOW -> new Evaluation(PlaybackAutoContext.MemoryPressure.MODERATE,
                        PlaybackAutoContext.Confidence.MEDIUM, Reason.LEGACY_RUNNING_LOW, true);
                case TRIM_MEMORY_RUNNING_MODERATE -> new Evaluation(PlaybackAutoContext.MemoryPressure.MODERATE,
                        PlaybackAutoContext.Confidence.LOW, Reason.LEGACY_RUNNING_MODERATE, true);
                default -> normalOrUnknown(snapshot);
            };
        }
        return normalOrUnknown(snapshot);
    }

    private static Evaluation normalOrUnknown(PlaybackAutoContext.MemorySnapshot snapshot) {
        if (Boolean.FALSE.equals(snapshot.systemLowMemory())) {
            return new Evaluation(PlaybackAutoContext.MemoryPressure.NORMAL,
                    PlaybackAutoContext.Confidence.HIGH, Reason.SYSTEM_NORMAL, false);
        }
        Long available = snapshot.systemAvailableBytes();
        Long threshold = snapshot.systemThresholdBytes();
        if (available != null && threshold != null && available > threshold) {
            return new Evaluation(PlaybackAutoContext.MemoryPressure.NORMAL,
                    PlaybackAutoContext.Confidence.HIGH, Reason.SYSTEM_NORMAL, false);
        }
        return Evaluation.unknown();
    }

    private static boolean atOrBelowThreshold(Long available, Long threshold) {
        return available != null && threshold != null && available <= threshold;
    }

    private static PlaybackAutoContext.Confidence snapshotConfidence(PlaybackAutoContext.MemorySnapshot snapshot) {
        if (snapshot == null || !snapshot.hasEvidence()) return PlaybackAutoContext.Confidence.UNKNOWN;
        if (snapshot.hasJavaMetrics() && snapshot.hasSystemMetrics()) return PlaybackAutoContext.Confidence.HIGH;
        return PlaybackAutoContext.Confidence.MEDIUM;
    }

    private record Evaluation(
            PlaybackAutoContext.MemoryPressure pressure,
            PlaybackAutoContext.Confidence confidence,
            Reason reason,
            boolean callbackEvidence) {

        Evaluation {
            pressure = pressure == null ? PlaybackAutoContext.MemoryPressure.UNKNOWN : pressure;
            confidence = confidence == null ? PlaybackAutoContext.Confidence.UNKNOWN : confidence;
            reason = reason == null ? Reason.UNKNOWN : reason;
        }

        static Evaluation unknown() {
            return new Evaluation(PlaybackAutoContext.MemoryPressure.UNKNOWN,
                    PlaybackAutoContext.Confidence.UNKNOWN, Reason.UNKNOWN, false);
        }

        boolean known() {
            return pressure != PlaybackAutoContext.MemoryPressure.UNKNOWN
                    && confidence != PlaybackAutoContext.Confidence.UNKNOWN;
        }

        PlaybackAutoContext.ValueSource source() {
            return callbackEvidence ? PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK
                    : PlaybackAutoContext.ValueSource.SYSTEM_API;
        }
    }

    public enum Reason {
        LOW_MEMORY_CALLBACK("low-memory-callback"),
        SYSTEM_LOW_MEMORY("system-low-memory"),
        SYSTEM_THRESHOLD("system-threshold"),
        JAVA_HEAP_EXHAUSTED("java-heap-exhausted"),
        LEGACY_RUNNING_CRITICAL("legacy-running-critical"),
        LEGACY_RUNNING_LOW("legacy-running-low"),
        LEGACY_RUNNING_MODERATE("legacy-running-moderate"),
        SYSTEM_NORMAL("system-normal"),
        UNKNOWN("unknown");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public interface Listener {
        void onMemoryUpdate(Update update);
    }

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    public record Update(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressure,
            PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> snapshot,
            PlaybackAutoContext.Fact<Long> diagnosticPssBytes,
            Reason reason,
            long publishedAtElapsedMs) {

        public String logSummary() {
            PlaybackAutoContext.MemorySnapshot value = snapshot == null
                    ? PlaybackAutoContext.MemorySnapshot.unknown() : snapshot.value();
            long pss = diagnosticPssBytes != null && diagnosticPssBytes.hasValue()
                    ? diagnosticPssBytes.value() : -1;
            return "pressure=" + (pressure == null || !pressure.hasValue() ? "unknown" : pressure.value().label())
                    + " reason=" + (reason == null ? Reason.UNKNOWN.label() : reason.label())
                    + " trigger=" + value.trigger().label()
                    + " javaUsed=" + safeLong(value.javaHeapUsedBytes())
                    + " javaLimit=" + safeLong(value.javaHeapLimitBytes())
                    + " javaHeadroom=" + safeLong(value.javaHeapHeadroomBytes())
                    + " systemAvail=" + safeLong(value.systemAvailableBytes())
                    + " systemThreshold=" + safeLong(value.systemThresholdBytes())
                    + " systemLow=" + (value.systemLowMemory() == null ? "unknown" : value.systemLowMemory())
                    + " trim=" + (value.lastTrimLevel() == null ? -1 : value.lastTrimLevel())
                    + " importance=" + (value.processImportance() == null ? -1 : value.processImportance())
                    + " nativeHeap=" + safeLong(value.nativeHeapAllocatedBytes())
                    + " pssBytes=" + pss;
        }

        private static long safeLong(Long value) {
            return value == null ? -1 : value;
        }
    }

    private final class ListenerRegistration implements Registration {

        private final Listener listener;
        private final AtomicBoolean closed;

        private ListenerRegistration(Listener listener) {
            this.listener = listener;
            this.closed = new AtomicBoolean();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            listeners.remove(this);
        }
    }
}
