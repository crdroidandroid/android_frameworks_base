package com.android.keyguard;

import android.content.Context;
import android.provider.Settings;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.RotateAnimation;
import android.view.View;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.R;
import android.util.Log;

public class DescendantSeamlessClockSwitch {

    private static Context mContext;
    private static NotificationLockscreenUserManager mLockscreenUserManager = Dependency.get(NotificationLockscreenUserManager.class);
    private static String TAG = "DescendantSeamlessClockSwitch";

    private static final String[] CLOCK_FACES = {
        "com.android.keyguard.clock.DefaultClockController",
        "com.android.keyguard.clock.DefaultBoldClockController",
        "com.android.keyguard.clock.SamsungClockController",
        "com.android.keyguard.clock.SamsungBoldClockController",
        "com.android.keyguard.clock.SamsungHighlightClockController.",
        "com.android.keyguard.clock.BubbleClockController",
        "com.android.keyguard.clock.AnalogClockController",
        "com.android.keyguard.clock.TypeClockController",
        "com.android.keyguard.clock.TypeClockAltController",
        "com.android.keyguard.clock.BinaryClockController",
        "com.android.keyguard.clock.DividedLinesClockController",
        "com.android.keyguard.clock.SfunyClockController",
        "com.android.keyguard.clock.MNMLBoxClockController",
        "com.android.keyguard.clock.MNMLMinimalClockController",
        "com.android.keyguard.clock.IDEClockController",
        "com.android.keyguard.clock.FluidClockController",
        "com.android.keyguard.clock.OronosClockController",
        "com.android.keyguard.clock.SpectrumClockController",
        "com.android.keyguard.clock.SneekyClockController",
        "com.android.keyguard.clock.ShapeShiftClockController",
        "com.android.keyguard.clock.TuxClockController"
    };

    public static int getCurrentPosition(String currentClockFace) {
        for (int i=0; i<CLOCK_FACES.length; i++) {
            if (CLOCK_FACES[i].equals(currentClockFace)) {
                return i;
            }
        }
        //we should never reach this return
        Log.wtf(TAG, "there's something really broken here");
        return 0;
    }

    public static void changeClockFace(Context context, int direction) {
        mContext = context;
        int curPos = getCurrentPosition(getCurrentClockFace());
        if (direction == 2) {
            curPos--;
        } else {
            curPos++;
        }
        if (curPos == CLOCK_FACES.length && direction == 1) {
            curPos = 0;
        }
        if (curPos < 0) {
            curPos = CLOCK_FACES.length - 1;
        }
        String clockFace = CLOCK_FACES[curPos];
        Settings.Secure.putStringForUser(mContext.getContentResolver(), Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                                         clockFace, mLockscreenUserManager.getCurrentUserId());

    }

    public static String getCurrentClockFace() {
        String myString = Settings.Secure.getStringForUser(mContext.getContentResolver(), Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                                                           mLockscreenUserManager.getCurrentUserId());
        if (myString == null) {
            //this behaviour should be fixed elsewhere
            Settings.Secure.putStringForUser(mContext.getContentResolver(), Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                                             "com.android.keyguard.clock.DefaultClockController", mLockscreenUserManager.getCurrentUserId());
        }

        myString = myString.replace("{\"clock\":\"","");
        myString = myString.replace("\"}","");
        return myString;
    }

    public static void shakeClock(View targetView, boolean status) {
        if (status) {
            Animation anim = new RotateAnimation(-3, 3, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
            anim.setDuration(100);
            anim.setRepeatMode(Animation.REVERSE);
            anim.setRepeatCount(Animation.INFINITE);
            targetView.startAnimation(anim);
        } else {
            targetView.clearAnimation();
        }
    }

    public static void blinkInfo(TextView targetView, boolean status, int color, Context context) {
        targetView.setText(context.getString(R.string.change_clock_face));
        if (status) {
            targetView.setTextColor(color);
            targetView.setVisibility(View.VISIBLE);
        } else {
	        targetView.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                @Override
                public void run() {
                    targetView.setVisibility(View.GONE);
                }
            });
        }
    }
}
