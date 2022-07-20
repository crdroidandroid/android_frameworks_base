package com.google.android.systemui.smartspace.uitemplate;

import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.uitemplatedata.CarouselTemplateData;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Constraints;
import com.android.systemui.bcsmartspace.R;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.google.android.systemui.smartspace.BcSmartSpaceUtil;
import com.google.android.systemui.smartspace.BcSmartspaceCardSecondary;
import com.google.android.systemui.smartspace.BcSmartspaceTemplateDataUtils;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public class CarouselTemplateCard extends BcSmartspaceCardSecondary {
    public static final int clinit = 0;

    public CarouselTemplateCard(Context context) {
        super(context);
    }

    @Override // com.google.android.systemui.smartspace.BcSmartspaceCardSecondary
    public final void resetUi() {
        for (int i = 0; i < getChildCount(); i++) {
            View childAt = getChildAt(i);
            BcSmartspaceTemplateDataUtils.updateVisibility(childAt.findViewById(R.id.upper_text), 8);
            BcSmartspaceTemplateDataUtils.updateVisibility(childAt.findViewById(R.id.icon), 8);
            BcSmartspaceTemplateDataUtils.updateVisibility(childAt.findViewById(R.id.lower_text), 8);
        }
    }

    @Override // com.google.android.systemui.smartspace.BcSmartspaceCardSecondary
    public final void setTextColor(int i) {
        for (int i2 = 0; i2 < getChildCount(); i2++) {
            ((TextView) getChildAt(i2).findViewById(R.id.upper_text)).setTextColor(i);
            ((TextView) getChildAt(i2).findViewById(R.id.lower_text)).setTextColor(i);
        }
    }

    public CarouselTemplateCard(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }

    public final void onFinishInflate() {
        ConstraintLayout constraintLayout;
        ConstraintLayout constraintLayout2;
        super/*android.view.ViewGroup*/.onFinishInflate();
        ConstraintLayout[] constraintLayoutArr = new ConstraintLayout[4];
        for (int i = 0; i < 4; i++) {
            ConstraintLayout constraintLayout3 = (ConstraintLayout) ViewGroup.inflate(getContext(), R.layout.smartspace_carousel_column_template_card, null);
            constraintLayout3.setId(View.generateViewId());
            constraintLayoutArr[i] = constraintLayout3;
        }
        for (int i2 = 0; i2 < 4; i2++) {
            Constraints.LayoutParams layoutParams = new Constraints.LayoutParams(-2, 0);
            ConstraintLayout constraintLayout4 = constraintLayoutArr[i2];
            if (i2 > 0) {
                constraintLayout = constraintLayoutArr[i2 - 1];
            } else {
                constraintLayout = null;
            }
            if (i2 < 3) {
                constraintLayout2 = constraintLayoutArr[i2 + 1];
            } else {
                constraintLayout2 = null;
            }
            if (i2 == 0) {
                ((ConstraintLayout.LayoutParams) layoutParams).startToStart = 0;
                ((ConstraintLayout.LayoutParams) layoutParams).horizontalChainStyle = 1;
            } else {
                ((ConstraintLayout.LayoutParams) layoutParams).startToEnd = constraintLayout.getId();
            }
            if (i2 == 3) {
                ((ConstraintLayout.LayoutParams) layoutParams).endToEnd = 0;
            } else {
                ((ConstraintLayout.LayoutParams) layoutParams).endToStart = constraintLayout2.getId();
            }
            ((ConstraintLayout.LayoutParams) layoutParams).topToTop = 0;
            ((ConstraintLayout.LayoutParams) layoutParams).bottomToBottom = 0;
            addView(constraintLayout4, layoutParams);
        }
    }

    @Override // com.google.android.systemui.smartspace.BcSmartspaceCardSecondary
    public final boolean setSmartspaceActions(SmartspaceTarget smartspaceTarget, BcSmartspaceDataPlugin.SmartspaceEventNotifier smartspaceEventNotifier, BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo) {
        int i;
        int i2;
        CarouselTemplateData templateData = (CarouselTemplateData) smartspaceTarget.getTemplateData();
        if (templateData != null && templateData.getCarouselItems() != null) {
            List carouselItems = templateData.getCarouselItems();
            int intExact = Math.toIntExact(carouselItems.stream().filter(new Predicate() { // from class: com.google.android.systemui.smartspace.uitemplate.CarouselTemplateCard$$ExternalSyntheticLambda0
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean z;
                    CarouselTemplateData.CarouselItem carouselItem = (CarouselTemplateData.CarouselItem) obj;
                    int i3 = CarouselTemplateCard.clinit;
                    if (carouselItem.getImage() != null && carouselItem.getLowerText() != null && carouselItem.getUpperText() != null) {
                        z = true;
                    } else {
                        z = false;
                    }
                    return z;
                }
            }).count());
            if (intExact < 4) {
                int i3 = 4 - intExact;
                Log.w("CarouselTemplateCard", String.format(Locale.US, "Hiding %d incomplete column(s).", Integer.valueOf(i3)));
                for (int i4 = 0; i4 < 4; i4++) {
                    View childAt = getChildAt(i4);
                    if (i4 <= 3 - i3) {
                        i2 = 0;
                    } else {
                        i2 = 8;
                    }
                    BcSmartspaceTemplateDataUtils.updateVisibility(childAt, i2);
                }
                ConstraintLayout.LayoutParams layoutParams = (ConstraintLayout.LayoutParams) getChildAt(0).getLayoutParams();
                if (i3 == 0) {
                    i = 1;
                } else {
                    i = 0;
                }
                layoutParams.horizontalChainStyle = i;
            }
            for (int i5 = 0; i5 < intExact; i5++) {
                TextView textView = (TextView) getChildAt(i5).findViewById(R.id.upper_text);
                ImageView imageView = (ImageView) getChildAt(i5).findViewById(R.id.icon);
                TextView textView2 = (TextView) getChildAt(i5).findViewById(R.id.lower_text);
                BcSmartspaceTemplateDataUtils.setText(textView, ((CarouselTemplateData.CarouselItem) carouselItems.get(i5)).getUpperText());
                BcSmartspaceTemplateDataUtils.updateVisibility(textView, 0);
                BcSmartspaceTemplateDataUtils.setIcon(imageView, ((CarouselTemplateData.CarouselItem) carouselItems.get(i5)).getImage());
                BcSmartspaceTemplateDataUtils.updateVisibility(imageView, 0);
                BcSmartspaceTemplateDataUtils.setText(textView2, ((CarouselTemplateData.CarouselItem) carouselItems.get(i5)).getLowerText());
                BcSmartspaceTemplateDataUtils.updateVisibility(textView2, 0);
            }
            if (templateData.getCarouselAction() != null) {
                BcSmartSpaceUtil.setOnClickListener(this, smartspaceTarget, templateData.getCarouselAction(), smartspaceEventNotifier, "CarouselTemplateCard", bcSmartspaceCardLoggingInfo);
            }
            for (CarouselTemplateData.CarouselItem carouselItem : templateData.getCarouselItems()) {
                if (carouselItem.getTapAction() != null) {
                    BcSmartSpaceUtil.setOnClickListener(this, smartspaceTarget, carouselItem.getTapAction(), smartspaceEventNotifier, "CarouselTemplateCard", bcSmartspaceCardLoggingInfo);
                }
            }
            return true;
        }
        Log.w("CarouselTemplateCard", "CarouselTemplateData is null or has no CarouselItem");
        return false;
    }
}
