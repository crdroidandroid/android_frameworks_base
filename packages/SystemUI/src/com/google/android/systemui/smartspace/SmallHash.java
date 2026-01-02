package com.google.android.systemui.smartspace;

import java.util.Objects;

public abstract class SmallHash {
    public static int hash(String str) {
        return Math.abs(Math.floorMod(Objects.hashCode(str), 8192));
    }
}
