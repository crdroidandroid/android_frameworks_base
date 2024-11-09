package com.android.systemui.clocks;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Calendar;

public class NothingAnalogClockView extends View {

    private Paint mPaint;
    private float centerX, centerY, width, height;

    public NothingAnalogClockView(Context context) {
        super(context);
        init();
    }

    public NothingAnalogClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NothingAnalogClockView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mPaint = new Paint();
        mPaint.setAntiAlias(true); // Smooth edges for drawing
        mPaint.setStrokeCap(Paint.Cap.ROUND); // Rounded ends for clock hands

        // Refresh the clock every second
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);

        this.width = width;
        this.height = height;

        centerX = width / 2f;
        centerY = height / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Get current time
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR);
        int minute = calendar.get(Calendar.MINUTE);
        int second = calendar.get(Calendar.SECOND);

        // Draw clock ticks in a rectangular shape
        drawRectangularTicks(canvas);

        // Draw hour hand
        drawHourHand(canvas, hour, minute);

        // Draw minute hand
        drawMinuteHand(canvas, minute);

        // Draw second hand
        drawSecondHand(canvas, second);

        // Trigger continuous updates
        postInvalidateDelayed(16); // Smooth 60 FPS animation
    }

    private void drawRectangularTicks(Canvas canvas) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(0xFFFFFFFF); // White color for ticks

        // Rectangular bounds (inset from edges)
        float horizontalInset = width * 0.1f;
        float verticalInset = height * 0.2f;

        for (int i = 0; i < 60; i++) {
            float angle = (float) (Math.PI / 30 * i);
            float startX, startY, endX, endY;

            if (i % 5 == 0) {
                // Major ticks (longer and thicker)
                mPaint.setStrokeWidth(6);
                startX = (float) (centerX + (width / 2 - horizontalInset) * Math.cos(angle));
                startY = (float) (centerY + (height / 2 - verticalInset) * Math.sin(angle));
                endX = (float) (centerX + (width / 2 - horizontalInset * 1.5) * Math.cos(angle));
                endY = (float) (centerY + (height / 2 - verticalInset * 1.5) * Math.sin(angle));
            } else {
                // Minor ticks (shorter and thinner)
                mPaint.setStrokeWidth(2);
                startX = (float) (centerX + (width / 2 - horizontalInset) * Math.cos(angle));
                startY = (float) (centerY + (height / 2 - verticalInset) * Math.sin(angle));
                endX = (float) (centerX + (width / 2 - horizontalInset * 1.2) * Math.cos(angle));
                endY = (float) (centerY + (height / 2 - verticalInset * 1.2) * Math.sin(angle));
            }

            canvas.drawLine(startX, startY, endX, endY, mPaint);
        }
    }

    private void drawHourHand(Canvas canvas, int hour, int minute) {
        float hourAngle = (float) (Math.PI / 6 * (hour + minute / 60.0));
        mPaint.setColor(0xFFFFFFFF); // White color for hour hand
        mPaint.setStrokeWidth(12);

        float endX = (float) (centerX + (width * 0.25f) * Math.cos(hourAngle - Math.PI / 2));
        float endY = (float) (centerY + (height * 0.25f) * Math.sin(hourAngle - Math.PI / 2));

        canvas.drawLine(centerX, centerY, endX, endY, mPaint);
    }

    private void drawMinuteHand(Canvas canvas, int minute) {
        float minuteAngle = (float) (Math.PI / 30 * minute);
        mPaint.setColor(0xFFFFFFFF); // White color for minute hand
        mPaint.setStrokeWidth(8);

        float endX = (float) (centerX + (width * 0.4f) * Math.cos(minuteAngle - Math.PI / 2));
        float endY = (float) (centerY + (height * 0.4f) * Math.sin(minuteAngle - Math.PI / 2));

        canvas.drawLine(centerX, centerY, endX, endY, mPaint);
    }

    private void drawSecondHand(Canvas canvas, int second) {
        float secondAngle = (float) (Math.PI / 30 * second);
        mPaint.setColor(0xFFFF0000); // Red color for second hand
        mPaint.setStrokeWidth(4);

        float endX = (float) (centerX + (width * 0.45f) * Math.cos(secondAngle - Math.PI / 2));
        float endY = (float) (centerY + (height * 0.45f) * Math.sin(secondAngle - Math.PI / 2));

        canvas.drawLine(centerX, centerY, endX, endY, mPaint);
    }
}
