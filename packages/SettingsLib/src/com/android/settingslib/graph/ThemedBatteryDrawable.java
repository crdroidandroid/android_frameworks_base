package com.android.settingslib.graph;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Join;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.Path.Op;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.PathParser;
import android.util.TypedValue;

import com.android.settingslib.R;
import com.android.settingslib.Utils;

public class ThemedBatteryDrawable extends BatteryMeterDrawableBase {
    private int backgroundColor = 0xFFFF00FF;
    private final Path boltPath = new Path();
    private boolean charging;
    private int[] colorLevels;
    private final Context context;
    private int criticalLevel;
    private boolean dualTone;
    private int fillColor = 0xFFFF00FF;
    private final Path fillMask = new Path();
    private final RectF fillRect = new RectF();
    private int intrinsicHeight;
    private int intrinsicWidth;
    private boolean invertFillIcon;
    private int levelColor = 0xFFFF00FF;
    private final Path levelPath = new Path();
    private final RectF levelRect = new RectF();
    private final Rect padding = new Rect();
    private final Path perimeterPath = new Path();
    private final Path plusPath = new Path();
    private boolean powerSaveEnabled;
    private final Matrix scaleMatrix = new Matrix();
    private final Path scaledBolt = new Path();
    private final Path scaledFill = new Path();
    private final Path scaledPerimeter = new Path();
    private final Path scaledPlus = new Path();
    private final Path unifiedPath = new Path();
    private final Path textPath = new Path();
    private final RectF iconRect = new RectF();

    private final Paint dualToneBackgroundFill;
    private final Paint fillColorStrokePaint;
    private final Paint fillColorStrokeProtection;
    private final Paint fillPaint;
    private final Paint textPaint;
    private final Paint errorPaint;

    private final float mWidthDp = 12f;
    private final float mHeightDp = 20f;

    private int mMeterStyle;
    private int level;
    private boolean showPercent;

    public int getOpacity() {
        return -1;
    }

    public void setAlpha(int i) {
    }

    public ThemedBatteryDrawable(Context context, int frameColor) {
        super(context, frameColor);

        this.context = context;
        float f = this.context.getResources().getDisplayMetrics().density;
        this.intrinsicHeight = (int) (mHeightDp * f);
        this.intrinsicWidth = (int) (mWidthDp * f);
        Resources res = this.context.getResources();

        TypedArray levels = res.obtainTypedArray(R.array.batterymeter_color_levels);
        TypedArray colors = res.obtainTypedArray(R.array.batterymeter_color_values);

        final int N = levels.length();
        colorLevels = new int[2 * N];
        for (int i = 0; i < N; i++) {
            colorLevels[2 * i] = levels.getInt(i, 0);
            if (colors.getType(i) == TypedValue.TYPE_ATTRIBUTE) {
                colorLevels[2 * i + 1] = Utils.getColorAttr(context, colors.getThemeAttributeId(i, 0));
            } else {
                colorLevels[2 * i + 1] = colors.getColor(i, 0);
            }
        }
        levels.recycle();
        colors.recycle();
        
        setCriticalLevel(res.getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel));

        dualToneBackgroundFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        dualToneBackgroundFill.setColor(frameColor);
        dualToneBackgroundFill.setAlpha(255);
        dualToneBackgroundFill.setDither(true);
        dualToneBackgroundFill.setStrokeWidth(0f);
        dualToneBackgroundFill.setStyle(Style.FILL_AND_STROKE);

        fillColorStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillColorStrokePaint.setColor(frameColor);
        fillColorStrokePaint.setDither(true);
        fillColorStrokePaint.setStrokeWidth(5f);
        fillColorStrokePaint.setStyle(Style.STROKE);
        fillColorStrokePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        fillColorStrokePaint.setStrokeMiter(5f);
        fillColorStrokePaint.setStrokeJoin(Join.ROUND);

        fillColorStrokeProtection = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillColorStrokeProtection.setDither(true);
        fillColorStrokeProtection.setStrokeWidth(5f);
        fillColorStrokeProtection.setStyle(Style.STROKE);
        fillColorStrokeProtection.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        fillColorStrokeProtection.setStrokeMiter(5f);
        fillColorStrokeProtection.setStrokeJoin(Join.ROUND);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(frameColor);
        fillPaint.setAlpha(255);
        fillPaint.setDither(true);
        fillPaint.setStrokeWidth(0f);
        fillPaint.setStyle(Style.FILL_AND_STROKE);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        textPaint.setTypeface(font);
        textPaint.setTextAlign(Paint.Align.CENTER);

        errorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        errorPaint.setColor(Utils.getDefaultColor(mContext, R.color.batterymeter_plus_color));
        errorPaint.setAlpha(255);
        errorPaint.setAlpha(255);
        errorPaint.setDither(true);
        errorPaint.setStrokeWidth(0f);
        errorPaint.setStyle(Style.FILL_AND_STROKE);
        errorPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));

        loadPaths();
    }

    public void setCriticalLevel(int i) {
        this.criticalLevel = i;
    }

    public final void setCharging(boolean charging) {
        this.charging = charging;
        super.setCharging(charging);
    }

    public boolean getCharging() {
        return this.charging;
    }

    public final boolean getPowerSaveEnabled() {
        return this.powerSaveEnabled;
    }

    public final void setPowerSaveEnabled(boolean enabled) {
        this.powerSaveEnabled = enabled;
        super.setPowerSave(enabled);
    }

    public void setShowPercent(boolean show) {
        this.showPercent = show;
        super.setShowPercent(show);
    }

    public void draw(Canvas canvas) {
        if (useSuper()) {
            super.draw(canvas);
            return;
        }
        
        boolean opaqueBolt = this.level <= 30;
        boolean drawText;
        float pctX = 0, pctY = 0, textHeight;
        String pctText = null;
        boolean pctOpaque = false;
        if (!this.charging && !this.powerSaveEnabled && this.showPercent) {
            float baseHeight = (this.dualTone ? this.iconRect : this.fillRect).height();
            this.textPaint.setColor(getColorForLevel(level));
            final float full = 0.38f;
            final float nofull = 0.5f;
            this.textPaint.setTextSize(baseHeight * (this.level == 100 ? full : nofull));
            textHeight = -mTextPaint.getFontMetrics().ascent;
            pctText = String.valueOf(level);
            pctX = this.fillRect.width() * 0.5f + this.fillRect.left;
            pctY = (this.fillRect.height() + textHeight) * 0.47f + this.fillRect.top;
            this.textPath.reset();
            this.textPaint.getTextPath(pctText, 0, pctText.length(), pctX, pctY, this.textPath);
            drawText = true;
        } else {
            drawText = false;
        }

        this.unifiedPath.reset();
        this.levelPath.reset();
        this.levelRect.set(this.fillRect);
        float level = ((float) this.level) / 100.0f;
        float levelTop;
        if (this.level >= 95) {
            levelTop = this.fillRect.top;
        } else {
            RectF rectF = this.fillRect;
            levelTop = (rectF.height() * (((float) 1) - level)) + rectF.top;
        }
        pctOpaque = this.dualTone && levelTop > pctY;
        this.levelRect.top = (float) Math.floor(this.dualTone ? this.fillRect.top : levelTop);
        this.levelPath.addRect(this.levelRect, Direction.CCW);
        this.unifiedPath.addPath(this.scaledPerimeter);
        this.unifiedPath.op(this.levelPath, Op.UNION);
        if (drawText && !pctOpaque) {
            this.unifiedPath.op(this.textPath, Op.DIFFERENCE);
        }
        this.fillPaint.setColor(this.levelColor);
        if (this.charging) {
            if (!this.dualTone || !opaqueBolt) {
                this.unifiedPath.op(this.scaledBolt, Op.DIFFERENCE);
            }
            if (!this.dualTone && !this.invertFillIcon) {
                canvas.drawPath(this.scaledBolt, this.fillPaint);
            }
        }
        if (this.dualTone) {
            canvas.drawPath(this.unifiedPath, this.dualToneBackgroundFill);
            canvas.save();
            float clipTop = getBounds().bottom - getBounds().height() * level;
            canvas.clipRect(0f, clipTop, (float) getBounds().right, (float) getBounds().bottom);
            canvas.drawPath(this.unifiedPath, fillPaint);
            canvas.restore();
            if (this.charging && opaqueBolt) {
                canvas.drawPath(this.scaledBolt, fillPaint);
            }
            if (drawText && pctOpaque) {
                canvas.drawPath(this.textPath, fillPaint);
            }
        } else {
            this.fillPaint.setColor(this.fillColor);
            canvas.drawPath(this.unifiedPath, this.fillPaint);
            this.fillPaint.setColor(this.levelColor);
            if (this.level <= 15 && !this.charging) {
                canvas.save();
                canvas.clipPath(this.scaledFill);
                canvas.drawPath(this.levelPath, this.fillPaint);
                canvas.restore();
            }
            if (drawText) {
                this.textPath.op(this.levelPath, Op.DIFFERENCE);
                canvas.drawPath(this.textPath, this.fillPaint);
            }
        }
        if (!this.dualTone && this.charging) {
            canvas.clipOutPath(this.scaledBolt);
            if (this.invertFillIcon) {
                canvas.drawPath(this.scaledBolt, this.fillColorStrokePaint);
            } else {
                canvas.drawPath(this.scaledBolt, this.fillColorStrokeProtection);
            }
        } else if (this.powerSaveEnabled) {
            canvas.drawPath(this.scaledPerimeter, this.errorPaint);
            canvas.drawPath(this.scaledPlus, this.errorPaint);
        }
    }

    public int getBatteryLevel() {
        return this.level;
    }

    protected int batteryColorForLevel(int level) {
        return (this.charging || this.powerSaveEnabled)
                ? this.fillColor
                : getColorForLevel(level);
    }

    private final int getColorForLevel(int percent) {
        int thresh, color = 0;
        for (int i = 0; i < colorLevels.length; i += 2) {
            thresh = colorLevels[i];
            color = colorLevels[i + 1];
            if (percent <= thresh) {

                // Respect tinting for "normal" level
                if (i == colorLevels.length - 2) {
                    return this.fillColor;
                } else {
                    return color;
                }
            }
        }
        return color;
    }

    public void setColorFilter(ColorFilter colorFilter) {
        this.fillPaint.setColorFilter(colorFilter);
        this.fillColorStrokePaint.setColorFilter(colorFilter);
        this.dualToneBackgroundFill.setColorFilter(colorFilter);
    }

    public int getIntrinsicHeight() {
        if (!useSuper()) {
            return this.intrinsicHeight;
        } else {
            return super.getIntrinsicHeight();
        }
    }

    public int getIntrinsicWidth() {
        if (!useSuper()) {
            return this.intrinsicWidth;
        } else {
            return super.getIntrinsicWidth();
        }
    }

    public void setBatteryLevel(int val) {
        this.level = val;
        this.invertFillIcon = val >= 67 ? true : val <= 33 ? false : this.invertFillIcon;
        this.levelColor = batteryColorForLevel(this.level);
        super.setBatteryLevel(val);
    }

    protected void onBoundsChange(Rect rect) {
        super.onBoundsChange(rect);
        updateSize();
    }

    public void setColors(int fillColor, int backgroundColor, int singleToneColor) {
        this.dualTone = getMeterStyle() == BATTERY_STYLE_PORTRAIT;
        this.fillColor = this.dualTone ? fillColor : singleToneColor;
        this.fillPaint.setColor(this.fillColor);
        this.fillColorStrokePaint.setColor(this.fillColor);
        this.backgroundColor = backgroundColor;
        this.dualToneBackgroundFill.setColor(backgroundColor);
        this.levelColor = batteryColorForLevel(this.level);
        super.setColors(fillColor, backgroundColor);
    }

    private final void updateSize() {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            this.scaleMatrix.setScale(1.0f, 1.0f);
        } else {
            this.scaleMatrix.setScale(bounds.right / mWidthDp, bounds.bottom / mHeightDp);
        }
        this.perimeterPath.transform(this.scaleMatrix, this.scaledPerimeter);
        this.fillMask.transform(this.scaleMatrix, this.scaledFill);
        this.scaledFill.computeBounds(this.fillRect, true);
        this.boltPath.transform(this.scaleMatrix, this.scaledBolt);
        this.plusPath.transform(this.scaleMatrix, this.scaledPlus);
        float max = Math.max(bounds.right / mWidthDp * 3f, 6f);
        this.fillColorStrokePaint.setStrokeWidth(max);
        this.fillColorStrokeProtection.setStrokeWidth(max);
        this.iconRect.set(bounds);
    }

    private final void loadPaths() {
        Resources res = context.getResources();
        this.perimeterPath.set(PathParser.createPathFromPathData(res.getString(R.string.config_batterymeterPerimeterPath)));
        this.perimeterPath.computeBounds(new RectF(), true);
        this.fillMask.set(PathParser.createPathFromPathData(res.getString(R.string.config_batterymeterFillMask)));
        this.fillMask.computeBounds(this.fillRect, true);
        this.boltPath.set(PathParser.createPathFromPathData(res.getString(R.string.config_batterymeterBoltPath)));
        this.plusPath.set(PathParser.createPathFromPathData(res.getString(R.string.config_batterymeterPowersavePath)));
        this.dualTone = false;
    }

    private boolean useSuper() {
        int style = getMeterStyle();
        return style != BATTERY_STYLE_PORTRAIT && style != BATTERY_STYLE_Q;
    }
}