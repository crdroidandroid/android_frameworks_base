package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceUtils;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.app.smartspace.uitemplatedata.TapAction;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

public final class BcNextAlarmData {
    public static final SmartspaceAction SHOW_ALARMS_ACTION = new SmartspaceAction.Builder("nextAlarmId", "Next alarm").setIntent(new Intent("android.intent.action.SHOW_ALARMS")).build();
    public String mDescription;
    public SmartspaceTarget mHolidayAlarmsTarget;
    public Drawable mImage;

    public CharSequence getHolidayAlarmText(BaseTemplateData.SubItemInfo subItemInfo) {
        SmartspaceAction headerAction;
        if (subItemInfo != null && !SmartspaceUtils.isEmpty(subItemInfo.getText())) {
            return subItemInfo.getText().getText();
        }
        SmartspaceTarget smartspaceTarget = this.mHolidayAlarmsTarget;
        if (smartspaceTarget != null && (headerAction = smartspaceTarget.getHeaderAction()) != null) {
            return headerAction.getTitle();
        }
        return null;
    }

    public void setOnClickListener(View view, TapAction tapAction, BcSmartspaceDataPlugin.SmartspaceEventNotifier smartspaceEventNotifier, int i) {
        BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo;
        SmartspaceTarget smartspaceTarget = this.mHolidayAlarmsTarget;
        if (smartspaceTarget == null) {
            BcSmartspaceCardLoggingInfo.Builder builder = new BcSmartspaceCardLoggingInfo.Builder();
            builder.mInstanceId = InstanceId.create("upcoming_alarm_card_94510_12684");
            builder.mFeatureType = 23;
            builder.mDisplaySurface = i;
            bcSmartspaceCardLoggingInfo = new BcSmartspaceCardLoggingInfo(builder);
        } else {
            BcSmartspaceCardLoggingInfo.Builder builder2 = new BcSmartspaceCardLoggingInfo.Builder();
            builder2.mInstanceId = InstanceId.create(smartspaceTarget);
            builder2.mFeatureType = this.mHolidayAlarmsTarget.getFeatureType();
            builder2.mDisplaySurface = i;
            bcSmartspaceCardLoggingInfo = new BcSmartspaceCardLoggingInfo(builder2);
        }
        if (tapAction == null || (tapAction.getIntent() == null && tapAction.getPendingIntent() == null)) {
            BcSmartSpaceUtil.setOnClickListener(view, this.mHolidayAlarmsTarget, SHOW_ALARMS_ACTION, smartspaceEventNotifier, "BcNextAlarmData", bcSmartspaceCardLoggingInfo, 0);
        } else {
            BcSmartSpaceUtil.setOnClickListener(view, this.mHolidayAlarmsTarget, tapAction, smartspaceEventNotifier, "BcNextAlarmData", bcSmartspaceCardLoggingInfo, 0);
        }
    }

    public String getDescription(BaseTemplateData.SubItemInfo subItemInfo) {
        CharSequence holidayAlarmText = getHolidayAlarmText(subItemInfo);
        if (!TextUtils.isEmpty(holidayAlarmText)) {
            return this.mDescription + " · " + ((Object) holidayAlarmText);
        }
        return this.mDescription;
    }
}
