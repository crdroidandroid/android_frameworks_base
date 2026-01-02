package com.google.android.systemui.smartspace.uitemplate;

import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.uitemplatedata.SubListTemplateData;
import android.app.smartspace.uitemplatedata.Text;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.BcSmartSpaceUtil;
import com.google.android.systemui.smartspace.BcSmartspaceCardSecondary;
import com.google.android.systemui.smartspace.BcSmartspaceTemplateDataUtils;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggerUtil;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import com.android.systemui.res.R;

import java.util.List;
import java.util.Locale;

public class SubListTemplateCard extends BcSmartspaceCardSecondary {
    public static final int[] LIST_ITEM_TEXT_VIEW_IDS = {R.id.list_item_1, R.id.list_item_2, R.id.list_item_3};
    public ImageView mListIconView;
    public final TextView[] mListItems;

    public SubListTemplateCard(Context context) {
        super(context);
        mListItems = new TextView[3];
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        mListIconView = findViewById(R.id.list_icon);
        for (int i = 0; i < 3; i++) {
            mListItems[i] = findViewById(LIST_ITEM_TEXT_VIEW_IDS[i]);
        }
    }

    @Override
    public final void resetUi() {
        BcSmartspaceTemplateDataUtils.updateVisibility(mListIconView, View.GONE);
        for (int i = 0; i < 3; i++) {
            BcSmartspaceTemplateDataUtils.updateVisibility(mListItems[i], View.GONE);
        }
    }

    @Override
    public final boolean setSmartspaceActions(SmartspaceTarget target, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, BcSmartspaceCardLoggingInfo loggingInfo) {
        reset(target.getSmartspaceTargetId());
        SubListTemplateData templateData = (SubListTemplateData) target.getTemplateData();
        if (!BcSmartspaceCardLoggerUtil.containsValidTemplateType(templateData)) {
            Log.w("SubListTemplateCard", "SubListTemplateData is null or contains invalid template type");
            return false;
        }
        if (templateData.getSubListIcon() != null) {
            BcSmartspaceTemplateDataUtils.setIcon(mListIconView, templateData.getSubListIcon());
            BcSmartspaceTemplateDataUtils.updateVisibility(mListIconView, View.VISIBLE);
        } else {
            BcSmartspaceTemplateDataUtils.updateVisibility(mListIconView, View.GONE);
        }
        if (templateData.getSubListTexts() != null) {
            List<Text> subListTexts = templateData.getSubListTexts();
            if (subListTexts.isEmpty()) {
                return false;
            }
            for (int i = 0; i < 3; i++) {
                TextView textView = mListItems[i];
                if (textView == null) {
                    Log.w(
                            "SubListTemplateCard",
                            String.format(
                                    Locale.US, "Missing list item view to update at row: %d", i + 1));
                    break;
                }
                if (i < subListTexts.size()) {
                    BcSmartspaceTemplateDataUtils.setText(textView, subListTexts.get(i));
                    BcSmartspaceTemplateDataUtils.updateVisibility(textView, View.VISIBLE);
                } else {
                    textView.setText("");
                    BcSmartspaceTemplateDataUtils.updateVisibility(textView, View.GONE);
                }
            }
        }
        if (templateData.getSubListAction() != null) {
            BcSmartSpaceUtil.setOnClickListener(this, target, templateData.getSubListAction(), eventNotifier, "SubListTemplateCard", loggingInfo, 0);
        }
        return true;
    }

    @Override
    public final void setTextColor(int color) {
        for (int i = 0; i < 3; i++) {
            TextView textView = mListItems[i];
            if (textView == null) {
                Log.w(
                        "SubListTemplateCard",
                        String.format(
                                Locale.US, "Missing list item view to update at row: %d", i + 1));
                return;
            }
            textView.setTextColor(color);
        }
    }

    public SubListTemplateCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        mListItems = new TextView[3];
    }
}
