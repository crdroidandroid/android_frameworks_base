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

import android.annotation.NonNull;
import android.annotation.Size;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.ColorSpace;
import android.hardware.display.ColorDisplayManager;
import android.opengl.Matrix;
import android.os.IBinder;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.SurfaceControl.DisplayPrimaries;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

abstract class ChromaticAdaptationTintController extends TintController {

    // Three chromaticity coordinates per color: X, Y, and Z
    private static final int NUM_VALUES_PER_PRIMARY = 3;
    // Four colors: red, green, blue, and white
    private static final int NUM_DISPLAY_PRIMARIES_VALS = 4 * NUM_VALUES_PER_PRIMARY;
    private static final int COLORSPACE_MATRIX_LENGTH = 9;

    protected final Object mLock = new Object();
    @VisibleForTesting
    float[] mDisplayNominalWhiteXYZ = new float[NUM_VALUES_PER_PRIMARY];
    @VisibleForTesting
    ColorSpace.Rgb mDisplayColorSpaceRGB;
    private float[] mChromaticAdaptationMatrix;
    private float[] mCurrentTargetXYZ;
    @VisibleForTesting
    boolean mSetUp = false;
    protected float[] mMatrix = new float[16];

    @Override
    public void setUp(Context context, boolean needsLinear) {
        mSetUp = false;
        final Resources res = context.getResources();

        ColorSpace.Rgb displayColorSpaceRGB = getDisplayColorSpaceFromSurfaceControl();
        if (displayColorSpaceRGB == null) {
            Slog.w(ColorDisplayService.TAG,
                    "Failed to get display color space from SurfaceControl, trying res");
            displayColorSpaceRGB = getDisplayColorSpaceFromResources(res);
        }

        // Make sure display color space is valid
        if (!isColorMatrixValid(displayColorSpaceRGB.getTransform())) {
            Slog.e(ColorDisplayService.TAG, "Invalid display color space RGB-to-XYZ transform");
            return;
        }
        if (!isColorMatrixValid(displayColorSpaceRGB.getInverseTransform())) {
            Slog.e(ColorDisplayService.TAG, "Invalid display color space XYZ-to-RGB transform");
            return;
        }

        final String[] nominalWhiteValues = res.getStringArray(
                R.array.config_displayWhiteBalanceDisplayNominalWhite);
        float[] displayNominalWhiteXYZ = new float[NUM_VALUES_PER_PRIMARY];
        for (int i = 0; i < nominalWhiteValues.length; i++) {
            displayNominalWhiteXYZ[i] = Float.parseFloat(nominalWhiteValues[i]);
        }

        synchronized (mLock) {
            mDisplayColorSpaceRGB = displayColorSpaceRGB;
            mDisplayNominalWhiteXYZ = displayNominalWhiteXYZ;
            setUpLocked(context, needsLinear);
            mSetUp = true;
        }
    }

    abstract protected void setUpLocked(Context context, boolean needsLinear);

    @Override
    public float[] getMatrix() {
        return mSetUp && isActivated() ? mMatrix
                : ColorDisplayService.MATRIX_IDENTITY;
    }

    /**
     * Multiplies two 3x3 matrices, represented as non-null arrays of 9 floats.
     *
     * @param lhs 3x3 matrix, as a non-null array of 9 floats
     * @param rhs 3x3 matrix, as a non-null array of 9 floats
     * @return A new array of 9 floats containing the result of the multiplication
     *         of rhs by lhs
     */
    @NonNull
    @Size(9)
    private static float[] mul3x3(@NonNull @Size(9) float[] lhs, @NonNull @Size(9) float[] rhs) {
        float[] r = new float[9];
        r[0] = lhs[0] * rhs[0] + lhs[3] * rhs[1] + lhs[6] * rhs[2];
        r[1] = lhs[1] * rhs[0] + lhs[4] * rhs[1] + lhs[7] * rhs[2];
        r[2] = lhs[2] * rhs[0] + lhs[5] * rhs[1] + lhs[8] * rhs[2];
        r[3] = lhs[0] * rhs[3] + lhs[3] * rhs[4] + lhs[6] * rhs[5];
        r[4] = lhs[1] * rhs[3] + lhs[4] * rhs[4] + lhs[7] * rhs[5];
        r[5] = lhs[2] * rhs[3] + lhs[5] * rhs[4] + lhs[8] * rhs[5];
        r[6] = lhs[0] * rhs[6] + lhs[3] * rhs[7] + lhs[6] * rhs[8];
        r[7] = lhs[1] * rhs[6] + lhs[4] * rhs[7] + lhs[7] * rhs[8];
        r[8] = lhs[2] * rhs[6] + lhs[5] * rhs[7] + lhs[8] * rhs[8];
        return r;
    }

    protected void setMatrixLocked(float[] targetXyz) {
        if (!mSetUp) {
            Slog.w(ColorDisplayService.TAG,
                    "Can't set chromatic adaptation matrix: uninitialized");
            return;
        }

        // Adapt the display's nominal white point to match the requested CCT value
        mCurrentTargetXYZ = targetXyz;

        // XYZ -> LMS -> [CAT] -> LMS -> XYZ
        mChromaticAdaptationMatrix =
                ColorSpace.chromaticAdaptation(ColorSpace.Adaptation.CAT16,
                        mDisplayNominalWhiteXYZ, mCurrentTargetXYZ);

        // Convert the adaptation matrix to RGB space
        float[] result = mul3x3(mChromaticAdaptationMatrix,
                mDisplayColorSpaceRGB.getTransform());
        result = mul3x3(mDisplayColorSpaceRGB.getInverseTransform(), result);

        // Normalize the transform matrix to peak white value in RGB space
        final float adaptedMaxR = result[0] + result[3] + result[6];
        final float adaptedMaxG = result[1] + result[4] + result[7];
        final float adaptedMaxB = result[2] + result[5] + result[8];
        final float denum = Math.max(Math.max(adaptedMaxR, adaptedMaxG), adaptedMaxB);

        Matrix.setIdentityM(mMatrix, 0);
        for (int i = 0; i < result.length; i++) {
            result[i] /= denum;
            if (!isColorMatrixCoeffValid(result[i])) {
                Slog.e(ColorDisplayService.TAG, "Invalid chromatic adaptation color matrix");
                return;
            }
        }

        System.arraycopy(result, 0, mMatrix, 0, 3);
        System.arraycopy(result, 3, mMatrix, 4, 3);
        System.arraycopy(result, 6, mMatrix, 8, 3);

        String xyzString = "[" + targetXyz[0] + "," + targetXyz[1] + "," + targetXyz[2] + "]";
        Slog.d(ColorDisplayService.TAG, "setChromaticAdaptationMatrix: xyz = " + xyzString
                + " matrix = " + matrixToString(mMatrix, 4));
    }

    @Override
    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("    mSetUp = " + mSetUp);
            if (!mSetUp) {
                return;
            }

            dumpLocked(pw);
            pw.println("    mCurrentTargetXYZ = "
                    + matrixToString(mCurrentTargetXYZ, 3));
            pw.println("    mDisplayColorSpaceRGB RGB-to-XYZ = "
                    + matrixToString(mDisplayColorSpaceRGB.getTransform(), 3));
            pw.println("    mChromaticAdaptationMatrix = "
                    + matrixToString(mChromaticAdaptationMatrix, 3));
            pw.println("    mDisplayColorSpaceRGB XYZ-to-RGB = "
                    + matrixToString(mDisplayColorSpaceRGB.getInverseTransform(), 3));
            pw.println("    mMatrix = "
                    + matrixToString(mMatrix, 4));
        }
    }

    protected void dumpLocked(PrintWriter pw) {
    }

    private ColorSpace.Rgb makeRgbColorSpaceFromXYZ(float[] redGreenBlueXYZ, float[] whiteXYZ) {
        return new ColorSpace.Rgb(
                "Display Color Space",
                redGreenBlueXYZ,
                whiteXYZ,
                2.2f // gamma, unused for chromatic adaptation
        );
    }

    private ColorSpace.Rgb getDisplayColorSpaceFromSurfaceControl() {
        final IBinder displayToken = SurfaceControl.getInternalDisplayToken();
        if (displayToken == null) {
            return null;
        }

        DisplayPrimaries primaries = SurfaceControl.getDisplayNativePrimaries(displayToken);
        if (primaries == null || primaries.red == null || primaries.green == null
                || primaries.blue == null || primaries.white == null) {
            return null;
        }

        return makeRgbColorSpaceFromXYZ(
                new float[]{
                        primaries.red.X, primaries.red.Y, primaries.red.Z,
                        primaries.green.X, primaries.green.Y, primaries.green.Z,
                        primaries.blue.X, primaries.blue.Y, primaries.blue.Z,
                },
                new float[]{primaries.white.X, primaries.white.Y, primaries.white.Z}
        );
    }

    private ColorSpace.Rgb getDisplayColorSpaceFromResources(Resources res) {
        final String[] displayPrimariesValues = res.getStringArray(
                R.array.config_displayWhiteBalanceDisplayPrimaries);
        float[] displayRedGreenBlueXYZ =
                new float[NUM_DISPLAY_PRIMARIES_VALS - NUM_VALUES_PER_PRIMARY];
        float[] displayWhiteXYZ = new float[NUM_VALUES_PER_PRIMARY];

        for (int i = 0; i < displayRedGreenBlueXYZ.length; i++) {
            displayRedGreenBlueXYZ[i] = Float.parseFloat(displayPrimariesValues[i]);
        }

        for (int i = 0; i < displayWhiteXYZ.length; i++) {
            displayWhiteXYZ[i] = Float.parseFloat(
                    displayPrimariesValues[displayRedGreenBlueXYZ.length + i]);
        }

        return makeRgbColorSpaceFromXYZ(displayRedGreenBlueXYZ, displayWhiteXYZ);
    }

    private boolean isColorMatrixCoeffValid(float coeff) {
        if (Float.isNaN(coeff) || Float.isInfinite(coeff)) {
            return false;
        }

        return true;
    }

    private boolean isColorMatrixValid(float[] matrix) {
        if (matrix == null || matrix.length != COLORSPACE_MATRIX_LENGTH) {
            return false;
        }

        for (int i = 0; i < matrix.length; i++) {
            if (!isColorMatrixCoeffValid(matrix[i])) {
                return false;
            }
        }

        return true;
    }

}
