package com.yingtu.plus.playback;

import android.content.pm.ActivityInfo;

/**
 * 播放页方向映射：把“是不是竖屏视频 / 是不是竖屏模式”等布尔状态
 * 翻译成 setRequestedOrientation 要的 ActivityInfo 常量。
 */
public final class PlaybackOrientation {

    private PlaybackOrientation() {}

    public static int getRotateOrientation(boolean portrait) {
        return portrait ? ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
    }

    public static int getEnterFullscreenOrientation(boolean portraitVideo) {
        if (portraitVideo) return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
    }

    public static int getDefaultEnterFullscreenOrientation(boolean portraitVideo, boolean preferLandscape) {
        if (preferLandscape) return getEnterFullscreenOrientation(portraitVideo);
        return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
    }

    public static int getExitFullscreenOrientation(boolean portMode) {
        if (portMode) return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        return ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
    }

    public static int getPortAutoRotateOrientation() {
        return ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
    }

    public static int getLandAutoRotateOrientation() {
        return ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
    }

    public static int getPortraitVideoSizeOrientation() {
        return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
    }

    public static int getLandscapeVideoSizeOrientation() {
        return ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
    }
}
