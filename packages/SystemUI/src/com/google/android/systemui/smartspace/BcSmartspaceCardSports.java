package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import com.android.systemui.res.R;

public class BcSmartspaceCardSports extends BcSmartspaceCardSecondary {
    public ImageView mFirstCompetitorLogo;
    public TextView mFirstCompetitorScore;
    public ImageView mSecondCompetitorLogo;
    public TextView mSecondCompetitorScore;
    public TextView mSummaryView;

    public BcSmartspaceCardSports(Context context) {
        super(context);
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        mSummaryView = findViewById(R.id.match_time_summary);
        mFirstCompetitorScore = findViewById(R.id.first_competitor_score);
        mSecondCompetitorScore = findViewById(R.id.second_competitor_score);
        mFirstCompetitorLogo = findViewById(R.id.first_competitor_logo);
        mSecondCompetitorLogo = findViewById(R.id.second_competitor_logo);
    }

    @Override
    public final void resetUi() {
        BcSmartspaceTemplateDataUtils.updateVisibility(mSummaryView, 4);
        BcSmartspaceTemplateDataUtils.updateVisibility(mFirstCompetitorScore, 4);
        BcSmartspaceTemplateDataUtils.updateVisibility(mSecondCompetitorScore, 4);
        BcSmartspaceTemplateDataUtils.updateVisibility(mFirstCompetitorLogo, 4);
        BcSmartspaceTemplateDataUtils.updateVisibility(mSecondCompetitorLogo, 4);
    }

    @Override
    public final boolean setSmartspaceActions(SmartspaceTarget target, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, BcSmartspaceCardLoggingInfo loggingInfo) {
        boolean z;
        SmartspaceAction baseAction = target.getBaseAction();
        Bundle extras = baseAction == null ? null : baseAction.getExtras();
        if (extras == null) {
            return false;
        }
        if (extras.containsKey("matchTimeSummary")) {
            String string = extras.getString("matchTimeSummary");
            TextView textView = mSummaryView;
            if (textView == null) {
                Log.w("BcSmartspaceCardSports", "No match time summary view to update");
            } else {
                BcSmartspaceTemplateDataUtils.updateVisibility(textView, 0);
                this.mSummaryView.setText(string);
            }
            z = true;
        } else {
            z = false;
        }
        if (extras.containsKey("firstCompetitorScore")) {
            String string2 = extras.getString("firstCompetitorScore");
            TextView textView2 = mFirstCompetitorScore;
            if (textView2 == null) {
                Log.w("BcSmartspaceCardSports", "No first competitor logo view to update");
            } else {
                BcSmartspaceTemplateDataUtils.updateVisibility(textView2, 0);
                mFirstCompetitorScore.setText(string2);
            }
            z = true;
        }
        if (extras.containsKey("secondCompetitorScore")) {
            String string3 = extras.getString("secondCompetitorScore");
            TextView textView3 = mSecondCompetitorScore;
            if (textView3 == null) {
                Log.w("BcSmartspaceCardSports", "No second competitor logo view to update");
            } else {
                BcSmartspaceTemplateDataUtils.updateVisibility(textView3, 0);
                mSecondCompetitorScore.setText(string3);
            }
            z = true;
        }
        if (extras.containsKey("firstCompetitorLogo")) {
            Bitmap bitmap = (Bitmap) extras.get("firstCompetitorLogo");
            ImageView imageView = mFirstCompetitorLogo;
            if (imageView == null) {
                Log.w("BcSmartspaceCardSports", "No first competitor logo view to update");
            } else {
                BcSmartspaceTemplateDataUtils.updateVisibility(imageView, 0);
                mFirstCompetitorLogo.setImageBitmap(bitmap);
            }
            z = true;
        }
        if (!extras.containsKey("secondCompetitorLogo")) {
            return z;
        }
        Bitmap bitmap2 = (Bitmap) extras.get("secondCompetitorLogo");
        ImageView imageView2 = mSecondCompetitorLogo;
        if (imageView2 == null) {
            Log.w("BcSmartspaceCardSports", "No second competitor logo view to update");
        } else {
            BcSmartspaceTemplateDataUtils.updateVisibility(imageView2, 0);
            mSecondCompetitorLogo.setImageBitmap(bitmap2);
        }
        return true;
    }

    @Override
    public final void setTextColor(int color) {
        mSummaryView.setTextColor(color);
        mFirstCompetitorScore.setTextColor(color);
        mSecondCompetitorScore.setTextColor(color);
    }

    public BcSmartspaceCardSports(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }
}
