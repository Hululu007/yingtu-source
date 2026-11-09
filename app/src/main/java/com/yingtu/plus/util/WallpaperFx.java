package com.yingtu.plus.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import com.orhanobut.hawk.Hawk;

/**
 * 壁纸虚化与亮度：应用在 FirstActivity / HomeActivity 设窗口背景之前。
 * 虚化没有接 RenderScript（树里无现成 Blur 工具），用“缩小再放大”模拟虚化，
 * blur 越大缩得越小、越糊；亮度用 ColorMatrix 按比例缩放 RGB，0=不变。
 */
public final class WallpaperFx {

    private WallpaperFx() {}

    public static int getBlur() {
        int v = 0;
        try {
            v = Hawk.get(HawkConfig.WALLPAPER_BLUR, 0);
        } catch (Throwable ignored) {
        }
        return Math.max(0, Math.min(25, v));
    }

    public static int getBrightness() {
        int v = 0;
        try {
            v = Hawk.get(HawkConfig.WALLPAPER_BRIGHTNESS, 0);
        } catch (Throwable ignored) {
        }
        return Math.max(-100, Math.min(100, v));
    }

    /** 返回处理后的新图（原图不动；blur=0 且 brightness=0 时直接返回原图）。 */
    public static Bitmap apply(Bitmap src, int blur, int brightness) {
        if (src == null) return null;
        Bitmap out = src;
        if (blur > 0) {
            int w = Math.max(1, src.getWidth() / (blur + 1));
            int h = Math.max(1, src.getHeight() / (blur + 1));
            Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
            out = Bitmap.createScaledBitmap(small, src.getWidth(), src.getHeight(), true);
            if (small != src) small.recycle();
        }
        if (brightness != 0) {
            float f = 1.0f + brightness / 100.0f;
            if (f < 0.05f) f = 0.05f;
            ColorMatrix cm = new ColorMatrix();
            cm.setScale(f, f, f, 1.0f);
            Bitmap bright = Bitmap.createBitmap(out.getWidth(), out.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bright);
            Paint paint = new Paint();
            paint.setColorFilter(new ColorMatrixColorFilter(cm));
            canvas.drawBitmap(out, 0, 0, paint);
            if (out != src) out.recycle();
            out = bright;
        }
        return out;
    }

    public static Bitmap applyCurrent(Bitmap src) {
        return apply(src, getBlur(), getBrightness());
    }
}
