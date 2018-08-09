package com.android.systemui.statusbar.screen_gestures;

import android.content.Context;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.internal.util.gesture.EdgeGesturePosition;
import com.android.systemui.Dependency;
import com.android.systemui.tuner.TunerService;

public class ScreenGesturesView extends FrameLayout implements TunerService.Tunable {

    public static final boolean DEBUG = false;

    public static final int SIGNIFICANT_MOVE = 10;

    private static final String TAG = "ScreenGesturesView";

    public static class GestureType {
        public static final int NONE = 0;
        public static final int HOME = 1;
        public static final int BACK = 1 << 1;
        public static final int RECENTS = 1 << 2;
    }

    private OnGestureCompletedListener onGestureCompletedListener;

    private int initialX = -1;
    private int initialY = -1;

    private int lastX = -1;
    private int lastY = -1;

    private int possibleGestures = GestureType.NONE;

    private Vibrator vibrator;

    private Handler handler = new Handler(Looper.getMainLooper());

    private BackArrowView leftArrowView;
    private BackArrowView rightArrowView;

    private int mFeedbackDuration, mLongPressDuration, mBackEdges, mBackEdgesLandscape;
    private boolean mShowUIFeedback;

    private static final String EDGE_GESTURES_FEEDBACK_DURATION =
            Settings.Secure.EDGE_GESTURES_FEEDBACK_DURATION;
    private static final String EDGE_GESTURES_LONG_PRESS_DURATION =
            Settings.Secure.EDGE_GESTURES_LONG_PRESS_DURATION;
    private static final String EDGE_GESTURES_BACK_SHOW_UI_FEEDBACK =
            Settings.Secure.EDGE_GESTURES_BACK_SHOW_UI_FEEDBACK;
    private static final String EDGE_GESTURES_BACK_EDGES =
            Settings.Secure.EDGE_GESTURES_BACK_EDGES;
    private static final String EDGE_GESTURES_LANDSCAPE_BACK_EDGES =
            Settings.Secure.EDGE_GESTURES_LANDSCAPE_BACK_EDGES;

    public ScreenGesturesView(Context context) {
        this(context, null);
    }

    public ScreenGesturesView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScreenGesturesView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ScreenGesturesView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        leftArrowView = new BackArrowView(context);
        leftArrowView.setReversed(true);
        leftArrowView.name = "LEFT";

        rightArrowView = new BackArrowView(context);
        rightArrowView.name = "RIGHT";

        addView(leftArrowView);
        addView(rightArrowView);

        final float density = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams((int) (48 * density), ViewGroup.LayoutParams.MATCH_PARENT);
        FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams((int) (48 * density), ViewGroup.LayoutParams.MATCH_PARENT);
        rightParams.gravity = Gravity.RIGHT;
        rightParams.rightMargin = 0;
        leftArrowView.setLayoutParams(leftParams);
        rightArrowView.setLayoutParams(rightParams);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setVisibility(View.GONE);

        Dependency.get(TunerService.class).addTunable(this,
                EDGE_GESTURES_FEEDBACK_DURATION,
                EDGE_GESTURES_LONG_PRESS_DURATION,
                EDGE_GESTURES_BACK_SHOW_UI_FEEDBACK,
                EDGE_GESTURES_BACK_EDGES,
                EDGE_GESTURES_LANDSCAPE_BACK_EDGES);

        vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case EDGE_GESTURES_FEEDBACK_DURATION:
                mFeedbackDuration =
                        newValue == null ? 100 : Integer.parseInt(newValue);
                break;
            case EDGE_GESTURES_LONG_PRESS_DURATION:
                mLongPressDuration =
                        newValue == null ? 500 : Integer.parseInt(newValue);
                break;
            case EDGE_GESTURES_BACK_SHOW_UI_FEEDBACK:
                mShowUIFeedback =
                        newValue != null && Integer.parseInt(newValue) != 0;
                break;
            case EDGE_GESTURES_BACK_EDGES:
                mBackEdges =
                        newValue == null ? 0 : Integer.parseInt(newValue);
                break;
            case EDGE_GESTURES_LANDSCAPE_BACK_EDGES:
                mBackEdgesLandscape =
                        newValue == null ? 0 : Integer.parseInt(newValue);
                break;
            default:
                break;
        }
    }

    public void startGesture(int initialX, int initialY, EdgeGesturePosition position) {
        if (DEBUG) Log.d(TAG, "startGesture: Gesture started");

        this.initialX = initialX;
        this.initialY = initialY;

        this.lastX = initialX;
        this.lastY = initialY;

        int backGestureEdgesFlag = getContext().getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT ?
                mBackEdges : mBackEdgesLandscape;

        setVisibility(View.VISIBLE);

        if ((position.FLAG & backGestureEdgesFlag) != 0) {
            possibleGestures = GestureType.BACK;

            if (mShowUIFeedback) {
                if (initialX < getWidth() / 2) {
                    leftArrowView.onTouchStarted(initialX - leftArrowView.getLeft(), initialY - leftArrowView.getTop());
                } else {
                    rightArrowView.onTouchStarted(initialX - rightArrowView.getLeft(), initialY - rightArrowView.getTop());
                }
            }
        } else if ((position.FLAG & EdgeGesturePosition.BOTTOM.FLAG) != 0) {
            possibleGestures = GestureType.HOME | GestureType.RECENTS;
        } else {
            if (onGestureCompletedListener != null) {
                onGestureCompletedListener.onGestureCompleted(GestureType.NONE);
            }
            return;
        }
    }

    private void stopGesture(int posX, int posY) {
        if (DEBUG) Log.d(TAG, "stopGesture: Gesture stopped");

        stopLongPress();

        if (onGestureCompletedListener == null) return;

        if (DEBUG) Log.d(TAG, "stopGesture: Initial x: " + String.valueOf(initialX) + ", final x: " + String.valueOf(posX));
        if (DEBUG) Log.d(TAG, "stopGesture: Initial y: " + String.valueOf(initialY) + ", final y: " + String.valueOf(posY));

        final int threshold = 20;
        boolean canSendHome = (possibleGestures & GestureType.HOME) != 0;
        if (canSendHome && (posY - initialY < -threshold)) {
            if (DEBUG) Log.d(TAG, "stopGesture: Home");
            vibrator.vibrate(mFeedbackDuration);
            onGestureCompletedListener.onGestureCompleted(GestureType.HOME);
            return;
        }

        boolean canSendBack = (possibleGestures & GestureType.BACK) != 0;
        if (canSendBack && (Math.abs(posX - initialX) > threshold)) {
            if (DEBUG) Log.d(TAG, "stopGesture: Back");
            vibrator.vibrate(mFeedbackDuration);
            onGestureCompletedListener.onGestureCompleted(GestureType.BACK);
            return;
        }

        if (DEBUG) Log.d(TAG, "stopGesture: None");
        onGestureCompletedListener.onGestureCompleted(GestureType.NONE);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (DEBUG) Log.d(TAG, "onTouchEvent: DOWN");
                return true;
            case MotionEvent.ACTION_MOVE:
                if (DEBUG) Log.d(TAG, "onTouchEvent: MOVE");
                int x = (int) event.getX();
                int y = (int) event.getY();
                if (Math.abs(x - lastX) > SIGNIFICANT_MOVE || Math.abs(y - lastY) > SIGNIFICANT_MOVE) {
                    stopLongPress();
                    startLongPress();
                }

                lastX = x;
                lastY = y;

                if ((possibleGestures & GestureType.BACK) != 0) {
                    leftArrowView.onTouchMoved(x - leftArrowView.getLeft(), y - leftArrowView.getTop());
                    rightArrowView.onTouchMoved(x - rightArrowView.getLeft(), y - rightArrowView.getTop());
                }

                return false;
            case MotionEvent.ACTION_UP:
                if (DEBUG) Log.d(TAG, "onTouchEvent: UP");

                if (mShowUIFeedback) {
                    leftArrowView.onTouchEnded();
                    rightArrowView.onTouchEnded();
                }

                if (possibleGestures != GestureType.NONE) {
                    stopGesture((int) event.getX(), (int) event.getY());
                    handler.postDelayed(() -> setVisibility(View.GONE), 10);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (DEBUG) Log.d(TAG, "onTouchEvent: CANCEL");

                if (mShowUIFeedback) {
                    leftArrowView.onTouchEnded();
                    rightArrowView.onTouchEnded();
                }

                stopGesture((int) event.getX(), (int) event.getY());
                handler.postDelayed(() -> setVisibility(View.GONE), 10);

                return true;
        }
        return false;
    }

    private void startLongPress() {
        if (DEBUG) Log.d(TAG, "startLongPress: scheduling long press");
        handler.postDelayed(longPressRunnable, mLongPressDuration);
    }

    private void stopLongPress() {
        if (DEBUG) Log.d(TAG, "stopLongPress: cancellling long press");
        handler.removeCallbacks(longPressRunnable);
    }

    private Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            boolean canSendRecents = (possibleGestures & GestureType.RECENTS) != 0;
            if (canSendRecents) {
                possibleGestures = GestureType.NONE;

                leftArrowView.onTouchEnded();
                rightArrowView.onTouchEnded();

                setVisibility(View.GONE);
                if (onGestureCompletedListener != null) {
                    onGestureCompletedListener.onGestureCompleted(GestureType.RECENTS);
                }
                vibrator.vibrate(mFeedbackDuration);
            }
        }
    };

    public OnGestureCompletedListener getOnGestureCompletedListener() {
        return onGestureCompletedListener;
    }

    public void setOnGestureCompletedListener(OnGestureCompletedListener onGestureCompletedListener) {
        this.onGestureCompletedListener = onGestureCompletedListener;
    }

    interface OnGestureCompletedListener {
        void onGestureCompleted(int gestureType);
    }
}
