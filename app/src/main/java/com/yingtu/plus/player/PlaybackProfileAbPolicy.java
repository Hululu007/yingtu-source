package com.yingtu.plus.player;

import com.yingtu.plus.setting.PlaybackPerformanceSetting;

public final class PlaybackProfileAbPolicy {

    private static final int DIGEST_HEX_LENGTH = 64;

    private PlaybackProfileAbPolicy() {
    }

    public static Arm armForProfile(int profile) {
        if (profile == PlaybackPerformanceSetting.PROFILE_AUTO) {
            return Arm.AUTO;
        }
        if (profile == PlaybackPerformanceSetting.PROFILE_RECOMMENDED) {
            return Arm.RECOMMENDED;
        }
        return null;
    }

    public static boolean gateAllows(
            boolean experimentStatePresent,
            PlaybackAutoContext.Kernel kernel) {
        return experimentStatePresent
                && kernel != null
                && kernel != PlaybackAutoContext.Kernel.UNKNOWN;
    }

    static boolean validDigest(String value) {
        if (value == null || value.length() != DIGEST_HEX_LENGTH) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '0' && character <= '9') continue;
            if (character >= 'a' && character <= 'f') continue;
            return false;
        }
        return true;
    }

    // 2026-11-08 补：设备摘要（照 webhtv PlaybackProfileAbIdentity.currentDeviceDigest 的结构：
    // 命名空间 + 设备指纹/版本信息拼成规范串再 SHA-256 取小写 hex，64 位）。树里没有
    // PlaybackProfileAbIdentity 这个类，摘要方法放这里，供两处 Setting 的
    // getEnrollmentResolution() 用；算不出时返回空串，Enrollment.active() 会是 false（安全关断）。
    public static String currentDeviceDigest() {
        try {
            String canonical = "yingtu-device\n"
                    + safe(android.os.Build.FINGERPRINT) + "\n"
                    + Math.max(0, com.yingtu.plus.BuildConfig.VERSION_CODE) + "\n"
                    + safe(com.yingtu.plus.BuildConfig.VERSION_NAME) + "\n"
                    + safe(com.yingtu.plus.BuildConfig.MEDIA3_VERSION);
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public enum Arm {
        AUTO("auto"),
        RECOMMENDED("recommended"),
        LIGHTWEIGHT("lightweight");

        private final String label;

        Arm(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum EnrollmentStatus {
        NOT_ENROLLED,
        CURRENT,
        CORRUPT,
        FUTURE_SCHEMA,
        STALE_DEVICE
    }

    public record Enrollment(String deviceDigest, boolean enabled) {

        public Enrollment {
            deviceDigest = validDigest(deviceDigest) ? deviceDigest : "";
        }

        public static Enrollment none() {
            return new Enrollment("", false);
        }

        public boolean active() {
            return enabled && validDigest(deviceDigest);
        }
    }

    public record EnrollmentResolution(
            Enrollment enrollment,
            EnrollmentStatus status,
            boolean sourceValid) {

        public EnrollmentResolution {
            enrollment = enrollment == null ? Enrollment.none() : enrollment;
            status = status == null ? EnrollmentStatus.CORRUPT : status;
        }

        public static EnrollmentResolution notEnrolled() {
            return new EnrollmentResolution(
                    Enrollment.none(),
                    EnrollmentStatus.NOT_ENROLLED,
                    true);
        }

        public boolean active() {
            return sourceValid
                    && status == EnrollmentStatus.CURRENT
                    && enrollment.active();
        }

        public boolean failClosed() {
            return !sourceValid || status == EnrollmentStatus.FUTURE_SCHEMA;
        }
    }
}
