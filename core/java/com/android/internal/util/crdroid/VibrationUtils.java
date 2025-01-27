/*
 * Copyright (C) 2023-2024 risingOS Android Project
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

package com.android.internal.util.crdroid;

import android.content.Context;
import android.os.AsyncTask;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class VibrationUtils {

    private static Executor executor = Executors.newSingleThreadExecutor();

    public static void triggerVibration(final Context context, final int intensity) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator == null || intensity == 0) {
                    return;
                }
                VibrationEffect effect = createVibrationEffect(intensity);
                if (effect == null) {
                    return;
                }
                vibrator.cancel();
                vibrator.vibrate(effect);
            }
        });
    }

    private static VibrationEffect createVibrationEffect(int intensity) {
        switch (intensity) {
            case 1:
                return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TEXTURE_TICK);
            case 2:
                return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            case 3:
                return VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
            case 4:
                return VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK);
            case 5:
                return VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK);
            default:
                return null;
        }
    }
}
