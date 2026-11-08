package com.yingtu.plus.setting;

import com.yingtu.plus.util.Prefers;

public class MpvPerformanceSetting {
    public static final int OUTPUT_AUTO = 0;
    public static final int OUTPUT_GPU = 1;
    public static final int OUTPUT_SURFACE_DIRECT = 2;
    public static final int HWDEC_AUTO = 0;
    public static final int HWDEC_DIRECT = 1;
    public static final int SYNC_AUDIO = 0;
    public static final int FRAME_DROP_OFF = 1;
    public static final int VULKAN_BACKEND_AUTO = 0;
    // 软解调优档位：0=关闭，1=温和，2=积极（与 getSoftTuneMode() 的 Prefers 默认值 0=关闭一致）。
    public static final int SOFT_TUNE_OFF = 0;
    public static final int SOFT_TUNE_MILD = 1;
    public static final int SOFT_TUNE_AGGRESSIVE = 2;

    public static int getOutputMode() { return Prefers.getInt("perf_mpv_output_mode", OUTPUT_AUTO); }
    public static String getOutputModeText() { return "auto"; }
    public static boolean shouldUseSurfaceDirect() { return getOutputMode() == OUTPUT_SURFACE_DIRECT; }
    // 三参含义（照两处调用处）：autoEligible=自动直出是否具备条件（MpvAutoOutputPolicy.canStartSurfaceDirect 的结果），
    // leanback=是否电视界面，hardDecode=是否硬解。非硬解一律不用直出；指定直出则用，指定 GPU 则不用，
    // 自动档只在电视界面且具备条件时用。
    public static boolean shouldUseSurfaceDirect(boolean autoEligible, boolean leanback, boolean hardDecode) {
        if (!hardDecode) return false;
        switch (getOutputMode()) {
            case OUTPUT_SURFACE_DIRECT: return true;
            case OUTPUT_GPU: return false;
            default: return leanback && autoEligible;
        }
    }
    
    public static int getHwdecOption() { return Prefers.getInt("perf_mpv_hwdec", HWDEC_AUTO); }
    public static String getHwdecOptionText() { return "auto"; }
    
    public static int getSyncOption() { return Prefers.getInt("perf_mpv_sync", SYNC_AUDIO); }
    public static String getSyncOptionText() { return "audio"; }
    
    public static int getFrameDropOption() { return Prefers.getInt("perf_mpv_frame_drop", FRAME_DROP_OFF); }
    public static String getFrameDropOptionText() { return "off"; }
    
    public static boolean isInterpolation() { return Prefers.getBoolean("perf_mpv_interpolation", false); }
    
    public static int getSoftTuneMode() { return Prefers.getInt("perf_mpv_soft_tune", 0); }
    public static String getSoftTuneOptionText() { return "off"; }
    
    public static int getRebufferMs() { return Prefers.getInt("perf_mpv_rebuffer_ms", 500); }
    
    public static int getHlsBitrateOption() { return Prefers.getInt("perf_mpv_hls_bitrate", 0); }
    public static String getHlsBitrateOptionText() { return "highest"; }
    
    public static int getVulkanBackendOption() { return Prefers.getInt("perf_mpv_vulkan_backend", VULKAN_BACKEND_AUTO); }
    public static String getVulkanBackendOptionText() { return "auto"; }
    
    public static boolean isPerformancePriority() { return Prefers.getBoolean("perf_mpv_option_priority", false); }
    public static boolean isMultichannelPcm() { return Prefers.getBoolean("perf_mpv_multichannel_pcm", false); }

    public static final int FRAME_RATE_OFF = 0;
    public static final int FRAME_RATE_SEAMLESS = 1;
    public static int getFrameRateMode() { return Prefers.getInt("perf_mpv_frame_rate", FRAME_RATE_SEAMLESS); }
    public static void putFrameRateMode(int value) { Prefers.put("perf_mpv_frame_rate", value == FRAME_RATE_OFF ? FRAME_RATE_OFF : FRAME_RATE_SEAMLESS); }
    public static String getFrameRateText() { return getFrameRateMode() == FRAME_RATE_OFF ? "关闭" : "仅无缝"; }
}
