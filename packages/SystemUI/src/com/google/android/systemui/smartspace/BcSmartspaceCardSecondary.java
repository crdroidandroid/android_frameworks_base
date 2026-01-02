package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.util.AttributeSet;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

public abstract class BcSmartspaceCardSecondary extends ConstraintLayout {
    public String mPrevSmartspaceTargetId;

    public BcSmartspaceCardSecondary(Context context) {
        super(context);
        mPrevSmartspaceTargetId = "";
    }

    public final void reset(String str) {
        if (mPrevSmartspaceTargetId.equals(str)) {
            return;
        }
        mPrevSmartspaceTargetId = str;
        resetUi();
    }

    public abstract boolean setSmartspaceActions(SmartspaceTarget target, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, BcSmartspaceCardLoggingInfo loggingInfo);

    public abstract void setTextColor(int color);

    public BcSmartspaceCardSecondary(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        mPrevSmartspaceTargetId = "";
    }

    public void resetUi() {
    }
}
