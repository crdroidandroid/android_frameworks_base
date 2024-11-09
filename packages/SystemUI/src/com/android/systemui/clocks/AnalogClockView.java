package com.android.systemui.clocks;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import java.util.Calendar;

public class AnalogClockView extends View {
    private Paint mPaint;
    private float centerX, centerY, radius;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public AnalogClockView(Context context) {
        super(context);
        init();
    }

    public AnalogClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AnalogClockView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mPaint = new Paint();
        mPaint.setAntiAlias(true);  // Smooth edges
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                invalidate(); // Redraw the view
                handler.postDelayed(this, 1000); // Update every second
            }
        }, 1000);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        centerX = width / 2;
        centerY = height / 2;
        radius = Math.min(centerX, centerY) - 20; // Leave margin
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Keep the wallpaper background (no transparent background here)

        // Draw clock face with hour markers
        mPaint.setColor(0xFFFFFFFF); // White color for hour markers
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setStrokeWidth(5);
        for (int i = 0; i < 12; i++) {
            float angle = (float) (Math.PI / 6 * i); // 30 degrees per hour
            float startX = (float) (centerX + radius * 0.85 * Math.cos(angle));
            float startY = (float) (centerY + radius * 0.85 * Math.sin(angle));
            float endX = (float) (centerX + radius * 0.95 * Math.cos(angle));
            float endY = (float) (centerY + radius * 0.95 * Math.sin(angle));
            canvas.drawRect(startX - 5, startY - 5, endX + 5, endY + 5, mPaint);
        }

        // Get current time
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR);
        int minute = calendar.get(Calendar.MINUTE);

        // Draw hour hand
        mPaint.setColor(0xFFFFFFFF); // White color for clock hands
        mPaint.setStrokeWidth(12);
        float hourAngle = (float) (Math.PI / 6 * (hour + minute / 60.0)); // Convert time to angle
        canvas.save();
        canvas.rotate((float) Math.toDegrees(hourAngle), centerX, centerY);
        canvas.drawLine(centerX, centerY, centerX, centerY - radius * 0.5f, mPaint);
        canvas.restore();

        // Draw minute hand
        mPaint.setStrokeWidth(8);
        float minuteAngle = (float) (Math.PI / 30 * minute); // Convert time to angle
        canvas.save();
        canvas.rotate((float) Math.toDegrees(minuteAngle), centerX, centerY);
        canvas.drawLine(centerX, centerY, centerX, centerY - radius * 0.7f, mPaint);
        canvas.restore();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacksAndMessages(null); // Stop updates when detached
    }
}
