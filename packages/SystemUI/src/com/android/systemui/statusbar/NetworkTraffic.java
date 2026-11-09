/**
 * Copyright (C) 2019-2026 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar;

import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.res.R;
import com.android.systemui.tuner.TunerService;

import java.text.DecimalFormat;
import java.util.ArrayList;

/**
 * Status bar network traffic indicator.
 *
 * @hide
 */
public class NetworkTraffic extends TextView implements DarkReceiver, StatusIconDisplayable {

    private static final int MODE_UPSTREAM_AND_DOWNSTREAM = 0;
    private static final int MODE_UPSTREAM_ONLY = 1;
    private static final int MODE_DOWNSTREAM_ONLY = 2;

    private final Monitor mMonitor;

    private String mSlot;
    private boolean mBlocked;
    private boolean mTintManagedExternally;
    private boolean mColorIsStatic;
    private int mTint = Color.WHITE;

    private boolean mAttached;
    private boolean mListening;
    private int mVisibleState = STATE_ICON;
    private boolean mWantVisible;
    private boolean mShown;
    private boolean mHideArrows;
    private int mCurrentWidth = -1;

    private CharSequence mAppliedText;
    private int mDrawableResId;
    private Drawable mDrawable;

    public NetworkTraffic(Context context) {
        this(context, null);
    }

    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mMonitor = Monitor.get(context);

        // Static styling, applied once instead of on every "enabled" tuning callback.
        setLines(2);
        setMaxLines(2);
        final String txtFont = getResources().getString(
                com.android.internal.R.string.config_bodyFontFamily);
        setTypeface(Typeface.create(txtFont, Typeface.BOLD));
        setLineSpacing(0f, 0.95f);
        setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        setTextDirection(View.TEXT_DIRECTION_LOCALE);
        setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        setElegantTextHeight(false);
        setIncludeFontPadding(false);
        super.setTextColor(mTint);

        // Nothing to show until the monitor says otherwise.
        setVisibility(View.GONE);
    }

    public static NetworkTraffic fromContext(Context context, String slot) {
        return fromContext(context, slot, false);
    }

    public static NetworkTraffic fromContext(Context context, String slot, boolean blocked) {
        NetworkTraffic v = new NetworkTraffic(context);
        v.mSlot = slot;
        v.mBlocked = blocked;
        v.mTintManagedExternally = true;
        return v;
    }

    public void setSlot(String slot) {
        mSlot = slot;
    }


    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAttached) return;
        mAttached = true;
        if (!mTintManagedExternally) {
            Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        }
        if (!mBlocked) {
            mListening = true;
            mMonitor.addListener(this);
        }
    }
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!mAttached) return;
        mAttached = false;
        if (mListening) {
            mListening = false;
            mMonitor.removeListener(this);
        }
        if (!mTintManagedExternally) {
            Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mCurrentWidth = -1;
        mDrawableResId = -1;
        mMonitor.onConfigurationChanged();
        if (mListening) {
            onTrafficStateChanged();
        } else {
            updateWidth();
        }
    }

    private void onTrafficStateChanged() {
        final Monitor m = mMonitor;
        final boolean want = m.mShow;
        if (want) {
            if (m.mText != mAppliedText) {
                mAppliedText = m.mText;
                setText(m.mText);
            }
            updateDrawable(m.mDrawableRes);
        }
        if (want != mWantVisible || m.mArrowsHidden != mHideArrows || mCurrentWidth < 0) {
            final boolean wantChanged = want != mWantVisible;
            mWantVisible = want;
            mHideArrows = m.mArrowsHidden;
            updateWidth();
            if (wantChanged) {
                requestLayout();
            }
        }
        updateVisibility();
    }

    private boolean canShowTraffic() {
        if (!mAttached || mBlocked || getWindowVisibility() != View.VISIBLE) return false;
        final ViewParent parent = getParent();
        return parent instanceof View && ((View) parent).isShown();
    }

    private void updateVisibility() {
        final boolean shown = mWantVisible && mVisibleState == STATE_ICON;
        if (shown != mShown) {
            mShown = shown;
            setVisibility(shown ? View.VISIBLE : View.GONE);
        }
    }

    private void updateWidth() {
        final ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) return;
        int width = 0;
        if (mWantVisible) {
            final float density = getResources().getDisplayMetrics().density;
            width = (int) ((mHideArrows ? 18 : 30) * density);
        }
        if (width == mCurrentWidth && lp.width == width) return;
        mCurrentWidth = width;
        lp.width = width;
        setLayoutParams(lp);
    }

    private void updateDrawable(int resId) {
        if (resId == mDrawableResId) return;
        mDrawableResId = resId;
        Drawable drawable = null;
        if (resId != 0) {
            drawable = getContext().getDrawable(resId);
            if (drawable != null) {
                // mutate(): vector constant state (incl. tint) is shared between instances,
                // e.g. dark status bar vs. white shade header.
                drawable = drawable.mutate();
                drawable.setTint(mTint);
            }
        }
        mDrawable = drawable;
        setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, drawable, null);
    }

    @Override
    public void setTextColor(int color) {
        mTint = color;
        super.setTextColor(color);
        if (mDrawable != null) {
            mDrawable.setTint(color);
        }
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        if (mColorIsStatic) return;
        final int newTint = DarkIconDispatcher.getTint(areas, this, tint);
        if (newTint != mTint) {
            setTextColor(newTint);
        }
    }

    @Override
    public void setStaticDrawableColor(int color) {
        mColorIsStatic = true;
        if (color != mTint) {
            setTextColor(color);
        }
    }

    @Override
    public void setDecorColor(int color) {
    }

    @Override
    public String getSlot() {
        return mSlot;
    }

    @Override
    public boolean isIconVisible() {
        return mWantVisible;
    }

    public boolean isIconBlocked() {
        return mBlocked;
    }

    @Override
    public int getVisibleState() {
        return mVisibleState;
    }

    @Override
    public void setVisibleState(int state, boolean animate) {
        if (state == mVisibleState) return;
        mVisibleState = state;
        updateVisibility();
    }

    @Override
    public String toString() {
        return "NetworkTraffic(slot=" + mSlot + " blocked=" + mBlocked + " want=" + mWantVisible
                + " visibleState=" + mVisibleState + " shown=" + mShown + ")";
    }

    /**
     * Process-wide traffic sampler shared by all {@link NetworkTraffic} views.
     * Everything is main-thread only unless marked "bg".
     */
    private static final class Monitor implements TunerService.Tunable {

        private static final String TAG = "NetworkTraffic";

        // This must match the interface prefix in Connectivity's clatd.c.
        private static final String CLAT_PREFIX = "v4-";

        private static final long KILO = 1000L;
        private static final long MEGA = KILO * KILO;
        private static final long GIGA = MEGA * KILO;

        private static final long MIN_SAMPLE_INTERVAL_MS = 500L;

        private static final String NETWORK_TRAFFIC_ENABLED =
                "system:" + Settings.System.NETWORK_TRAFFIC_ENABLED;
        private static final String NETWORK_TRAFFIC_MODE =
                "system:" + Settings.System.NETWORK_TRAFFIC_MODE;
        private static final String NETWORK_TRAFFIC_AUTOHIDE =
                "system:" + Settings.System.NETWORK_TRAFFIC_AUTOHIDE;
        private static final String NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD =
                "system:" + Settings.System.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD;
        private static final String NETWORK_TRAFFIC_UNITS =
                "system:" + Settings.System.NETWORK_TRAFFIC_UNITS;
        private static final String NETWORK_TRAFFIC_REFRESH_INTERVAL =
                "system:" + Settings.System.NETWORK_TRAFFIC_REFRESH_INTERVAL;
        private static final String NETWORK_TRAFFIC_HIDEARROW =
                "system:" + Settings.System.NETWORK_TRAFFIC_HIDEARROW;

        private static Monitor sInstance;

        static Monitor get(Context context) {
            if (sInstance == null) {
                sInstance = new Monitor(context.getApplicationContext());
            }
            return sInstance;
        }

        private final Context mContext;
        private final ConnectivityManager mConnectivityManager;
        private final PowerManager mPowerManager;
        private final TunerService mTunerService;
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private final Handler mBgHandler;
        private final ArraySet<NetworkTraffic> mListeners = new ArraySet<>();
        private final Runnable mTick = this::onTick;

        private final NetworkRequest mRequest = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();

        private final AbsoluteSizeSpan mSpeedSpan = new AbsoluteSizeSpan(7, true);
        private final AbsoluteSizeSpan mUnitSpan = new AbsoluteSizeSpan(6, true);
        private DecimalFormat mFormat02;
        private DecimalFormat mFormat20;
        private DecimalFormat mFormat11;

        private boolean mEnabled;
        private int mMode = MODE_UPSTREAM_AND_DOWNSTREAM;
        private boolean mAutoHide;
        private long mAutoHideThreshold;
        private int mUnits = 1;
        private long mRefreshIntervalMs = 2000L;
        private boolean mHideArrows;

        private boolean mTunablesRegistered;
        private boolean mRunning;
        private boolean mReceiverRegistered;
        private boolean mScreenOn = true;
        private boolean mHasInternet;
        private boolean mResetBaseline = true;
        private long mTxRate;
        private long mRxRate;
        private volatile TrafficCallback mTrafficCallback;
        private volatile DefaultCallback mDefaultCallback;

        private boolean mShow;
        private CharSequence mText = "";
        private int mDrawableRes;
        private boolean mArrowsHidden;
        private String mLastNumber;
        private int mLastUnitRes;

        private final ArrayMap<Network, String> mBgIfaces = new ArrayMap<>();
        private final ArraySet<String> mBgIfaceNames = new ArraySet<>();
        private boolean mBgNetworksChanged = true;
        private long mBgLastTx;
        private long mBgLastRx;
        private long mBgLastTime;

        private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final boolean on = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                if (on == mScreenOn) return;
                mScreenOn = on;
                if (on) {
                    kickTick();
                } else {
                    mMainHandler.removeCallbacks(mTick);
                    mResetBaseline = true;
                }
            }
        };

        private Monitor(Context context) {
            mContext = context;
            mConnectivityManager = context.getSystemService(ConnectivityManager.class);
            mPowerManager = context.getSystemService(PowerManager.class);
            mTunerService = Dependency.get(TunerService.class);
            final HandlerThread thread =
                    new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            mBgHandler = new Handler(thread.getLooper());
            createFormatters();
        }

        private void createFormatters() {
            mFormat02 = new DecimalFormat("0.##");
            mFormat20 = new DecimalFormat("##0");
            mFormat11 = new DecimalFormat("#0.#");
        }

        void addListener(NetworkTraffic view) {
            if (!mListeners.add(view)) return;
            if (!mTunablesRegistered) {
                mTunablesRegistered = true;
                // Fires onTuningChanged() synchronously for every key.
                mTunerService.addTunable(this,
                        NETWORK_TRAFFIC_ENABLED,
                        NETWORK_TRAFFIC_MODE,
                        NETWORK_TRAFFIC_AUTOHIDE,
                        NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD,
                        NETWORK_TRAFFIC_UNITS,
                        NETWORK_TRAFFIC_REFRESH_INTERVAL,
                        NETWORK_TRAFFIC_HIDEARROW);
            }
            updateRunning();
            view.onTrafficStateChanged();
        }

        void removeListener(NetworkTraffic view) {
            if (!mListeners.remove(view)) return;
            if (mListeners.isEmpty() && mTunablesRegistered) {
                mTunablesRegistered = false;
                mTunerService.removeTunable(this);
            }
            updateRunning();
        }

        void onConfigurationChanged() {
            createFormatters();
            mLastNumber = null;
            mLastUnitRes = 0;
            updateDisplay();
        }

        @Override
        public void onTuningChanged(String key, String newValue) {
            if (key == null) return;
            switch (key) {
                case NETWORK_TRAFFIC_ENABLED:
                    mEnabled = TunerService.parseIntegerSwitch(newValue, false);
                    updateRunning();
                    break;
                case NETWORK_TRAFFIC_MODE:
                    mMode = TunerService.parseInteger(newValue, MODE_UPSTREAM_AND_DOWNSTREAM);
                    break;
                case NETWORK_TRAFFIC_AUTOHIDE:
                    mAutoHide = TunerService.parseIntegerSwitch(newValue, false);
                    break;
                case NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD:
                    // kB -> bytes
                    mAutoHideThreshold =
                            Math.max(0, TunerService.parseInteger(newValue, 0)) * KILO;
                    break;
                case NETWORK_TRAFFIC_UNITS:
                    mUnits = TunerService.parseInteger(newValue, 1);
                    mLastNumber = null;
                    break;
                case NETWORK_TRAFFIC_REFRESH_INTERVAL:
                    mRefreshIntervalMs =
                            Math.max(1, TunerService.parseInteger(newValue, 2)) * 1000L;
                    break;
                case NETWORK_TRAFFIC_HIDEARROW:
                    mHideArrows = TunerService.parseIntegerSwitch(newValue, false);
                    break;
                default:
                    return;
            }
            updateDisplay();
        }

        private void updateRunning() {
            final boolean run = mEnabled && !mListeners.isEmpty();
            if (run == mRunning) return;
            if (run) {
                start();
            } else {
                stop();
            }
            updateDisplay();
        }

        private void start() {
            mRunning = true;
            mScreenOn = mPowerManager == null || mPowerManager.isInteractive();
            mHasInternet = false;
            mResetBaseline = true;

            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            mContext.registerReceiver(mScreenReceiver, filter, null, mMainHandler,
                    Context.RECEIVER_NOT_EXPORTED);
            mReceiverRegistered = true;

            mBgHandler.post(() -> {
                mBgIfaces.clear();
                mBgNetworksChanged = true;
            });
            final TrafficCallback trafficCallback = new TrafficCallback();
            final DefaultCallback defaultCallback = new DefaultCallback();
            mTrafficCallback = trafficCallback;
            mDefaultCallback = defaultCallback;
            mConnectivityManager.registerNetworkCallback(mRequest, trafficCallback, mBgHandler);
            mConnectivityManager.registerDefaultNetworkCallback(defaultCallback, mBgHandler);
        }

        private void stop() {
            mRunning = false;
            mMainHandler.removeCallbacks(mTick);
            if (mReceiverRegistered) {
                mReceiverRegistered = false;
                mContext.unregisterReceiver(mScreenReceiver);
            }
            final TrafficCallback trafficCallback = mTrafficCallback;
            final DefaultCallback defaultCallback = mDefaultCallback;
            // Clearing these first makes any already-queued bg callbacks no-ops.
            mTrafficCallback = null;
            mDefaultCallback = null;
            unregister(trafficCallback);
            unregister(defaultCallback);
            mHasInternet = false;
            mTxRate = 0;
            mRxRate = 0;
            mResetBaseline = true;
        }

        private void unregister(ConnectivityManager.NetworkCallback callback) {
            if (callback == null) return;
            try {
                mConnectivityManager.unregisterNetworkCallback(callback);
            } catch (IllegalArgumentException e) {
                // Not registered
            }
        }

        private void kickTick() {
            mResetBaseline = true;
            mMainHandler.removeCallbacks(mTick);
            mMainHandler.post(mTick);
        }

        private void onTick() {
            mMainHandler.removeCallbacks(mTick);
            if (!mRunning || !mScreenOn || !mHasInternet) {
                // Restarted by screen-on / internet-regained, which also reset the baseline.
                mResetBaseline = true;
                return;
            }
            if (anyListenerCanShow()) {
                final boolean reset = mResetBaseline;
                mResetBaseline = false;
                mBgHandler.post(() -> sample(reset));
            } else {
                // Nothing on screen (immersive app, collapsed shade, ...): skip the binder
                // calls, keep a cheap tick to notice when a view becomes visible again.
                mResetBaseline = true;
            }
            mMainHandler.postDelayed(mTick, mRefreshIntervalMs);
        }

        private boolean anyListenerCanShow() {
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                if (mListeners.valueAt(i).canShowTraffic()) return true;
            }
            return false;
        }

        private void sample(boolean resetBaseline) {
            if (mTrafficCallback == null) return; // stopped meanwhile

            final long now = SystemClock.elapsedRealtime();
            final boolean baselineValid =
                    !resetBaseline && !mBgNetworksChanged && mBgLastTime > 0;
            final long timeDelta = now - mBgLastTime;
            if (baselineValid && timeDelta < MIN_SAMPLE_INTERVAL_MS) {
                // Too close to the previous sample: keep the baseline, skip the IPC.
                return;
            }

            final ArraySet<String> names = mBgIfaceNames;
            names.clear();
            for (int i = 0, n = mBgIfaces.size(); i < n; i++) {
                final String iface = mBgIfaces.valueAt(i);
                if (TextUtils.isEmpty(iface)) continue;
                names.add(iface);
                // Clat's IPv4 interface for IPv6-only networks.
                names.add(CLAT_PREFIX + iface);
            }

            long txBytes = 0;
            long rxBytes = 0;
            for (int i = 0, n = names.size(); i < n; i++) {
                final String iface = names.valueAt(i);
                // Missing interfaces report UNSUPPORTED (-1), not 0.
                final long tx = TrafficStats.getTxBytes(iface);
                if (tx > 0) txBytes += tx;
                final long rx = TrafficStats.getRxBytes(iface);
                if (rx > 0) rxBytes += rx;
            }

            long txRate = 0;
            long rxRate = 0;
            if (baselineValid && timeDelta > 0) {
                final long txDelta = txBytes - mBgLastTx;
                final long rxDelta = rxBytes - mBgLastRx;
                if (txDelta >= 0 && rxDelta >= 0) {
                    txRate = txDelta * 1000L / timeDelta;
                    rxRate = rxDelta * 1000L / timeDelta;
                }
            }
            mBgNetworksChanged = false;
            mBgLastTx = txBytes;
            mBgLastRx = rxBytes;
            mBgLastTime = now;

            final long finalTx = txRate;
            final long finalRx = rxRate;
            mMainHandler.post(() -> onSample(finalTx, finalRx));
        }

        private void onSample(long txRate, long rxRate) {
            if (!mRunning) return;
            mTxRate = txRate;
            mRxRate = rxRate;
            updateDisplay();
        }

        private void onInternetChanged(boolean hasInternet) {
            if (!mRunning || hasInternet == mHasInternet) return;
            mHasInternet = hasInternet;
            if (hasInternet) {
                kickTick();
            } else {
                mMainHandler.removeCallbacks(mTick);
                mTxRate = 0;
                mRxRate = 0;
            }
            updateDisplay();
        }

        private void updateDisplay() {
            final boolean showUpstream =
                    mMode == MODE_UPSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
            final boolean showDownstream =
                    mMode == MODE_DOWNSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
            final long tx = mTxRate;
            final long rx = mRxRate;
            final boolean aboveThreshold = (showUpstream && tx > mAutoHideThreshold)
                    || (showDownstream && rx > mAutoHideThreshold);
            final boolean active = mRunning && mHasInternet && (!mAutoHide || aboveThreshold);

            int subMode = MODE_UPSTREAM_AND_DOWNSTREAM;
            CharSequence text = mText;
            if (active) {
                if (showUpstream && showDownstream) {
                    if (tx > rx) {
                        text = buildText(tx);
                        subMode = MODE_UPSTREAM_ONLY;
                    } else if (tx < rx) {
                        text = buildText(rx);
                        subMode = MODE_DOWNSTREAM_ONLY;
                    } else {
                        text = buildText(rx);
                    }
                } else if (showDownstream) {
                    text = buildText(rx);
                } else if (showUpstream) {
                    text = buildText(tx);
                }
            }
            final boolean show = active && !TextUtils.isEmpty(text);

            final int drawableRes;
            if (mHideArrows) {
                drawableRes = 0;
            } else if (tx <= 0 && rx <= 0) {
                drawableRes = R.drawable.stat_sys_network_traffic;
            } else if (mMode == MODE_UPSTREAM_ONLY || subMode == MODE_UPSTREAM_ONLY) {
                drawableRes = R.drawable.stat_sys_network_traffic_up;
            } else if (mMode == MODE_DOWNSTREAM_ONLY || subMode == MODE_DOWNSTREAM_ONLY) {
                drawableRes = R.drawable.stat_sys_network_traffic_down;
            } else {
                drawableRes = R.drawable.stat_sys_network_traffic_updown;
            }

            if (show == mShow && text == mText && drawableRes == mDrawableRes
                    && mHideArrows == mArrowsHidden) {
                return;
            }
            mShow = show;
            mText = text;
            mDrawableRes = drawableRes;
            mArrowsHidden = mHideArrows;
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                mListeners.valueAt(i).onTrafficStateChanged();
            }
        }

        private CharSequence buildText(long speed) {
            final boolean bits = mUnits == 0;
            if (bits) speed *= 8;

            final String number;
            final int unitRes;
            if (speed >= GIGA) {
                number = mFormat02.format(speed / (double) GIGA);
                unitRes = bits ? R.string.gigabitspersecond_short
                        : R.string.gigabytespersecond_short;
            } else if (speed >= 100 * MEGA) {
                number = mFormat20.format(speed / (double) MEGA);
                unitRes = bits ? R.string.megabitspersecond_short
                        : R.string.megabytespersecond_short;
            } else if (speed >= 10 * MEGA) {
                number = mFormat11.format(speed / (double) MEGA);
                unitRes = bits ? R.string.megabitspersecond_short
                        : R.string.megabytespersecond_short;
            } else if (speed >= MEGA) {
                number = mFormat02.format(speed / (double) MEGA);
                unitRes = bits ? R.string.megabitspersecond_short
                        : R.string.megabytespersecond_short;
            } else if (speed >= 100 * KILO) {
                number = mFormat20.format(speed / (double) KILO);
                unitRes = bits ? R.string.kilobitspersecond_short
                        : R.string.kilobytespersecond_short;
            } else if (speed >= 10 * KILO) {
                number = mFormat11.format(speed / (double) KILO);
                unitRes = bits ? R.string.kilobitspersecond_short
                        : R.string.kilobytespersecond_short;
            } else {
                number = mFormat02.format(speed / (double) KILO);
                unitRes = bits ? R.string.kilobitspersecond_short
                        : R.string.kilobytespersecond_short;
            }

            if (unitRes == mLastUnitRes && number.equals(mLastNumber)) {
                return mText;
            }
            mLastUnitRes = unitRes;
            mLastNumber = number;

            final String unit = mContext.getString(unitRes);
            final SpannableString speedString = new SpannableString(number);
            speedString.setSpan(mSpeedSpan, 0, number.length(),
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            final SpannableString unitString = new SpannableString(unit);
            unitString.setSpan(mUnitSpan, 0, unit.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            return TextUtils.concat(speedString, "\n", unitString);
        }

        private final class TrafficCallback extends ConnectivityManager.NetworkCallback {
            // onAvailable() is always followed by onLinkPropertiesChanged(), no IPC needed.
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                if (mTrafficCallback != this) return;
                final String iface = lp.getInterfaceName();
                final int index = mBgIfaces.indexOfKey(network);
                if (index >= 0 && TextUtils.equals(iface, mBgIfaces.valueAt(index))) return;
                mBgIfaces.put(network, iface);
                mBgNetworksChanged = true;
            }

            @Override
            public void onLost(Network network) {
                if (mTrafficCallback != this) return;
                final int index = mBgIfaces.indexOfKey(network);
                if (index < 0) return;
                mBgIfaces.removeAt(index);
                mBgNetworksChanged = true;
            }
        }

        private final class DefaultCallback extends ConnectivityManager.NetworkCallback {
            private int mLastReported = -1; // bg

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                // Fires on every signal strength change on cellular: dedupe before posting.
                report(caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
            }

            @Override
            public void onLost(Network network) {
                report(false);
            }

            private void report(boolean hasInternet) {
                if (mDefaultCallback != this) return;
                final int value = hasInternet ? 1 : 0;
                if (value == mLastReported) return;
                mLastReported = value;
                mMainHandler.post(() -> {
                    if (mDefaultCallback == this) onInternetChanged(hasInternet);
                });
            }
        }
    }
}
