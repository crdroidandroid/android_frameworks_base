/*
 * Copyright (C) 2023-2024 The risingOS Android Project
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

import static com.android.server.display.color.DisplayTransformManager.LEVEL_COLOR_MATRIX_INVERT_COLOR;

import android.content.Context;
import android.graphics.Color;
import android.hardware.display.ColorDisplayManager;
import android.opengl.Matrix;
import android.provider.Settings;

import java.util.Arrays;

/** Controls color transformation for display engine modes. */
final class DisplayEngineController extends TintController {

    private final float[] mMatrix = new float[16];
    private float mHue = 0.0f;
    private float mContrast = 1.0f;
    private float mValue = 1.0f;
    private float mSaturation = 1.0f;
    private int mRed = 255;
    private int mGreen = 255;
    private int mBlue = 255;

    // Mode presets {r ,g ,b ,sat, cont, val}
    private static final int[] X_REALITY_MODE = {252, 227, 228, 278, 260, 264};
    private static final int[] TRILUMINOUS_PRO_MODE = {250, 235, 255, 260, 260, 280};
    private static final int[] VIVID_MODE = {240, 240, 225, 281, 255, 262};

    @Override
    public void setUp(Context context, boolean needsLinear) {
    }

    @Override
    public float[] getMatrix() {
        return Arrays.copyOf(mMatrix, mMatrix.length);
    }

    @Override
    public void setMatrix(int rgb) {
        Matrix.setIdentityM(mMatrix, 0);
        mMatrix[0] = Color.red(rgb) / 255.0f;
        mMatrix[5] = Color.green(rgb) / 255.0f;
        mMatrix[10] = Color.blue(rgb) / 255.0f;
        applyHue(mMatrix, mHue);
        applyContrast(mMatrix, mContrast);
        applyValue(mMatrix, mValue);
        applySaturation(mMatrix, mSaturation);
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
        int displayMode =
                Settings.Secure.getIntForUser(
                        context.getContentResolver(),
                        Settings.Secure.DISPLAY_ENGINE_MODE,
                        0,
                        userId);
        int[] mode;
        switch (displayMode) {
            case 1:
                mode = X_REALITY_MODE;
                mHue = 0.0f; 
                break;
            case 2:
                mode = VIVID_MODE;
                mHue = 0.0f; 
                break;
            case 3:
                mode = TRILUMINOUS_PRO_MODE;
                mHue = 3f;
                break;
            case 0:
            default:
                mRed = 255;
                mGreen = 255;
                mBlue = 255;
                mContrast = 1.0f;
                mValue = 1.0f;
                mSaturation = 1.0f;
                mode = null;
                mHue = 0.0f; 
        }
        if (mode != null) {
            mRed = mode[0];
            mGreen = mode[1];
            mBlue = mode[2];
            mSaturation = mode[3] / 255.0f;
            mContrast = mode[4] / 255.0f;
            mValue = mode[5] / 255.0f;
        }
        setMatrix(Color.rgb(mRed, mGreen, mBlue));
    }

    private void applyHue(float[] matrix, float hue) {
        float angle = hue * (float) Math.PI / 180;
        float cosA = (float) Math.cos(angle);
        float sinA = (float) Math.sin(angle);
        float[] hueMatrix = {
            0.213f + cosA * 0.787f - sinA * 0.213f, 0.715f - cosA * 0.715f - sinA * 0.715f, 0.072f - cosA * 0.072f + sinA * 0.928f, 0,
            0.213f - cosA * 0.213f + sinA * 0.143f, 0.715f + cosA * 0.285f + sinA * 0.140f, 0.072f - cosA * 0.072f - sinA * 0.283f, 0,
            0.213f - cosA * 0.213f - sinA * 0.787f, 0.715f - cosA * 0.715f + sinA * 0.715f, 0.072f + cosA * 0.928f + sinA * 0.072f, 0,
            0, 0, 0, 1
        };
        Matrix.multiplyMM(matrix, 0, hueMatrix, 0, matrix, 0);
    }

    private void applyContrast(float[] matrix, float contrast) {
        float translate = (1 - contrast) / 2;
        float[] contrastMatrix = {
            contrast, 0, 0, 0,
            0, contrast, 0, 0,
            0, 0, contrast, 0,
            translate, translate, translate, 1
        };
        Matrix.multiplyMM(matrix, 0, contrastMatrix, 0, matrix, 0);
    }

    private void applyValue(float[] matrix, float value) {
        float[] valueMatrix = {
            value, 0, 0, 0,
            0, value, 0, 0,
            0, 0, value, 0,
            0, 0, 0, 1
        };
        Matrix.multiplyMM(matrix, 0, valueMatrix, 0, matrix, 0);
    }

    private void applySaturation(float[] matrix, float saturation) {
        float rw = 0.3086f, gw = 0.6094f, bw = 0.0820f;
        float invSat = 1.0f - saturation;
        float R = invSat * rw, G = invSat * gw, B = invSat * bw;
        float[] saturationMatrix = {
            R + saturation, G, B, 0,
            R, G + saturation, B, 0,
            R, G, B + saturation, 0,
            0, 0, 0, 1
        };
        Matrix.multiplyMM(matrix, 0, saturationMatrix, 0, matrix, 0);
    }
}
