package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Constraints;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import com.android.systemui.res.R;

import java.util.Locale;

public class BcSmartspaceCardWeatherForecast extends BcSmartspaceCardSecondary {

    public interface ItemUpdateFunction {
        void update(View view, int i);
    }
    public BcSmartspaceCardWeatherForecast(Context context) {
        super(context);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        ConstraintLayout[] constraintLayoutArr = new ConstraintLayout[4];
        for (int i = 0; i < 4; i++) {
            ConstraintLayout constraintLayout = (ConstraintLayout) ViewGroup.inflate(getContext(), R.layout.smartspace_card_weather_forecast_column, null);
            constraintLayout.setId(View.generateViewId());
            constraintLayoutArr[i] = constraintLayout;
        }
        int i2 = 0;
        while (i2 < 4) {
            Constraints.LayoutParams layoutParams = new Constraints.LayoutParams(-2, 0);
            ConstraintLayout constraintLayout2 = constraintLayoutArr[i2];
            ConstraintLayout constraintLayout3 = i2 > 0 ? constraintLayoutArr[i2 - 1] : null;
            ConstraintLayout constraintLayout4 = i2 < 3 ? constraintLayoutArr[i2 + 1] : null;
            if (i2 == 0) {
                layoutParams.startToStart = 0;
                layoutParams.horizontalChainStyle = 1;
            } else {
                layoutParams.startToEnd = constraintLayout3.getId();
            }
            if (i2 == 3) {
                layoutParams.endToEnd = 0;
            } else {
                layoutParams.endToStart = constraintLayout4.getId();
            }
            layoutParams.topToTop = 0;
            layoutParams.bottomToBottom = 0;
            addView(constraintLayout2, layoutParams);
            i2++;
        }
    }

    @Override
    public final boolean setSmartspaceActions(SmartspaceTarget target, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, BcSmartspaceCardLoggingInfo loggingInfo) {
        boolean z;
        SmartspaceAction baseAction = target.getBaseAction();
        Bundle extras = baseAction == null ? null : baseAction.getExtras();

        if (extras == null) {
            return false;
        }

        if (extras.containsKey("temperatureValues")) {
            String[] stringArray = extras.getStringArray("temperatureValues");
            if (stringArray == null) {
                Log.w("BcSmartspaceCardWeatherForecast", "Temperature values array is null.");
            } else {
                updateFields((view, i) -> ((TextView) view).setText(stringArray[i]), 
                            stringArray.length, R.id.temperature_value, "temperature value");
            }
            z = true;
        } else {
            z = false;
        }

        if (extras.containsKey("weatherIcons")) {
            Bitmap[] bitmapArr = (Bitmap[]) extras.get("weatherIcons");
            if (bitmapArr == null) {
                Log.w("BcSmartspaceCardWeatherForecast", "Weather icons array is null.");
            } else {
                updateFields((view, i) -> ((ImageView) view).setImageBitmap(bitmapArr[i]), 
                            bitmapArr.length, R.id.weather_icon, "weather icon");
            }
            z = true;
        }

        if (!extras.containsKey("timestamps")) {
            return z;
        }
        String[] stringArray2 = extras.getStringArray("timestamps");
        if (stringArray2 == null) {
            Log.w("BcSmartspaceCardWeatherForecast", "Timestamps array is null.");
            return true;
        }

        updateFields((view, i) -> ((TextView) view).setText(stringArray2[i]), 
                    stringArray2.length, R.id.timestamp, "timestamp");

        return true;
    }

    @Override
    public final void setTextColor(int color) {
        updateFields((view, index) -> ((TextView) view).setTextColor(color), 
                    4, R.id.temperature_value, "temperature value");

        updateFields((view, index) -> ((TextView) view).setTextColor(color), 
                    4, R.id.timestamp, "timestamp");
    }

    public final void updateFields(ItemUpdateFunction itemUpdateFunction, int count, int viewId, String viewName) {
        if (getChildCount() < 4) {
            Log.w(
                    "BcSmartspaceCardWeatherForecast",
                    String.format(
                            Locale.US,
                            "Missing %d %s view(s) to update.",
                            4 - getChildCount(),
                            viewName));
            return;
        }
        if (count < 4) {
            int i3 = 4 - count;
            Log.w(
                    "BcSmartspaceCardWeatherForecast",
                    String.format(
                            Locale.US,
                            "Missing %d %s(s). Hiding incomplete columns.",
                            i3,
                            viewName));
            if (getChildCount() < 4) {
                Log.w("BcSmartspaceCardWeatherForecast", "Missing " + (4 - getChildCount()) + " columns to update.");
            } else {
                int i4 = 3 - i3;
                for (int i = 0; i < 4; i++) {
                    BcSmartspaceTemplateDataUtils.updateVisibility(getChildAt(i), i <= i4 ? 0 : 8);
                }
                ((ConstraintLayout.LayoutParams) ((ConstraintLayout) getChildAt(0)).getLayoutParams()).horizontalChainStyle = i3 == 0 ? 1 : 0;
            }
        }
        int min = Math.min(4, count);
        for (int i = 0; i < min; i++) {
            View findViewById = getChildAt(i).findViewById(viewId);
            if (findViewById == null) {
                Log.w(
                        "BcSmartspaceCardWeatherForecast",
                        String.format(
                                Locale.US,
                                "Missing %s view to update at column: %d.",
                                viewName,
                                i + 1));
                return;
            }
            itemUpdateFunction.update(findViewById, i);
        }
    }

    /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 2 */
    public BcSmartspaceCardWeatherForecast(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }
}
