package com.google.android.systemui.assist;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import com.android.internal.widget.ILockSettings;
import com.android.systemui.broadcast.BroadcastDispatcher;
import java.util.ArrayList;
import java.util.List;

public class OpaEnabledReceiver {
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final BroadcastReceiver mBroadcastReceiver = new OpaEnabledBroadcastReceiver();
    private final ContentObserver mContentObserver;
    private final ContentResolver mContentResolver;
    private final Context mContext;
    private boolean mIsAGSAAssistant;
    private boolean mIsOpaEligible;
    private boolean mIsOpaEnabled;
    private final List<OpaEnabledListener> mListeners = new ArrayList();
    private final ILockSettings mLockSettings;

    public OpaEnabledReceiver(Context context, BroadcastDispatcher broadcastDispatcher) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mContentObserver = new AssistantContentObserver(mContext);
        mLockSettings = ILockSettings.Stub.asInterface(ServiceManager.getService("lock_settings"));
        mBroadcastDispatcher = broadcastDispatcher;
        updateOpaEnabledState(mContext);
        registerContentObserver();
        registerEnabledReceiver(-2);
    }

    public void addOpaEnabledListener(OpaEnabledListener opaEnabledListener) {
        mListeners.add(opaEnabledListener);
        opaEnabledListener.onOpaEnabledReceived(mContext, mIsOpaEligible, mIsAGSAAssistant, mIsOpaEnabled);
    }

    public void onUserSwitching(int i) {
        updateOpaEnabledState(mContext);
        dispatchOpaEnabledState(mContext);
        mContentResolver.unregisterContentObserver(mContentObserver);
        registerContentObserver();
        mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);
        registerEnabledReceiver(i);
    }

    private boolean isOpaEligible(Context context) {
        if (Settings.Secure.getIntForUser(context.getContentResolver(), "systemui.google.opa_enabled", 0, -2) != 0) {
            return true;
        }
        return false;
    }

    private boolean isOpaEnabled(Context context) {
        try {
            return mLockSettings.getBoolean("systemui.google.opa_user_enabled", false, -2);
        } catch (RemoteException e) {
            Log.e("OpaEnabledReceiver", "isOpaEnabled RemoteException", e);
            return false;
        }
    }

    private void updateOpaEnabledState(Context context) {
        mIsOpaEligible = isOpaEligible(context);
        mIsAGSAAssistant = OpaUtils.isAGSACurrentAssistant(context);
        mIsOpaEnabled = isOpaEnabled(context);
    }

    public void dispatchOpaEnabledState() {
        dispatchOpaEnabledState(mContext);
    }

    private void dispatchOpaEnabledState(Context context) {
        Log.i("OpaEnabledReceiver", "Dispatching OPA eligble = " + mIsOpaEligible + "; AGSA = " + mIsAGSAAssistant + "; OPA enabled = " + mIsOpaEnabled);
        for (int i = 0; i < mListeners.size(); i++) {
            mListeners.get(i).onOpaEnabledReceived(context, mIsOpaEligible, mIsAGSAAssistant, mIsOpaEnabled);
        }
    }

    private void registerContentObserver() {
        mContentResolver.registerContentObserver(Settings.Secure.getUriFor("assistant"), false, mContentObserver, -2);
    }

    private void registerEnabledReceiver(int i) {
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, new IntentFilter("com.google.android.systemui.OPA_ENABLED"), null, new UserHandle(i));
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, new IntentFilter("com.google.android.systemui.OPA_USER_ENABLED"), null, new UserHandle(i));
    }

    private class AssistantContentObserver extends ContentObserver {
        public AssistantContentObserver(Context context) {
            super(new Handler(context.getMainLooper()));
        }

        @Override
        public void onChange(boolean z, Uri uri) {
            updateOpaEnabledState(mContext);
            dispatchOpaEnabledState(mContext);
        }
    }

    private class OpaEnabledBroadcastReceiver extends BroadcastReceiver {
        private OpaEnabledBroadcastReceiver() {
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("com.google.android.systemui.OPA_ENABLED")) {
                Settings.Secure.putIntForUser(context.getContentResolver(), "systemui.google.opa_enabled", intent.getBooleanExtra("OPA_ENABLED", false) ? 1 : 0, -2);
            } else if (intent.getAction().equals("com.google.android.systemui.OPA_USER_ENABLED")) {
                try {
                    mLockSettings.setBoolean("systemui.google.opa_user_enabled", intent.getBooleanExtra("OPA_USER_ENABLED", false), -2);
                } catch (RemoteException e) {
                    Log.e("OpaEnabledReceiver", "RemoteException on OPA_USER_ENABLED", e);
                }
            }
            updateOpaEnabledState(context);
            dispatchOpaEnabledState(context);
        }
    }
}
