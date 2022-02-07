package org.pixelexperience.systemui.columbus;

import android.os.SystemProperties;

public class ColumbusCompatibilityHelper {
    public static boolean useApSensor() {
        return SystemProperties.getBoolean("persist.columbus.use_ap_sensor", true);
    }

    public static String getModelFileName() {
        return SystemProperties.get("persist.columbus.model", "tap7cls_crosshatch.tflite");
    }

    public static Long apSensorThrottleMs() {
        return SystemProperties.getLong("persist.columbus.ap_sensor.throttle_ms", 500);
    }
}