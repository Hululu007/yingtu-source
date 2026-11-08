package com.yingtu.plus.player;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import com.yingtu.plus.player.engine.PlayerEngine;

import java.util.Locale;
import java.util.Objects;

public final class PlaybackMediaFactsCoordinator {

    private final PlaybackAutoContextStore store;
    private PlaybackAutoContext.SessionToken session = PlaybackAutoContext.SessionToken.none();
    private long trackSequence;
    private EngineObservationKey lastEngineObservation;
    private PlaybackAutoContext.RenderTarget lastRenderTarget;
    private DisplayObservationKey lastDisplayObservation;

    public PlaybackMediaFactsCoordinator(PlaybackAutoContextStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public synchronized boolean beginSession(PlaybackAutoContext.SessionToken session) {
        if (session == null || !session.active() || !session.equals(store.snapshot().session())) return false;
        this.session = session;
        trackSequence = 0;
        lastEngineObservation = null;
        lastRenderTarget = null;
        lastDisplayObservation = null;
        return true;
    }

    public synchronized boolean endSession(PlaybackAutoContext.SessionToken session) {
        if (session == null || !session.equals(this.session)) return false;
        this.session = PlaybackAutoContext.SessionToken.none();
        trackSequence = 0;
        lastEngineObservation = null;
        lastRenderTarget = null;
        lastDisplayObservation = null;
        return true;
    }

    public synchronized boolean publishEngineFacts(
            PlaybackAutoContext.SessionToken session,
            long observedTrackSequence,
            PlayerEngine.PlaybackFactsSnapshot snapshot,
            boolean acceptDecoder,
            long sampledAtElapsedMs) {
        if (!isCurrent(session) || observedTrackSequence < trackSequence) return false;
        PlayerEngine.PlaybackFactsSnapshot observed = snapshot == null
                ? PlayerEngine.PlaybackFactsSnapshot.empty() : snapshot;
        EngineObservationKey key = EngineObservationKey.from(observedTrackSequence, observed, acceptDecoder);
        if (key.equals(lastEngineObservation)) return false;
        boolean sequenceAdvanced = observedTrackSequence > trackSequence;
        PlaybackAutoContext.TrackFacts video = mapTrack(observed.selectedVideoFormat(), sampledAtElapsedMs);
        PlaybackAutoContext.TrackFacts audio = mapTrack(observed.selectedAudioFormat(), sampledAtElapsedMs);
        if (video == null || !video.hasEvidence()) {
            video = sequenceAdvanced ? PlaybackAutoContext.TrackFacts.unknown() : null;
        }
        if (audio == null || !audio.hasEvidence()) {
            audio = sequenceAdvanced ? PlaybackAutoContext.TrackFacts.unknown() : null;
        }
        PlaybackAutoContext.DecoderFacts decoder = acceptDecoder
                ? mapDecoder(observed, observedTrackSequence, sampledAtElapsedMs) : null;
        if (sequenceAdvanced && decoder == null) {
            decoder = PlaybackAutoContext.DecoderFacts.unknown(observedTrackSequence);
        }
        PlaybackAutoContext.OutputFacts output = mapOutput(observed, sampledAtElapsedMs);
        boolean published = store.publishMediaEngineFacts(session, observedTrackSequence,
                video, audio, decoder, output, sampledAtElapsedMs);
        if (published) {
            trackSequence = observedTrackSequence;
            lastEngineObservation = key;
        }
        return published;
    }

    public synchronized boolean publishRenderTarget(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.RenderTarget renderTarget,
            long sampledAtElapsedMs) {
        if (!isCurrent(session)) return false;
        PlaybackAutoContext.RenderTarget observed = renderTarget == null
                ? PlaybackAutoContext.RenderTarget.UNKNOWN : renderTarget;
        if (observed == lastRenderTarget) return false;
        PlaybackAutoContext.Fact<PlaybackAutoContext.RenderTarget> fact = observed == PlaybackAutoContext.RenderTarget.UNKNOWN
                ? PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.RenderTarget.UNKNOWN)
                : PlaybackAutoContext.Fact.untilReplaced(observed, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs);
        boolean published = store.publishRenderTargetFact(session, fact, sampledAtElapsedMs);
        if (published) lastRenderTarget = observed;
        return published;
    }

    public synchronized boolean publishDisplayFacts(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.DisplayMode currentMode,
            PlaybackAutoContext.DisplayMode requestedMode,
            long sampledAtElapsedMs) {
        if (!isCurrent(session)) return false;
        PlaybackAutoContext.DisplayMode current = currentMode == null
                ? PlaybackAutoContext.DisplayMode.unknown() : currentMode;
        PlaybackAutoContext.DisplayMode requested = requestedMode == null
                ? PlaybackAutoContext.DisplayMode.unknown() : requestedMode;
        DisplayObservationKey key = new DisplayObservationKey(current, requested);
        if (key.equals(lastDisplayObservation)) return false;
        PlaybackAutoContext.DisplayFacts display = new PlaybackAutoContext.DisplayFacts(
                current.hasEvidence()
                        ? PlaybackAutoContext.Fact.untilReplaced(current, PlaybackAutoContext.ValueSource.SYSTEM_API,
                        PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs)
                        : PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.DisplayMode.unknown()),
                requested.hasEvidence()
                        ? PlaybackAutoContext.Fact.untilReplaced(requested, PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST,
                        PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs)
                        : PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.DisplayMode.unknown()));
        boolean published = store.publishDisplayFacts(session, display, sampledAtElapsedMs);
        if (published) lastDisplayObservation = key;
        return published;
    }

    private boolean isCurrent(PlaybackAutoContext.SessionToken session) {
        return session != null && session.active() && session.equals(this.session)
                && session.equals(store.snapshot().session());
    }

    private PlaybackAutoContext.TrackFacts mapTrack(Format format, long sampledAtElapsedMs) {
        if (format == null) return null;
        PlaybackAutoContext.HdrType hdrType = hdrTypeOf(format);
        PlaybackAutoContext.ColorSnapshot color = colorOf(format.colorInfo);
        return new PlaybackAutoContext.TrackFacts(
                stringFact(format.sampleMimeType, sampledAtElapsedMs),
                stringFact(format.codecs, sampledAtElapsedMs),
                PlaybackAutoContext.Fact.unknown(-1),
                PlaybackAutoContext.Fact.unknown(-1),
                intFact(format.width, sampledAtElapsedMs),
                intFact(format.height, sampledAtElapsedMs),
                floatFact(format.frameRate, sampledAtElapsedMs),
                hdrType == PlaybackAutoContext.HdrType.UNKNOWN
                        ? PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.HdrType.UNKNOWN)
                        : PlaybackAutoContext.Fact.untilReplaced(hdrType, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs),
                color.hasEvidence()
                        ? PlaybackAutoContext.Fact.untilReplaced(color, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs)
                        : PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.ColorSnapshot.unknown()),
                longFact(format.averageBitrate, sampledAtElapsedMs),
                longFact(format.peakBitrate, sampledAtElapsedMs));
    }

    private PlaybackAutoContext.DecoderFacts mapDecoder(
            PlayerEngine.PlaybackFactsSnapshot snapshot, long observedTrackSequence, long sampledAtElapsedMs) {
        PlaybackAutoContext.DecodeMode mode = switch (snapshot.videoDecoderKind()) {
            case HARDWARE -> PlaybackAutoContext.DecodeMode.HARDWARE;
            case SOFTWARE -> PlaybackAutoContext.DecodeMode.SOFTWARE;
            default -> PlaybackAutoContext.DecodeMode.UNKNOWN;
        };
        PlaybackAutoContext.DecoderFacts decoder = new PlaybackAutoContext.DecoderFacts(
                observedTrackSequence,
                stringFact(snapshot.videoDecoderName(), sampledAtElapsedMs),
                stringFact(snapshot.audioDecoderName(), sampledAtElapsedMs),
                mode == PlaybackAutoContext.DecodeMode.UNKNOWN
                        ? PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.DecodeMode.UNKNOWN)
                        : PlaybackAutoContext.Fact.untilReplaced(mode, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs),
                snapshot.secureVideoDecoder() == null
                        ? PlaybackAutoContext.Fact.unknown(false)
                        : PlaybackAutoContext.Fact.untilReplaced(snapshot.secureVideoDecoder(),
                        PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs));
        return decoder.hasEvidence() ? decoder : null;
    }

    private PlaybackAutoContext.OutputFacts mapOutput(
            PlayerEngine.PlaybackFactsSnapshot snapshot, long sampledAtElapsedMs) {
        String hwdec = safeLabel(snapshot.hwdecCurrent());
        String videoOutput = safeLabel(snapshot.currentVideoOutput());
        if (hwdec.isEmpty() && videoOutput.isEmpty() && snapshot.tunneling() == null) return null;
        PlaybackAutoContext.Fact<PlaybackAutoContext.RenderPath> renderPath =
                store.snapshot().media().output().renderPath();
        return new PlaybackAutoContext.OutputFacts(
                renderPath,
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.RenderTarget.UNKNOWN),
                snapshot.tunneling() == null
                        ? PlaybackAutoContext.Fact.unknown(false)
                        : PlaybackAutoContext.Fact.untilReplaced(snapshot.tunneling(),
                        PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs),
                stringFact(hwdec, sampledAtElapsedMs),
                stringFact(videoOutput, sampledAtElapsedMs));
    }

    private static PlaybackAutoContext.HdrType hdrTypeOf(Format format) {
        if (format == null) return PlaybackAutoContext.HdrType.UNKNOWN;
        String codecs = format.codecs == null ? "" : format.codecs.toLowerCase(Locale.US);
        if (MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)
                || codecs.startsWith("dvhe.") || codecs.startsWith("dvh1.")
                || codecs.startsWith("dvav.") || codecs.startsWith("dvc.")) {
            return PlaybackAutoContext.HdrType.DOLBY_VISION;
        }
        ColorInfo color = format.colorInfo;
        if (color == null) return PlaybackAutoContext.HdrType.UNKNOWN;
        return switch (color.colorTransfer) {
            case C.COLOR_TRANSFER_ST2084 -> PlaybackAutoContext.HdrType.HDR10;
            case C.COLOR_TRANSFER_HLG -> PlaybackAutoContext.HdrType.HLG;
            case C.COLOR_TRANSFER_SDR, C.COLOR_TRANSFER_SRGB,
                    C.COLOR_TRANSFER_LINEAR -> PlaybackAutoContext.HdrType.SDR;
            default -> color.hdrStaticInfo != null && color.hdrStaticInfo.length > 0
                    ? PlaybackAutoContext.HdrType.HDR_OTHER : PlaybackAutoContext.HdrType.UNKNOWN;
        };
    }

    private static PlaybackAutoContext.ColorSnapshot colorOf(ColorInfo color) {
        if (color == null) return PlaybackAutoContext.ColorSnapshot.unknown();
        return new PlaybackAutoContext.ColorSnapshot(
                color.colorSpace, color.colorRange, color.colorTransfer,
                color.hdrStaticInfo != null && color.hdrStaticInfo.length > 0);
    }

    private static PlaybackAutoContext.Fact<String> stringFact(String value, long sampledAtElapsedMs) {
        String label = safeLabel(value);
        return label.isEmpty()
                ? PlaybackAutoContext.Fact.unknown("")
                : PlaybackAutoContext.Fact.untilReplaced(label, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs);
    }

    private static PlaybackAutoContext.Fact<Integer> intFact(int value, long sampledAtElapsedMs) {
        return value > 0
                ? PlaybackAutoContext.Fact.untilReplaced(value, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs)
                : PlaybackAutoContext.Fact.unknown(-1);
    }

    private static PlaybackAutoContext.Fact<Float> floatFact(float value, long sampledAtElapsedMs) {
        return value > 0 && Float.isFinite(value)
                ? PlaybackAutoContext.Fact.untilReplaced(value, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs)
                : PlaybackAutoContext.Fact.unknown(-1f);
    }

    private static PlaybackAutoContext.Fact<Long> longFact(int value, long sampledAtElapsedMs) {
        return value > 0
                ? PlaybackAutoContext.Fact.untilReplaced((long) value, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH, sampledAtElapsedMs)
                : PlaybackAutoContext.Fact.unknown(-1L);
    }

    private static String safeLabel(String value) {
        return value == null ? "" : value.trim();
    }

    private record DisplayObservationKey(
            PlaybackAutoContext.DisplayMode currentMode,
            PlaybackAutoContext.DisplayMode requestedMode) {
    }

    private record EngineObservationKey(
            long trackSequence,
            boolean acceptDecoder,
            FormatKey selectedVideo,
            FormatKey selectedAudio,
            FormatKey decoderVideo,
            FormatKey decoderAudio,
            String videoDecoderName,
            String audioDecoderName,
            PlayerEngine.DecoderKind decoderKind,
            Boolean secure,
            String hwdec,
            String videoOutput,
            Boolean tunneling) {

        private static EngineObservationKey from(
                long trackSequence,
                PlayerEngine.PlaybackFactsSnapshot snapshot,
                boolean acceptDecoder) {
            return new EngineObservationKey(
                    trackSequence,
                    acceptDecoder,
                    FormatKey.from(snapshot.selectedVideoFormat()),
                    FormatKey.from(snapshot.selectedAudioFormat()),
                    FormatKey.from(snapshot.videoDecoderFormat()),
                    FormatKey.from(snapshot.audioDecoderFormat()),
                    safeLabel(snapshot.videoDecoderName()),
                    safeLabel(snapshot.audioDecoderName()),
                    snapshot.videoDecoderKind(),
                    snapshot.secureVideoDecoder(),
                    safeLabel(snapshot.hwdecCurrent()),
                    safeLabel(snapshot.currentVideoOutput()),
                    snapshot.tunneling());
        }
    }

    private record FormatKey(
            String mimeType,
            String codecs,
            int width,
            int height,
            int frameRateMilli,
            int averageBitrate,
            int peakBitrate,
            int colorSpace,
            int colorRange,
            int colorTransfer,
            boolean hdrStaticMetadata) {

        private static FormatKey from(Format format) {
            if (format == null) return new FormatKey("", "", -1, -1, -1,
                    -1, -1, -1, -1, -1, false);
            ColorInfo color = format.colorInfo;
            return new FormatKey(
                    safeLabel(format.sampleMimeType),
                    safeLabel(format.codecs),
                    format.width > 0 ? format.width : -1,
                    format.height > 0 ? format.height : -1,
                    format.frameRate > 0 && Float.isFinite(format.frameRate) ? Math.round(format.frameRate * 1_000f) : -1,
                    format.averageBitrate > 0 ? format.averageBitrate : -1,
                    format.peakBitrate > 0 ? format.peakBitrate : -1,
                    color == null ? -1 : color.colorSpace,
                    color == null ? -1 : color.colorRange,
                    color == null ? -1 : color.colorTransfer,
                    color != null && color.hdrStaticInfo != null && color.hdrStaticInfo.length > 0);
        }
    }
}
