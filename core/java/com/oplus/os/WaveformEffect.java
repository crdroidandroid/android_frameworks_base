/*
 * Copyright (C) 2022 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.oplus.os;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Slog;

public class WaveformEffect implements Parcelable {

    private static final String TAG = "WaveformEffect";

    public static final Parcelable.Creator<WaveformEffect> CREATOR =
            new Parcelable.Creator<WaveformEffect>() {
        @Override
        public WaveformEffect createFromParcel(Parcel in) {
            return new WaveformEffect(in);
        }

        @Override
        public WaveformEffect[] newArray(int size) {
            return new WaveformEffect[size];
        }
    };

    private int mEffectType;
    private boolean mEffectLoop;

    private WaveformEffect() {
        mEffectType = -1;
        mEffectLoop = false;
    }

    public int getEffectType() {
        return mEffectType;
    }

    public boolean getEffectLoop() {
        return mEffectLoop;
    }

    public static class Builder {
        private int mEffectType;
        private boolean mEffectLoop;

        public Builder() {
            mEffectType = -1;
            mEffectLoop = false;
        }

        public Builder(WaveformEffect effect) {
            mEffectType = -1;
            mEffectLoop = false;
        }

        public WaveformEffect build() {
            WaveformEffect effect = new WaveformEffect();
            effect.mEffectType = mEffectType;
            effect.mEffectLoop = mEffectLoop;
            return effect;
        }

        public Builder setEffectType(int type) {
            mEffectType = type;
            return this;
        }

        public Builder setEffectLoop(boolean loop) {
            mEffectLoop = loop;
            return this;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mEffectType);
        dest.writeBoolean(mEffectLoop);
    }

    private WaveformEffect(Parcel in) {
        mEffectType = in.readInt();
        mEffectLoop = in.readBoolean();
    }

    @Override
    public String toString() {
        return String.valueOf(mEffectType);
    }
}
