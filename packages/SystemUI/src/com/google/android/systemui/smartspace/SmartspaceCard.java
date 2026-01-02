package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceTarget;
import android.view.View;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

public interface SmartspaceCard {
    void bindData(SmartspaceTarget target, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, BcSmartspaceCardLoggingInfo loggingInfo, boolean usePageIndicatorUi);

    BcSmartspaceCardLoggingInfo getLoggingInfo();

    View getView();

    void setDozeAmount(float dozeAmount);

    void setPrimaryTextColor(int color);

    void setScreenOn(boolean screenOn);
}
