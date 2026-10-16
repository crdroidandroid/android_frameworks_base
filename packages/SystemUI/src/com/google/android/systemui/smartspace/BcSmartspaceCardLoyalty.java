package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.res.R;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

public class BcSmartspaceCardLoyalty extends BcSmartspaceCardGenericImage {
    public TextView mCardPromptView;
    public ImageView mLoyaltyProgramLogoView;
    public TextView mLoyaltyProgramNameView;

    public BcSmartspaceCardLoyalty(Context context) {
        super(context);
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        mLoyaltyProgramLogoView = findViewById(R.id.loyalty_program_logo);
        mLoyaltyProgramNameView = findViewById(R.id.loyalty_program_name);
        mCardPromptView = findViewById(R.id.card_prompt);
    }

    @Override
    public final void resetUi() {
        super.resetUi();
        BcSmartspaceTemplateDataUtils.updateVisibility(mImageView, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mLoyaltyProgramLogoView, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mLoyaltyProgramNameView, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mCardPromptView, View.GONE);
    }

    @Override
    public final void setImageBitmap(Bitmap bitmap) {
        super.setImageBitmap(bitmap);
        mLoyaltyProgramLogoView.setImageBitmap(bitmap);
    }

    @Override
    public final boolean setSmartspaceActions(
            SmartspaceTarget target,
            BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier,
            BcSmartspaceCardLoggingInfo loggingInfo) {
        super.setSmartspaceActions(target, eventNotifier, loggingInfo);
        SmartspaceAction baseAction = target.getBaseAction();
        Bundle extras = baseAction == null ? null : baseAction.getExtras();
        if (extras == null) {
            return false;
        }
        boolean containsKey = extras.containsKey("imageBitmap");
        if (extras.containsKey("cardPrompt")) {
            String string = extras.getString("cardPrompt");
            TextView textView = mCardPromptView;
            if (textView == null) {
                Log.w("BcSmartspaceCardLoyalty", "No card prompt view to update");
            } else {
                textView.setText(string);
            }
            BcSmartspaceTemplateDataUtils.updateVisibility(mCardPromptView, View.VISIBLE);
            if (containsKey) {
                BcSmartspaceTemplateDataUtils.updateVisibility(mImageView, View.VISIBLE);
            }
            return true;
        }
        if (!extras.containsKey("loyaltyProgramName")) {
            if (containsKey) {
                BcSmartspaceTemplateDataUtils.updateVisibility(mLoyaltyProgramLogoView, View.VISIBLE);
            }
            return containsKey;
        }
        String string2 = extras.getString("loyaltyProgramName");
        TextView textView2 = mLoyaltyProgramNameView;
        if (textView2 == null) {
            Log.w("BcSmartspaceCardLoyalty", "No loyalty program name view to update");
        } else {
            textView2.setText(string2);
        }
        BcSmartspaceTemplateDataUtils.updateVisibility(mLoyaltyProgramNameView, View.VISIBLE);
        if (containsKey) {
            BcSmartspaceTemplateDataUtils.updateVisibility(mLoyaltyProgramLogoView, View.VISIBLE);
        }
        return true;
    }

    @Override
    public final void setTextColor(int color) {
        mLoyaltyProgramNameView.setTextColor(color);
        mCardPromptView.setTextColor(color);
    }

    public BcSmartspaceCardLoyalty(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }
}
