package com.android.systemui.statusbar.phone;

public class FaceUnlockProxy {
    public static FaceUnlockController INSTANCE() {
        return FaceUnlockController.Companion.getInstance();
    }
}

