package com.google.android.systemui.smartspace;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.os.Handler;
import android.os.SystemClock;
import android.util.AttributeSet;
import com.android.systemui.bcsmartspace.R;
import java.util.Locale;
import java.util.Objects;

public class IcuDateTextView extends DoubleShadowTextView {
    public DateFormat mFormatter;
    public Handler mHandler;
    public final BroadcastReceiver mIntentReceiver;
    public String mText;
    public final Runnable mTicker;

    public IcuDateTextView(Context context) {
        this(context, null);
    }

    public IcuDateTextView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet, 0);
        this.mTicker = this::onTimeTick;
        this.mIntentReceiver = new BroadcastReceiver() { // from class: com.google.android.systemui.smartspace.IcuDateTextView.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                IcuDateTextView.this.onTimeChanged(!"android.intent.action.TIME_TICK".equals(intent.getAction()));
            }
        };
    }

    @Override // android.widget.TextView, android.view.View
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.TIME_SET");
        intentFilter.addAction("android.intent.action.TIMEZONE_CHANGED");
        getContext().registerReceiver(this.mIntentReceiver, intentFilter);
        onTimeChanged(true);
        this.mHandler = new Handler();
    }

    @Override // android.view.View
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (this.mHandler != null) {
            getContext().unregisterReceiver(this.mIntentReceiver);
            this.mHandler = null;
        }
    }

    private void onTimeTick() {
        onTimeChanged(false);
        if (this.mHandler != null) {
            long uptimeMillis = SystemClock.uptimeMillis();
            this.mHandler.postAtTime(this.mTicker, uptimeMillis + (1000 - (uptimeMillis % 1000)));
        }
    }

    @Override // android.view.View
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (this.mHandler != null) {
            this.mHandler.removeCallbacks(this.mTicker);
            if (isVisible) {
                this.mTicker.run();
            }
        }
    }

    public void onTimeChanged(boolean force) {
        if (!isShown()) {
            return;
        }
        if (this.mFormatter == null || force) {
            DateFormat format = DateFormat.getInstanceForSkeleton(getContext().getString(R.string.smartspace_icu_date_pattern), Locale.getDefault());
            this.mFormatter = format;
            format.setContext(DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE);
        }
        String format2 = this.mFormatter.format(Long.valueOf(System.currentTimeMillis()));
        if (!Objects.equals(this.mText, format2)) {
            this.mText = format2;
            setText(format2);
            setContentDescription(format2);
        }
    }
}
