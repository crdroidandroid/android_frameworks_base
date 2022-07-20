package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceUtils;
import android.app.smartspace.uitemplatedata.Icon;
import android.app.smartspace.uitemplatedata.Text;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.systemui.bcsmartspace.R;

public final class BcSmartspaceTemplateDataUtils {
    public static int getSecondaryCardRes(int i) {
        switch (i) {
            case 2:
                return R.layout.smartspace_sub_image_template_card;
            case 3:
                return R.layout.smartspace_sub_list_template_card;
            case 4:
                return R.layout.smartspace_carousel_template_card;
            case 5:
                return R.layout.smartspace_head_to_head_template_card;
            case 6:
                return R.layout.smartspace_combined_cards_template_card;
            case 7:
                return R.layout.smartspace_sub_card_template_card;
            default:
                return 0;
        }
    }

    public static void offsetImageViewForIcon(ImageView imageView, DoubleShadowIconDrawable doubleShadowIconDrawable) {
        if (doubleShadowIconDrawable == null) {
            imageView.setTranslationX(0.0f);
            imageView.setTranslationY(0.0f);
            return;
        }
        float f = -doubleShadowIconDrawable.mIconInsetSize;
        imageView.setTranslationX(f);
        imageView.setTranslationY(f);
    }

    public static void offsetTextViewForIcon(TextView textView, DoubleShadowIconDrawable doubleShadowIconDrawable, boolean z) {
        int i;
        if (doubleShadowIconDrawable == null) {
            textView.setTranslationX(0.0f);
            return;
        }
        if (z) {
            i = 1;
        } else {
            i = -1;
        }
        textView.setTranslationX(i * doubleShadowIconDrawable.mIconInsetSize);
    }

    public static void setIcon(ImageView imageView, Icon icon) {
        if (imageView == null) {
            Log.w("BcSmartspaceTemplateDataUtils", "Cannot set. The image view is null");
            return;
        }
        if (icon == null) {
            Log.w("BcSmartspaceTemplateDataUtils", "Cannot set. The given icon is null");
            updateVisibility(imageView, 8);
        }
        imageView.setImageIcon(icon.getIcon());
        if (icon.getContentDescription() != null) {
            imageView.setContentDescription(icon.getContentDescription());
        }
    }

    public static void setText(TextView textView, Text text) {
        if (textView == null) {
            Log.w("BcSmartspaceTemplateDataUtils", "Cannot set. The text view is null");
            return;
        }
        if (SmartspaceUtils.isEmpty(text)) {
            Log.w("BcSmartspaceTemplateDataUtils", "Cannot set. The given text is empty");
            updateVisibility(textView, 8);
        }
        textView.setText(text.getText());
        textView.setEllipsize(text.getTruncateAtType());
        textView.setMaxLines(text.getMaxLines());
    }

    public static void updateVisibility(View view, int i) {
        if (view != null && view.getVisibility() != i) {
            view.setVisibility(i);
        }
    }
}
