package com.android.systemui.util;

import android.graphics.Point;
import android.util.Log;

public class TapPositionUtil {
    private static final TapPositionUtil INSTANCE = new TapPositionUtil();
    private Point tapPos = null;

    private TapPositionUtil() {}

    public static TapPositionUtil INSTANCE() {
        return INSTANCE;
    }

    public void setTapPos(int x, int y) {
        if (tapPos == null) {
            tapPos = new Point(x, y);
        } else {
            tapPos.set(x, y);
        }
    }

    public void clearTapPos() {
        tapPos = null;
    }

    public Point getTapPos() {
        return tapPos == null ? null : new Point(tapPos);
    }
}
