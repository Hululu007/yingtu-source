package com.yingtu.plus.player;

import com.yingtu.plus.setting.PlaybackPerformanceSetting;

public final class PlaybackLightweightAssessmentPolicy {

    private PlaybackLightweightAssessmentPolicy() {
    }

    public static PlaybackProfileAbPolicy.Arm armForProfile(int profile) {
        if (profile == PlaybackPerformanceSetting.PROFILE_AUTO) {
            return PlaybackProfileAbPolicy.Arm.AUTO;
        }
        if (profile == PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT) {
            return PlaybackProfileAbPolicy.Arm.LIGHTWEIGHT;
        }
        return null;
    }
}
