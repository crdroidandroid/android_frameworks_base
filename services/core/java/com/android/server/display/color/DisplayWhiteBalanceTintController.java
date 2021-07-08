/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.display.color;

import static com.android.server.display.color.DisplayTransformManager.LEVEL_COLOR_MATRIX_DISPLAY_WHITE_BALANCE;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.ColorSpace;
import android.hardware.display.ColorDisplayManager;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

final class DisplayWhiteBalanceTintController extends ChromaticAdaptationTintController {

    private Boolean mIsAvailable;

    @VisibleForTesting
    int mTemperatureMin;
    @VisibleForTesting
    int mTemperatureMax;
    private int mTemperatureDefault;
    @VisibleForTesting
    int mCurrentColorTemperature;

    @Override
    public void setUp(Context context, boolean needsLinear) {
        super.setUp(context, needsLinear);
        setMatrix(mTemperatureDefault);
    }

    @Override
    protected void setUpLocked(Context context, boolean needsLinear) {
        final Resources res = context.getResources();

        final int colorTemperatureMin = res.getInteger(
                R.integer.config_displayWhiteBalanceColorTemperatureMin);
        if (colorTemperatureMin <= 0) {
            Slog.e(ColorDisplayService.TAG,
                    "Display white balance minimum temperature must be greater than 0");
            return;
        }

        final int colorTemperatureMax = res.getInteger(
                R.integer.config_displayWhiteBalanceColorTemperatureMax);
        if (colorTemperatureMax < colorTemperatureMin) {
            Slog.e(ColorDisplayService.TAG,
                    "Display white balance max temp must be greater or equal to min");
            return;
        }

        final int colorTemperature = res.getInteger(
                R.integer.config_displayWhiteBalanceColorTemperatureDefault);

        mTemperatureMin = colorTemperatureMin;
        mTemperatureMax = colorTemperatureMax;
        mTemperatureDefault = colorTemperature;
    }

    @Override
    public void setMatrix(int cct) {
        if (cct < mTemperatureMin) {
            Slog.w(ColorDisplayService.TAG,
                    "Requested display color temperature is below allowed minimum");
            cct = mTemperatureMin;
        } else if (cct > mTemperatureMax) {
            Slog.w(ColorDisplayService.TAG,
                    "Requested display color temperature is above allowed maximum");
            cct = mTemperatureMax;
        }

        synchronized (mLock) {
            mCurrentColorTemperature = cct;

            // Adapt the display's nominal white point to match the requested CCT value
            setMatrixLocked(ColorSpace.cctToXyz(cct));
        }

        Slog.d(ColorDisplayService.TAG, "setDisplayWhiteBalanceTemperatureMatrix: cct = " + cct);
    }

    @Override
    public boolean isAvailable(Context context) {
        if (mIsAvailable == null) {
            mIsAvailable = ColorDisplayManager.isDisplayWhiteBalanceAvailable(context);
        }
        return mIsAvailable;
    }

    @Override
    protected void dumpLocked(PrintWriter pw) {
        pw.println("    mTemperatureMin = " + mTemperatureMin);
        pw.println("    mTemperatureMax = " + mTemperatureMax);
        pw.println("    mTemperatureDefault = " + mTemperatureDefault);
        pw.println("    mCurrentColorTemperature = " + mCurrentColorTemperature);
    }

    @Override
    public int getLevel() {
        return LEVEL_COLOR_MATRIX_DISPLAY_WHITE_BALANCE;
    }
}
