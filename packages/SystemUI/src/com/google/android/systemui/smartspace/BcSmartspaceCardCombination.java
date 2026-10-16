package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.res.R;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import java.util.List;

public class BcSmartspaceCardCombination extends BcSmartspaceCardSecondary {
    public ConstraintLayout mFirstSubCard;
    public ConstraintLayout mSecondSubCard;

    public BcSmartspaceCardCombination(Context context) {
        super(context);
    }

    public final boolean fillSubCard(
            ConstraintLayout constraintLayout,
            SmartspaceTarget target,
            SmartspaceAction action,
            BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier,
            BcSmartspaceCardLoggingInfo loggingInfo) {
        boolean z;
        TextView textView = (TextView) constraintLayout.findViewById(R.id.sub_card_text);
        ImageView imageView = (ImageView) constraintLayout.findViewById(R.id.sub_card_icon);
        if (textView == null) {
            Log.w("BcSmartspaceCardCombination", "No sub-card text field to update");
            return false;
        }
        if (imageView == null) {
            Log.w("BcSmartspaceCardCombination", "No sub-card image field to update");
            return false;
        }
        BcSmartSpaceUtil.setOnClickListener(
                constraintLayout,
                target,
                action,
                eventNotifier,
                "BcSmartspaceCardCombination",
                loggingInfo,
                0);
        Icon icon = action.getIcon();
        Context context = getContext();
        Drawable iconDrawableWithCustomSize =
                BcSmartSpaceUtil.getIconDrawableWithCustomSize(
                        icon,
                        context,
                        context.getResources()
                                .getDimensionPixelSize(R.dimen.enhanced_smartspace_icon_size));
        boolean z2 = true;
        if (iconDrawableWithCustomSize == null) {
            BcSmartspaceTemplateDataUtils.updateVisibility(imageView, View.GONE);
            z = false;
        } else {
            imageView.setImageDrawable(iconDrawableWithCustomSize);
            BcSmartspaceTemplateDataUtils.updateVisibility(imageView, View.VISIBLE);
            z = true;
        }
        CharSequence title = action.getTitle();
        if (TextUtils.isEmpty(title)) {
            BcSmartspaceTemplateDataUtils.updateVisibility(textView, View.GONE);
            z2 = z;
        } else {
            textView.setText(title);
            BcSmartspaceTemplateDataUtils.updateVisibility(textView, View.VISIBLE);
        }
        constraintLayout.setContentDescription(z2 ? action.getContentDescription() : null);
        if (z2) {
            BcSmartspaceTemplateDataUtils.updateVisibility(constraintLayout, View.VISIBLE);
            return z2;
        }
        BcSmartspaceTemplateDataUtils.updateVisibility(constraintLayout, View.GONE);
        return z2;
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        mFirstSubCard = findViewById(R.id.first_sub_card);
        mSecondSubCard = findViewById(R.id.second_sub_card);
    }

    @Override
    public final void resetUi() {
        BcSmartspaceTemplateDataUtils.updateVisibility(mFirstSubCard, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mSecondSubCard, View.GONE);
    }

    @Override
    public boolean setSmartspaceActions(
            SmartspaceTarget target,
            BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier,
            BcSmartspaceCardLoggingInfo loggingInfo) {
        SmartspaceAction action;
        List actionChips = target.getActionChips();
        if (actionChips == null
                || actionChips.size() < 1
                || (action = (SmartspaceAction) actionChips.get(0)) == null) {
            return false;
        }
        ConstraintLayout constraintLayout = mFirstSubCard;
        boolean z =
                constraintLayout != null
                        && fillSubCard(
                                constraintLayout, target, action, eventNotifier, loggingInfo);
        boolean z2 = actionChips.size() > 1 && actionChips.get(1) != null;
        boolean fillSubCard =
                z2
                        ? fillSubCard(
                                mSecondSubCard,
                                target,
                                (SmartspaceAction) actionChips.get(1),
                                eventNotifier,
                                loggingInfo)
                        : true;
        if (getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) getLayoutParams();
            if (z2 && fillSubCard) {
                layoutParams.weight = 3.0f;
            } else {
                layoutParams.weight = 1.0f;
            }
            setLayoutParams(layoutParams);
        }
        return z && fillSubCard;
    }

    public BcSmartspaceCardCombination(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }

    @Override
    public final void setTextColor(int i) {}
}
