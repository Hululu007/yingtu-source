// 按调用点重建，行为取最直白的合理实现。
package com.yingtu.plus.player;

public final class PreloadPausePolicy {

    private static final int PAUSE_PRELOAD_ALWAYS = 2;

    private PreloadPausePolicy() {
    }

    public static Decision evaluate(
            boolean playWhenReady,
            int policy,
            PlaybackAutoContext.NetworkSnapshot network) {
        if (playWhenReady) return new Decision(true, Reason.PLAYING);
        if (policy == PAUSE_PRELOAD_ALWAYS) {
            return new Decision(true, Reason.ALWAYS_ALLOWED);
        }
        PlaybackAutoContext.NetworkSnapshot snapshot = network == null
                ? PlaybackAutoContext.NetworkSnapshot.unknown() : network;
        if (Boolean.FALSE.equals(snapshot.available())) {
            return new Decision(false, Reason.NETWORK_UNAVAILABLE);
        }
        if (snapshot.transport() == PlaybackAutoContext.NetworkTransport.CELLULAR) {
            return new Decision(false, Reason.NOT_WIFI);
        }
        return new Decision(true, Reason.WIFI_ALLOWED);
    }

    public record Decision(boolean allowed, Reason reason) {

        public Decision {
            reason = reason == null ? Reason.NOT_WIFI : reason;
        }
    }

    public enum Reason {
        PLAYING("playing"),
        ALWAYS_ALLOWED("always"),
        WIFI_ALLOWED("wifi"),
        NETWORK_UNAVAILABLE("network-unavailable"),
        NETWORK_UNVALIDATED("network-unvalidated"),
        NOT_WIFI("not-wifi");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
