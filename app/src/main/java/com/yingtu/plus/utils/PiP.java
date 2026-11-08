package com.yingtu.plus.utils;

// 按调用点重建：供 VideoActivity 调用的画中画工具（update/enter/setAudioMode 均按 VideoActivity 内的调用签名）。

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.os.Build;
import android.util.Rational;
import android.view.View;

public class PiP {

    private boolean audioMode;
    private boolean playing;
    private int width;
    private int height;
    private int scale = 1;

    public PiP() {
    }

    public void update(Activity activity, View view) {
        if (view == null) return;
        int w = view.getWidth();
        int h = view.getHeight();
        if (w > 0 && h > 0) update(activity, w, h, scale);
    }

    public void update(Activity activity, boolean isPlaying) {
        this.playing = isPlaying;
        if (activity == null) return;
        if (activity.isInPictureInPictureMode()) applyParams(activity);
    }

    public void update(Activity activity, int width, int height, int scale) {
        if (width > 0) this.width = width;
        if (height > 0) this.height = height;
        this.scale = scale;
        if (activity != null && activity.isInPictureInPictureMode()) applyParams(activity);
    }

    public boolean enter(Activity activity, int width, int height, int scale) {
        if (activity == null || audioMode) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        if (activity.isInPictureInPictureMode()) return true;
        update(activity, width, height, scale);
        try {
            activity.enterPictureInPictureMode(buildParams());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void setAudioMode(Activity activity, boolean audioMode) {
        this.audioMode = audioMode;
        if (audioMode && activity != null && activity.isInPictureInPictureMode()) {
            applyParams(activity);
        }
    }

    public boolean isAudioMode() {
        return audioMode;
    }

    private void applyParams(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            activity.setPictureInPictureParams(buildParams());
        } catch (RuntimeException ignored) {
        }
    }

    private PictureInPictureParams buildParams() {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        if (width > 0 && height > 0) {
            int w = width;
            int h = height;
            int gcd = gcd(w, h);
            builder.setAspectRatio(new Rational(w / gcd, h / gcd));
        } else {
            builder.setAspectRatio(new Rational(16, 9));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(false);
        }
        return builder.build();
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a <= 0 ? 1 : a;
    }
}
