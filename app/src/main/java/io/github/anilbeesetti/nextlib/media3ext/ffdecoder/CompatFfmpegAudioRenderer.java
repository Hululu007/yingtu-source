package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import static androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

import com.github.catvod.crawler.SpiderDebug;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// FFmpeg 软解音频渲染器：把 ExoPlayer 不认或系统解码器不支持的音频交给 nextlib 的 FFmpeg 解码。
// 与上游 nextlib 自带的 FfmpegAudioRenderer 的区别：多做三件事——
// 1) 进解码前先把容器里的 mime 规整成 FFmpeg 认的写法（DTS-HD 去后缀、AMR 当 AMR_NB、AV3A、ALAC 初始化数据）；
// 2) 先探 AudioSink 能不能接多声道，接不了就下混成立体声再解码；
// 3) systemDecoderFallbackOnly 为真时，系统解码器能解的格式不抢。
// 注意：树里这个 nextlib 变体的 FfmpegAudioDecoder 只有 5 参数构造器（无输出声道数参数），
// 所以输出声道数要先算好、写进传给构造器的 Format 里，解码器按 Format 的 channelCount 出声道。
public final class CompatFfmpegAudioRenderer extends DecoderAudioRenderer<FfmpegAudioDecoder> {

    private static final int INPUT_BUFFERS = 16;
    private static final int OUTPUT_BUFFERS = 16;
    private static final int FALLBACK_INPUT_BUFFER_SIZE = 5760;
    private static final int STEREO_CHANNELS = 2;

    private final Context context;
    private final boolean systemDecoderFallbackOnly;

    public CompatFfmpegAudioRenderer(Handler eventHandler, AudioRendererEventListener eventListener, AudioSink audioSink) {
        this(null, eventHandler, eventListener, audioSink, false);
    }

    public CompatFfmpegAudioRenderer(Context context, Handler eventHandler, AudioRendererEventListener eventListener, AudioSink audioSink, boolean systemDecoderFallbackOnly) {
        super(eventHandler, eventListener, audioSink);
        this.context = context;
        this.systemDecoderFallbackOnly = systemDecoderFallbackOnly;
    }

    @Override
    public String getName() {
        return "CompatFfmpegAudioRenderer";
    }

    @Override
    protected int supportsFormatInternal(Format format) {
        Format decodeFormat = normalizeFormat(format);
        String mime = decodeFormat.sampleMimeType;
        if (mime == null || !FfmpegLibrary.isAvailable() || !MimeTypes.isAudio(mime)) {
            return C.FORMAT_UNSUPPORTED_TYPE;
        }
        if (systemDecoderFallbackOnly && isDecodableBySystem(decodeFormat)) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE;
        }
        if (!FfmpegLibrary.supportsFormat(mime)) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE;
        }
        if (resolveOutputChannels(decodeFormat) == Format.NO_VALUE) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE;
        }
        return decodeFormat.cryptoType == C.CRYPTO_TYPE_NONE ? C.FORMAT_HANDLED : C.FORMAT_UNSUPPORTED_DRM;
    }

    @Override
    protected FfmpegAudioDecoder createDecoder(Format format, CryptoConfig cryptoConfig) throws FfmpegDecoderException {
        TraceUtil.beginSection("createCompatFfmpegAudioDecoder");
        try {
            Format decodeFormat = normalizeFormat(format);
            int outputChannels = resolveOutputChannels(decodeFormat);
            Format decoderFormat = decodeFormat.buildUpon().setChannelCount(outputChannels).build();
            int inputBufferSize = decodeFormat.maxInputSize != Format.NO_VALUE
                    ? decodeFormat.maxInputSize : FALLBACK_INPUT_BUFFER_SIZE;
            boolean outputFloat = shouldOutputFloat(decodeFormat, outputChannels);
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("exo-audio", "ffmpeg output mime=%s sourceChannels=%d outputChannels=%d downmix=%s",
                        decodeFormat.sampleMimeType, decodeFormat.channelCount, outputChannels,
                        outputChannels == STEREO_CHANNELS && decodeFormat.channelCount > STEREO_CHANNELS);
            }
            if (MimeTypes.AUDIO_ALAC.equals(decodeFormat.sampleMimeType)) {
                logAlacInitData(decodeFormat);
            }
            return new FfmpegAudioDecoder(decoderFormat, INPUT_BUFFERS, OUTPUT_BUFFERS, inputBufferSize, outputFloat);
        } finally {
            TraceUtil.endSection();
        }
    }

    @Override
    protected Format getOutputFormat(FfmpegAudioDecoder decoder) {
        Assertions.checkNotNull(decoder);
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(decoder.getChannelCount())
                .setSampleRate(decoder.getSampleRate())
                .setPcmEncoding(decoder.getEncoding())
                .build();
    }

    @Override
    public int supportsMixedMimeTypeAdaptation() {
        return RendererCapabilities.ADAPTIVE_NOT_SEAMLESS;
    }

    // 把容器 mime 规整成 FFmpeg 认的写法；ALAC 顺带把采样率/声道数从初始化数据里补全。
    private static Format normalizeFormat(Format format) {
        String mime = format.sampleMimeType;
        if (mime == null) return format;
        if (mime.startsWith(MimeTypes.AUDIO_DTS_HD + ";")
                || MimeTypes.AUDIO_MEDIA3_DTS_HD_MA_CORELESS.equals(mime)) {
            return format.buildUpon().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).build();
        }
        if (MimeTypes.AUDIO_AMR.equals(mime)) {
            return format.buildUpon().setSampleMimeType(MimeTypes.AUDIO_AMR_NB).build();
        }
        if (MimeTypes.AUDIO_AV3A.equals(mime)
                || (MimeTypes.AUDIO_MP4.equals(mime)
                && format.codecs != null
                && format.codecs.toLowerCase(Locale.US).startsWith("av3a"))) {
            return format.buildUpon().setSampleMimeType(MimeTypes.AUDIO_AV3A).build();
        }
        if (MimeTypes.AUDIO_ALAC.equals(mime) && !format.initializationData.isEmpty()) {
            try {
                byte[] initData = stripAlacHeader(format.initializationData.get(0));
                int[] config = CodecSpecificDataUtil.parseAlacAudioSpecificConfig(initData);
                Format.Builder builder = format.buildUpon();
                if (format.sampleRate == Format.NO_VALUE && config[0] > 0) {
                    builder.setSampleRate(config[0]);
                }
                if (format.channelCount == Format.NO_VALUE && config[1] > 0) {
                    builder.setChannelCount(config[1]);
                }
                if (initData != format.initializationData.get(0)) {
                    builder.setInitializationData(List.of(initData));
                }
                return builder.build();
            } catch (RuntimeException ignored) {
                // 初始化数据不合法就原样交给解码器，由解码器报错。
            }
        }
        return format;
    }

    // ALAC 初始化数据有 12 字节 'alac' 包头时去掉，剩下的才是解码器要的配置。
    static byte[] stripAlacHeader(byte[] initData) {
        if (initData.length >= 12
                && readInt32(initData, 0) == initData.length
                && initData[4] == 'a' && initData[5] == 'l' && initData[6] == 'a' && initData[7] == 'c') {
            return Arrays.copyOfRange(initData, 12, initData.length);
        }
        return initData;
    }

    private static int readInt32(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static void logAlacInitData(Format format) {
        byte[] data = format.initializationData.isEmpty() ? null : format.initializationData.get(0);
        int length = data == null ? 0 : data.length;
        StringBuilder prefix = new StringBuilder();
        if (data != null) {
            int shown = Math.min(data.length, 36);
            for (int i = 0; i < shown; i++) {
                int value = data[i] & 0xFF;
                if (value < 0x10) prefix.append('0');
                prefix.append(Integer.toHexString(value));
            }
        }
        Log.d("ExoAlac", "init count=" + format.initializationData.size()
                + " length=" + length + " prefix=" + prefix);
    }

    // 系统解码器能不能解这个格式（fallback-only 模式用）。
    private boolean isDecodableBySystem(Format format) {
        if (context == null || format.sampleMimeType == null) return false;
        try {
            for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo info
                    : MediaCodecSelector.DEFAULT.getDecoderInfos(format.sampleMimeType, false, false)) {
                if (info.isFormatSupported(context, format)) return true;
            }
            return false;
        } catch (MediaCodecUtil.DecoderQueryException e) {
            return false;
        }
    }

    // AudioSink 接不接得住某种 PCM 输出：先问 sink，再拿 AudioTrack 的最小缓冲探一次，
    // 有些厂商 HAL 在建 AudioTrack 时才拒多声道掩码，提前探到就能先下混成立体声。
    private boolean sinkAccepts(Format format, int pcmEncoding, int channelCount) {
        Format pcmFormat = Util.getPcmFormat(pcmEncoding, channelCount, format.sampleRate);
        if (!sinkSupportsFormat(pcmFormat)) return false;
        if (format.sampleRate <= 0 || channelCount <= 0) return true;
        int channelConfig = format.channelMask != Format.NO_VALUE
                ? format.channelMask
                : Util.getAudioTrackChannelConfig(channelCount);
        if (channelConfig == AudioFormat.CHANNEL_INVALID) return false;
        try {
            return AudioTrack.getMinBufferSize(format.sampleRate, channelConfig, pcmEncoding)
                    != AudioTrack.ERROR_BAD_VALUE;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    // 输出声道数：原声道能接就原样，多声道接不住、立体声能接就下混成立体声，都不行返回 NO_VALUE。
    private int resolveOutputChannels(Format format) {
        boolean sourceOk = sinkAccepts(format, C.ENCODING_PCM_16BIT, format.channelCount)
                || sinkAccepts(format, C.ENCODING_PCM_FLOAT, format.channelCount);
        boolean stereoOk = sinkAccepts(format, C.ENCODING_PCM_16BIT, STEREO_CHANNELS)
                || sinkAccepts(format, C.ENCODING_PCM_FLOAT, STEREO_CHANNELS);
        return pickOutputChannels(format.channelCount, sourceOk, stereoOk);
    }

    static int pickOutputChannels(int sourceChannelCount, boolean sourceSupported, boolean stereoSupported) {
        if (sourceSupported) return sourceChannelCount;
        if (sourceChannelCount > STEREO_CHANNELS && stereoSupported) return STEREO_CHANNELS;
        return Format.NO_VALUE;
    }

    // 16bit 接不住就出 float；AC3 例外（float 直通会坏声，留 16bit）。
    private boolean shouldOutputFloat(Format format, int outputChannelCount) {
        if (!sinkAccepts(format, C.ENCODING_PCM_16BIT, outputChannelCount)) return true;
        int floatSupport = getSinkFormatSupport(
                Util.getPcmFormat(C.ENCODING_PCM_FLOAT, outputChannelCount, format.sampleRate));
        return floatSupport == SINK_FORMAT_SUPPORTED_DIRECTLY
                && !MimeTypes.AUDIO_AC3.equals(format.sampleMimeType);
    }
}
