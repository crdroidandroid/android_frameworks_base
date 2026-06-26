package com.android.internal.app;

/**
 * @hide
 */
oneway interface IAppLockStateListener {
    void onAppLockStateChanged(String packageName, boolean locked);
}
