package com.android.systemui;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.view.animation.BounceInterpolator;
import android.view.View;

public class RetickerAnimations {

    static boolean mIsAnimatingTicker;

    public static void doBounceAnimationIn(View targetView) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(targetView, "translationY", -100, 0, 0);
        ObjectAnimator animator2 = ObjectAnimator.ofFloat(targetView, "translationX", 0, 17, 0);
        animator.setInterpolator(new BounceInterpolator());
        animator2.setInterpolator(new BounceInterpolator());
        animator.setDuration(500);
        animator2.setStartDelay(500);
        animator2.setDuration(500);
        animator.start();
        animator2.start();
        targetView.setVisibility(View.VISIBLE);
    }

    public static void doBounceAnimationOut(View targetView, View notificationStackScroller) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(targetView, "translationY", 0, -1000, -1000);
        animator.setInterpolator(new BounceInterpolator());
        animator.setDuration(350);
        animator.start();
        animator.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
            }
            @Override
            public void onAnimationRepeat(Animator animation) {
            }
            @Override
            public void onAnimationEnd(Animator animation) {
                notificationStackScroller.setVisibility(View.VISIBLE);
                targetView.setVisibility(View.GONE);
                mIsAnimatingTicker = false;
            }
            @Override
            public void onAnimationCancel(Animator animation) {
            }
        });
    }

    public static boolean isTickerAnimating() {
        return mIsAnimatingTicker;
    }

}
