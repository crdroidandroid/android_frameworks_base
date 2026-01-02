package com.google.android.systemui.smartspace;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.viewpager.widget.ViewPager;

import com.android.systemui.res.R;

import java.util.function.Predicate;

public class InterceptingViewPager extends ViewPager {
    public boolean mHasPerformedLongPress;
    public boolean mHasPostedLongPress;
    public final Runnable mLongPressCallback;
    public final Predicate<MotionEvent> mSuperOnIntercept;
    public final Predicate<MotionEvent> mSuperOnTouch;

    public InterceptingViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);

        mSuperOnTouch = super::onTouchEvent;
        mSuperOnIntercept = super::onInterceptTouchEvent;
        mLongPressCallback = () -> {
            mHasPerformedLongPress = true;
            if (performLongClick()) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
        };
    }

    public final void cancelScheduledLongPress() {
        if (mHasPostedLongPress) {
            mHasPostedLongPress = false;
            removeCallbacks(mLongPressCallback);
        }
    }

    @Override
    public final AccessibilityNodeInfo createAccessibilityNodeInfo() {
        AccessibilityNodeInfo info = super.createAccessibilityNodeInfo();
        AccessibilityNodeInfoCompat.wrap(info).setRoleDescription(getContext().getString(R.string.smartspace_role_desc));
        return info;
    }

    public final boolean handleTouchOverride(MotionEvent event, Predicate<MotionEvent> superMethod) {
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            mHasPerformedLongPress = false;
            if (isLongClickable()) {
                cancelScheduledLongPress();
                mHasPostedLongPress = true;
                postDelayed(mLongPressCallback, ViewConfiguration.getLongPressTimeout());
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            cancelScheduledLongPress();
        }

        if (mHasPerformedLongPress) {
            cancelScheduledLongPress();
            return true;
        }

        if (!superMethod.test(event)) {
            return false;
        }

        cancelScheduledLongPress();
        return true;
    }

    @Override
    public final boolean onInterceptTouchEvent(MotionEvent event) {
        return handleTouchOverride(event, mSuperOnIntercept);
    }

    @Override
    public final boolean onTouchEvent(MotionEvent event) {
        return handleTouchOverride(event, mSuperOnTouch);
    }

    public InterceptingViewPager(Context context) {
        super(context);

        mSuperOnTouch = super::onTouchEvent;
        mSuperOnIntercept = super::onInterceptTouchEvent;
        mLongPressCallback = () -> {
            mHasPerformedLongPress = true;
            if (performLongClick()) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
        };
    }
}
