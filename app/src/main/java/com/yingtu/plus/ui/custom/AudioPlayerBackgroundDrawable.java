package com.yingtu.plus.ui.custom;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yingtu.plus.setting.PlayerSetting;

import java.util.Random;

// 对照 webhtv 重写
public class AudioPlayerBackgroundDrawable extends Drawable {

    private final int style;
    private final int artworkColor;
    private final boolean decorated;
    private final boolean lightEffect;
    private boolean animated;
    private final int backgroundSeed;
    private final int decorationSeed;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float haloX;
    private float haloY;
    private float haloRadius;
    private boolean hasHalo;
    private int alpha = 255;

    public AudioPlayerBackgroundDrawable(int style, int artworkColor) {
        this(style, artworkColor, true);
    }

    public AudioPlayerBackgroundDrawable(int style, int artworkColor, boolean decorated) {
        this(style, artworkColor, decorated, 0);
    }

    public AudioPlayerBackgroundDrawable(int style, int artworkColor, boolean decorated, int seed) {
        this(style, artworkColor, decorated, seed, seed);
    }

    public AudioPlayerBackgroundDrawable(int style, int artworkColor, boolean decorated, int backgroundSeed, int decorationSeed) {
        this(style, artworkColor, decorated, false, backgroundSeed, decorationSeed);
    }

    public AudioPlayerBackgroundDrawable(int style, int artworkColor, boolean decorated, boolean lightEffect, int backgroundSeed, int decorationSeed) {
        this(style, artworkColor, decorated, lightEffect, false, backgroundSeed, decorationSeed);
    }

    public AudioPlayerBackgroundDrawable(int style, int artworkColor, boolean decorated, boolean lightEffect, boolean animated, int backgroundSeed, int decorationSeed) {
        this.style = style;
        this.artworkColor = artworkColor;
        this.decorated = decorated;
        this.lightEffect = lightEffect;
        this.animated = animated;
        this.backgroundSeed = backgroundSeed == 0 ? 0x5A17B3 : backgroundSeed;
        this.decorationSeed = decorationSeed == 0 ? 0x5A17B3 : decorationSeed;
    }

    public void setRecordHaloAnchor(float centerX, float centerY, float radius) {
        this.haloX = centerX;
        this.haloY = centerY;
        this.haloRadius = radius;
        this.hasHalo = radius > 0;
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        int w = bounds.width();
        int h = bounds.height();
        if (w <= 0 || h <= 0) return;
        int resolvedStyle = style == PlayerSetting.AUDIO_BACKGROUND_RANDOM ? pickRandomStyle() : style;
        switch (resolvedStyle) {
            case PlayerSetting.AUDIO_BACKGROUND_DARK_NEON -> drawDarkNeon(canvas, w, h);
            case PlayerSetting.AUDIO_BACKGROUND_BLACK_GOLD -> drawBlackGold(canvas, w, h);
            default -> drawArtwork(canvas, w, h);
        }
        if (decorated) drawDecorations(canvas, w, h);
        if (hasHalo) drawHalo(canvas);
        if (lightEffect) drawLightEffect(canvas, w, h);
    }

    private int pickRandomStyle() {
        int[] styles = new int[]{PlayerSetting.AUDIO_BACKGROUND_ARTWORK, PlayerSetting.AUDIO_BACKGROUND_DARK_NEON, PlayerSetting.AUDIO_BACKGROUND_BLACK_GOLD};
        return styles[Math.abs(backgroundSeed) % styles.length];
    }

    private void drawArtwork(Canvas canvas, int w, int h) {
        int base = artworkColor == 0 ? 0xFF1B1B2F : artworkColor;
        int dark = darken(base, 0.35f);
        int light = lighten(base, 1.25f);
        paint.setShader(new LinearGradient(0, 0, w * 0.35f, h, new int[]{light, base, dark}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    private void drawDarkNeon(Canvas canvas, int w, int h) {
        paint.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xFF0B1030, 0xFF070A18, 0xFF120A2E}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setColor(0x552E6BFF);
        canvas.drawCircle(w * 0.82f, h * 0.2f, Math.min(w, h) * 0.28f, paint);
        paint.setColor(0x44B537F2);
        canvas.drawCircle(w * 0.12f, h * 0.85f, Math.min(w, h) * 0.22f, paint);
    }

    private void drawBlackGold(Canvas canvas, int w, int h) {
        paint.setShader(new LinearGradient(0, 0, w, h, new int[]{0xFF1A1405, 0xFF060608, 0xFF241A02}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setColor(0x66D4AF37);
        canvas.drawCircle(w * 0.78f, h * 0.24f, Math.min(w, h) * 0.2f, paint);
        paint.setColor(0x33897A1F);
        canvas.drawCircle(w * 0.2f, h * 0.8f, Math.min(w, h) * 0.26f, paint);
    }

    private void drawDecorations(Canvas canvas, int w, int h) {
        Random random = new Random(decorationSeed);
        paint.setShader(null);
        for (int i = 0; i < 14; i++) {
            float x = random.nextFloat() * w;
            float y = random.nextFloat() * h;
            float r = 1 + random.nextFloat() * 3;
            paint.setColor(Color.argb(28 + random.nextInt(40), 255, 255, 255));
            canvas.drawCircle(x, y, r, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(Color.argb(36, 255, 255, 255));
        canvas.drawCircle(w * 0.5f, h * 0.5f, Math.min(w, h) * 0.42f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawHalo(Canvas canvas) {
        int color = artworkColor == 0 ? 0xFF8A7CFF : lighten(artworkColor, 1.3f);
        paint.setShader(new RadialGradient(haloX, haloY, haloRadius, new int[]{Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(haloX, haloY, haloRadius, paint);
        paint.setShader(null);
    }

    private void drawLightEffect(Canvas canvas, int w, int h) {
        float phase = animated ? (System.currentTimeMillis() % 4000L) / 4000f : 0.25f;
        float x = w * phase;
        paint.setShader(new LinearGradient(x - w * 0.12f, 0, x + w * 0.12f, 0, new int[]{Color.TRANSPARENT, Color.argb(26, 255, 255, 255), Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    private static int darken(int color, float factor) {
        return Color.rgb(Math.round(Color.red(color) * factor), Math.round(Color.green(color) * factor), Math.round(Color.blue(color) * factor));
    }

    private static int lighten(int color, float factor) {
        return Color.rgb(Math.min(255, Math.round(Color.red(color) * factor)), Math.min(255, Math.round(Color.green(color) * factor)), Math.min(255, Math.round(Color.blue(color) * factor)));
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (visible) invalidateSelf();
        return changed;
    }

    public void setAnimated(boolean animated) {
        if (this.animated == animated) return;
        this.animated = animated;
        invalidateSelf();
    }

    public boolean isAnimated() {
        return animated;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
