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
        // F7：退出全屏一律强制回竖屏。旧逻辑横屏退出时给 FULL_USER，手机还横拿着
        // 就停在横屏非全屏详情布局。详情页是竖屏布局，先回 USER_PORTRAIT；
        // 2 秒后 setOrient（mR3）见竖屏 + 自动旋转开会再给 FULL_USER 解锁跟传感器。
        // 参数保留只为兼容旧调用，退出方向不再由它决定。
        return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
    }

    public static int getPortAutoRotateOrientation() {
        return ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
    }

    public static int getLandAutoRotateOrientation() {
        // F2：横屏分支原来钉死 USER_LANDSCAPE，进全屏 2 秒后 setOrient 一跑方向就
        // 钉死，转回竖屏不产生配置变化、退不了全屏。改成 FULL_USER，跟竖屏分支一致，
        // 自动旋转开时跟传感器走。
        return ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
    }

    public static int getPortraitVideoSizeOrientation() {
        return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
    }

    public static int getLandscapeVideoSizeOrientation() {
        return ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
    }
}
