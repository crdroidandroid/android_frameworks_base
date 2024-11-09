/**
 * Copyright (C) 2019-2024 crDroid Android Project
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

import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Spanned;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.Dependency;
import com.android.systemui.res.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.phone.PhoneStatusBarPolicy.NetworkTrafficState;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;

import com.android.systemui.tuner.TunerService;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Stream;

/** @hide */
public class NetworkTraffic extends TextView implements TunerService.Tunable,
        DarkReceiver, StatusIconDisplayable {

    private static final String TAG = "NetworkTraffic";

    // This must match the interface prefix in Connectivity's clatd.c.
    private static final String CLAT_PREFIX = "v4-";

    private static final int MODE_UPSTREAM_AND_DOWNSTREAM = 0;
    private static final int MODE_UPSTREAM_ONLY = 1;
    private static final int MODE_DOWNSTREAM_ONLY = 2;

    private static final int MESSAGE_TYPE_PERIODIC_REFRESH = 0;
    private static final int MESSAGE_TYPE_UPDATE_VIEW = 1;
    private static final int MESSAGE_TYPE_ADD_NETWORK = 2;
    private static final int MESSAGE_TYPE_REMOVE_NETWORK = 3;

    private static final int Kilo = 1000;
    private static final int Mega = Kilo * Kilo;
    private static final int Giga = Mega * Kilo;

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

    private int mMode = MODE_UPSTREAM_AND_DOWNSTREAM;
    private int mSubMode = MODE_UPSTREAM_AND_DOWNSTREAM;
    private boolean mIsActive;
    private boolean mTrafficActive;
    private long mTxBytes;
    private long mRxBytes;
    private long mLastTxBytes;
    private long mLastRxBytes;
    private long mLastUpdateTime;
    private boolean mAutoHide;
    private long mAutoHideThreshold;
    private int mUnits;
    private int mIconTint = 0;
    private int newTint = Color.WHITE;

    private Drawable mDrawable;

    private int mRefreshInterval = 2;

    private boolean mAttached;
    private boolean mHideArrows;

    private boolean mVisible = true;

    private ConnectivityManager mConnectivityManager;
    private final Handler mTrafficHandler;

    private RelativeSizeSpan mSpeedRelativeSizeSpan = new RelativeSizeSpan(0.70f);
    private RelativeSizeSpan mUnitRelativeSizeSpan = new RelativeSizeSpan(0.65f);

    private boolean mEnabled = false;
    private boolean mConnectionAvailable = true;

    private final HashMap<Network, LinkProperties> mLinkPropertiesMap = new HashMap<>();
    // Used to indicate that the set of sources contributing
    // to current stats have changed.
    private boolean mNetworksChanged = true;

    private int mVisibleState = -1;
    private boolean mColorIsStatic;

    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private boolean mKeyguardShowing;

    private String mSlot;

    public NetworkTraffic(Context context) {
        this(context, null);
    }

    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mConnectivityManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mTrafficHandler = new Handler(mContext.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MESSAGE_TYPE_PERIODIC_REFRESH:
                        recalculateStats();
                        displayStatsAndReschedule();
                        break;

                    case MESSAGE_TYPE_UPDATE_VIEW:
                        displayStatsAndReschedule();
                        break;

                    case MESSAGE_TYPE_ADD_NETWORK:
                        final LinkPropertiesHolder lph = (LinkPropertiesHolder) msg.obj;
                        mLinkPropertiesMap.put(lph.getNetwork(), lph.getLinkProperties());
                        mNetworksChanged = true;
                        break;

                    case MESSAGE_TYPE_REMOVE_NETWORK:
                        mLinkPropertiesMap.remove((Network) msg.obj);
                        mNetworksChanged = true;
                        break;
                }
            }

            private void recalculateStats() {
                final long now = SystemClock.elapsedRealtime();
                long timeDelta = now - mLastUpdateTime; /* ms */

                if (timeDelta < mRefreshInterval * 1000 * 0.95f) {
                    return;
                }
                // Sum tx and rx bytes from all sources of interest
                long txBytes = 0;
                long rxBytes = 0;
                // Add interface stats, including stats from Clat's IPv4 interface
                // (for applicable IPv6 networks). Stats are 0 if it doesn't exist.
                final String[] ifaces = mLinkPropertiesMap.values().stream()
                        .map(link -> link.getInterfaceName()).filter(iface -> iface != null)
                        .flatMap(iface -> Stream.of(iface, CLAT_PREFIX + iface))
                        .toArray(String[]::new);
                for (String iface : ifaces) {
                    final long ifaceTxBytes = TrafficStats.getTxBytes(iface);
                    final long ifaceRxBytes = TrafficStats.getRxBytes(iface);
                    txBytes += ifaceTxBytes;
                    rxBytes += ifaceRxBytes;
                }

                final long txBytesDelta = txBytes - mLastTxBytes;
                final long rxBytesDelta = rxBytes - mLastRxBytes;

                if (!mNetworksChanged && timeDelta > 0 && txBytesDelta >= 0 && rxBytesDelta >= 0) {
                    mTxBytes = (long) (txBytesDelta / (timeDelta / 1000f));
                    mRxBytes = (long) (rxBytesDelta / (timeDelta / 1000f));
                } else if (mNetworksChanged) {
                    mTxBytes = 0;
                    mRxBytes = 0;
                    mNetworksChanged = false;
                }
                mLastTxBytes = txBytes;
                mLastRxBytes = rxBytes;
                mLastUpdateTime = now;
            }

            private void displayStatsAndReschedule() {
                final boolean showUpstream =
                        mMode == MODE_UPSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
                final boolean showDownstream =
                        mMode == MODE_DOWNSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
                final boolean aboveThreshold = (showUpstream && mTxBytes > mAutoHideThreshold)
                        || (showDownstream && mRxBytes > mAutoHideThreshold);
                mIsActive = mAttached && mConnectionAvailable && (!mAutoHide || aboveThreshold);
                int submode = MODE_UPSTREAM_AND_DOWNSTREAM;
                final boolean trafficactive = (mTxBytes > 0 || mRxBytes > 0);

                clearHandlerCallbacks();

                if (mEnabled && mIsActive) {
                    CharSequence output = "";
                    if (showUpstream && showDownstream) {
                        if (mTxBytes > mRxBytes) {
                            output = formatOutput(mTxBytes);
                            submode = MODE_UPSTREAM_ONLY;
                        } else if (mTxBytes < mRxBytes) {
                            output = formatOutput(mRxBytes);
                            submode = MODE_DOWNSTREAM_ONLY;
                        } else {
                            output = formatOutput(mRxBytes);
                            submode = MODE_UPSTREAM_AND_DOWNSTREAM;
                        }
                    } else if (showDownstream) {
                        output = formatOutput(mRxBytes);
                    } else if (showUpstream) {
                        output = formatOutput(mTxBytes);
                    }

                    // Update view if there's anything new to show
                    if (output != getText()) {
                        setText(output);
                    }
                }

                updateVisibility();

                if (mVisible && (mSubMode != submode ||
                        mTrafficActive != trafficactive)) {
                    mSubMode = submode;
                    mTrafficActive = trafficactive;
                    setTrafficDrawable();
                }

                // Schedule periodic refresh
                if (mEnabled && mAttached) {
                    mTrafficHandler.sendEmptyMessageDelayed(MESSAGE_TYPE_PERIODIC_REFRESH,
                            mRefreshInterval * 1000);
                }
            }

            private CharSequence formatOutput(long speed) {
                DecimalFormat decimalFormat;
                String unit;
                String formatSpeed;
                SpannableString spanUnitString;
                SpannableString spanSpeedString;
                String gunit, munit, kunit;

                if (mUnits == 0) {
                    // speed is in bytes, convert to bits
                    speed = speed * 8;
                    gunit = mContext.getString(R.string.gigabitspersecond_short);
                    munit = mContext.getString(R.string.megabitspersecond_short);
                    kunit = mContext.getString(R.string.kilobitspersecond_short);
                } else {
                    gunit = mContext.getString(R.string.gigabytespersecond_short);
                    munit = mContext.getString(R.string.megabytespersecond_short);
                    kunit = mContext.getString(R.string.kilobytespersecond_short);
                }

                if (speed >= Giga) {
                    unit = gunit;
                    decimalFormat = new DecimalFormat("0.##");
                    formatSpeed = decimalFormat.format(speed / (float)Giga);
                } else if (speed >= 100 * Mega) {
                    decimalFormat = new DecimalFormat("##0");
                    unit = munit;
                    formatSpeed = decimalFormat.format(speed / (float)Mega);
                } else if (speed >= 10 * Mega) {
                    decimalFormat = new DecimalFormat("#0.#");
                    unit = munit;
                    formatSpeed = decimalFormat.format(speed / (float)Mega);
                } else if (speed >= Mega) {
                    decimalFormat = new DecimalFormat("0.##");
                    unit = munit;
                    formatSpeed = decimalFormat.format(speed / (float)Mega);
                } else if (speed >= 100 * Kilo) {
                    decimalFormat = new DecimalFormat("##0");
                    unit = kunit;
                    formatSpeed = decimalFormat.format(speed / (float)Kilo);
                } else if (speed >= 10 * Kilo) {
                    decimalFormat = new DecimalFormat("#0.#");
                    unit = kunit;
                    formatSpeed = decimalFormat.format(speed / (float)Kilo);
                } else {
                    decimalFormat = new DecimalFormat("0.##");
                    unit = kunit;
                    formatSpeed = decimalFormat.format(speed / (float)Kilo);
                }
                spanSpeedString = new SpannableString(formatSpeed);
                spanSpeedString.setSpan(mSpeedRelativeSizeSpan, 0, (formatSpeed).length(),
                        Spanned.SPAN_INCLUSIVE_INCLUSIVE);

                spanUnitString = new SpannableString(unit);
                spanUnitString.setSpan(mUnitRelativeSizeSpan, 0, (unit).length(),
                        Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                return TextUtils.concat(spanSpeedString, "\n", spanUnitString);
            }
        };
    }

    public static NetworkTraffic fromContext(Context context, String slot) {
        NetworkTraffic v = new NetworkTraffic(context);
        v.setSlot(slot);
        v.setVisibleState(STATE_ICON);
        return v;
    }

    public void setSlot(String slot) {
        mSlot = slot;
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        if (mColorIsStatic) {
            return;
        }
        newTint = DarkIconDispatcher.getTint(areas, this, tint);
        checkUpdateTrafficDrawable();
    }

    @Override
    public void setStaticDrawableColor(int color) {
        mColorIsStatic = true;
        newTint = color;
        checkUpdateTrafficDrawable();
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
        return mEnabled;
    }

    @Override
    public int getVisibleState() {
        return mVisibleState;
    }

    @Override
    public void setVisibleState(int state, boolean animate) {
        mVisibleState = state;
        updateVisibility();
    }

    // Network tracking related variables
    private NetworkRequest mRequest = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build();

    private ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onLinkPropertiesChanged(Network network,
                        LinkProperties linkProperties) {
                    if (mTrafficHandler != null) {
                        Message msg = new Message();
                        msg.what = MESSAGE_TYPE_ADD_NETWORK;
                        msg.obj = new LinkPropertiesHolder(network, linkProperties);
                        mTrafficHandler.sendMessage(msg);
                    }
                }

                @Override
                public void onLost(Network network) {
                    if (mTrafficHandler != null) {
                        Message msg = new Message();
                        msg.what = MESSAGE_TYPE_REMOVE_NETWORK;
                        msg.obj = network;
                        mTrafficHandler.sendMessage(msg);
                    }
                }
            };

    private ConnectivityManager.NetworkCallback mDefaultNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            updateViews();
        }

        @Override
        public void onLost(Network network) {
            updateViews();
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            final TunerService tunerService = Dependency.get(TunerService.class);
            tunerService.addTunable(this, NETWORK_TRAFFIC_ENABLED);
            tunerService.addTunable(this, NETWORK_TRAFFIC_MODE);
            tunerService.addTunable(this, NETWORK_TRAFFIC_AUTOHIDE);
            tunerService.addTunable(this, NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD);
            tunerService.addTunable(this, NETWORK_TRAFFIC_UNITS);
            tunerService.addTunable(this, NETWORK_TRAFFIC_REFRESH_INTERVAL);
            tunerService.addTunable(this, NETWORK_TRAFFIC_HIDEARROW);

            mConnectivityManager.registerNetworkCallback(mRequest, mNetworkCallback);
            mConnectivityManager.registerDefaultNetworkCallback(mDefaultNetworkCallback);

            mConnectionAvailable = mConnectivityManager.getActiveNetworkInfo() != null;

            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            mContext.registerReceiver(mIntentReceiver, filter, null, mTrafficHandler);

            mKeyguardUpdateMonitor = Dependency.get(KeyguardUpdateMonitor.class);
            mKeyguardUpdateMonitor.registerCallback(mUpdateCallback);

            updateViews();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            clearHandlerCallbacks();
            mContext.unregisterReceiver(mIntentReceiver);
            mConnectivityManager.unregisterNetworkCallback(mDefaultNetworkCallback);
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            Dependency.get(TunerService.class).removeTunable(this);
            mAttached = false;
        }
    }

    public void applyNetworkTrafficState(NetworkTrafficState state) {
        // mEnabled and state.visible will have same values, no need to set again
        updateVisibility();
        checkUpdateTrafficDrawable();
    }

    private final KeyguardUpdateMonitorCallback mUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onKeyguardVisibilityChanged(boolean showing) {
                    mKeyguardShowing = showing;
                    updateVisibility();
                }
            };

    private void updateVisibility() {
        boolean visible = mEnabled && mIsActive && getText() != ""
                    && !mKeyguardShowing 
                    && mVisibleState == STATE_ICON;
        if (visible != mVisible) {
            mVisible = visible;
            setVisibility(mVisible ? View.VISIBLE : View.GONE);
        }
    }

    private void checkUpdateTrafficDrawable() {
        // Wait for icon to be visible and tint to be changed
        if (mVisible && mIconTint != newTint) {
            mIconTint = newTint;
            updateTrafficDrawable();
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                mConnectionAvailable = mConnectivityManager.getActiveNetworkInfo() != null;
                updateViews();
            }
        }
    };

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case NETWORK_TRAFFIC_ENABLED:
                mEnabled =
                        TunerService.parseIntegerSwitch(newValue, false);
                if (mEnabled) {
                    setLines(2);
                    String txtFont = getResources().getString(com.android.internal.R.string.config_bodyFontFamily);
                    setTypeface(Typeface.create(txtFont, Typeface.BOLD));
                    setLineSpacing(0.80f, 0.80f);
                }
                updateViews();
                break;
            case NETWORK_TRAFFIC_MODE:
                mMode =
                        TunerService.parseInteger(newValue, 0);
                updateViews();
                setTrafficDrawable();
                break;
            case NETWORK_TRAFFIC_AUTOHIDE:
                mAutoHide =
                        TunerService.parseIntegerSwitch(newValue, false);
                updateViews();
                break;
            case NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD:
                int autohidethreshold =
                        TunerService.parseInteger(newValue, 0);
                mAutoHideThreshold = autohidethreshold * Kilo; /* Convert kB to Bytes */
                updateViews();
                break;
            case NETWORK_TRAFFIC_UNITS:
                mUnits =
                        TunerService.parseInteger(newValue, 1);
                updateViews();
                break;
            case NETWORK_TRAFFIC_REFRESH_INTERVAL:
                mRefreshInterval =
                        TunerService.parseInteger(newValue, 2);
                updateViews();
                break;
            case NETWORK_TRAFFIC_HIDEARROW:
                mHideArrows =
                        TunerService.parseIntegerSwitch(newValue, false);
                if (!mHideArrows) {
                    setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
                } else {
                    setGravity(Gravity.CENTER);
                }
                setTrafficDrawable();
                break;
            default:
                break;
        }
    }

    private void updateViews() {
        if (mEnabled) {
            updateViewState();
        }
    }

    private void updateViewState() {
        mTrafficHandler.removeMessages(MESSAGE_TYPE_UPDATE_VIEW);
        mTrafficHandler.sendEmptyMessageDelayed(MESSAGE_TYPE_UPDATE_VIEW, 1000);
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeMessages(MESSAGE_TYPE_PERIODIC_REFRESH);
        mTrafficHandler.removeMessages(MESSAGE_TYPE_UPDATE_VIEW);
    }

    private void setTrafficDrawable() {
        final int drawableResId;
        final Drawable drawable;

        if (mHideArrows) {
            drawableResId = 0;
        } else if (!mTrafficActive) {
            drawableResId = R.drawable.stat_sys_network_traffic;
        } else if (mMode == MODE_UPSTREAM_ONLY || mSubMode == MODE_UPSTREAM_ONLY) {
            drawableResId = R.drawable.stat_sys_network_traffic_up;
        } else if (mMode == MODE_DOWNSTREAM_ONLY || mSubMode == MODE_DOWNSTREAM_ONLY) {
            drawableResId = R.drawable.stat_sys_network_traffic_down;
        } else if (mMode == MODE_UPSTREAM_AND_DOWNSTREAM) {
            drawableResId = R.drawable.stat_sys_network_traffic_updown;
        } else {
            drawableResId = 0;
        }
        drawable = drawableResId != 0 ? getResources().getDrawable(drawableResId) : null;
        if (mDrawable != drawable || mIconTint != newTint) {
            mDrawable = drawable;
            mIconTint = newTint;
            setCompoundDrawablesWithIntrinsicBounds(null, null, mDrawable, null);
            updateTrafficDrawable();
        }
    }

    public void setTint(int tint) {
        newTint = tint;
        // Wait for icon to be visible and tint to be changed
        if (mVisible && mIconTint != newTint) {
            mIconTint = newTint;
            updateTrafficDrawable();
        }
    }

    private void updateTrafficDrawable() {
        if (mDrawable != null) {
            mDrawable.setColorFilter(mIconTint, PorterDuff.Mode.MULTIPLY);
        }
        setTextColor(mIconTint);
    }

    private static class LinkPropertiesHolder {
        private final Network mNetwork;
        private final LinkProperties mLinkProperties;

        public LinkPropertiesHolder(Network network, LinkProperties linkProperties) {
            mNetwork = network;
            mLinkProperties = linkProperties;
        }

        public Network getNetwork() {
            return mNetwork;
        }

        public LinkProperties getLinkProperties() {
            return mLinkProperties;
        }
    }
}
