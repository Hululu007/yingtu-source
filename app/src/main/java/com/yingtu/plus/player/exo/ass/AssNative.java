package com.yingtu.plus.player.exo.ass;

import android.view.Surface;

/** Loaded only by the ASS worker after eligible, selected subtitle data arrives. */
final class AssNative {
    // HDR/wide-gamut video keeps ASS colors in the independent SDR RGB layer.
    // Zero is outside Media3's BT.601/BT.709 values; exo_ass.cpp must match it.
    static final int COLOR_SPACE_SDR_RGB = 0;
    private static boolean loaded;

    static synchronized void ensureLoaded() {
        if (loaded) return;
        try {
            System.loadLibrary("exo_ass");
            loaded = true;
        } catch (Throwable ignored) {
            // exo_ass.so 不在 APK 里（只在分叉 media3 源码里有同名 C++，没编进包）：
            // 这里吞掉，让调用方 ExoAssSession.drain() 的 catch (Exception | LinkageError)
            // 像往常一样把本次字幕会话记为失败，而不是让 UnsatisfiedLinkError 杀掉进程。
            // loaded 保持 false，下次有 ASS 字幕时可再试一次。
        }
    }

    static native long create(String fontConfig, String[] fontNames, byte[][] fonts);
    static native boolean load(long handle, byte[] utf8);
    static native boolean loadHeader(long handle, byte[] utf8);
    static native boolean chunk(long handle, byte[] utf8, long startMs, long durationMs);
    static native boolean setSurface(long handle, Surface surface);
    static native int render(long handle, long timeMs, int width, int height,
                             int storageWidth, int storageHeight, double pixelAspect,
                             int colorSpace, int colorRange, boolean force, long[] stats);
    static native void destroy(long handle);
    static native boolean testSurface(long handle, int width, int height);
    static native long createTestFonts(String[] names, byte[][] fonts);
    static native byte[] readPixels(long handle);

    private AssNative() { }
}
