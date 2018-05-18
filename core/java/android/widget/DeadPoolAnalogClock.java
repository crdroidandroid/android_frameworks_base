/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.widget;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.format.Time;
import android.util.AttributeSet;
import android.widget.RemoteViews.RemoteView;

/**
 * This widget display an analogic clock with two hands for hours and
 * minutes.
 *
 * @attr ref android.R.styleable#DeadPoolAnalogClock_dial
 * @attr ref android.R.styleable#DeadPoolAnalogClock_hand_hour
 * @attr ref android.R.styleable#DeadPoolAnalogClock_hand_minute
 * @deprecated This widget is no longer supported.
 */
@RemoteView
@Deprecated
public class DeadPoolAnalogClock extends android.widget.CustomAnalogClock {

    private boolean mChanged;

    public DeadPoolAnalogClock(Context context) {
        this(context, null);
    }

    public DeadPoolAnalogClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DeadPoolAnalogClock(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DeadPoolAnalogClock(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final Resources r = context.getResources();
        final TypedArray a = context.obtainStyledAttributes(
                attrs, com.android.internal.R.styleable.DeadPoolAnalogClock, defStyleAttr, defStyleRes);

        mDial = a.getDrawable(com.android.internal.R.styleable.DeadPoolAnalogClock_deadpool_dial);
        if (mDial == null) {
            mDial = context.getDrawable(com.android.internal.R.drawable.deadpool_clock_dial);
            mDialAmbient = context.getDrawable(com.android.internal.R.drawable.deadpool_clock_dial_ambient);
        }

        mHourHand = a.getDrawable(com.android.internal.R.styleable.DeadPoolAnalogClock_deadpool_hand_hour);
        if (mHourHand == null) {
            mHourHand = context.getDrawable(com.android.internal.R.drawable.deadpool_clock_hand_hour);
        }

        mMinuteHand = a.getDrawable(com.android.internal.R.styleable.DeadPoolAnalogClock_deadpool_hand_minute);
        if (mMinuteHand == null) {
            mMinuteHand = context.getDrawable(com.android.internal.R.drawable.deadpool_clock_hand_minute);
        }

        a.recycle();

        mCalendar = new Time();

        mDialWidth = mDial.getIntrinsicWidth();
        mDialHeight = mDial.getIntrinsicHeight();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        boolean changed = mChanged;
        if (changed) {
            mChanged = false;
        }

        int availableWidth = mRight - mLeft;
        int availableHeight = mBottom - mTop;

        int x = availableWidth / 2;
        int y = availableHeight / 2;

        final Drawable dial = mIsAmbientDisplay ? mDialAmbient : mDial;
        int w = dial.getIntrinsicWidth();
        int h = dial.getIntrinsicHeight();

        boolean scaled = false;

        if (availableWidth < w || availableHeight < h) {
            scaled = true;
            float scale = Math.min((float) availableWidth / (float) w,
                    (float) availableHeight / (float) h);
            canvas.save();
            canvas.scale(scale, scale, x, y);
        }

        if (changed) {
            dial.setBounds(x - (w / 2), y - (h / 2), x + (w / 2), y + (h / 2));
        }
        dial.draw(canvas);

        canvas.save();
        canvas.rotate(mHour / 12.0f * 360.0f, x, y);
        final Drawable hourHand = mHourHand;
        if (changed) {
            w = hourHand.getIntrinsicWidth();
            h = hourHand.getIntrinsicHeight();
            hourHand.setBounds(x - (w / 2), y - (h / 2), x + (w / 2), y + (h / 2));
        }
        if (mIsAmbientDisplay) {
            hourHand.setTint(Color.GRAY);
        } else {
            hourHand.setTintList(null);
        }
        hourHand.draw(canvas);
        canvas.restore();

        canvas.save();
        canvas.rotate(mMinutes / 60.0f * 360.0f, x, y);

        final Drawable minuteHand = mMinuteHand;
        if (changed) {
            w = minuteHand.getIntrinsicWidth();
            h = minuteHand.getIntrinsicHeight();
            minuteHand.setBounds(x - (w / 2), y - (h / 2), x + (w / 2), y + (h / 2));
        }
        if (mIsAmbientDisplay) {
            minuteHand.setTint(Color.GRAY);
        } else {
            minuteHand.setTintList(null);
        }
        minuteHand.draw(canvas);
        canvas.restore();

        if (scaled) {
            canvas.restore();
        }
    }
}
