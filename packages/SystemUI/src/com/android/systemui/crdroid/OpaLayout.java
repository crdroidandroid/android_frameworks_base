package com.android.systemui.crdroid;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Property;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.android.app.animation.Interpolators;
import com.android.systemui.R;
import com.android.systemui.Dependency;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.navigationbar.buttons.ButtonInterface;
import com.android.systemui.navigationbar.buttons.KeyButtonDrawable;
import com.android.systemui.navigationbar.buttons.KeyButtonView;
import java.util.ArrayList;

public class OpaLayout extends FrameLayout implements ButtonInterface {

    private static final int OPA_FADE_IN_DURATION = 50;
    private static final int OPA_FADE_OUT_DURATION = 250;

    private final Interpolator HOME_DISAPPEAR_INTERPOLATOR;
    private final ArrayList<View> mAnimatedViews;
    private int mAnimationState;
    private View mBlue;
    private View mBottom;
    private final ArraySet<Animator> mCurrentAnimators;
    private boolean mDelayTouchFeedback;
    private final Runnable mDiamondAnimation;
    private boolean mDiamondAnimationDelayed;
    private final Interpolator mDiamondInterpolator;
    private long mGestureAnimationSetDuration;
    private AnimatorSet mGestureAnimatorSet;
    private AnimatorSet mGestureLineSet;
    private int mGestureState;
    private View mGreen;
    private KeyButtonView mHome;
    private int mHomeDiameter;
    private boolean mIsPressed;
    private boolean mIsVertical;
    private View mLeft;
    private final OverviewProxyService.OverviewProxyListener mOverviewProxyListener;
    private OverviewProxyService mOverviewProxyService;
    private View mRed;
    private Resources mResources;
    private final Runnable mRetract;
    private View mRight;
    private long mStartTime;
    private View mTop;
    private int mTouchDownX;
    private int mTouchDownY;
    private ImageView mWhite;
    private boolean mWindowVisible;
    private View mYellow;

    private Context mContext;
    private Handler mHandler;
    private SettingsObserver mSettingsObserver;
    private boolean mAllowAnimation = true;

    public OpaLayout(Context context) {
        this(context, null);
    }

    public OpaLayout(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    public OpaLayout(Context context, AttributeSet attributeSet, int i, int i2) {
        super(context, attributeSet, i, i2);
        HOME_DISAPPEAR_INTERPOLATOR = new PathInterpolator(0.65f, 0.0f, 1.0f, 1.0f);
        mDiamondInterpolator = new PathInterpolator(0.2f, 0.0f, 0.2f, 1.0f);
        mCurrentAnimators = new ArraySet<>();
        mAnimatedViews = new ArrayList<>();
        mAnimationState = 0;
        mGestureState = 0;
        mRetract = new Runnable() {
            @Override
            public void run() {
                cancelCurrentAnimation("retract");
                startRetractAnimation();
                hideAllOpa();
            }
        };
        mOverviewProxyListener = new OverviewProxyService.OverviewProxyListener() {
            @Override
            public void onConnectionChanged(boolean z) {
                updateOpaLayout();
            }
        };
        mDiamondAnimation = new Runnable() {
            @Override
            public final void run() {
                if (mCurrentAnimators.isEmpty()) {
                    startDiamondAnimation();
                }
            }
        };
        mContext = context;
        mHandler = new Handler();
        mSettingsObserver = new SettingsObserver(mHandler);
    }

    public OpaLayout(Context context, AttributeSet attributeSet, int i) {
        this(context, attributeSet, i, 0);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mResources = getResources();
        mBlue = findViewById(R.id.blue);
        mRed = findViewById(R.id.red);
        mYellow = findViewById(R.id.yellow);
        mGreen = findViewById(R.id.green);
        mWhite = (ImageView) findViewById(R.id.white);
        mHome = (KeyButtonView) findViewById(R.id.home_button);
        mHomeDiameter = mResources.getDimensionPixelSize(R.dimen.opa_disabled_home_diameter);
        mAnimatedViews.add(mBlue);
        mAnimatedViews.add(mRed);
        mAnimatedViews.add(mYellow);
        mAnimatedViews.add(mGreen);
        mAnimatedViews.add(mWhite);
        mOverviewProxyService = (OverviewProxyService) Dependency.get(OverviewProxyService.class);
        mSettingsObserver.observe();
        hideAllOpa();
    }

    @Override
    public void onWindowVisibilityChanged(int i) {
        super.onWindowVisibilityChanged(i);
        mWindowVisible = i == 0;
        if (i == 0) {
            updateOpaLayout();
            return;
        }
        cancelCurrentAnimation("winVis=" + i);
        skipToStartingValue();
    }

    @Override
    public void setOnLongClickListener(View.OnLongClickListener onLongClickListener) {
        mHome.setOnLongClickListener(onLongClickListener);
    }

    @Override
    public void setOnTouchListener(View.OnTouchListener onTouchListener) {
        mHome.setOnTouchListener(onTouchListener);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent motionEvent) {
        boolean z;
        if (ValueAnimator.areAnimatorsEnabled() && mGestureState == 0) {
            int action = motionEvent.getAction();
            if (action != 0) {
                if (action != 1) {
                    if (action != 2) {
                        if (action != 3) {
                            return false;
                        }
                    } else {
                        final float quickStepTouchSlopPx = QuickStepContract.getQuickStepTouchSlopPx(getContext());
                        if (Math.abs(motionEvent.getRawX() - mTouchDownX) > quickStepTouchSlopPx || Math.abs(motionEvent.getRawY() - mTouchDownY) > quickStepTouchSlopPx) {
                            abortCurrentGesture();
                            return false;
                        }
                        return false;
                    }
                }
                if (mDiamondAnimationDelayed) {
                    if (mIsPressed) {
                        postDelayed(mRetract, 200);
                    }
                } else if (mAnimationState == 1) {
                    removeCallbacks(mRetract);
                    postDelayed(mRetract, 100 - (SystemClock.elapsedRealtime() - mStartTime));
                    removeCallbacks(mDiamondAnimation);
                    cancelLongPress();
                    return false;
                } else if (mIsPressed) {
                    mRetract.run();
                }
                mIsPressed = false;
            } else {
                mTouchDownX = (int) motionEvent.getRawX();
                mTouchDownY = (int) motionEvent.getRawY();
                if (mCurrentAnimators.isEmpty()) {
                    z = false;
                } else if (mAnimationState != 2) {
                    return false;
                } else {
                    endCurrentAnimation("touchDown");
                    z = true;
                }
                mStartTime = SystemClock.elapsedRealtime();
                mIsPressed = true;
                removeCallbacks(mDiamondAnimation);
                removeCallbacks(mRetract);
                if (!mDelayTouchFeedback || z) {
                    mDiamondAnimationDelayed = false;
                    startDiamondAnimation();
                } else {
                    mDiamondAnimationDelayed = true;
                    postDelayed(mDiamondAnimation, (long) ViewConfiguration.getTapTimeout());
                }
            }
        }
        return false;
    }

    @Override
    public void setAccessibilityDelegate(View.AccessibilityDelegate accessibilityDelegate) {
        super.setAccessibilityDelegate(accessibilityDelegate);
        mHome.setAccessibilityDelegate(accessibilityDelegate);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        mWhite.setImageDrawable(drawable);
    }

    @Override
    public void abortCurrentGesture() {
        Trace.beginSection("OpaLayout.abortCurrentGesture: animState=" + mAnimationState);
        Trace.endSection();
        mHome.abortCurrentGesture();
        mIsPressed = false;
        mDiamondAnimationDelayed = false;
        removeCallbacks(mDiamondAnimation);
        cancelLongPress();
        int i = mAnimationState;
        if (i == 3 || i == 1) {
            mRetract.run();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        updateOpaLayout();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mOverviewProxyService.addCallback(mOverviewProxyListener);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mOverviewProxyService.removeCallback(mOverviewProxyListener);
    }

    private class SettingsObserver extends ContentObserver {

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIXEL_NAV_ANIMATION),
                    false, this, UserHandle.USER_ALL);
            update();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            update();
        }

        public void update() {
            mAllowAnimation = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.PIXEL_NAV_ANIMATION, 1, UserHandle.USER_CURRENT) == 1;
        }
    }

    private void startDiamondAnimation() {
        if (allowAnimations()) {
            mCurrentAnimators.clear();
            setDotsVisible();
            mCurrentAnimators.addAll((ArraySet<? extends Animator>) getDiamondAnimatorSet());
            mAnimationState = 1;
            startAll(mCurrentAnimators);
            return;
        }
        skipToStartingValue();
    }

    private void startRetractAnimation() {
        if (allowAnimations()) {
            mCurrentAnimators.clear();
            mCurrentAnimators.addAll((ArraySet<? extends Animator>) getRetractAnimatorSet());
            mAnimationState = 2;
            startAll(mCurrentAnimators);
            return;
        }
        skipToStartingValue();
    }

    private void startLineAnimation() {
        if (allowAnimations()) {
            mCurrentAnimators.clear();
            mCurrentAnimators.addAll((ArraySet<? extends Animator>) getLineAnimatorSet());
            mAnimationState = 3;
            startAll(mCurrentAnimators);
            return;
        }
        skipToStartingValue();
    }

    private void startCollapseAnimation() {
        if (allowAnimations()) {
            mCurrentAnimators.clear();
            mCurrentAnimators.addAll((ArraySet<? extends Animator>) getCollapseAnimatorSet());
            mAnimationState = 3;
            startAll(mCurrentAnimators);
            return;
        }
        skipToStartingValue();
    }

    private void startAll(ArraySet<Animator> arraySet) {
        showAllOpa();
        for (int size = arraySet.size() - 1; size >= 0; size--) {
            arraySet.valueAt(size).start();
        }
        for (int size2 = mAnimatedViews.size() - 1; size2 >= 0; size2--) {
            mAnimatedViews.get(size2).invalidate();
        }
    }

    private boolean allowAnimations() {
        return mAllowAnimation && isAttachedToWindow() && mWindowVisible;
    }

    private ArraySet<Animator> getDiamondAnimatorSet() {
        ArraySet<Animator> arraySet = new ArraySet<>();
        View view = mTop;
        arraySet.add(getPropertyAnimator(view, View.Y, (-OpaUtils.getPxVal(mResources, R.dimen.opa_diamond_translation)) + view.getY(), 200, mDiamondInterpolator));
        arraySet.add(getPropertyAnimator(mTop, FrameLayout.SCALE_X, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        arraySet.add(getPropertyAnimator(mTop, FrameLayout.SCALE_Y, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        View view2 = mBottom;
        arraySet.add(getPropertyAnimator(view2, View.Y, view2.getY() + OpaUtils.getPxVal(mResources, R.dimen.opa_diamond_translation), 200, mDiamondInterpolator));
        arraySet.add(getPropertyAnimator(mBottom, FrameLayout.SCALE_X, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        arraySet.add(getPropertyAnimator(mBottom, FrameLayout.SCALE_Y, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        View view3 = mLeft;
        arraySet.add(getPropertyAnimator(view3, View.X, view3.getX() + (-OpaUtils.getPxVal(mResources, R.dimen.opa_diamond_translation)), 200, mDiamondInterpolator));
        arraySet.add(getPropertyAnimator(mLeft, FrameLayout.SCALE_X, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        arraySet.add(getPropertyAnimator(mLeft, FrameLayout.SCALE_Y, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        View view4 = mRight;
        arraySet.add(getPropertyAnimator(view4, View.X, view4.getX() + OpaUtils.getPxVal(mResources, R.dimen.opa_diamond_translation), 200, mDiamondInterpolator));
        arraySet.add(getPropertyAnimator(mRight, FrameLayout.SCALE_X, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        arraySet.add(getPropertyAnimator(mRight, FrameLayout.SCALE_Y, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        arraySet.add(getPropertyAnimator(mWhite, FrameLayout.SCALE_X, 0.625f, 200, Interpolators.FAST_OUT_SLOW_IN));
        arraySet.add(getPropertyAnimator(mWhite, FrameLayout.SCALE_Y, 0.625f, 200, Interpolators.FAST_OUT_SLOW_IN));
        getLongestAnim(arraySet).addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animator) {
                Trace.beginSection("OpaLayout.start.diamond");
                Trace.endSection();
            }

            @Override
            public void onAnimationCancel(Animator animator) {
                Trace.beginSection("OpaLayout.cancel.diamond");
                Trace.endSection();
                mCurrentAnimators.clear();
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                Trace.beginSection("OpaLayout.end.diamond");
                Trace.endSection();
                startLineAnimation();
            }
        });
        return arraySet;
    }

    private ArraySet<Animator> getRetractAnimatorSet() {
        ArraySet<Animator> arraySet = new ArraySet<>();
        arraySet.add(getPropertyAnimator(mRed, FrameLayout.TRANSLATION_X, 0.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mRed, FrameLayout.TRANSLATION_Y, 0.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mRed, FrameLayout.SCALE_X, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mRed, FrameLayout.SCALE_Y, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mBlue, FrameLayout.TRANSLATION_X, 0.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mBlue, FrameLayout.TRANSLATION_Y, 0.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mBlue, FrameLayout.SCALE_X, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mBlue, FrameLayout.SCALE_Y, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mGreen, FrameLayout.TRANSLATION_X, 0.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mGreen, FrameLayout.TRANSLATION_Y, 0.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mGreen, FrameLayout.SCALE_X, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mGreen, FrameLayout.SCALE_Y, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mYellow, FrameLayout.TRANSLATION_X, 0.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mYellow, FrameLayout.TRANSLATION_Y, 0.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mYellow, FrameLayout.SCALE_X, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mYellow, FrameLayout.SCALE_Y, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mWhite, FrameLayout.SCALE_X, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mWhite, FrameLayout.SCALE_Y, 1.0f, 190, OpaUtils.INTERPOLATOR_40_OUT));
        getLongestAnim(arraySet).addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animator) {
                Trace.beginSection("OpaLayout.start.retract");
                Trace.endSection();
            }

            @Override
            public void onAnimationCancel(Animator animator) {
                Trace.beginSection("OpaLayout.cancel.retract");
                Trace.endSection();
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                Trace.beginSection("OpaLayout.end.retract");
                Trace.endSection();
                mCurrentAnimators.clear();
                skipToStartingValue();
            }
        });
        return arraySet;
    }

    private ArraySet<Animator> getCollapseAnimatorSet() {
        Animator animator;
        Animator animator2;
        Animator animator3;
        Animator animator4;
        ArraySet<Animator> arraySet = new ArraySet<>();
        if (mIsVertical) {
            animator = getPropertyAnimator(mRed, FrameLayout.TRANSLATION_Y, 0.0f, 133, OpaUtils.INTERPOLATOR_40_OUT);
        } else {
            animator = getPropertyAnimator(mRed, FrameLayout.TRANSLATION_X, 0.0f, 133, OpaUtils.INTERPOLATOR_40_OUT);
        }
        arraySet.add(animator);
        arraySet.add(getPropertyAnimator(mRed, FrameLayout.SCALE_X, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mRed, FrameLayout.SCALE_Y, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        if (mIsVertical) {
            animator2 = getPropertyAnimator(mBlue, FrameLayout.TRANSLATION_Y, 0.0f, 150, OpaUtils.INTERPOLATOR_40_OUT);
        } else {
            animator2 = getPropertyAnimator(mBlue, FrameLayout.TRANSLATION_X, 0.0f, 150, OpaUtils.INTERPOLATOR_40_OUT);
        }
        arraySet.add(animator2);
        arraySet.add(getPropertyAnimator(mBlue, FrameLayout.SCALE_X, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mBlue, FrameLayout.SCALE_Y, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        if (mIsVertical) {
            animator3 = getPropertyAnimator(mYellow, FrameLayout.TRANSLATION_Y, 0.0f, 133, OpaUtils.INTERPOLATOR_40_OUT);
        } else {
            animator3 = getPropertyAnimator(mYellow, FrameLayout.TRANSLATION_X, 0.0f, 133, OpaUtils.INTERPOLATOR_40_OUT);
        }
        arraySet.add(animator3);
        arraySet.add(getPropertyAnimator(mYellow, FrameLayout.SCALE_X, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mYellow, FrameLayout.SCALE_Y, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        if (mIsVertical) {
            animator4 = getPropertyAnimator(mGreen, FrameLayout.TRANSLATION_Y, 0.0f, 150, OpaUtils.INTERPOLATOR_40_OUT);
        } else {
            animator4 = getPropertyAnimator(mGreen, FrameLayout.TRANSLATION_X, 0.0f, 150, OpaUtils.INTERPOLATOR_40_OUT);
        }
        arraySet.add(animator4);
        arraySet.add(getPropertyAnimator(mGreen, FrameLayout.SCALE_X, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        arraySet.add(getPropertyAnimator(mGreen, FrameLayout.SCALE_Y, 1.0f, 200, OpaUtils.INTERPOLATOR_40_OUT));
        Animator propertyAnimator = getPropertyAnimator(mWhite, FrameLayout.SCALE_X, 1.0f, 150, Interpolators.FAST_OUT_SLOW_IN);
        Animator propertyAnimator2 = getPropertyAnimator(mWhite, FrameLayout.SCALE_Y, 1.0f, 150, Interpolators.FAST_OUT_SLOW_IN);
        propertyAnimator.setStartDelay(33);
        propertyAnimator2.setStartDelay(33);
        arraySet.add(propertyAnimator);
        arraySet.add(propertyAnimator2);
        getLongestAnim(arraySet).addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animator) {
                Trace.beginSection("OpaLayout.start.collapse");
                Trace.endSection();
            }

            @Override
            public void onAnimationCancel(Animator animator) {
                Trace.beginSection("OpaLayout.cancel.collapse");
                Trace.endSection();
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                Trace.beginSection("OpaLayout.end.collapse");
                Trace.endSection();
                mCurrentAnimators.clear();
                skipToStartingValue();
            }
        });
        return arraySet;
    }

    private ArraySet<Animator> getLineAnimatorSet() {
        ArraySet<Animator> arraySet = new ArraySet<>();
        if (mIsVertical) {
            View view = mRed;
            arraySet.add(getPropertyAnimator(view, View.Y, view.getY() + OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry), 225, Interpolators.FAST_OUT_SLOW_IN));
            View view2 = mRed;
            arraySet.add(getPropertyAnimator(view2, View.X, view2.getX() + OpaUtils.getPxVal(mResources, R.dimen.opa_line_y_translation), 133, Interpolators.FAST_OUT_SLOW_IN));
            View view3 = mBlue;
            arraySet.add(getPropertyAnimator(view3, View.Y, view3.getY() + OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg), 225, Interpolators.FAST_OUT_SLOW_IN));
            View view4 = mYellow;
            arraySet.add(getPropertyAnimator(view4, View.Y, view4.getY() + (-OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry)), 225, Interpolators.FAST_OUT_SLOW_IN));
            View view5 = mYellow;
            arraySet.add(getPropertyAnimator(view5, View.X, view5.getX() + (-OpaUtils.getPxVal(mResources, R.dimen.opa_line_y_translation)), 133, Interpolators.FAST_OUT_SLOW_IN));
            View view6 = mGreen;
            arraySet.add(getPropertyAnimator(view6, View.Y, view6.getY() + (-OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg)), 225, Interpolators.FAST_OUT_SLOW_IN));
        } else {
            View view7 = mRed;
            arraySet.add(getPropertyAnimator(view7, View.X, view7.getX() + (-OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry)), 225, Interpolators.FAST_OUT_SLOW_IN));
            View view8 = mRed;
            arraySet.add(getPropertyAnimator(view8, View.Y, view8.getY() + OpaUtils.getPxVal(mResources, R.dimen.opa_line_y_translation), 133, Interpolators.FAST_OUT_SLOW_IN));
            View view9 = mBlue;
            arraySet.add(getPropertyAnimator(view9, View.X, view9.getX() + (-OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg)), 225, Interpolators.FAST_OUT_SLOW_IN));
            View view10 = mYellow;
            arraySet.add(getPropertyAnimator(view10, View.X, view10.getX() + OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry), 225, Interpolators.FAST_OUT_SLOW_IN));
            View view11 = mYellow;
            arraySet.add(getPropertyAnimator(view11, View.Y, view11.getY() + (-OpaUtils.getPxVal(mResources, R.dimen.opa_line_y_translation)), 133, Interpolators.FAST_OUT_SLOW_IN));
            View view12 = mGreen;
            arraySet.add(getPropertyAnimator(view12, View.X, view12.getX() + OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg), 225, Interpolators.FAST_OUT_SLOW_IN));
        }
        arraySet.add(getPropertyAnimator(mWhite, FrameLayout.SCALE_X, 0.0f, 83, HOME_DISAPPEAR_INTERPOLATOR));
        arraySet.add(getPropertyAnimator(mWhite, FrameLayout.SCALE_Y, 0.0f, 83, HOME_DISAPPEAR_INTERPOLATOR));
        getLongestAnim(arraySet).addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animator) {
                Trace.beginSection("OpaLayout.start.line");
                Trace.endSection();
            }

            @Override
            public void onAnimationCancel(Animator animator) {
                Trace.beginSection("OpaLayout.cancel.line");
                Trace.endSection();
                mCurrentAnimators.clear();
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                Trace.beginSection("OpaLayout.end.line");
                Trace.endSection();
                startCollapseAnimation();
            }
        });
        return arraySet;
    }

    public void updateOpaLayout() {
        boolean shouldShowSwipeUpUI = mOverviewProxyService.shouldShowSwipeUpUI();
        boolean z = true;
        boolean z2 = !shouldShowSwipeUpUI;
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) mWhite.getLayoutParams();
        if (z2 || shouldShowSwipeUpUI) {
            z = false;
        }
        int i = z ? mHomeDiameter : -1;
        layoutParams.width = i;
        layoutParams.height = i;
        mWhite.setLayoutParams(layoutParams);
        ImageView.ScaleType scaleType = z ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER;
        mWhite.setScaleType(scaleType);
    }

    private void cancelCurrentAnimation(String str) {
        Trace.beginSection("OpaLayout.cancelCurrentAnimation: reason=" + str);
        Trace.endSection();
        if (!mCurrentAnimators.isEmpty()) {
            for (int size = mCurrentAnimators.size() - 1; size >= 0; size--) {
                Animator valueAt = mCurrentAnimators.valueAt(size);
                valueAt.removeAllListeners();
                valueAt.cancel();
            }
            mCurrentAnimators.clear();
            mAnimationState = 0;
        }
        AnimatorSet animatorSet = mGestureAnimatorSet;
        if (animatorSet != null) {
            animatorSet.cancel();
            mGestureState = 0;
        }
    }

    private void endCurrentAnimation(String str) {
        Trace.beginSection("OpaLayout.endCurrentAnimation: reason=" + str);
        if (!mCurrentAnimators.isEmpty()) {
            for (int size = mCurrentAnimators.size() - 1; size >= 0; size--) {
                Animator valueAt = mCurrentAnimators.valueAt(size);
                valueAt.removeAllListeners();
                valueAt.end();
            }
            mCurrentAnimators.clear();
        }
        mAnimationState = 0;
    }

    private Animator getLongestAnim(ArraySet<Animator> arraySet) {
        long j = Long.MIN_VALUE;
        Animator animator = null;
        for (int size = arraySet.size() - 1; size >= 0; size--) {
            Animator valueAt = arraySet.valueAt(size);
            if (valueAt.getTotalDuration() > j) {
                j = valueAt.getTotalDuration();
                animator = valueAt;
            }
        }
        return animator;
    }

    private void setDotsVisible() {
        int size = mAnimatedViews.size();
        for (int i = 0; i < size; i++) {
            mAnimatedViews.get(i).setAlpha(1.0f);
        }
    }

    private void skipToStartingValue() {
        int size = mAnimatedViews.size();
        for (int i = 0; i < size; i++) {
            View view = mAnimatedViews.get(i);
            view.setScaleY(1.0f);
            view.setScaleX(1.0f);
            view.setTranslationY(0.0f);
            view.setTranslationX(0.0f);
            view.setAlpha(0.0f);
        }
        mWhite.setAlpha(1.0f);
        mAnimationState = 0;
        mGestureState = 0;
    }

    @Override
    public void setVertical(boolean z) {
        AnimatorSet animatorSet;
        if (!(mIsVertical == z || (animatorSet = mGestureAnimatorSet) == null)) {
            animatorSet.cancel();
            mGestureAnimatorSet = null;
            skipToStartingValue();
        }
        mIsVertical = z;
        mHome.setVertical(z);
        if (mIsVertical) {
            mTop = mGreen;
            mBottom = mBlue;
            mRight = mYellow;
            mLeft = mRed;
            return;
        }
        mTop = mRed;
        mBottom = mYellow;
        mLeft = mBlue;
        mRight = mGreen;
    }

    @Override
    public void setDarkIntensity(float f) {
        if (mWhite.getDrawable() instanceof KeyButtonDrawable) {
            ((KeyButtonDrawable) mWhite.getDrawable()).setDarkIntensity(f);
        }
        mWhite.invalidate();
        mHome.setDarkIntensity(f);
    }

    @Override
    public void setDelayTouchFeedback(boolean z) {
        mHome.setDelayTouchFeedback(z);
        mDelayTouchFeedback = z;
    }

    private AnimatorSet getGestureAnimatorSet() {
        AnimatorSet animatorSet = mGestureLineSet;
        if (animatorSet != null) {
            animatorSet.removeAllListeners();
            mGestureLineSet.cancel();
            return mGestureLineSet;
        }
        mGestureLineSet = new AnimatorSet();
        ObjectAnimator scaleObjectAnimator = OpaUtils.getScaleObjectAnimator(mWhite, 0.0f, 100, OpaUtils.INTERPOLATOR_40_OUT);
        scaleObjectAnimator.setStartDelay(50);
        mGestureLineSet.play(scaleObjectAnimator);
        mGestureLineSet.play(OpaUtils.getScaleObjectAnimator(mTop, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN)).with(scaleObjectAnimator).with(OpaUtils.getAlphaObjectAnimator(mRed, 1.0f, 50, 130, Interpolators.LINEAR)).with(OpaUtils.getAlphaObjectAnimator(mYellow, 1.0f, 50, 130, Interpolators.LINEAR)).with(OpaUtils.getAlphaObjectAnimator(mBlue, 1.0f, 50, 113, Interpolators.LINEAR)).with(OpaUtils.getAlphaObjectAnimator(mGreen, 1.0f, 50, 113, Interpolators.LINEAR)).with(OpaUtils.getScaleObjectAnimator(mBottom, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN)).with(OpaUtils.getScaleObjectAnimator(mLeft, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN)).with(OpaUtils.getScaleObjectAnimator(mRight, 0.8f, 200, Interpolators.FAST_OUT_SLOW_IN));
        if (mIsVertical) {
            ObjectAnimator translationObjectAnimatorY = OpaUtils.getTranslationObjectAnimatorY(mRed, OpaUtils.INTERPOLATOR_40_40, OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry), mRed.getY() + OpaUtils.getDeltaDiamondPositionLeftY(), 350);
            translationObjectAnimatorY.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animator) {
                    startCollapseAnimation();
                }
            });
            mGestureLineSet.play(translationObjectAnimatorY).with(OpaUtils.getTranslationObjectAnimatorY(mBlue, OpaUtils.INTERPOLATOR_40_40, OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg), mBlue.getY() + OpaUtils.getDeltaDiamondPositionBottomY(mResources), 350)).with(OpaUtils.getTranslationObjectAnimatorY(mYellow, OpaUtils.INTERPOLATOR_40_40, -OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry), mYellow.getY() + OpaUtils.getDeltaDiamondPositionRightY(), 350)).with(OpaUtils.getTranslationObjectAnimatorY(mGreen, OpaUtils.INTERPOLATOR_40_40, -OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg), mGreen.getY() + OpaUtils.getDeltaDiamondPositionTopY(mResources), 350));
        } else {
            ObjectAnimator translationObjectAnimatorX = OpaUtils.getTranslationObjectAnimatorX(mRed, OpaUtils.INTERPOLATOR_40_40, -OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry), mRed.getX() + OpaUtils.getDeltaDiamondPositionTopX(), 350);
            translationObjectAnimatorX.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animator) {
                    startCollapseAnimation();
                }
            });
            mGestureLineSet.play(translationObjectAnimatorX).with(scaleObjectAnimator).with(OpaUtils.getTranslationObjectAnimatorX(mBlue, OpaUtils.INTERPOLATOR_40_40, -OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg), mBlue.getX() + OpaUtils.getDeltaDiamondPositionLeftX(mResources), 350)).with(OpaUtils.getTranslationObjectAnimatorX(mYellow, OpaUtils.INTERPOLATOR_40_40, OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_ry), mYellow.getX() + OpaUtils.getDeltaDiamondPositionBottomX(), 350)).with(OpaUtils.getTranslationObjectAnimatorX(mGreen, OpaUtils.INTERPOLATOR_40_40, OpaUtils.getPxVal(mResources, R.dimen.opa_line_x_trans_bg), mGreen.getX() + OpaUtils.getDeltaDiamondPositionRightX(mResources), 350));
        }
        return mGestureLineSet;
    }

    private Animator getPropertyAnimator(View view, Property<View, Float> property, float f, int i, Interpolator interpolator) {
        ObjectAnimator ofFloat = ObjectAnimator.ofFloat(view, property, f);
        ofFloat.setDuration((long) i);
        ofFloat.setInterpolator(interpolator);
        return ofFloat;
    }

    private void hideAllOpa() {
        fadeOutButton(mBlue);
        fadeOutButton(mRed);
        fadeOutButton(mYellow);
        fadeOutButton(mGreen);
    }

    private void showAllOpa() {
        fadeInButton(mBlue);
        fadeInButton(mRed);
        fadeInButton(mYellow);
        fadeInButton(mGreen);
    }

    private void fadeInButton(View viewToFade){
        if (viewToFade == null) return;
        ObjectAnimator animator = ObjectAnimator.ofFloat(viewToFade, View.ALPHA, 0.0f, 1.0f);
        animator.setDuration(OpaLayout.OPA_FADE_IN_DURATION); //ms
        animator.start();
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                viewToFade.setVisibility(View.VISIBLE);
            }
        });
    }

    private void fadeOutButton(View viewToFade){
        if (viewToFade == null) return;
        ObjectAnimator animator = ObjectAnimator.ofFloat(viewToFade, View.ALPHA, 1.0f, 0.0f);
        animator.setDuration(OpaLayout.OPA_FADE_OUT_DURATION); //ms
        animator.start();
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                viewToFade.setVisibility(View.INVISIBLE);
            }
        });
    }
}
