// 按调用点重建，行为取最直白的合理实现。
package com.yingtu.plus.player;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PlaybackSystemConditionCoordinator {

    private static final long FACT_TTL_MS = 2 * 60_000;
    private static final int API_THERMAL_STATUS = 29;
    private static final int THERMAL_NONE = 0;
    private static final int THERMAL_LIGHT = 1;
    private static final int THERMAL_MODERATE = 2;
    private static final int THERMAL_SEVERE = 3;
    private static final int THERMAL_CRITICAL = 4;
    private static final int THERMAL_EMERGENCY = 5;
    private static final int THERMAL_SHUTDOWN = 6;

    private static final PlaybackSystemConditionCoordinator PROCESS =
            new PlaybackSystemConditionCoordinator(PlaybackAutoContextStore.process());

    private final PlaybackAutoContextStore store;
    private final AtomicReference<PlaybackAutoContext.SessionToken> activeSession;
    private final CopyOnWriteArrayList<ListenerRegistration> listeners;

    public PlaybackSystemConditionCoordinator(PlaybackAutoContextStore store) {
        this.store = Objects.requireNonNull(store);
        this.activeSession = new AtomicReference<>(PlaybackAutoContext.SessionToken.none());
        this.listeners = new CopyOnWriteArrayList<>();
    }

    public static PlaybackSystemConditionCoordinator process() {
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

    public boolean publish(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.SystemConditionTrigger trigger,
            PlaybackAutoContext.NetworkSnapshot networkSnapshot,
            Boolean powerSave,
            Integer thermalStatus,
            int sdkInt,
            long publishedAtElapsedMs) {
        if (session == null || !session.equals(activeSession.get())) return false;
        PlaybackAutoContext.SystemConditionTrigger safeTrigger = trigger == null
                ? PlaybackAutoContext.SystemConditionTrigger.UNKNOWN : trigger;
        long publishedAt = Math.max(0, publishedAtElapsedMs);

        NetworkResult networkResult = evaluateNetwork(networkSnapshot);
        PlaybackAutoContext.Confidence networkConfidence =
                networkSnapshotConfidence(networkSnapshot);
        PlaybackAutoContext.ValueSource networkSource = networkSource(safeTrigger);
        PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkSnapshot> networkSnapshotFact =
                networkSnapshot != null && networkSnapshot.hasEvidence()
                        && networkConfidence != PlaybackAutoContext.Confidence.UNKNOWN
                        ? PlaybackAutoContext.Fact.withTtl(networkSnapshot, networkSource,
                        networkConfidence, publishedAt, FACT_TTL_MS)
                        : null;
        PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkCost> networkCostFact = networkResult.known()
                ? PlaybackAutoContext.Fact.withTtl(networkResult.cost(), networkSource,
                networkResult.confidence(), publishedAt, FACT_TTL_MS)
                : null;

        PlaybackAutoContext.PowerState powerState = powerState(powerSave);
        PlaybackAutoContext.Fact<PlaybackAutoContext.PowerState> powerFact =
                powerState == PlaybackAutoContext.PowerState.UNKNOWN ? null
                        : PlaybackAutoContext.Fact.withTtl(powerState, powerSource(safeTrigger),
                        PlaybackAutoContext.Confidence.HIGH, publishedAt,
                        FACT_TTL_MS);

        PlaybackAutoContext.ThermalState thermalState =
                thermalState(thermalStatus, sdkInt);
        PlaybackAutoContext.Fact<PlaybackAutoContext.ThermalState> thermalFact =
                thermalState == PlaybackAutoContext.ThermalState.UNKNOWN ? null
                        : PlaybackAutoContext.Fact.withTtl(thermalState, thermalSource(safeTrigger),
                        PlaybackAutoContext.Confidence.HIGH, publishedAt,
                        FACT_TTL_MS);

        if (thermalFact == null && powerFact == null && networkCostFact == null && networkSnapshotFact == null) {
            return false;
        }
        if (!store.publishSystemConditionFacts(session, thermalFact, powerFact,
                networkCostFact, networkSnapshotFact, publishedAt)) return false;
        if (!session.equals(activeSession.get())) return false;
        PlaybackAutoContext current = store.snapshot();
        if (!session.equals(current.session())) return false;
        Update update = new Update(
                session,
                safeTrigger,
                current.device().thermalState(),
                current.device().powerState(),
                current.device().networkCost(),
                current.device().networkSnapshot(),
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
                registration.listener.onSystemConditionUpdate(update);
            } catch (Throwable ignored) {
            }
        }
    }

    private static PlaybackAutoContext.ValueSource networkSource(
            PlaybackAutoContext.SystemConditionTrigger trigger) {
        return switch (trigger) {
            case NETWORK_CALLBACK, DATA_SAVER_CALLBACK -> PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK;
            default -> PlaybackAutoContext.ValueSource.SYSTEM_API;
        };
    }

    private static PlaybackAutoContext.ValueSource powerSource(
            PlaybackAutoContext.SystemConditionTrigger trigger) {
        return trigger == PlaybackAutoContext.SystemConditionTrigger.POWER_CALLBACK
                ? PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK
                : PlaybackAutoContext.ValueSource.SYSTEM_API;
    }

    private static PlaybackAutoContext.ValueSource thermalSource(
            PlaybackAutoContext.SystemConditionTrigger trigger) {
        return trigger == PlaybackAutoContext.SystemConditionTrigger.THERMAL_CALLBACK
                ? PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK
                : PlaybackAutoContext.ValueSource.SYSTEM_API;
    }

    static NetworkResult evaluateNetwork(PlaybackAutoContext.NetworkSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasEvidence() || !Boolean.TRUE.equals(snapshot.available())) {
            return NetworkResult.unknown();
        }
        if (Boolean.TRUE.equals(snapshot.roaming())) {
            return new NetworkResult(PlaybackAutoContext.NetworkCost.ROAMING,
                    PlaybackAutoContext.Confidence.HIGH);
        }
        if (snapshot.metered() == null) return NetworkResult.unknown();
        PlaybackAutoContext.NetworkCost cost = snapshot.metered()
                ? PlaybackAutoContext.NetworkCost.METERED
                : PlaybackAutoContext.NetworkCost.UNMETERED;
        PlaybackAutoContext.Confidence confidence = Boolean.FALSE.equals(snapshot.roaming())
                && snapshot.hasCoreEvidence()
                ? PlaybackAutoContext.Confidence.HIGH
                : PlaybackAutoContext.Confidence.MEDIUM;
        return new NetworkResult(cost, confidence);
    }

    static PlaybackAutoContext.Confidence networkSnapshotConfidence(
            PlaybackAutoContext.NetworkSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasEvidence()) return PlaybackAutoContext.Confidence.UNKNOWN;
        return snapshot.hasCoreEvidence()
                ? PlaybackAutoContext.Confidence.HIGH
                : PlaybackAutoContext.Confidence.MEDIUM;
    }

    static PlaybackAutoContext.DataSaverState dataSaverState(Integer rawStatus) {
        if (rawStatus == null) return PlaybackAutoContext.DataSaverState.UNKNOWN;
        return switch (rawStatus) {
            case 1 -> PlaybackAutoContext.DataSaverState.DISABLED;
            case 2 -> PlaybackAutoContext.DataSaverState.WHITELISTED;
            case 3 -> PlaybackAutoContext.DataSaverState.ENABLED;
            default -> PlaybackAutoContext.DataSaverState.UNKNOWN;
        };
    }

    static PlaybackAutoContext.PowerState powerState(Boolean powerSave) {
        if (powerSave == null) return PlaybackAutoContext.PowerState.UNKNOWN;
        return powerSave ? PlaybackAutoContext.PowerState.POWER_SAVE : PlaybackAutoContext.PowerState.NORMAL;
    }

    static PlaybackAutoContext.ThermalState thermalState(Integer rawStatus, int sdkInt) {
        if (sdkInt < API_THERMAL_STATUS || rawStatus == null) return PlaybackAutoContext.ThermalState.UNKNOWN;
        return switch (rawStatus) {
            case THERMAL_NONE, THERMAL_LIGHT -> PlaybackAutoContext.ThermalState.NOMINAL;
            case THERMAL_MODERATE -> PlaybackAutoContext.ThermalState.MODERATE;
            case THERMAL_SEVERE -> PlaybackAutoContext.ThermalState.SEVERE;
            case THERMAL_CRITICAL, THERMAL_EMERGENCY, THERMAL_SHUTDOWN -> PlaybackAutoContext.ThermalState.CRITICAL;
            default -> PlaybackAutoContext.ThermalState.UNKNOWN;
        };
    }

    record NetworkResult(
            PlaybackAutoContext.NetworkCost cost,
            PlaybackAutoContext.Confidence confidence) {

        NetworkResult {
            cost = cost == null ? PlaybackAutoContext.NetworkCost.UNKNOWN : cost;
            confidence = confidence == null ? PlaybackAutoContext.Confidence.UNKNOWN : confidence;
        }

        static NetworkResult unknown() {
            return new NetworkResult(PlaybackAutoContext.NetworkCost.UNKNOWN,
                    PlaybackAutoContext.Confidence.UNKNOWN);
        }

        boolean known() {
            return cost != PlaybackAutoContext.NetworkCost.UNKNOWN
                    && confidence != PlaybackAutoContext.Confidence.UNKNOWN;
        }
    }

    public interface Listener {
        void onSystemConditionUpdate(Update update);
    }

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    public record Update(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.SystemConditionTrigger trigger,
            PlaybackAutoContext.Fact<PlaybackAutoContext.ThermalState> thermal,
            PlaybackAutoContext.Fact<PlaybackAutoContext.PowerState> power,
            PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkCost> networkCost,
            PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkSnapshot> networkSnapshot,
            long publishedAtElapsedMs) {

        public String logSummary() {
            PlaybackAutoContext.NetworkSnapshot network = networkSnapshot == null
                    ? PlaybackAutoContext.NetworkSnapshot.unknown() : networkSnapshot.value();
            return "trigger=" + (trigger == null
                    ? PlaybackAutoContext.SystemConditionTrigger.UNKNOWN.label() : trigger.label())
                    + " thermal=" + factValue(thermal, PlaybackAutoContext.ThermalState.UNKNOWN.label())
                    + " power=" + factValue(power, PlaybackAutoContext.PowerState.UNKNOWN.label())
                    + " networkCost=" + factValue(networkCost, PlaybackAutoContext.NetworkCost.UNKNOWN.label())
                    + " available=" + safeBoolean(network.available())
                    + " validated=" + safeBoolean(network.validated())
                    + " metered=" + safeBoolean(network.metered())
                    + " roaming=" + safeBoolean(network.roaming())
                    + " transport=" + network.transport().label()
                    + " dataSaver=" + network.dataSaverState().label();
        }

        private static String factValue(PlaybackAutoContext.Fact<?> fact, String unknown) {
            if (fact == null || !fact.hasValue()) return unknown;
            Object value = fact.value();
            if (value instanceof PlaybackAutoContext.ThermalState thermal) return thermal.label();
            if (value instanceof PlaybackAutoContext.PowerState power) return power.label();
            if (value instanceof PlaybackAutoContext.NetworkCost cost) return cost.label();
            return unknown;
        }

        private static String safeBoolean(Boolean value) {
            return value == null ? "unknown" : value.toString();
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
