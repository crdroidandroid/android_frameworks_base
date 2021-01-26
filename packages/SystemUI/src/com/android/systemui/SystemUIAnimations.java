package com.android.systemui;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.view.animation.Animation;
import android.view.animation.BounceInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.View;

import java.util.Random;

public class SystemUIAnimations {

    private static boolean mAnimationStarted = false;

    public static void faceLockShake(View targetView, boolean isCancelling) {
        if (!isCancelling) {
            ObjectAnimator animator = ObjectAnimator.ofFloat(targetView, "translationY", -100, 0, 0);
            ObjectAnimator animator2 = ObjectAnimator.ofFloat(targetView, "translationX", 0, 17, 0);
            animator.setInterpolator(new BounceInterpolator());
            animator2.setInterpolator(new BounceInterpolator());
            animator.setDuration(500);
            animator2.setStartDelay(500);
            animator2.setDuration(500);
            animator2.setRepeatCount(-1);
            animator.setAutoCancel(true);
            animator2.setAutoCancel(true);
            animator.start();
            animator2.start();
        } else {
            setNullAnim(targetView);
        }
    }

    public static void aodBackgroundRotation(View targetView, boolean isCancelling, boolean isPinwheel) {
        if (!mAnimationStarted) {
            if (!isCancelling) {
                ObjectAnimator animator = ObjectAnimator.ofFloat(targetView, "rotation", 0f, 360f);
                if (isPinwheel) {
                    animator.setDuration(getRandomNumberInRange(1000,3000));
                } else {
                    animator.setDuration(30000);
                }
                animator.setInterpolator(new LinearInterpolator());
                animator.setRepeatCount(ObjectAnimator.INFINITE);
                animator.setRepeatMode(ObjectAnimator.INFINITE);
                animator.start();
            } else {
                setNullAnim(targetView);
            }
        }
    }

    private static void setNullAnim(View targetView) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(targetView, "translationY", 0, 0, 0);
        ObjectAnimator animator2 = ObjectAnimator.ofFloat(targetView, "translationX", 0, 0, 0);
        mAnimationStarted = false;
        animator.setDuration(0);
        animator2.setDuration(0);
        animator.start();
        animator2.start();
    }

    private static int getRandomNumberInRange(int min, int max) {
        Random r = new Random();
        return r.nextInt((max - min) + 1) + min;
    }
}
