package com.yingtu.plus.setting;

import com.yingtu.plus.player.PlaybackProfileAbCoordinator;
import com.yingtu.plus.player.PlaybackProfileAbPolicy;
import com.yingtu.plus.util.Prefers;

public class PlaybackLightweightAssessmentSetting {
    public static boolean isEnrolled() { return Prefers.getBoolean("ab_enrolled", false); }
    // 2026-11-08 云构建第一轮失败后补查：PlayerManager:6250 调 getEnrollmentResolution()，
    // 本文件原来根本没有这个方法（第一轮 33 条报错被缺包错误掩盖了没报出来）；与
    // PlaybackProfileAbSetting 同款映射（见该文件注释）：没开通返回 notEnrolled()，
    // 开通返回 CURRENT + 设备摘要。
    public static PlaybackProfileAbPolicy.EnrollmentResolution getEnrollmentResolution() {
        if (!isEnrolled()) return PlaybackProfileAbPolicy.EnrollmentResolution.notEnrolled();
        return new PlaybackProfileAbPolicy.EnrollmentResolution(
                new PlaybackProfileAbPolicy.Enrollment(PlaybackProfileAbPolicy.currentDeviceDigest(), true),
                PlaybackProfileAbPolicy.EnrollmentStatus.CURRENT, true);
    }
    // 2026-11-08 云构建第二轮后补查：原来返回 Object（null），但 PlayerManager:296
    // 把它赋给 PlaybackProfileAbCoordinator 类型字段，编不过（前两轮报错被掩盖了
    // 没报出来）。改成返回独立实例——不能复用 process() 单例，PlayerManager 里
    // profile AB 与轻量评估各持一个 coordinator，共用会在第二个 beginSession 时
    // 把第一个的会话顶掉。
    public static PlaybackProfileAbCoordinator coordinator() {
        return PlaybackProfileAbCoordinator.newInstance();
    }
}
