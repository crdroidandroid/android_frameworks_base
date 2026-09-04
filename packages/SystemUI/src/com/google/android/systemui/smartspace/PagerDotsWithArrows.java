package com.google.android.systemui.smartspace;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.android.app.animation.Interpolators;
import com.android.systemui.res.R;

/**
 * Wraps {@link PagerDots} with a {@link PaginationArrow} on either side.
 *
 * <p>The arrows are only meaningful when the carousel has more than one page and are revealed with
 * a short scale/fade so the indicator does not pop when the user starts interacting.
 */
public final class PagerDotsWithArrows extends FrameLayout {

    private static final PathInterpolator OPEN_INTERPOLATOR =
            new PathInterpolator(0.34f, 1.25f, 0.64f, 1f);
    private static final Interpolator CLOSE_INTERPOLATOR = Interpolators.EMPHASIZED_ACCELERATE;

    private static final long OPEN_DURATION_MS = 300L;
    private static final long CLOSE_DURATION_MS = 180L;
    private static final float CLOSED_SCALE = 0.7f;
    private static final float OPEN_FROM_SCALE = 0.6f;

    /** Which edge of the carousel an arrow pages towards. */
    public enum Direction {
        START,
        END
    }

    /** Notified when the user taps one of the paging arrows. */
    public interface OnArrowClickListener {
        void onArrowClick(Direction direction);
    }

    private final LinearLayout mContainer;
    private final PaginationArrow mLeftArrow;
    private final PaginationArrow mRightArrow;
    private final PagerDots mPagerDots;
    private final int mSavedPaddingTop;

    private boolean mArrowsEnabled;
    private int mNumPages = -1;
    private int mCurrentPageIndex = -1;
    @Nullable private AnimatorSet mCurrentAnimator;
    @Nullable private OnArrowClickListener mOnArrowClickListener;

    public PagerDotsWithArrows(Context context) {
        this(context, null);
    }

    public PagerDotsWithArrows(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PagerDotsWithArrows(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        LayoutInflater.from(context).inflate(R.layout.pager_dots_with_arrows_container, this, true);
        mContainer = findViewById(R.id.pager_dots_with_arrows_container);
        mLeftArrow = findViewById(R.id.left_pagination_arrow);
        mRightArrow = findViewById(R.id.right_pagination_arrow);
        mPagerDots = findViewById(R.id.pager_dots);
        mSavedPaddingTop = getPaddingTop();

        setContainerBackgroundAlpha(0);
        mLeftArrow.setOnClickListener(v -> notifyArrowClick(Direction.START));
        mRightArrow.setOnClickListener(v -> notifyArrowClick(Direction.END));
    }

    /** Whether the paging arrows are currently shown. */
    public boolean areArrowsEnabled() {
        return mArrowsEnabled;
    }

    /** Returns the wrapped dot indicator, so callers can tint or pad it directly. */
    public PagerDots getPagerDots() {
        return mPagerDots;
    }

    public void setOnArrowClickListener(@Nullable OnArrowClickListener listener) {
        mOnArrowClickListener = listener;
    }

    private void notifyArrowClick(Direction direction) {
        if (mOnArrowClickListener != null) {
            mOnArrowClickListener.onArrowClick(direction);
        }
    }

    /** Mirrors {@link PagerDots#setNumPages} and refreshes the arrow enabled states. */
    public void setNumPages(int numPages, boolean rtl) {
        if (numPages == mNumPages) {
            return;
        }
        mPagerDots.setNumPages(numPages, rtl);
        mNumPages = mPagerDots.numPages;
        mCurrentPageIndex = mPagerDots.currentPageIndex;
        updateArrowStates();
        BcSmartspaceTemplateDataUtils.updateVisibility(this, mPagerDots.getVisibility());
        requestLayout();
        invalidate();
    }

    /** Mirrors {@link PagerDots#setPageOffset} and refreshes the arrow enabled states. */
    public void setPageOffset(float offset, int position) {
        mPagerDots.setPageOffset(offset, position);
        if (mCurrentPageIndex != mPagerDots.currentPageIndex) {
            mCurrentPageIndex = mPagerDots.currentPageIndex;
            updateArrowStates();
        }
    }

    /** Disables the arrow that would page past either end of the carousel. */
    public void updateArrowStates() {
        if (mNumPages <= 1) {
            mLeftArrow.setEnabled(false);
            mRightArrow.setEnabled(false);
        } else {
            mLeftArrow.setEnabled(mCurrentPageIndex > 0);
            mRightArrow.setEnabled(mCurrentPageIndex < mNumPages - 1);
        }
    }

    /** Shows or hides the arrows, animating unless animations are off or we are detached. */
    public void setArrowsEnabled(boolean enabled) {
        if (mArrowsEnabled == enabled && mCurrentAnimator == null) {
            return;
        }
        mArrowsEnabled = enabled;
        cancelCurrentAnimator();

        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) {
            applyArrowsStateImmediate(enabled);
            return;
        }
        mCurrentAnimator = enabled ? createOpenAnimator() : createCloseAnimator();
        mCurrentAnimator.start();
    }

    /** Jumps straight to the shown/hidden arrow state with no animation. */
    public void applyArrowsStateImmediate(boolean enabled) {
        int visibility = enabled ? View.VISIBLE : View.GONE;
        mLeftArrow.setVisibility(visibility);
        mRightArrow.setVisibility(visibility);
        mLeftArrow.setAlpha(1f);
        mRightArrow.setAlpha(1f);
        mContainer.setScaleX(1f);
        mContainer.setScaleY(1f);
        mContainer.setAlpha(1f);
        setContainerBackgroundAlpha(enabled ? 255 : 0);

        ViewGroup.LayoutParams lp = mContainer.getLayoutParams();
        if (enabled) {
            lp.height = getResources().getDimensionPixelSize(R.dimen.pagination_arrow_touch_size);
            setPaddingRelative(getPaddingStart(), 0, getPaddingEnd(), getPaddingBottom());
        } else {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            setPaddingRelative(
                    getPaddingStart(), mSavedPaddingTop, getPaddingEnd(), getPaddingBottom());
        }
        mContainer.setLayoutParams(lp);
        invalidate();
    }

    private AnimatorSet createOpenAnimator() {
        float pivot = getResources().getDimensionPixelSize(R.dimen.pagination_arrow_touch_size) / 2f;
        mContainer.setPivotX(pivot);
        mContainer.setPivotY(pivot);

        // Start from the current scale if a close animation was interrupted mid-flight.
        float scale = mContainer.getScaleX();
        float fromScale = (scale > 0.01f && scale < 0.99f) ? scale : OPEN_FROM_SCALE;

        applyArrowsStateImmediate(true);
        mContainer.setScaleX(fromScale);
        mContainer.setScaleY(fromScale);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(mContainer, View.SCALE_X, fromScale, 1f),
                ObjectAnimator.ofFloat(mContainer, View.SCALE_Y, fromScale, 1f),
                ObjectAnimator.ofFloat(mLeftArrow, View.ALPHA, mLeftArrow.getAlpha(), 1f),
                ObjectAnimator.ofFloat(mRightArrow, View.ALPHA, mRightArrow.getAlpha(), 1f),
                createBackgroundAlphaAnimator(255));
        set.setDuration(OPEN_DURATION_MS);
        set.setInterpolator(OPEN_INTERPOLATOR);
        set.addListener(new AnimatorEndListener(true));
        return set;
    }

    private AnimatorSet createCloseAnimator() {
        float pivot = getResources().getDimensionPixelSize(R.dimen.pagination_arrow_touch_size) / 2f;
        mContainer.setPivotX(pivot);
        mContainer.setPivotY(pivot);

        float fromScale = mContainer.getScaleX();
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(mContainer, View.SCALE_X, fromScale, CLOSED_SCALE),
                ObjectAnimator.ofFloat(mContainer, View.SCALE_Y, fromScale, CLOSED_SCALE),
                ObjectAnimator.ofFloat(mLeftArrow, View.ALPHA, mLeftArrow.getAlpha(), 0f),
                ObjectAnimator.ofFloat(mRightArrow, View.ALPHA, mRightArrow.getAlpha(), 0f),
                createBackgroundAlphaAnimator(0));
        set.setDuration(CLOSE_DURATION_MS);
        set.setInterpolator(CLOSE_INTERPOLATOR);
        set.addListener(new AnimatorEndListener(false));
        return set;
    }

    private ValueAnimator createBackgroundAlphaAnimator(int targetAlpha) {
        Drawable background = getContainerBackground();
        int from = background != null ? background.getAlpha() : 255;
        ValueAnimator animator = ValueAnimator.ofInt(from, targetAlpha);
        animator.addUpdateListener(
                a -> {
                    Drawable bg = getContainerBackground();
                    if (bg != null) {
                        bg.setAlpha((Integer) a.getAnimatedValue());
                    }
                });
        return animator;
    }

    @Nullable
    private Drawable getContainerBackground() {
        Drawable background = mContainer.getBackground();
        return background != null ? background.mutate() : null;
    }

    private void setContainerBackgroundAlpha(int alpha) {
        Drawable background = getContainerBackground();
        if (background != null) {
            background.setAlpha(alpha);
        }
    }

    private void cancelCurrentAnimator() {
        if (mCurrentAnimator != null) {
            mCurrentAnimator.cancel();
            mCurrentAnimator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelCurrentAnimator();
        applyArrowsStateImmediate(mArrowsEnabled);
    }

    /** Settles into the final state, unless the animation was cancelled to start another one. */
    private final class AnimatorEndListener extends AnimatorListenerAdapter {
        private final boolean mOpening;
        private boolean mCancelled;

        AnimatorEndListener(boolean opening) {
            mOpening = opening;
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mCancelled = true;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (mCancelled) {
                return;
            }
            mCurrentAnimator = null;
            applyArrowsStateImmediate(mOpening);
        }
    }
}
