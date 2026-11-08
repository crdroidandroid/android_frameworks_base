/*
 * Copyright (C) 2018 The Dirty Unicorns Project
 * Copyright (C) 2022 The Potato Open Sauce Project
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

package com.android.server.display;

import android.content.Context;

import java.io.PrintWriter;

/**
 * Common contract for the surfaces that animate a display power transition
 * ({@link ColorFade} and {@link ElectronBeam}).
 *
 * All methods must be called on the {@link DisplayPowerController} handler thread,
 * except {@link #destroy()} which may also be called from the async destroy executor
 * once the owning {@link DisplayPowerState} has been stopped.
 *
 * @hide
 */
interface ScreenStateAnimator {
    /** Turning on: the content appears. */
    int MODE_WARM_UP = 0;

    /** Turning off: the "signature" effect (gamma fade for ColorFade, CRT for ElectronBeam). */
    int MODE_COOL_DOWN = 1;

    /** A plain black colour layer whose alpha follows the level. */
    int MODE_FADE = 2;

    /** Turning off: the content shrinks into the centre while dimming (ElectronBeam only). */
    int MODE_SCALE_DOWN = 3;

    /** Turning off: a soft-edged circle closes onto the centre (ElectronBeam only). */
    int MODE_IRIS = 4;

    /** Turning off: colour drains to grey, then the picture fades out (ElectronBeam only). */
    int MODE_DESATURATE = 5;

    /** Turning off: black shutters slide in from both edges (ElectronBeam only). */
    int MODE_SHUTTER = 6;

    boolean prepare(Context context, int mode);

    void dismissResources();

    void dismiss();

    void destroy();

    boolean draw(float level);

    void dump(PrintWriter pw);
}
