package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.ComponentName;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.FalsingManager;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLogger;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import com.android.systemui.res.R;

import java.lang.invoke.VarHandle;

/* compiled from: go/retraceme af8e0b46c0cb0ee2c99e9b6d0c434e5c0b686fd9230eaab7fb9a40e3a9d0cf6f */
/* loaded from: classes2.dex */
public class DateSmartspaceView extends LinearLayout implements BcSmartspaceDataPlugin.SmartspaceView {
    public static final boolean DEBUG = Log.isLoggable("DateSmartspaceView", Log.DEBUG);
    public final ContentObserver mAodSettingsObserver;
    public Handler mBgHandler;
    public int mCurrentTextColor;
    public BcSmartspaceDataPlugin mDataProvider;
    public final SmartspaceAction mDateAction;
    public final SmartspaceTarget mDateTarget;
    public IcuDateTextView mDateView;
    public final DoubleShadowIconDrawable mDndIconDrawable;
    public ImageView mDndImageView;
    public float mDozeAmount;
    public boolean mIsAodEnabled;
    public BcSmartspaceCardLoggingInfo mLoggingInfo;
    public final BcNextAlarmData mNextAlarmData;
    public final DoubleShadowIconDrawable mNextAlarmIconDrawable;
    public DoubleShadowTextView mNextAlarmTextView;
    public int mPrimaryTextColor;
    public String mUiSurface;

    public DateSmartspaceView(Context context) {
        this(context, null);
    }

    @Override
    public final void onAttachedToWindow() {
        Handler handler;
        super.onAttachedToWindow();
        if (TextUtils.equals(mUiSurface, BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)) {
            try {
                handler = mBgHandler;
            } catch (Exception e) {
                Log.w("DateSmartspaceView", "Unable to register DOZE_ALWAYS_ON content observer: ", e);
            }
            if (mBgHandler == null) {
                throw new IllegalStateException("Must set background handler to avoid making binder calls on main thread");
            }
            mBgHandler.post(() -> {
                getContext().getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor("doze_always_on"), 
                        false, 
                        mAodSettingsObserver, 
                        -1);
            });
            mIsAodEnabled = Settings.Secure.getIntForUser(getContext().getContentResolver(), "doze_always_on", 0, getContext().getUserId()) == 1;
        }
        BcSmartspaceCardLoggingInfo.Builder builder =
                new BcSmartspaceCardLoggingInfo.Builder()
                        .setInstanceId(InstanceId.create(mDateTarget))
                        .setFeatureType(mDateTarget.getFeatureType())
                        .setDisplaySurface(
                                BcSmartSpaceUtil.getLoggingDisplaySurface(mUiSurface, mDozeAmount))
                        .setUid(-1);
        mLoggingInfo = new BcSmartspaceCardLoggingInfo(builder);
        BcSmartSpaceUtil.setOnClickListener(mDateView, mDateTarget, mDateAction, mDataProvider != null ? mDataProvider.getEventNotifier() : null, "DateSmartspaceView", mLoggingInfo, 0);
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
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        mDateView = findViewById(R.id.date);
        mNextAlarmTextView = findViewById(R.id.alarm_text_view);
        mDndImageView = findViewById(R.id.dnd_icon);
    }

    @Override
    public final void registerDataProvider(BcSmartspaceDataPlugin dataProvider) {
        mDataProvider = dataProvider;
    }

    @Override
    public final void setBgHandler(Handler handler) {
        mBgHandler = handler;
        mDateView.mBgHandler = handler;
    }

    @Override
    public final void setDnd(Drawable image, String description) {
        if (image == null) {
            BcSmartspaceTemplateDataUtils.updateVisibility(mDndImageView, View.GONE);
        } else {
            mDndIconDrawable.setIcon(image.mutate());
            mDndImageView.setImageDrawable(mDndIconDrawable);
            mDndImageView.setContentDescription(description);
            BcSmartspaceTemplateDataUtils.updateVisibility(mDndImageView, View.VISIBLE);
        }
        updateColorForExtras();
    }

    /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 1 */
    @Override // com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceView
    public final void setDozeAmount(float dozeAmount) {
        int loggingSurface;
        mDozeAmount = dozeAmount;
        mCurrentTextColor = ColorUtils.blendARGB(mPrimaryTextColor, -1, dozeAmount);
        mDateView.setTextColor(mCurrentTextColor);
        updateColorForExtras();
        if (mLoggingInfo == null || (loggingSurface = BcSmartSpaceUtil.getLoggingDisplaySurface(mUiSurface, mDozeAmount)) == -1) {
            return;
        }
        if (loggingSurface != 3 || mIsAodEnabled) {
            if (DEBUG) {
                Log.d("DateSmartspaceView", "@" + Integer.toHexString(hashCode()) + ", setDozeAmount: Logging SMARTSPACE_CARD_SEEN, loggingSurface = " + loggingSurface);
            }
            BcSmartspaceCardLoggingInfo.Builder builder =
                    new BcSmartspaceCardLoggingInfo.Builder()
                            .setInstanceId(mLoggingInfo.mInstanceId)
                            .setFeatureType(mLoggingInfo.mFeatureType)
                            .setDisplaySurface(loggingSurface)
                            .setUid(mLoggingInfo.mUid);
            BcSmartspaceCardLogger.log(
                    BcSmartspaceEvent.SMARTSPACE_CARD_SEEN, new BcSmartspaceCardLoggingInfo(builder));
            if (mNextAlarmData.mImage != null) {
                BcSmartspaceCardLoggingInfo.Builder alarmBuilder =
                        new BcSmartspaceCardLoggingInfo.Builder()
                                .setInstanceId(InstanceId.create("upcoming_alarm_card_94510_12684"))
                                .setFeatureType(23)
                                .setDisplaySurface(loggingSurface)
                                .setUid(mLoggingInfo.mUid);
                BcSmartspaceCardLogger.log(
                        BcSmartspaceEvent.SMARTSPACE_CARD_SEEN,
                        new BcSmartspaceCardLoggingInfo(alarmBuilder));
            }
        }
    }

    @Override
    public final void setFalsingManager(FalsingManager falsingManager) {
        BcSmartSpaceUtil.sFalsingManager = falsingManager;
    }

    @Override
    public final void setNextAlarm(Drawable image, String description) {
        mNextAlarmData.mImage = image;
        if (image != null) {
            image.mutate();
        }
        mNextAlarmData.mDescription = description;
        if (mNextAlarmData.mImage == null) {
            BcSmartspaceTemplateDataUtils.updateVisibility(mNextAlarmTextView, View.GONE);
        } else {
            mNextAlarmTextView.setContentDescription(getContext().getString(R.string.accessibility_next_alarm, description));
            String displayText = TextUtils.isEmpty(null) ? mNextAlarmData.mDescription : mNextAlarmData.mDescription + " · null";
            mNextAlarmTextView.setText(displayText);
            int iconSize = getContext().getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_icon_size);
            mNextAlarmData.mImage.setBounds(0, 0, iconSize, iconSize);
            mNextAlarmIconDrawable.setIcon(mNextAlarmData.mImage);
            mNextAlarmTextView.setCompoundDrawablesRelative(mNextAlarmIconDrawable, null, null, null);
            BcSmartspaceTemplateDataUtils.updateVisibility(mNextAlarmTextView, View.VISIBLE);
            BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier = mDataProvider == null ? null : mDataProvider.getEventNotifier();
            int loggingSurface = BcSmartSpaceUtil.getLoggingDisplaySurface(mUiSurface, mDozeAmount);
            BcSmartspaceCardLoggingInfo loggingInfo = new BcSmartspaceCardLoggingInfo.Builder()
                    .setInstanceId(InstanceId.create("upcoming_alarm_card_94510_12684"))
                    .setFeatureType(23)
                    .setDisplaySurface(loggingSurface)
                    .build();
            BcSmartSpaceUtil.setOnClickListener(mNextAlarmTextView, null, BcNextAlarmData.SHOW_ALARMS_ACTION, eventNotifier, "BcNextAlarmData", loggingInfo, 0);
        }
        updateColorForExtras();
    }

    @Override
    public final void setPrimaryTextColor(int color) {
        mPrimaryTextColor = color;
        mCurrentTextColor = ColorUtils.blendARGB(color, -1, mDozeAmount);
        mDateView.setTextColor(mCurrentTextColor);
        updateColorForExtras();
    }

    @Override
    public final void setScreenOn(boolean screenOn) {
        if (mDateView != null) {
            mDateView.mIsInteractive = screenOn;
            mDateView.rescheduleTicker();
        }
    }

    @Override
    public final void setTimeChangedDelegate(BcSmartspaceDataPlugin.TimeChangedDelegate delegate) {
        if (mDateView != null) {
            if (mDateView.isAttachedToWindow()) {
                throw new IllegalStateException("Must call before attaching view to window.");
            }
            mDateView.mTimeChangedDelegate = delegate;
        }
    }

    @Override
    public final void setUiSurface(String uiSurface) {
        if (isAttachedToWindow()) {
            throw new IllegalStateException("Must call before attaching view to window.");
        }
        mUiSurface = uiSurface;
        if (TextUtils.equals(uiSurface, BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)) {
            if (mDateView.isAttachedToWindow()) {
                throw new IllegalStateException("Must call before attaching view to window.");
            }
            mDateView.mUpdatesOnAod = true;
        }
    }

    public final void updateColorForExtras() {
        if (mNextAlarmTextView != null) {
            mNextAlarmTextView.setTextColor(mCurrentTextColor);
            mNextAlarmIconDrawable.setTint(mCurrentTextColor);
        }
        if (mDndImageView == null || mDndImageView.getDrawable() == null) {
            return;
        }
        mDndImageView.getDrawable().setTint(mCurrentTextColor);
        mDndImageView.invalidate();
    }

    public DateSmartspaceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /* JADX WARN: Type inference failed for: r4v10, types: [com.google.android.systemui.smartspace.DateSmartspaceView$1] */
    public DateSmartspaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mUiSurface = null;
        mDozeAmount = 0.0f;
        mDateTarget = new SmartspaceTarget.Builder("date_card_794317_92634", new ComponentName(getContext(), getClass()), getContext().getUser()).setFeatureType(1).build();
        mDateAction = new SmartspaceAction.Builder("dateId", "Date").setIntent(BcSmartSpaceUtil.getOpenCalendarIntent()).build();
        mNextAlarmData = new BcNextAlarmData();
        mAodSettingsObserver = new ContentObserver(new Handler()) { // from class: com.google.android.systemui.smartspace.DateSmartspaceView.1
            /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 1 */
            @Override // android.database.ContentObserver
            public final void onChange(boolean selfChange) {
                boolean isAodEnabled = Settings.Secure.getIntForUser(getContext().getContentResolver(), "doze_always_on", 0, getContext().getUserId()) == 1;
                if (mIsAodEnabled == isAodEnabled) {
                    return;
                }
                mIsAodEnabled = isAodEnabled;
            }
        };
        context.getTheme().applyStyle(R.style.Smartspace, false);
        mNextAlarmIconDrawable = new DoubleShadowIconDrawable(context);
        mDndIconDrawable = new DoubleShadowIconDrawable(context);
    }
}
