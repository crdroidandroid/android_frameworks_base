package com.android.systemui.statusbar.info;

import android.content.Context;
import android.graphics.Canvas;
import android.provider.Settings;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.SubscriptionManager;
import android.text.BidiFormatter;
import android.text.format.Formatter;
import android.text.format.Formatter.BytesResult;
import android.util.AttributeSet;
import android.widget.TextView;

import com.android.settingslib.net.DataUsageController;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.NetworkController;

public class DataUsageView extends TextView {

    private Context mContext;
    private NetworkController mNetworkController;
    private ConnectivityManager mConnectivityManager;
    private int mQSDataUsage = 0;
    private DataUsageController mDataUsageController;

    public DataUsageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mNetworkController = Dependency.get(NetworkController.class);
        mDataUsageController = new DataUsageController(mContext);
        mDataUsageController.setSubscriptionId(
            SubscriptionManager.getDefaultDataSubscriptionId());
        mConnectivityManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void updateUsageData(int usage) {
        DataUsageController.DataUsageInfo info;

        switch (usage) {
            default:
            case 0:
                return;
            case 1:
                if (isWiFiConnected()) {
                    info = mDataUsageController.getDailyWifiDataUsageInfo();
                } else {
                    info = mDataUsageController.getDailyDataUsageInfo();
                }
                break;
            case 2:
                if (isWiFiConnected()) {
                    info = mDataUsageController.getWifiDataUsageInfo();
                } else {
                    info = mDataUsageController.getDataUsageInfo();
                }
                break;
        }

        setText(formatDataUsage(info.usageLevel));
    }

    private String formatDataUsage(long byteValue) {
        final BytesResult res = Formatter.formatBytes(mContext.getResources(), byteValue,
                Formatter.FLAG_IEC_UNITS);
        return BidiFormatter.getInstance().unicodeWrap(res.value + res.units);
    }

    // Check if device is connected to Wi-Fi
    public boolean isWiFiConnected() {
        if (mConnectivityManager == null) return false;

        NetworkInfo wifi = mConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return wifi.isConnected();
    }

    // Check if device is connected to the internet
    public boolean isConnected() {
        if (mConnectivityManager == null) return false;

        NetworkInfo wifi = mConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        NetworkInfo mobile = mConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        return wifi.isConnected() || mobile.isConnected();
    }
}
