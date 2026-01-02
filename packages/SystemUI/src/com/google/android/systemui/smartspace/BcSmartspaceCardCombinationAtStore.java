package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.util.AttributeSet;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import com.android.systemui.res.R;

import java.util.List;

public class BcSmartspaceCardCombinationAtStore extends BcSmartspaceCardCombination {
    public BcSmartspaceCardCombinationAtStore(Context context) {
        super(context);
    }

    @Override
    public final boolean setSmartspaceActions(SmartspaceTarget target, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, BcSmartspaceCardLoggingInfo loggingInfo) {
        SmartspaceAction action;
        List actionChips = target.getActionChips();
        if (actionChips == null || actionChips.isEmpty() || (action = (SmartspaceAction) actionChips.get(0)) == null) {
            return false;
        }
        ConstraintLayout constraintLayout = this.mFirstSubCard;
        boolean z = (constraintLayout instanceof BcSmartspaceCardShoppingList) && ((BcSmartspaceCardShoppingList) constraintLayout).setSmartspaceActions(target, eventNotifier, loggingInfo);
        ConstraintLayout constraintLayout2 = this.mSecondSubCard;
        boolean z2 = constraintLayout2 != null && fillSubCard(constraintLayout2, target, action, eventNotifier, loggingInfo);
        if (z) {
            this.mFirstSubCard.setBackgroundResource(R.drawable.bg_smartspace_combination_sub_card);
        }
        return z && z2;
    }

    public BcSmartspaceCardCombinationAtStore(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }
}
