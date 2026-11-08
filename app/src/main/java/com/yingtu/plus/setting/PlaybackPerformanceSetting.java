package com.yingtu.plus.setting;

import com.yingtu.plus.util.Prefers;

public class PlaybackPerformanceSetting {
    public static final int DEFAULT_PROFILE = 0;
    // 2026-11-08 云构建第一轮失败后补查：树里多处在用这 4 个档位常量与 getProfile(int)，
    // 但本文件从没声明过（第一轮 33 条报错没报出来，原因未明，可能是 javac 报错被掩盖；
    // 按 Java 语义缺常量引用必编不过，这里补上）。取值照 webhtv 同名文件结构：
    // RECOMMENDED=0（与 DEFAULT_PROFILE=0 一致）、COMPATIBLE=1、CUSTOM=2、LIGHTWEIGHT=3、AUTO=4。
    public static final int PROFILE_RECOMMENDED = 0;
    public static final int PROFILE_COMPATIBLE = 1;
    public static final int PROFILE_CUSTOM = 2;
    public static final int PROFILE_LIGHTWEIGHT = 3;
    public static final int PROFILE_AUTO = 4;
    // DV7 handling modes: P81 = 0 matches the default of getMpvDv7HandlingMode()
    // (Prefers "perf_mpv_dv7", 0), i.e. upgrade to P8.1 by default; FEL = 2.
    public static final int DV7_HANDLING_P81 = 0;
    public static final int DV7_HANDLING_FEL = 2;
    
    public static boolean isAuto() { return Prefers.getBoolean("perf_auto", true); }
    public static boolean isAuto(int kernel) { return isAuto(); }
    public static boolean isAuto(int kernel, String optionId) { return isAuto() && !isOverridden(); }
    public static boolean isOverridden(int kernel, String optionId) { return isOverridden(); }
    public static boolean isAdaptiveDowngradeEnabled() { return Prefers.getBoolean("perf_adaptive_downgrade", true); }
    public static boolean isBandwidthMeterEnabled() { return Prefers.getBoolean("perf_bandwidth_meter", true); }
    public static boolean isDecoderFallbackEnabled() { return Prefers.getBoolean("perf_decoder_fallback", false); }
    public static boolean isDv7P81Enabled() { return Prefers.getBoolean("perf_dv7_p81", false); }
    public static boolean isDv7Hdr10FallbackEnabled() { return Prefers.getBoolean("perf_dv7_hdr10_fallback", false); }
    public static boolean isDynamicSchedulingEnabled() { return Prefers.getBoolean("perf_dynamic_sched", true); }
    public static boolean isHighBufferEnabled() { return Prefers.getBoolean("perf_high_buffer", false); }
    public static boolean isLateDropInputEnabled() { return Prefers.getBoolean("perf_late_drop", false); }
    public static boolean isLoadOnlySelectedTracksEnabled() { return Prefers.getBoolean("perf_load_only_selected", false); }
    public static boolean isOverridden() { return Prefers.getBoolean("perf_overridden", false); }
    public static boolean isSoftVideoTuneEnabled() { return Prefers.getBoolean("perf_soft_video_tune", false); }
    public static boolean isTrackLimitEnabled() { return Prefers.getBoolean("perf_track_limit", false); }
    public static int getMpvDv7HandlingMode() { return Prefers.getInt("perf_mpv_dv7", 0); }
    public static int getProfile() { return Prefers.getInt("perf_profile", DEFAULT_PROFILE); }
    // 2026-11-08 补：PlayerManager 与 ExoUtil 在按播放内核取档位（getProfile(playerType)），
    // 树里没有按内核分的存储，只有一份全局档位，这里直接返回全局档位。
    public static int getProfile(int kernel) { return getProfile(); }
    public static boolean hasAutomaticOptions() { return isAuto(); }
    public static boolean hasAutomaticOptions(int kernel, String... optionIds) { return isAuto() && !isOverridden(); }
}
