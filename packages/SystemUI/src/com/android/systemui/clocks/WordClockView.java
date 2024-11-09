package com.android.systemui.clocks;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class WordClockView extends TextView {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateTimeRunnable = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 1000);
        }
    };

    public WordClockView(Context context) {
        super(context);
        init();
    }

    public WordClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WordClockView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        updateClock();
        handler.post(updateTimeRunnable);
    }

    private void updateClock() {
        String currentTime = new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date());
        String[] timeParts = currentTime.split(":");
        String hour = timeParts[0];
        String minute = timeParts[1].split(" ")[0];

        String hourInWords = convertToWords(Integer.parseInt(hour));
        String minuteInWords = convertToWords(Integer.parseInt(minute));

        // Set only the hour and minute in words
        setText(hourInWords + "\n" + minuteInWords);
    }

    private String convertToWords(int num) {
        String[] words = {
                "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
                "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
                "Seventeen", "Eighteen", "Nineteen", "Twenty", "Twenty-One", "Twenty-Two",
                "Twenty-Three", "Twenty-Four", "Twenty-Five", "Twenty-Six", "Twenty-Seven",
                "Twenty-Eight", "Twenty-Nine", "Thirty", "Thirty-One", "Thirty-Two",
                "Thirty-Three", "Thirty-Four", "Thirty-Five", "Thirty-Six", "Thirty-Seven",
                "Thirty-Eight", "Thirty-Nine", "Forty", "Forty-One", "Forty-Two",
                "Forty-Three", "Forty-Four", "Forty-Five", "Forty-Six", "Forty-Seven",
                "Forty-Eight", "Forty-Nine", "Fifty", "Fifty-One", "Fifty-Two", "Fifty-Three",
                "Fifty-Four", "Fifty-Five", "Fifty-Six", "Fifty-Seven", "Fifty-Eight",
                "Fifty-Nine"
        };
        return num < words.length ? words[num] : "";
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(updateTimeRunnable); // Stop updates when view is detached
    }
}
