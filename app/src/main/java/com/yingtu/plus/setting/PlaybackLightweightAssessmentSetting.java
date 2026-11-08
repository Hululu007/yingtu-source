package com.yingtu.plus.setting;

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
    public static Object coordinator() { return null; }
}
