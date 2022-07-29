/*
 * Copyright (C) 2020 The exTHmUI Open Source Project
 *               2022 Project Kaleidoscope
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

package com.android.systemui.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.icu.text.Bidi;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

public class LyricTextView extends TextView {

    private boolean mStopped = true;
    private int mTextWidth;
    private int mScrollSpeed = 4;
    private int mOffset = 0;
    private String mText;
    private boolean mTextRtl;

    private static final int START_SCROLL_DELAY = 500;
    private static final int INVALIDATE_DELAY = 10;

    private final Runnable mStartScrollRunnable = this::startScroll;
    private final Runnable mInvalidateRunnable = this::invalidate;

    public LyricTextView(Context context) {
        this(context, null);
    }

    public LyricTextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0); // com.android.internal.R.attr.textViewStyle
    }

    public LyricTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public LyricTextView(Context context, AttributeSet attrs, int defStyleAttr,
                         int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mStartScrollRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        stopScroll();
        if (text != null) {
            mText = text.toString();
            mTextRtl = Bidi.getBaseDirection(mText) == Bidi.RTL;
            if (mTextRtl) {
                getPaint().setTextAlign(Paint.Align.RIGHT);
                mOffset = -1;
            } else {
                getPaint().setTextAlign(Paint.Align.LEFT);
                if (View.LAYOUT_DIRECTION_RTL == getLayoutDirection()) {
                    mOffset = -1;
                } else {
                    mOffset = 0;
                }
            }
            mTextWidth = (int) getPaint().measureText(mText);
            postInvalidate();
            postDelayed(mStartScrollRunnable, START_SCROLL_DELAY);
        } else {
            mText = null;
        }
    }

    @Override
    public void setTextColor(int color) {
        getPaint().setColor(color);
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean viewRtl = View.LAYOUT_DIRECTION_RTL == getLayoutDirection();
        if (mTextRtl && mOffset == -1) {
            mOffset = getWidth();
        } else if (viewRtl && mOffset == -1) {
            mOffset = Math.max(0, getWidth() - mTextWidth);
        }
        if (canvas != null && mText != null) {
            float y = getHeight() / 2.0f + Math.abs(getPaint().ascent() + getPaint().descent()) / 2;
            canvas.drawText(mText, mOffset, y, getPaint());
        }
        if (!mStopped) {
            if (!mTextRtl) {
                if (getWidth() - mOffset + mScrollSpeed >= mTextWidth) {
                    mOffset = getWidth() > mTextWidth && !viewRtl ? 0 : getWidth() - mTextWidth;
                    stopScroll();
                } else {
                    mOffset -= mScrollSpeed;
                }
            } else {
                if (mOffset + mScrollSpeed >= mTextWidth) {
                    mOffset = Math.max(getWidth(), mTextWidth);
                    stopScroll();
                } else {
                    mOffset += mScrollSpeed;
                }
            }
            invalidateAfter(INVALIDATE_DELAY);
        }
    }

    private void invalidateAfter(long delay) {
        removeCallbacks(mInvalidateRunnable);
        postDelayed(mInvalidateRunnable, delay);
    }


    public void startScroll() {
        mStopped = false;
        postInvalidate();
    }

    public void stopScroll() {
        mStopped = true;
        removeCallbacks(mStartScrollRunnable);
        postInvalidate();
    }

    public void setScrollSpeed(int scrollSpeed) {
        this.mScrollSpeed = scrollSpeed;
    }
}
