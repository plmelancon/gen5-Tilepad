package com.tilepad;

/** Native cluster minimum pack temperature; unknown values must not cause a warm skip. */
final class BatteryTemperature {
    // Default and EV6 cutoff; profiles may set their own (PreconditioningProfile.warmThresholdC).
    static final int EXPECTED_HEATING_THRESHOLD_C = 20;
    static Integer decode(int[] values) {
        if (values == null || values.length != 8) return null;
        // Plausibility envelope, not a calibration claim. Reject reserved byte
        // values and malformed SOC (index 0, XEV_SOCDIS). Exact profiles only.
        if (values[0] < 0 || values[0] > 200 || values[1] < -40 || values[1] > 60) return null;
        return values[1];
    }
    /**
     * The whole cluster reply. Only {@link #minimumC} is a reading; the other fields are the
     * car's raw integers, kept raw until their scale is checked on a car.
     */
    static final class Info {
        final int minimumC, chargeRaw, outsideRaw, conditioningRaw;
        Info(int minimumC, int chargeRaw, int outsideRaw, int conditioningRaw) {
            this.minimumC = minimumC; this.chargeRaw = chargeRaw;
            this.outsideRaw = outsideRaw; this.conditioningRaw = conditioningRaw;
        }
    }
    /** Null unless {@link #decode} accepts the same array. */
    static Info decodeInfo(int[] values) {
        Integer minimum = decode(values);
        return minimum == null ? null : new Info(minimum, values[0], values[4], values[5]);
    }
    static boolean isWarm(Integer celsius, int thresholdC) {
        return celsius != null && celsius >= thresholdC && celsius <= 60;
    }
}
