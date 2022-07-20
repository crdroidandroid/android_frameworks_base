package com.google.android.systemui.smartspace;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.viewpager.widget.ViewPager;

public class InterceptingViewPager extends ViewPager {
    public boolean mHasPerformedLongPress;
    public boolean mHasPostedLongPress;
    public final Runnable mLongPressCallback;
    public final EventProxy mSuperOnIntercept;
    public final EventProxy mSuperOnTouch;

    public interface EventProxy {
        boolean delegateEvent(MotionEvent motionEvent);
    }

    public boolean superOnTouchEvent(MotionEvent event) {
        return super.onTouchEvent(event);
    }

    public boolean superOnInterceptTouchEvent(MotionEvent event) {
        return super.onInterceptTouchEvent(event);
    }

    public InterceptingViewPager(Context context) {
        super(context);
        this.mSuperOnTouch = this::superOnTouchEvent;
        this.mSuperOnIntercept = this::superOnInterceptTouchEvent;
        this.mLongPressCallback = this::triggerLongPress;
    }

    public InterceptingViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.mSuperOnTouch = this::superOnTouchEvent;
        this.mSuperOnIntercept = this::superOnInterceptTouchEvent;
        this.mLongPressCallback = this::triggerLongPress;
    }

    public boolean onInterceptTouchEvent(MotionEvent event) {
        return handleTouchOverride(event, this.mSuperOnIntercept);
    }

    public boolean onTouchEvent(MotionEvent event) {
        return handleTouchOverride(event, this.mSuperOnTouch);
    }

    private boolean handleTouchOverride(MotionEvent event, EventProxy proxy) {
        int action = event.getAction();
        if (action == 0) {
            this.mHasPerformedLongPress = false;
            if (isLongClickable()) {
                cancelScheduledLongPress();
                this.mHasPostedLongPress = true;
                postDelayed(this.mLongPressCallback, ViewConfiguration.getLongPressTimeout());
            }
        } else if (action == 1 || action == 3) {
            cancelScheduledLongPress();
        }
        if (this.mHasPerformedLongPress) {
            cancelScheduledLongPress();
            return true;
        } else if (!proxy.delegateEvent(event)) {
            return false;
        } else {
            cancelScheduledLongPress();
            return true;
        }
    }

    private void cancelScheduledLongPress() {
        if (this.mHasPostedLongPress) {
            this.mHasPostedLongPress = false;
            removeCallbacks(this.mLongPressCallback);
        }
    }

    public void triggerLongPress() {
        this.mHasPerformedLongPress = true;
        if (performLongClick()) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }
}
