package com.google.android.systemui.assist.uihints;

import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.util.MathUtils;
import com.android.systemui.R;
import com.android.systemui.assist.ui.CornerPathRenderer;
import com.android.systemui.assist.ui.InvocationLightsView;
import com.android.systemui.assist.ui.PathSpecCornerPathRenderer;
import com.android.systemui.assist.ui.PerimeterPathGuide;

public class AssistantInvocationLightsView extends InvocationLightsView {
    private final int mColorBlue;
    private final int mColorGreen;
    private final int mColorRed;
    private final int mColorYellow;

    public AssistantInvocationLightsView(Context context) {
        this(context, null);
    }

    public AssistantInvocationLightsView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    public AssistantInvocationLightsView(Context context, AttributeSet attributeSet, int i) {
        this(context, attributeSet, i, 0);
    }

    public AssistantInvocationLightsView(Context context, AttributeSet attributeSet, int i, int i2) {
        super(context, attributeSet, i, i2);
        Resources resources = context.getResources();
        mColorRed = resources.getColor(R.color.edge_light_red);
        mColorYellow = resources.getColor(R.color.edge_light_yellow);
        mColorBlue = resources.getColor(R.color.edge_light_blue);
        mColorGreen = resources.getColor(R.color.edge_light_green);
    }

    public void setGoogleAssistant(boolean z) {
        if (z) {
            setColors(mColorBlue, mColorRed, mColorYellow, mColorGreen);
        } else {
            setColors(null);
        }
    }

    @Override 
    public void onInvocationProgress(float f) {
        if (f <= 1.0f) {
            super.onInvocationProgress(f);
        } else {
            float regionWidth = mGuide.getRegionWidth(PerimeterPathGuide.Region.BOTTOM) / 4.0f;
            float lerp = MathUtils.lerp((mGuide.getRegionWidth(PerimeterPathGuide.Region.BOTTOM_LEFT) * 0.6f) / 2.0f, regionWidth, 1.0f - (f - 1.0f));
            setLight(0, regionWidth - lerp, regionWidth);
            float f2 = 2.0f * regionWidth;
            setLight(1, regionWidth, f2);
            float f3 = regionWidth * 3.0f;
            setLight(2, f2, f3);
            setLight(3, f3, lerp + f3);
            setVisibility(0);
        }
        invalidate();
    }

    @Override
    public CornerPathRenderer createCornerPathRenderer(Context context) {
        return new PathSpecCornerPathRenderer(context);
    }
}
