/*
 * Copyright (C) 2026 The LineageOS project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.biometrics;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/**
 * Controller for managing the UDFPS HBM (High Brightness Mode) Scrim on MediaTek devices lacking LHBM support.
 *
 * Logic reversed from Smali: Lcom/android/systemui/biometrics/PriUdfpsScrimController; from LAVA
 */
public class MtkUdfpsScrimController {
    private static MtkUdfpsScrimController mInstance;

    // Accessed directly by UdfpsController
    public View mScrimView;
    public WindowManager mWindowManager;

    private final Context mContext;

    public MtkUdfpsScrimController(Context context) {
        mContext = context;
    }

    public static MtkUdfpsScrimController getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new MtkUdfpsScrimController(context);
        }
        return mInstance;
    }

    /**
     * Calculates the alpha (transparency) for the HBM overlay based on screen brightness.
     * The formula ensures the sensor gets a consistent amount of light.
     *
     * @param brightness Current system brightness (0-255)
     * @return Alpha value (0.0f to 1.0f)
     */
    public float calculateAlpha(int brightness) {
        float alpha = 1.0f - (brightness / 255.0f);

        if (brightness < 25) {
            // Make alpha 5% more transparent when brightness is under 25.
            alpha = alpha * 0.95f;
        }

        Log.d("MtkUdfpsScrimController", "Requested Brightness value: " + brightness);
        Log.d("MtkUdfpsScrimController", "Alpha Value: " + alpha);

        // Ensure we stay in bounds
        return Math.max(0.0f, Math.min(1.0f, alpha));
    }

    public int getSystemBrightness() {
        if (mContext == null) {
            return 127;
        }

        // Try Float first
        float brightFloat = Settings.System.getFloat(
            mContext.getContentResolver(),
            "screen_brightness_float",
            -1.0f
        );

        if (brightFloat >= 0.0f) {
            return (int) (brightFloat * 255.0f);
        }

        // Fallback
        return Settings.System.getInt(
            mContext.getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS,
            127
        );
    }
}
