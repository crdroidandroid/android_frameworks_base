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
import com.android.systemui.bcsmartspace.R;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

public class BcSmartspaceCardWeatherForecast extends BcSmartspaceCardSecondary {

    public interface ItemUpdateFunction {
        void update(View view, int i);
    }

    public BcSmartspaceCardWeatherForecast(Context context) {
        super(context);
    }

    public BcSmartspaceCardWeatherForecast(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }

    @Override // com.google.android.systemui.smartspace.BcSmartspaceCardSecondary
    public final void setTextColor(int i) {
        updateFields((view, i2) -> {
            ((TextView) view).setTextColor(i);
        }, 4, R.id.temperature_value, "temperature value");
        updateFields((view2, i22) -> {
            ((TextView) view2).setTextColor(i);
        }, 4, R.id.timestamp, "timestamp");
    }

    public final void onFinishInflate() {
        super.onFinishInflate();
        ConstraintLayout[] constraintLayoutArr = new ConstraintLayout[4];
        for (int i = 0; i < 4; i++) {
            ConstraintLayout constraintLayout3 = (ConstraintLayout) ViewGroup.inflate(getContext(), R.layout.smartspace_card_weather_forecast_column, null);
            constraintLayout3.setId(View.generateViewId());
            constraintLayoutArr[i] = constraintLayout3;
        }
        for (int i2 = 0; i2 < 4; i2++) {
            Constraints.LayoutParams lp = new Constraints.LayoutParams(-2, 0);
            ConstraintLayout constraintLayout = constraintLayoutArr[i2];
            ConstraintLayout constraintLayout2 = null;
            if (i2 > 0) {
                constraintLayout2 = constraintLayoutArr[i2 - 1];
            }
            ConstraintLayout constraintLayout22 = null;
            if (i2 < 3) {
                constraintLayout22 = constraintLayoutArr[i2 + 1];
            }
            if (i2 == 0) {
                lp.startToStart = 0;
                lp.horizontalChainStyle = 1;
            } else {
                lp.startToEnd = constraintLayout2.getId();
            }
            if (i2 == 3) {
                lp.endToEnd = 0;
            } else {
                lp.endToStart = constraintLayout22.getId();
            }
            lp.topToTop = 0;
            lp.bottomToBottom = 0;
            addView(constraintLayout, lp);
        }
    }

    @Override // com.google.android.systemui.smartspace.BcSmartspaceCardSecondary
    public final boolean setSmartspaceActions(SmartspaceTarget smartspaceTarget, BcSmartspaceDataPlugin.SmartspaceEventNotifier smartspaceEventNotifier, BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo) {
        Bundle extras;
        SmartspaceAction baseAction = smartspaceTarget.getBaseAction();
        if (baseAction == null) {
            extras = null;
        } else {
            extras = baseAction.getExtras();
        }
        boolean z = false;
        if (extras == null) {
            return false;
        }
        if (extras.containsKey("temperatureValues")) {
            String[] temperatureValues = extras.getStringArray("temperatureValues");
            if (temperatureValues == null) {
                Log.w("BcSmartspaceCardWeatherForecast", "Temperature values array is null.");
            } else {
                updateFields((view, i) -> {
                    ((TextView) view).setText(temperatureValues[i]);
                }, temperatureValues.length, R.id.temperature_value, "temperature value");
            }
            z = true;
        }
        if (extras.containsKey("weatherIcons")) {
            Bitmap[] weatherIcons = (Bitmap[]) extras.get("weatherIcons");
            if (weatherIcons == null) {
                Log.w("BcSmartspaceCardWeatherForecast", "Weather icons array is null.");
            } else {
                updateFields((view2, i2) -> {
                    ((ImageView) view2).setImageBitmap(weatherIcons[i2]);
                }, weatherIcons.length, R.id.weather_icon, "weather icon");
            }
            z = true;
        }
        if (extras.containsKey("timestamps")) {
            String[] timestamps = extras.getStringArray("timestamps");
            if (timestamps == null) {
                Log.w("BcSmartspaceCardWeatherForecast", "Timestamps array is null.");
                return true;
            }
            updateFields((view3, i3) -> {
                ((TextView) view3).setText(timestamps[i3]);
            }, timestamps.length, R.id.timestamp, "timestamp");
            return true;
        }
        return z;
    }

    public final void updateFields(ItemUpdateFunction itemUpdateFunction, int i, int i2, String str) {
        int i3;
        int i4;
        if (getChildCount() < 4) {
            Log.w("BcSmartspaceForecast", "Missing %d " + str + " view(s) to update." + (4 - getChildCount()));
            return;
        }
        if (i < 4) {
            int i5 = 4 - i;
            Log.w("BcSmartspaceForecast", "Missing %d " + str + "(s). Hiding incomplete columns." + i5);
            if (getChildCount() < 4) {
                Log.w("BcSmartspaceForecast", "Missing %d columns to update." + (4 - getChildCount()));
            } else {
                int i6 = 3 - i5;
                for (int i7 = 0; i7 < 4; i7++) {
                    View childAt = getChildAt(i7);
                    if (i7 <= i6) {
                        i4 = 0;
                    } else {
                        i4 = 8;
                    }
                    int i42 = i4;
                    BcSmartspaceTemplateDataUtils.updateVisibility(childAt, i42);
                }
                ConstraintLayout.LayoutParams layoutParams = (ConstraintLayout.LayoutParams) getChildAt(0).getLayoutParams();
                if (i5 == 0) {
                    i3 = 1;
                } else {
                    i3 = 0;
                }
                layoutParams.horizontalChainStyle = i3;
            }
        }
        int min = Math.min(4, i);
        for (int i8 = 0; i8 < min; i8++) {
            View findViewById = getChildAt(i8).findViewById(i2);
            if (findViewById == null) {
                Log.w("BcSmartspaceForecast", "Missing " + str + " view to update at column: %d." + (i8 + 1));
                return;
            }
            itemUpdateFunction.update(findViewById, i8);
        }
    }
}
