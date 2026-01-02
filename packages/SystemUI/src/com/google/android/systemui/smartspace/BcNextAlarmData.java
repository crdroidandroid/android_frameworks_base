package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.content.Intent;
import android.graphics.drawable.Drawable;

public final class BcNextAlarmData {
    public static final SmartspaceAction SHOW_ALARMS_ACTION = new SmartspaceAction.Builder("nextAlarmId", "Next alarm").setIntent(new Intent("android.intent.action.SHOW_ALARMS")).build();
    public String mDescription;
    public Drawable mImage;
}
