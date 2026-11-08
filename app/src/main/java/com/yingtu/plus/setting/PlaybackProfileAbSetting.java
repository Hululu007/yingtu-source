package com.yingtu.plus.setting;

import com.yingtu.plus.player.PlaybackProfileAbPolicy;
import com.yingtu.plus.util.Prefers;

public class PlaybackProfileAbSetting {
    public static boolean isEnrolled() { return PlaybackLightweightAssessmentSetting.isEnrolled(); }
    // 2026-11-08 云构建第一轮失败后补查：原来这里返回 int（1/0），但 PlayerManager:6234
    // 把它赋给 PlaybackProfileAbPolicy.EnrollmentResolution，第一轮 33 条报错被缺包错误掩盖了
    // 没报出来；按调用点改成返回 EnrollmentResolution。树里没有 webhtv 的注册存储
    // （PlaybackProfileAbIdentity/Store），只按现有“ab_enrolled”开关映射：没开通返回
    // notEnrolled()，开通返回 CURRENT + 设备摘要（摘要算不出时 active() 为 false，安全关断）。
    public static PlaybackProfileAbPolicy.EnrollmentResolution getEnrollmentResolution() {
        if (!isEnrolled()) return PlaybackProfileAbPolicy.EnrollmentResolution.notEnrolled();
        return new PlaybackProfileAbPolicy.EnrollmentResolution(
                new PlaybackProfileAbPolicy.Enrollment(PlaybackProfileAbPolicy.currentDeviceDigest(), true),
                PlaybackProfileAbPolicy.EnrollmentStatus.CURRENT, true);
    }
}
