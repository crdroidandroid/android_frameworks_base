/*
 * SPDX-FileCopyrightText: The risingOS Android Project
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.display.color;

import static com.android.server.display.color.DisplayTransformManager.LEVEL_COLOR_MATRIX_INVERT_COLOR;

import android.content.Context;
import android.graphics.Color;
import android.hardware.display.ColorDisplayManager;
import android.opengl.Matrix;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.LocalServices;

import java.util.Arrays;

/** Controls color transformation for display engine modes. */
final class DisplayEngineController extends TintController {

    private static final String TAG = "DisplayEngineController";

    private static final float SRGB_GAMMA = 2.2f;

    private static final float[] LUMA_GAMMA = {0.3086f, 0.6094f, 0.0820f};
    private static final float[] LUMA_LINEAR = {0.2126f, 0.7152f, 0.0722f};

    // Mode presets: {red, green, blue, saturation, contrast, value, hueDegrees}
    // r/g/b/sat/cont/val are scaled by 255; hue is in degrees.
    private static final int[] X_REALITY_MODE       = {252, 227, 228, 278, 260, 264, 0};
    private static final int[] VIVID_MODE           = {240, 240, 225, 281, 255, 262, 0};
    private static final int[] TRILUMINOUS_PRO_MODE = {250, 235, 255, 260, 260, 280, 3};
    private static final int[] NATURAL_MODE         = {255, 246, 242, 266, 258, 255, 0};
    private static final int[] CINEMA_MODE          = {255, 240, 222, 248, 261, 252, 0};
    private static final int[] READING_MODE         = {255, 231, 205, 235, 255, 250, 0};

    private final float[] mMatrix = new float[16];

    private int mDisplayMode = 0;
    private boolean mNeedsLinear = false;
    private float mNormalization = 1.0f;

    private float mHue = 0.0f;
    private float mContrast = 1.0f;
    private float mValue = 1.0f;
    private float mSaturation = 1.0f;
    private int mRed = 255;
    private int mGreen = 255;
    private int mBlue = 255;

    DisplayEngineController() {
        Matrix.setIdentityM(mMatrix, 0);
    }

    @Override
    public void setUp(Context context, boolean needsLinear) {
        mNeedsLinear = needsLinear;
    }

    @Override
    public float[] getMatrix() {
        return Arrays.copyOf(mMatrix, mMatrix.length);
    }

    @Override
    public void setMatrix(int rgb) {
        Matrix.setIdentityM(mMatrix, 0);

        // Per-channel white point gains.
        mMatrix[0] = channelGain(Color.red(rgb));
        mMatrix[5] = channelGain(Color.green(rgb));
        mMatrix[10] = channelGain(Color.blue(rgb));

        applyHue(mMatrix, mHue);
        applyContrast(mMatrix, mContrast);
        applyValue(mMatrix, mNeedsLinear
                ? (float) Math.pow(mValue, SRGB_GAMMA) : mValue);
        applySaturation(mMatrix, mSaturation);

        normalize(mMatrix);
    }

    private float channelGain(int channel) {
        final float gain = channel / 255.0f;
        return mNeedsLinear ? (float) Math.pow(gain, SRGB_GAMMA) : gain;
    }

    private void normalize(float[] matrix) {
        float norm = 1.0f;

        for (int r = 0; r < 3; r++) {
            final float white = matrix[r] + matrix[4 + r] + matrix[8 + r] + matrix[12 + r];
            norm = Math.max(norm, white);
        }

        for (int i = 0; i < 15; i++) {
            if (i == 3 || i == 7 || i == 11) continue; // bottom row, stays 0
            norm = Math.max(norm, Math.abs(matrix[i]));
        }

        mNormalization = norm;
        if (norm <= 1.0f) {
            return;
        }
        final float inv = 1.0f / norm;
        for (int i = 0; i < 15; i++) {
            if (i == 3 || i == 7 || i == 11) continue;
            matrix[i] *= inv;
        }
        matrix[15] = 1.0f;
    }

    @Override
    public int getLevel() {
        return LEVEL_COLOR_MATRIX_INVERT_COLOR + 50;
    }

    @Override
    public boolean isAvailable(Context context) {
        return ColorDisplayManager.isColorTransformAccelerated(context);
    }

    public void updateBalance(Context context, int userId) {
        final DisplayTransformManager dtm =
                LocalServices.getService(DisplayTransformManager.class);
        if (dtm != null) {
            mNeedsLinear = dtm.needsLinearColorMatrix();
        }

        int displayMode = Settings.Secure.getIntForUser(
                context.getContentResolver(),
                Settings.Secure.DISPLAY_ENGINE_MODE,
                0,
                userId);

        final int[] mode;
        switch (displayMode) {
            case 1:
                mode = X_REALITY_MODE;
                break;
            case 2:
                mode = VIVID_MODE;
                break;
            case 3:
                mode = TRILUMINOUS_PRO_MODE;
                break;
            case 4:
                mode = NATURAL_MODE;
                break;
            case 5:
                mode = CINEMA_MODE;
                break;
            case 6:
                mode = READING_MODE;
                break;
            case 0:
            default:
                if (displayMode != 0) {
                    Slog.w(TAG, "Unknown display engine mode " + displayMode
                            + ", falling back to off");
                    displayMode = 0;
                }
                mode = null;
                break;
        }

        mDisplayMode = displayMode;

        if (mode != null) {
            mRed = mode[0];
            mGreen = mode[1];
            mBlue = mode[2];
            mSaturation = mode[3] / 255.0f;
            mContrast = mode[4] / 255.0f;
            mValue = mode[5] / 255.0f;
            mHue = mode[6];
        } else {
            mRed = 255;
            mGreen = 255;
            mBlue = 255;
            mSaturation = 1.0f;
            mContrast = 1.0f;
            mValue = 1.0f;
            mHue = 0.0f;
        }

        setActivated(mode != null);
        setMatrix(Color.rgb(mRed, mGreen, mBlue));
    }

    private static void preMultiply(float[] matrix, float[] lhs) {
        final float[] tmp = new float[16];
        Matrix.multiplyMM(tmp, 0, lhs, 0, matrix, 0);
        System.arraycopy(tmp, 0, matrix, 0, 16);
    }

    private void applyHue(float[] matrix, float hue) {
        if (hue == 0.0f) {
            return;
        }
        final float angle = hue * (float) Math.PI / 180;
        final float cosA = (float) Math.cos(angle);
        final float sinA = (float) Math.sin(angle);
        final float[] hueMatrix = {
            // column 0
            0.213f + cosA * 0.787f - sinA * 0.213f,
            0.213f - cosA * 0.213f + sinA * 0.143f,
            0.213f - cosA * 0.213f - sinA * 0.787f,
            0,
            // column 1
            0.715f - cosA * 0.715f - sinA * 0.715f,
            0.715f + cosA * 0.285f + sinA * 0.140f,
            0.715f - cosA * 0.715f + sinA * 0.715f,
            0,
            // column 2
            0.072f - cosA * 0.072f + sinA * 0.928f,
            0.072f - cosA * 0.072f - sinA * 0.283f,
            0.072f + cosA * 0.928f + sinA * 0.072f,
            0,
            // column 3
            0, 0, 0, 1
        };
        preMultiply(matrix, hueMatrix);
    }

    private void applyContrast(float[] matrix, float contrast) {
        if (contrast == 1.0f) {
            return;
        }
        final float pivot = mNeedsLinear ? (float) Math.pow(0.5, SRGB_GAMMA) : 0.5f;
        final float translate = (1.0f - contrast) * pivot;
        final float[] contrastMatrix = {
            contrast, 0, 0, 0,
            0, contrast, 0, 0,
            0, 0, contrast, 0,
            translate, translate, translate, 1
        };
        preMultiply(matrix, contrastMatrix);
    }

    private void applyValue(float[] matrix, float value) {
        if (value == 1.0f) {
            return;
        }
        final float[] valueMatrix = {
            value, 0, 0, 0,
            0, value, 0, 0,
            0, 0, value, 0,
            0, 0, 0, 1
        };
        preMultiply(matrix, valueMatrix);
    }

    private void applySaturation(float[] matrix, float saturation) {
        if (saturation == 1.0f) {
            return;
        }
        final float[] luma = mNeedsLinear ? LUMA_LINEAR : LUMA_GAMMA;
        final float invSat = 1.0f - saturation;
        final float r = invSat * luma[0];
        final float g = invSat * luma[1];
        final float b = invSat * luma[2];
        final float[] saturationMatrix = {
            // column 0
            r + saturation, r, r, 0,
            // column 1
            g, g + saturation, g, 0,
            // column 2
            b, b, b + saturation, 0,
            // column 3
            0, 0, 0, 1
        };
        preMultiply(matrix, saturationMatrix);
    }
}
