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

import java.util.Locale;

public class BcSmartspaceCardShoppingList extends BcSmartspaceCardSecondary {
    public static final int[] LIST_ITEM_TEXT_VIEW_IDS = {R.id.list_item_1, R.id.list_item_2, R.id.list_item_3};
    public ImageView mCardPromptIconView;
    public TextView mCardPromptView;
    public TextView mEmptyListMessageView;
    public ImageView mListIconView;
    public final TextView[] mListItems;

    public BcSmartspaceCardShoppingList(Context context) {
        super(context);
        mListItems = new TextView[3];
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        mCardPromptView = findViewById(R.id.card_prompt);
        mEmptyListMessageView = findViewById(R.id.empty_list_message);
        mCardPromptIconView = findViewById(R.id.card_prompt_icon);
        mListIconView = findViewById(R.id.list_icon);
        for (int i = 0; i < 3; i++) {
            mListItems[i] = findViewById(LIST_ITEM_TEXT_VIEW_IDS[i]);
        }
    }

    @Override
    public final void resetUi() {
        BcSmartspaceTemplateDataUtils.updateVisibility(mEmptyListMessageView, 8);
        BcSmartspaceTemplateDataUtils.updateVisibility(mListIconView, 8);
        BcSmartspaceTemplateDataUtils.updateVisibility(mCardPromptIconView, 8);
        BcSmartspaceTemplateDataUtils.updateVisibility(mCardPromptView, 8);
        for (int i = 0; i < 3; i++) {
            BcSmartspaceTemplateDataUtils.updateVisibility(mListItems[i], 8);
        }
    }

    @Override
    public final boolean setSmartspaceActions(SmartspaceTarget target, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, BcSmartspaceCardLoggingInfo loggingInfo) {
        SmartspaceAction baseAction = target.getBaseAction();
        Bitmap bitmap = null;
        Bundle extras = baseAction == null ? null : baseAction.getExtras();
        if (extras != null) {
            if (extras.containsKey("appIcon")) {
                bitmap = (Bitmap) extras.get("appIcon");
            } else if (extras.containsKey("imageBitmap")) {
                bitmap = (Bitmap) extras.get("imageBitmap");
            }
            mCardPromptIconView.setImageBitmap(bitmap);
            mListIconView.setImageBitmap(bitmap);
            if (extras.containsKey("cardPrompt")) {
                String string = extras.getString("cardPrompt");
                TextView textView = mCardPromptView;
                if (textView == null) {
                    Log.w("BcSmartspaceCardShoppingList", "No card prompt view to update");
                } else {
                    textView.setText(string);
                }
                BcSmartspaceTemplateDataUtils.updateVisibility(mCardPromptView, 0);
                if (bitmap != null) {
                    BcSmartspaceTemplateDataUtils.updateVisibility(mCardPromptIconView, 0);
                    return true;
                }
            } else {
                if (extras.containsKey("emptyListString")) {
                    String string2 = extras.getString("emptyListString");
                    TextView textView2 = mEmptyListMessageView;
                    if (textView2 == null) {
                        Log.w("BcSmartspaceCardShoppingList", "No empty list message view to update");
                    } else {
                        textView2.setText(string2);
                    }
                    BcSmartspaceTemplateDataUtils.updateVisibility(mEmptyListMessageView, 0);
                    BcSmartspaceTemplateDataUtils.updateVisibility(mListIconView, 0);
                    return true;
                }
                if (extras.containsKey("listItems")) {
                    String[] stringArray = extras.getStringArray("listItems");
                    if (stringArray.length != 0) {
                        BcSmartspaceTemplateDataUtils.updateVisibility(mListIconView, 0);
                        for (int i = 0; i < 3; i++) {
                            TextView textView3 = mListItems[i];
                            if (textView3 == null) {
                                Log.w(
                                        "BcSmartspaceCardShoppingList",
                                        String.format(
                                                Locale.US,
                                                "Missing list item view to update at row: %d",
                                                i + 1));
                                return true;
                            }
                            if (i < stringArray.length) {
                                BcSmartspaceTemplateDataUtils.updateVisibility(textView3, 0);
                                textView3.setText(stringArray[i]);
                            } else {
                                BcSmartspaceTemplateDataUtils.updateVisibility(textView3, 8);
                                textView3.setText("");
                            }
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public final void setTextColor(int i) {
        mCardPromptView.setTextColor(i);
        mEmptyListMessageView.setTextColor(i);
        for (int i2 = 0; i2 < 3; i2++) {
            TextView textView = mListItems[i2];
            if (textView == null) {
                Log.w(
                        "BcSmartspaceCardShoppingList",
                        String.format(
                                Locale.US, "Missing list item view to update at row: %d", i2 + 1));
                return;
            }
            textView.setTextColor(i);
        }
    }

    public BcSmartspaceCardShoppingList(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        mListItems = new TextView[3];
    }
}
