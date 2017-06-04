package com.android.systemui.qs.tiles;

import static cyanogenmod.hardware.LiveDisplayManager.FEATURE_MANAGED_OUTDOOR_MODE;
import static cyanogenmod.hardware.LiveDisplayManager.MODE_DAY;
import static cyanogenmod.hardware.LiveDisplayManager.MODE_OUTDOOR;

import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;

import com.android.internal.util.ArrayUtils;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

import com.android.internal.logging.MetricsProto.MetricsEvent;

import cyanogenmod.hardware.LiveDisplayManager;
import cyanogenmod.providers.CMSettings;
import cyanogenmod.app.CMContextConstants;

/** Quick settings tile: LiveDisplay mode switcher **/
public class LiveDisplayTile extends QSTile<LiveDisplayTile.LiveDisplayState> {

    private static final Intent LIVEDISPLAY_SETTINGS =
            new Intent(CMSettings.ACTION_LIVEDISPLAY_SETTINGS);

    private  LiveDisplayObserver mObserver;
    private String[] mEntries;
    private String[] mDescriptionEntries;
    private String[] mAnnouncementEntries;
    private String[] mValues;
    private  int[] mEntryIconRes;

    private boolean mListening;

    private int mDayTemperature;

    private  boolean mOutdoorModeAvailable;

    private  LiveDisplayManager mLiveDisplay;

    private static final int OFF_TEMPERATURE = 6500;

    public LiveDisplayTile(Host host) {
        super(host);
        populateList();
   }

   private void populateList() {
       if (!mContext.getPackageManager().hasSystemFeature(
                CMContextConstants.Features.LIVEDISPLAY)) {
                  return;
        }
        Resources res = mContext.getResources();
        TypedArray typedArray = res.obtainTypedArray(R.array.live_display_drawables);
        mEntryIconRes = new int[typedArray.length()];
        for (int i = 0; i < mEntryIconRes.length; i++) {
            mEntryIconRes[i] = typedArray.getResourceId(i, 0);
        }
        typedArray.recycle();

        updateEntries();

        mLiveDisplay = LiveDisplayManager.getInstance(mContext);
        mOutdoorModeAvailable = mLiveDisplay.getConfig().hasFeature(MODE_OUTDOOR) &&
                !mLiveDisplay.getConfig().hasFeature(FEATURE_MANAGED_OUTDOOR_MODE);

        mDayTemperature = mLiveDisplay.getDayColorTemperature();

        mObserver = new LiveDisplayObserver(mHandler);
        mObserver.startObserving();
    }

    private void updateEntries() {
        Resources res = mContext.getResources();
        mEntries = res.getStringArray(org.cyanogenmod.platform.internal.R.array.live_display_entries);
        mDescriptionEntries = res.getStringArray(R.array.live_display_description);
        mAnnouncementEntries = res.getStringArray(R.array.live_display_announcement);
        mValues = res.getStringArray(org.cyanogenmod.platform.internal.R.array.live_display_values);
    }

    @Override
    public LiveDisplayState newTileState() {
        return new LiveDisplayState();
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening)
            return;
        mListening = listening;
        if (listening) {
            mObserver.startObserving();
        } else {
            mObserver.endObserving();
        }
    }

    @Override
    protected void handleClick() {
        changeToNextMode();
    }

    @Override
    public Intent getLongClickIntent() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.live_display_title);
    }

    @Override
    protected void handleLongClick() {
        mHost.startActivityDismissingKeyguard(LIVEDISPLAY_SETTINGS);
    }

    @Override
    protected void handleUpdateState(LiveDisplayState state, Object arg) {
        updateEntries();
        state.mode = arg == null ? getCurrentModeIndex() : (Integer) arg;
        state.label = mEntries[state.mode];
        state.icon = ResourceIcon.get(mEntryIconRes[state.mode]);
        state.contentDescription = mDescriptionEntries[state.mode];
    }

    private boolean isLiveDisplaySupported(){
        boolean isSupported = false;
        isSupported = mLiveDisplay.getConfig().hasFeature(MODE_DAY);
        return isSupported;
    }

    @Override
    public boolean isAvailable(){
        return isLiveDisplaySupported();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QUICK_SETTINGS;
    }

    @Override
    protected String composeChangeAnnouncement() {
        return mAnnouncementEntries[getCurrentModeIndex()];
    }

    private int getCurrentModeIndex() {
        return ArrayUtils.indexOf(mValues, String.valueOf(mLiveDisplay.getMode()));
    }

    private void changeToNextMode() {
        int next = getCurrentModeIndex() + 1;

        if (next >= mValues.length) {
            next = 0;
        }

        int nextMode;

        while (true) {
            nextMode = Integer.valueOf(mValues[next]);
            if (nextMode == MODE_OUTDOOR) {
                // Only accept outdoor mode if it's supported by the hardware
                if (mOutdoorModeAvailable) {
                    break;
                }
            } else if (nextMode == MODE_DAY) {
                // Skip the day setting if it's the same as the off setting
                if (mDayTemperature != OFF_TEMPERATURE) {
                    break;
                }
            } else {
                // every other mode doesn't have any preconstraints
                break;
            }

            // If we come here, we decided to skip the mode
            next++;
            if (next >= mValues.length) {
                next = 0;
            }
        }

        mLiveDisplay.setMode(nextMode);
    }

    private class LiveDisplayObserver extends ContentObserver {
        public LiveDisplayObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            mDayTemperature = mLiveDisplay.getDayColorTemperature();
            refreshState(getCurrentModeIndex());
        }

        public void startObserving() {
            mContext.getContentResolver().registerContentObserver(
                    CMSettings.System.getUriFor(CMSettings.System.DISPLAY_TEMPERATURE_MODE),
                    false, this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    CMSettings.System.getUriFor(CMSettings.System.DISPLAY_TEMPERATURE_DAY),
                    false, this, UserHandle.USER_ALL);
        }

        public void endObserving() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }
    }

    public static class LiveDisplayState extends QSTile.State {
        public int mode;

        @Override
        public boolean copyTo(State other) {
            final LiveDisplayState o = (LiveDisplayState) other;
            final boolean changed = mode != o.mode;
            return super.copyTo(other) || changed;
        }

        @Override
        protected StringBuilder toStringBuilder() {
            final StringBuilder rt = super.toStringBuilder();
            rt.insert(rt.length() - 1, ",mode=" + mode);
            return rt;
        }
    }
}
