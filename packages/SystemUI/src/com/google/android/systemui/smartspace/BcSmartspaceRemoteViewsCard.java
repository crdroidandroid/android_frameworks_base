package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.view.View;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.BcSmartSpaceUtil;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

public final class BcSmartspaceRemoteViewsCard extends AppWidgetHostView implements SmartspaceCard {
    public BcSmartspaceDataPlugin.SmartspaceEventNotifier mEventNotifier;
    public BcSmartspaceCardLoggingInfo mLoggingInfo;
    public SmartspaceTarget mTarget;
    public String mUiSurface;

    public BcSmartspaceRemoteViewsCard(Context context) {
        super(context);
        setOnLongClickListener(null);
        if (BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD.equals(mUiSurface)) {
            super.setInteractionHandler(null);
        }
    }

    @Override
    public final void bindData(SmartspaceTarget target, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, BcSmartspaceCardLoggingInfo loggingInfo, boolean usePageIndicatorUi) {
        mTarget = target;
        mLoggingInfo = loggingInfo;
        mEventNotifier = eventNotifier;
        updateAppWidget(target.getRemoteViews());
        SmartspaceAction headerAction = target.getHeaderAction();
        if (headerAction == null) {
            setOnClickListener(null);
            super.setInteractionHandler(null);
            return;
        }

        BcSmartSpaceUtil.setOnClickListener(this, target, headerAction, mEventNotifier, "BcSmartspaceRemoteViewsCard", loggingInfo, 0);
        if (BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD.equals(mUiSurface)) {
            super.setInteractionHandler(
                    new BcSmartSpaceUtil.InteractionHandler(
                            loggingInfo, mEventNotifier, target, headerAction));
        }
    }

    @Override
    public final BcSmartspaceCardLoggingInfo getLoggingInfo() {
        if (mLoggingInfo == null) {
            BcSmartspaceCardLoggingInfo.Builder builder =
                    new BcSmartspaceCardLoggingInfo.Builder()
                            .setDisplaySurface(
                                    BcSmartSpaceUtil.getLoggingDisplaySurface(mUiSurface, 0f))
                            .setFeatureType(mTarget != null ? mTarget.getFeatureType() : 0)
                            .setUid(-1);
            return new BcSmartspaceCardLoggingInfo(builder);
        }
        return mLoggingInfo;
    }

    @Override
    public final View getView() {
        return this;
    }

    @Override
    public final void setDozeAmount(float dozeAmount) {
    }

    @Override
    public final void setPrimaryTextColor(int color) {
    }

    @Override
    public final void setScreenOn(boolean screenOn) {
    }
}
