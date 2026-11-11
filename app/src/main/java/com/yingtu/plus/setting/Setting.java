package com.yingtu.plus.setting;

import com.yingtu.plus.util.Prefers;
import java.util.List;
import java.util.ArrayList;

public class Setting {
    // Constants
    public static final int LANGUAGE_FOLLOW_SYSTEM = 0;
    public static final int LANGUAGE_SIMPLIFIED = 1;
    public static final int CSP_WARMUP_DISABLED = 0;
    public static final int CSP_WARMUP_DEFAULT = 1;
    public static final int CSP_WARMUP_CUSTOM = 2;
    public static final int UI_SCALE_FOLLOW_SYSTEM = 0;
    public static final int UI_SCALE_STANDARD = 1;
    public static final int UI_SCALE_COMPACT = 2;
    
    public static void putWall(int wall) { Prefers.put("wall", wall); }
    public static void putWallType(int type) { Prefers.put("wall_type", type); }
    public static int getCspWarmupMode() { return Prefers.getInt("csp_warmup_mode", CSP_WARMUP_DEFAULT); }
    public static List<String> getCspWarmupSites() { return new ArrayList<>(); }
    public static boolean isCspWarmup() { return getCspWarmupMode() != CSP_WARMUP_DISABLED; }
    public static boolean isCompactEpisodeTitle() { return Prefers.getBoolean("compact_episode_title", false); }
    public static void putCompactEpisodeTitle(boolean v) { Prefers.put("compact_episode_title", v); }
    public static String getUa() { return Prefers.getString("ua", ""); }
    public static void putUa(String ua) { Prefers.put("ua", ua == null ? "" : ua.trim()); }
    public static boolean isAdblock() { return Prefers.getBoolean("adblock", false); }
    public static boolean isIncognito() { return Prefers.getBoolean("incognito", false); }
    // WebHome 扩展注入总开关（与 Backup.PREF_WEB_HOME_EXTENSION 同键，默认开）
    public static boolean isWebHomeExtension() { return Prefers.getBoolean("web_home_extension", true); }
    public static void putWebHomeExtension(boolean v) { Prefers.put("web_home_extension", v); }
    // WebHome 全屏 chrome（站点的 webHomeChrome 顶栏模式是否接管，默认开）
    public static boolean isWebHomeFullscreen() { return Prefers.getBoolean("web_home_fullscreen", true); }
    public static void putWebHomeFullscreen(boolean v) { Prefers.put("web_home_fullscreen", v); }
    // 调试日志开关（仅调试对话框用）
    public static boolean isDebugLog() { return Prefers.getBoolean("debug_log", false); }
    public static void putDebugLog(boolean v) { Prefers.put("debug_log", v); }
    public static boolean isPlaybackArtworkWall() { return Prefers.getBoolean("playback_artwork_wall", false); }
    public static boolean hasFileAccess() { return true; }
    public static boolean hasFileManager() { return true; }
    
    public static Object create() { return null; }
    public static int getReset() { return Prefers.getInt("reset", 0); }
    public static void putReset(int reset) { Prefers.put("reset", reset); }
    public static void putReset(String key) { Prefers.remove(key); }
    public static void putLoginStateFindings(Object findings) {}
    public static void putLoginStatePaths(Object paths) {}
    public static void putLoginStatePendingPaths(Object paths) {}
    public static void putLoginStateSnapshot(Object snapshot) {}
    public static List<Object> getLoginStateFindings() { return new ArrayList<>(); }
    public static List<Object> getLoginStatePaths() { return new ArrayList<>(); }
    public static List<Object> getLoginStatePendingPaths() { return new ArrayList<>(); }
    public static Object getLoginStateSnapshot() { return null; }
    public static List<String> getSyncPaths() { return new ArrayList<>(); }
}
