package com.android.server.wm.onehand;

import android.content.Context;
import android.database.ContentObserver;
import android.os.UserHandle;
import android.provider.Settings;

class OneHandedSettings {

    final static Object sSync = new Object();

    static void saveGravity(Context ctx, int gravity) {
        Settings.Secure.putIntForUser(ctx.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_UI_GRAVITY, gravity, OneHandedAnimator.getCurrentUser());
    }

    static void saveScale(Context ctx, float scale) {
        Settings.Secure.putFloatForUser(ctx.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_UI_SCALE, scale, OneHandedAnimator.getCurrentUser());
    }

    static void saveXAdj(Context ctx, int xadj) {
        Settings.Secure.putIntForUser(ctx.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_UI_XADJ, xadj, OneHandedAnimator.getCurrentUser());
    }

    static void saveYAdj(Context ctx, int yadj) {
        Settings.Secure.putIntForUser(ctx.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_UI_YADJ, yadj, OneHandedAnimator.getCurrentUser());
    }

    static void setFeatureEnabled(Context ctx, boolean enabled, int userId) {
        Settings.Secure.putIntForUser(ctx.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_UI, enabled ? 1 : 0, userId);
    }

    static int getSavedGravity(Context ctx, int defaultGravity) {
        return Settings.Secure.getIntForUser(ctx.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_UI_GRAVITY, defaultGravity, OneHandedAnimator.getCurrentUser());
    }

    static float getSavedScale(Context ctx, float defaultV) {
        return Settings.Secure.getFloatForUser(ctx.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_UI_SCALE, defaultV, OneHandedAnimator.getCurrentUser());
    }

    static int getSavedXAdj(Context ctx, int defaultV) {
        return Settings.Secure.getIntForUser(ctx.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_UI_XADJ, defaultV, OneHandedAnimator.getCurrentUser());
    }

    static int getSavedYAdj(Context ctx, int defaultV) {
        return Settings.Secure.getIntForUser(ctx.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_UI_YADJ, defaultV, OneHandedAnimator.getCurrentUser());
    }

    static boolean isFeatureEnabled(Context ctx) {
        return Settings.Secure.getIntForUser(ctx.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_UI, 0, OneHandedAnimator.getCurrentUser()) != 0;
    }

    static boolean isFeatureEnabledSettingNotFound(Context ctx, int userId) {
        try {
            Settings.Secure.getIntForUser(ctx.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_UI, userId);
            return false;
        } catch (Settings.SettingNotFoundException e) {
            return true;
        }
    }

    static void registerFeatureEnableDisableObserver(Context ctx,
                            ContentObserver observer) {
        ctx.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ONE_HANDED_MODE_UI),
                true,
                observer, UserHandle.USER_ALL);
    }
}
