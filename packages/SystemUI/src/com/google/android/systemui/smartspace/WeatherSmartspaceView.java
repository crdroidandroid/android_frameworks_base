package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceUtils;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.app.smartspace.uitemplatedata.Icon;
import android.app.smartspace.uitemplatedata.TapAction;
import android.app.smartspace.uitemplatedata.Text;
import android.content.Context;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Parcelable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.FalsingManager;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLogger;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggerUtil;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import com.google.android.systemui.smartspace.utils.ContentDescriptionUtil;

import com.android.systemui.res.R;

import java.util.List;

public class WeatherSmartspaceView extends LinearLayout implements BcSmartspaceDataPlugin.SmartspaceTargetListener, BcSmartspaceDataPlugin.SmartspaceView {
    public static final boolean DEBUG = Log.isLoggable("WeatherSmartspaceView", Log.DEBUG);
    public final ContentObserver mAodSettingsObserver;
    public Handler mBgHandler;
    public BcSmartspaceDataPlugin mDataProvider;
    public float mDozeAmount;
    public final DoubleShadowIconDrawable mIconDrawable;
    public final int mIconSize;
    public boolean mIsAodEnabled;
    public BcSmartspaceCardLoggingInfo mLoggingInfo;
    public int mPrimaryTextColor;
    public final boolean mRemoveTextDescent;
    public final int mTextDescentExtraPadding;
    public String mUiSurface;
    public DoubleShadowTextView mView;

    public WeatherSmartspaceView(Context context) {
        this(context, null);
    }

    @Override
    public final void onAttachedToWindow() {
        super.onAttachedToWindow();
        
        if (TextUtils.equals(mUiSurface, BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)) {
            try {
                if (mBgHandler == null) {
                    throw new IllegalStateException("Must set background handler to avoid making binder calls on main thread");
                }

                mBgHandler.post(() -> {
                    getContext().getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor("doze_always_on"), 
                        false, 
                        mAodSettingsObserver, 
                        -1
                    );
                });
            } catch (Exception e) {
                Log.w("WeatherSmartspaceView", "Unable to register DOZE_ALWAYS_ON content observer: ", e);
            }

            mIsAodEnabled = Settings.Secure.getIntForUser(getContext().getContentResolver(), "doze_always_on", 0, getContext().getUserId()) == 1;
        }

        if (mDataProvider != null) {
            mDataProvider.registerListener(this);
        }
    }

    @Override
    public final void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        
        if (mBgHandler == null) {
            throw new IllegalStateException("Must set background handler to avoid making binder calls on main thread");
        }

        mBgHandler.post(() -> {
            getContext().getContentResolver().unregisterContentObserver(mAodSettingsObserver);
        });

        if (mDataProvider != null) {
            mDataProvider.unregisterListener(this);
        }
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        mView = findViewById(R.id.weather_text_view);
    }

    @Override
    public final void onSmartspaceTargetsUpdated(List<? extends Parcelable> targets) {
        List<SmartspaceTarget> smartspaceTargets =
                targets.stream()
                        .filter(t -> t instanceof SmartspaceTarget)
                        .map(t -> (SmartspaceTarget) t)
                        .collect(java.util.stream.Collectors.toList());
        if (smartspaceTargets.size() > 1) {
            return;
        }
        if (smartspaceTargets.isEmpty() && TextUtils.equals(mUiSurface, BcSmartspaceDataPlugin.UI_SURFACE_DREAM)) {
            return;
        }
        if (smartspaceTargets.isEmpty()) {
            BcSmartspaceTemplateDataUtils.updateVisibility(mView, View.GONE);
            return;
        }
        BcSmartspaceTemplateDataUtils.updateVisibility(mView, View.VISIBLE);
        SmartspaceTarget target = smartspaceTargets.get(0);
        if (target.getFeatureType() != 1) {
            return;
        }
        boolean hasValidTemplate = BcSmartspaceCardLoggerUtil.containsValidTemplateType(target.getTemplateData());
        if (hasValidTemplate || target.getHeaderAction() != null) {
            BcSmartspaceCardLoggingInfo.Builder builder =
                    new BcSmartspaceCardLoggingInfo.Builder()
                            .setInstanceId(InstanceId.create(target))
                            .setFeatureType(target.getFeatureType())
                            .setDisplaySurface(
                                    BcSmartSpaceUtil.getLoggingDisplaySurface(mUiSurface, mDozeAmount))
                            .setUid(-1)
                            .setDimensionalInfo(
                                    BcSmartspaceCardLoggerUtil.createDimensionalLoggingInfo(
                                            target.getTemplateData()));
            mLoggingInfo = new BcSmartspaceCardLoggingInfo(builder);
            if (!hasValidTemplate) {
                SmartspaceAction headerAction = target.getHeaderAction();
                if (headerAction == null) {
                    Log.d("WeatherSmartspaceView", "Passed-in header action is null");
                } else {
                    mView.setText(headerAction.getTitle().toString());
                    mView.setCompoundDrawablesRelative(null, null, null, null);
                    mIconDrawable.setIcon(BcSmartSpaceUtil.getIconDrawableWithCustomSize(headerAction.getIcon(), getContext(), mIconSize));
                    mView.setCompoundDrawablesRelative(mIconDrawable, null, null, null);
                    ContentDescriptionUtil.setFormattedContentDescription("WeatherSmartspaceView", mView, headerAction.getTitle(), headerAction.getContentDescription());
                    if (!TextUtils.equals(mUiSurface, BcSmartspaceDataPlugin.UI_SURFACE_DREAM)) {
                        BcSmartSpaceUtil.setOnClickListener(mView, target, headerAction, mDataProvider != null ? mDataProvider.getEventNotifier() : null, "WeatherSmartspaceView", mLoggingInfo, 0);
                    }
                }
            } else if (target.getTemplateData() != null) {
                BaseTemplateData.SubItemInfo subItemInfo = target.getTemplateData().getSubtitleItem();
                if (subItemInfo == null) {
                    Log.d("WeatherSmartspaceView", "Passed-in item info is null");
                } else {
                    BcSmartspaceTemplateDataUtils.setText(mView, subItemInfo.getText());
                    mView.setCompoundDrawablesRelative(null, null, null, null);
                    if (subItemInfo.getIcon() != null) {
                        mIconDrawable.setIcon(BcSmartSpaceUtil.getIconDrawableWithCustomSize(subItemInfo.getIcon().getIcon(), getContext(), mIconSize));
                        mView.setCompoundDrawablesRelative(mIconDrawable, null, null, null);
                    }
                    ContentDescriptionUtil.setFormattedContentDescription("WeatherSmartspaceView", mView, SmartspaceUtils.isEmpty(subItemInfo.getText()) ? "" : subItemInfo.getText().getText(), subItemInfo.getIcon() != null ? subItemInfo.getIcon().getContentDescription() : "");
                    if (subItemInfo.getTapAction() != null && !TextUtils.equals(mUiSurface, BcSmartspaceDataPlugin.UI_SURFACE_DREAM)) {
                        BcSmartSpaceUtil.setOnClickListener(mView, target, subItemInfo.getTapAction(), mDataProvider != null ? mDataProvider.getEventNotifier() : null, "WeatherSmartspaceView", mLoggingInfo, 0);
                    }
                }
            }
            if (mRemoveTextDescent) {
                mView.setPaddingRelative(0, 0, 0, mTextDescentExtraPadding - ((int) Math.floor(mView.getPaint().getFontMetrics().descent)));
            }
        }
    }

    @Override
    public final void registerDataProvider(BcSmartspaceDataPlugin dataProvider) {
        if (mDataProvider != null) {
            mDataProvider.unregisterListener(this);
        }
        mDataProvider = dataProvider;
        if (isAttachedToWindow()) {
            mDataProvider.registerListener(this);
        }
    }

    @Override
    public final void setBgHandler(Handler handler) {
        mBgHandler = handler;
    }

    @Override
    public final void setDozeAmount(float dozeAmount) {
        mDozeAmount = dozeAmount;
        mView.setTextColor(ColorUtils.blendARGB(mPrimaryTextColor, -1, dozeAmount));
        int loggingSurface = BcSmartSpaceUtil.getLoggingDisplaySurface(mUiSurface, mDozeAmount);
        if (mLoggingInfo == null || loggingSurface == -1) {
            return;
        }
        if (loggingSurface != 3 || mIsAodEnabled) {
            if (DEBUG) {
                Log.d("WeatherSmartspaceView", "@" + Integer.toHexString(hashCode()) + ", setDozeAmount: Logging SMARTSPACE_CARD_SEEN, loggingSurface = " + loggingSurface);
            }

            BcSmartspaceCardLoggingInfo.Builder builder =
                    new BcSmartspaceCardLoggingInfo.Builder()
                            .setInstanceId(mLoggingInfo.mInstanceId)
                            .setFeatureType(mLoggingInfo.mFeatureType)
                            .setDisplaySurface(loggingSurface)
                            .setUid(mLoggingInfo.mUid);
            BcSmartspaceCardLogger.log(
                    BcSmartspaceEvent.SMARTSPACE_CARD_SEEN, new BcSmartspaceCardLoggingInfo(builder));
        }
    }

    @Override
    public final void setFalsingManager(FalsingManager falsingManager) {
        BcSmartSpaceUtil.sFalsingManager = falsingManager;
    }

    @Override
    public final void setPrimaryTextColor(int color) {
        mPrimaryTextColor = color;
        mView.setTextColor(ColorUtils.blendARGB(color, -1, mDozeAmount));
    }

    @Override
    public final void setUiSurface(String uiSurface) {
        if (isAttachedToWindow()) {
            throw new IllegalStateException("Must call before attaching view to window.");
        }
        mUiSurface = uiSurface;
    }

    public WeatherSmartspaceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WeatherSmartspaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mUiSurface = null;
        mDozeAmount = 0.0f;
        mLoggingInfo = null;
        mAodSettingsObserver = new ContentObserver(new Handler()) {
            @Override
            public final void onChange(boolean selfChange) {
                boolean isAodEnabled = Settings.Secure.getIntForUser(getContext().getContentResolver(), "doze_always_on", 0, getContext().getUserId()) == 1;
                if (mIsAodEnabled == isAodEnabled) {
                    return;
                }
                mIsAodEnabled = isAodEnabled;
            }
        };
        context.getTheme().applyStyle(R.style.Smartspace, false);
        TypedArray obtainStyledAttributes = context.getTheme().obtainStyledAttributes(attrs, R.styleable.WeatherSmartspaceView, 0, 0);
        try {
            int iconSize = obtainStyledAttributes.getDimensionPixelSize(1, context.getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_icon_size));
            int iconInset = obtainStyledAttributes.getDimensionPixelSize(0, context.getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_icon_inset));
            mRemoveTextDescent = obtainStyledAttributes.getBoolean(2, false);
            mTextDescentExtraPadding = obtainStyledAttributes.getDimensionPixelSize(3, 0);
            obtainStyledAttributes.recycle();
            mIconSize = iconSize;
            mIconDrawable = new DoubleShadowIconDrawable(iconSize, iconInset, context);
        } catch (Throwable th) {
            obtainStyledAttributes.recycle();
            throw th;
        }
    }
}
