package com.yingtu.plus.setting;

import com.yingtu.plus.util.Prefers;

public class PlayerSetting {
    public static final int EXO = 0;
    public static final int IJK = 1;
    public static final int MPV = 2;
    public static final int RENDER_SURFACE = 0;
    public static final int RENDER_TEXTURE = 1;
    public static final int ORIENTATION_PORTRAIT = 0;
    public static final int ORIENTATION_LANDSCAPE = 1;

    public static int getOrientation() {
        int o = Prefers.getInt("orientation", ORIENTATION_PORTRAIT);
        return o == ORIENTATION_LANDSCAPE ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;
    }

    public static void putOrientation(int o) {
        Prefers.put("orientation", o == ORIENTATION_LANDSCAPE ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT);
    }

    public static boolean isLandscapeOrientation() {
        return getOrientation() == ORIENTATION_LANDSCAPE;
    }
    // mpv 渲染后端：字符串域，与 getMpvRender() 的 "gpu" 默认值一致。
    // 参考仓同名方法是 int 域（0=opengl / 1=vulkan），树里本方法是 String，不可直接照搬 int 常量。
    public static final String MPV_RENDER_OPENGL = "gpu";
    public static final String MPV_RENDER_VULKAN = "vulkan";
    
    // Audio background constants
    public static final int AUDIO_BACKGROUND_ARTWORK = 0;
    public static final int AUDIO_BACKGROUND_DARK_NEON = 1;
    public static final int AUDIO_BACKGROUND_BLACK_GOLD = 2;
    public static final int AUDIO_BACKGROUND_RANDOM = 12;

    public static int getPlayer() { 
        int p = Prefers.getInt("player", EXO); 
        return isPlayer(p) ? p : EXO; 
    }
    
    public static void putPlayer(int p) { Prefers.put("player", sanitizePlayer(p)); }
    
    public static boolean isPlayer(int p) {
        return p >= EXO && p <= MPV;
    }
    
    public static int sanitizePlayer(int p) {
        return Math.max(EXO, Math.min(p, MPV));
    }
    
    public static int nextPlayer() {
        int current = getPlayer();
        return (current + 1) % 3;
    }

    // 按给定内核取下一个内核：EXO -> IJK -> MPV -> EXO（与无参版对当前内核取下一个等价）。
    public static int nextPlayer(int player) {
        switch (sanitizePlayer(player)) {
            case EXO: return IJK;
            case IJK: return MPV;
            default: return EXO;
        }
    }

    public static int getRender() { return Prefers.getInt("render", RENDER_SURFACE); }
    public static void putRender(int r) { Prefers.put("render", r == RENDER_TEXTURE ? RENDER_TEXTURE : RENDER_SURFACE); }
    public static void putTunnel(boolean v) { Prefers.put("tunnel", v); }

    // 画面缩放：设置页 6 项（适应/16:9/4:3/填充/原始/裁剪）→ media3 PlayerView 的 5 种
    // ResizeMode。映射按 VideoActivity.setScale/PlaybackActivity.applyResizeMode 的旧口径：
    // 旧 select_scale 数组就是 [原始,16:9,4:3,填充,裁剪] 直接当 resizeMode 用（0=FIT，
    // 1=FIXED_WIDTH，2=FIXED_HEIGHT，3=FILL，4=ZOOM）。新 6 项只在前面加了“适应”，
    // 与“原始”同为 FIT(0)：media3 没有“原始尺寸 1:1”模式，两者是同一生效逻辑的不同叫法；
    // 16:9/4:3 对应 FIXED_WIDTH/FIXED_HEIGHT（按视频原始比例定宽/定高，不是强制拉成
    // 16:9/4:3 画面——强制比例无核心）。默认 0=适应。
    public static int getScale() { return sanitizeScale(Prefers.getInt("scale", 0)); }
    public static void putScale(int s) { Prefers.putInt("scale", sanitizeScale(s)); }
    public static int sanitizeScale(int s) { return s >= 0 && s <= 5 ? s : 0; }
    public static int scaleToResizeMode(int scale) {
        switch (sanitizeScale(scale)) {
            case 1: return 1;
            case 2: return 2;
            case 3: return 3;
            case 5: return 4;
            default: return 0;
        }
    }

    /** IJK解码：0=软解 1=硬解（与 PlayerEngine.SOFT/HARD 一致），默认硬解 */
    public static int getIjkDecode() { int d = Prefers.getInt("ijk_decode", 1); return d == 0 ? 0 : 1; }
    public static void putIjkDecode(int d) { Prefers.put("ijk_decode", d == 0 ? 0 : 1); }
    /** EXO播放器解码：0=硬件优先 1=软件优先 2=自动（跟随当前解码切换值） */
    public static int getExoDecode() { int d = Prefers.getInt("exo_decode", 0); return d >= 0 && d <= 2 ? d : 0; }
    public static void putExoDecode(int d) { Prefers.put("exo_decode", d >= 0 && d <= 2 ? d : 0); }
    public static int resolveExoDecode(int decode) {
        switch (getExoDecode()) {
            case 0: return 1;
            case 1: return 0;
            default: return decode;
        }
    }
    /** 播放缓冲时间（秒）：0=默认，1/2/3/5/8=起播前缓冲时长 */
    public static int getPlayBufferTimeSec() { int s = Prefers.getInt("play_buffer_time", 0); return s == 1 || s == 2 || s == 3 || s == 5 || s == 8 ? s : 0; }
    public static void putPlayBufferTimeSec(int s) { Prefers.put("play_buffer_time", s); }
    /** 缓冲超时时间（秒）：0=关闭（用默认 15/30 秒），1～20=连接与读取超时都取该秒数 */
    public static int getBufferTimeoutSec() { int s = Prefers.getInt("buffer_timeout_sec", 0); return s >= 1 && s <= 20 ? s : 0; }
    public static void putBufferTimeoutSec(int s) { Prefers.put("buffer_timeout_sec", s); }
    public static void putBuffer(int bytes) { Prefers.put("buffer", Math.max(0, bytes)); }
    
    public static int getBuffer() { return Prefers.getInt("buffer", 1024 * 1024); }
    // 树里没有按内核分的缓冲存储（KernelPerformanceSetting 只有 mode），带参版直接返回全局值。
    public static int getBuffer(int kernel) { return getBuffer(); }
    public static long getBufferBytes() { return getBuffer(); }
    // 带参版返回 int 而不是 long：全部带参调用处都赋给 int（MpvPlayerEngine 的 int bytes、
    // ExoUtil 的 int configuredTargetBytes / int configured），返回 long 会在赋值处再报类型错。
    // 值本身就是 getBuffer() 的 int，不会溢出。
    public static int getBufferBytes(int kernel) { return (int) getBufferBytes(); }
    
    public static int getBackBufferOption() { return Prefers.getInt("back_buffer_option", 0); }
    public static int getBackBufferOption(int kernel) { return getBackBufferOption(); }
    public static int getBackBufferMs() { return Prefers.getInt("back_buffer_ms", 0); }
    public static int getBackBufferMs(int kernel) { return getBackBufferMs(); }
    
    public static double getDefaultSpeed() { return Prefers.getDouble("default_speed", 1.0); }
    public static void putDefaultSpeed(double d) { Prefers.put("default_speed", d); }
    
    public static double getSpeed() { return Prefers.getDouble("speed", 1.0); }
    
    public static boolean isAutoChange() { return Prefers.getBoolean("auto_change", false); }
    public static boolean isCaption() { return Prefers.getBoolean("caption", false); }
    public static boolean isBackgroundOff() { return Prefers.getBoolean("background_off", false); }
    public static boolean isBlurayMenu() { return Prefers.getBoolean("bluray_menu", false); }
    public static boolean isTunnel() { return Prefers.getBoolean("tunnel", false); }
    public static boolean isPreferAAC() { return Prefers.getBoolean("prefer_aac", false); }
    public static boolean isPreferAAC(int kernel) { return isPreferAAC(); }
    public static boolean isAudioPassThrough() { return Prefers.getBoolean("audio_passthrough", false); }
    // 树里只有一份全局 audio_passthrough，没有按内核分的存储，带参版直接返回全局值。
    public static boolean isAudioPassThrough(int kernel) { return isAudioPassThrough(); }
    public static boolean isAudioBackgroundDecorated() { return Prefers.getBoolean("audio_background_decorated", true); }
    public static boolean isImmersiveAudioMode() { return Prefers.getBoolean("immersive_audio_mode", false); }
    
    public static int getAudioBackground() { return Prefers.getInt("audio_background", AUDIO_BACKGROUND_ARTWORK); }
    public static void putAudioBackground(int v) { Prefers.put("audio_background", v); }
    public static int getAudioBackgroundSeed() { return Prefers.getInt("audio_background_seed", 0x5A17B3); }
    public static void putAudioBackgroundSeed(int seed) { Prefers.put("audio_background_seed", seed); }
    public static int getAudioBackgroundDecorationSeed() { return Prefers.getInt("audio_background_decoration_seed", getAudioBackgroundSeed()); }
    public static void putAudioBackgroundDecorationSeed(int seed) { Prefers.put("audio_background_decoration_seed", seed); }
    public static boolean isAudioBackgroundLightEffect() { return Prefers.getBoolean("audio_background_light_effect", false); }
    public static void putAudioBackgroundLightEffect(boolean v) { Prefers.put("audio_background_light_effect", v); }
    public static void putAudioBackgroundDecorated(boolean v) { Prefers.put("audio_background_decorated", v); }

    public static int getSubtitlePosition() { return Prefers.getInt("subtitle_position", 0); }
    public static float getSubtitleTextSize() { return Prefers.getFloat("subtitle_text_size", 1.0f); }
    
    public static int getEpisodesColumn() { return Prefers.getInt("episode_column", 2); }
    public static void putEpisodesColumn(int n) { Prefers.put("episode_column", n); }
    // 与 getEpisodeColumn() 的 [1,2] 夹取语义一致：只认 1 列，其余一律写 2 列。
    public static void putEpisodeColumn(int column) { Prefers.put("episode_column", column == 1 ? 1 : 2); }

    public static int getEpisodeColumn() { return Math.min(Math.max(getEpisodesColumn(), 1), 2); }
    
    public static boolean isDesktopLyrics() { return Prefers.getBoolean("desktop_lyrics", false); }
    public static void putDesktopLyrics(boolean v) { Prefers.put("desktop_lyrics", v); }
    public static int getDesktopLyricsX() { return Prefers.getInt("desktop_lyrics_x", 0); }
    public static int getDesktopLyricsX(int defaultValue) { return Prefers.getInt("desktop_lyrics_x", defaultValue); }
    public static int getDesktopLyricsY() { return Prefers.getInt("desktop_lyrics_y", 0); }
    public static int getDesktopLyricsY(int defaultValue) { return Prefers.getInt("desktop_lyrics_y", defaultValue); }
    public static void putDesktopLyricsPosition(int x, int y) { 
        Prefers.put("desktop_lyrics_x", x); 
        Prefers.put("desktop_lyrics_y", y); 
    }
    
    public static int getLyricsRows() { return Prefers.getInt("lyrics_rows", 3); }
    public static void putLyricsRows(int n) { Prefers.put("lyrics_rows", n); }
    public static int getLyricsTextSizeOption() { return Prefers.getInt("lyrics_text_size", 0); }
    public static void putLyricsTextSizeOption(int o) { Prefers.put("lyrics_text_size", o); }
    public static int getLyricsTimeOffsetMs() { return Prefers.getInt("lyrics_time_offset", 0); }
    public static void putLyricsTimeOffsetMs(int ms) { Prefers.put("lyrics_time_offset", ms); }
    
    public static boolean isKaraokeMode() { return Prefers.getBoolean("karaoke_mode", false); }
    public static void putKaraokeMode(boolean v) { Prefers.put("karaoke_mode", v); }
    public static int getKaraokeDifficulty() { return Prefers.getInt("karaoke_difficulty", 0); }
    public static void putKaraokeDifficulty(int d) { Prefers.put("karaoke_difficulty", d); }
    public static boolean isKaraokeBasicPitchTflite() { return Prefers.getBoolean("karaoke_basic_pitch_tflite", false); }
    public static void putKaraokeBasicPitchTflite(boolean v) { Prefers.put("karaoke_basic_pitch_tflite", v); }
    public static int getKaraokeMicDelayMs() { return Prefers.getInt("karaoke_mic_delay", 0); }
    public static void putKaraokeMicDelayMs(int ms) { Prefers.put("karaoke_mic_delay", ms); }
    public static String getKaraokeGithubSources() { return Prefers.getString("karaoke_github_sources", ""); }
    public static void putKaraokeGithubSources(String value) { Prefers.put("karaoke_github_sources", value == null ? "" : value.trim()); }
    public static float getKaraokeToleranceSemitones() { return Prefers.getFloat("karaoke_tolerance_semitones", 3.0f); }
    public static void putKaraokeToleranceSemitones(float f) { Prefers.put("karaoke_tolerance_semitones", f); }
    
    public static boolean isOsdDiagnostics() { return Prefers.getBoolean("osd_diagnostics", false); }
    public static void putOsdDiagnostics(boolean v) { Prefers.put("osd_diagnostics", v); }
    
    public static int getPlayCacheSize() { return Prefers.getInt("play_cache_size", 32 * 1024 * 1024); }
    public static long getPlayCacheSize(int kernel) { return getPlayCacheSize(); }
    public static boolean isVideoPrefer() { return Prefers.getBoolean("video_prefer", false); }
    // 树里只有一份全局 video_prefer，没有按内核分的存储，带参版直接返回全局值（与 isPreferAAC(int) 等同款模式）。
    public static boolean isVideoPrefer(int kernel) { return isVideoPrefer(); }
    public static boolean isAudioPrefer() { return Prefers.getBoolean("audio_prefer", false); }
    // 同上，树里只有一份全局 audio_prefer，带参版直接返回全局值。
    public static boolean isAudioPrefer(int kernel) { return isAudioPrefer(); }
    
    public static String getMpvRender() { return Prefers.getString("mpv_render", "gpu"); }
    
    // Helper for Prefers integration
    public static void putInt(String key, int value) { Prefers.put(key, value); }
}
