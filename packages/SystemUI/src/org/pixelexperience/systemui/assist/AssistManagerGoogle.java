/*
 * Copyright (C) 2021 The Pixel Experience Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pixelexperience.systemui.assist;

import android.content.Context;
import android.database.ContentObserver;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.IWindowManager;

import com.android.internal.app.AssistUtils;
import com.android.internal.app.IVoiceInteractionSessionListener;
import com.android.internal.logging.MetricsLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.assist.AssistLogger;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.assist.AssistantSessionEvent;
import com.android.systemui.assist.PhoneStateMonitor;
import com.android.systemui.assist.ui.DefaultUiController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.google.android.systemui.assist.OpaEnabledDispatcher;
import com.google.android.systemui.assist.OpaEnabledListener;
import com.google.android.systemui.assist.OpaEnabledReceiver;
import com.google.android.systemui.assist.uihints.AssistantPresenceHandler;
import com.google.android.systemui.assist.uihints.GoogleDefaultUiController;
import com.google.android.systemui.assist.uihints.NgaMessageHandler;
import com.google.android.systemui.assist.uihints.NgaUiController;

import java.util.Objects;

import javax.inject.Inject;

import dagger.Lazy;

@SysUISingleton
public class AssistManagerGoogle extends AssistManager {
    private final AssistantPresenceHandler mAssistantPresenceHandler;
    private final GoogleDefaultUiController mDefaultUiController;
    private final NgaMessageHandler mNgaMessageHandler;
    private final NgaUiController mNgaUiController;
    private final OpaEnabledReceiver mOpaEnabledReceiver;
    private final Handler mUiHandler;
    private final IWindowManager mWindowManagerService;
    private boolean mGoogleIsAssistant;
    private int mNavigationMode;
    private boolean mNgaIsAssistant;
    private boolean mSqueezeSetUp;
    private AssistManager.UiController mUiController;
    private boolean mCheckAssistantStatus = true;
    private final Runnable mOnProcessBundle = new Runnable() {
        @Override
        public final void run() {
            mAssistantPresenceHandler.requestAssistantPresenceUpdate();
            mCheckAssistantStatus = false;
        }
    };

    private SettingsObserver mSettingsObserver;
    private boolean mAllowAnimation = true;

    @Inject
    public AssistManagerGoogle(DeviceProvisionedController deviceProvisionedController, Context context, AssistUtils assistUtils, NgaUiController ngaUiController, CommandQueue commandQueue, OpaEnabledReceiver opaEnabledReceiver, PhoneStateMonitor phoneStateMonitor, OverviewProxyService overviewProxyService, OpaEnabledDispatcher opaEnabledDispatcher, KeyguardUpdateMonitor keyguardUpdateMonitor, NavigationModeController navigationModeController, AssistantPresenceHandler assistantPresenceHandler, NgaMessageHandler ngaMessageHandler, Lazy<SysUiState> lazy, Handler handler, DefaultUiController defaultUiController, GoogleDefaultUiController googleDefaultUiController, IWindowManager iWindowManager, AssistLogger assistLogger) {
        super(deviceProvisionedController, context, assistUtils, commandQueue, phoneStateMonitor, overviewProxyService, lazy, defaultUiController, assistLogger, handler);
        mUiHandler = handler;
        mOpaEnabledReceiver = opaEnabledReceiver;
        addOpaEnabledListener(opaEnabledDispatcher);
        keyguardUpdateMonitor.registerCallback(new KeyguardUpdateMonitorCallback() {
            @Override
            public void onUserSwitching(int i) {
                mOpaEnabledReceiver.onUserSwitching(i);
            }
        });
        mNgaUiController = ngaUiController;
        mDefaultUiController = googleDefaultUiController;
        mUiController = googleDefaultUiController;
        mNavigationMode = navigationModeController.addListener(i -> mNavigationMode = i);
        mAssistantPresenceHandler = assistantPresenceHandler;
        assistantPresenceHandler.registerAssistantPresenceChangeListener((z, z2) -> {
            if (!(mGoogleIsAssistant == z && mNgaIsAssistant == z2)) {
                if (!z2) {
                    if (!mUiController.equals(mDefaultUiController)) {
                        mUiController = mDefaultUiController;
                        Objects.requireNonNull(mUiController);
                        mUiHandler.post(() -> mUiController.hide());
                    }
                    mDefaultUiController.setGoogleAssistant(z);
                } else if (!mUiController.equals(mNgaUiController)) {
                    mUiController = mNgaUiController;
                    Objects.requireNonNull(mUiController);
                    mUiHandler.post(() -> mUiController.hide());
                }
                mGoogleIsAssistant = z;
                mNgaIsAssistant = z2;
            }
            mCheckAssistantStatus = false;
        });
        mNgaMessageHandler = ngaMessageHandler;
        mWindowManagerService = iWindowManager;
        mSettingsObserver = new SettingsObserver(mUiHandler);
        mSettingsObserver.observe();
    }

    public boolean shouldUseHomeButtonAnimations() {
        return mAllowAnimation && !QuickStepContract.isGesturalMode(mNavigationMode);
    }

    @Override
    protected void registerVoiceInteractionSessionListener() {
        mAssistUtils.registerVoiceInteractionSessionListener(new IVoiceInteractionSessionListener.Stub() {
            @Override
            public void onVoiceSessionShown() {
                mAssistLogger.reportAssistantSessionEvent(AssistantSessionEvent.ASSISTANT_SESSION_UPDATE);
            }

            @Override
            public void onVoiceSessionHidden() {
                mAssistLogger.reportAssistantSessionEvent(AssistantSessionEvent.ASSISTANT_SESSION_CLOSE);
            }

            @Override
            public void onSetUiHints(Bundle bundle) {
                String string = bundle.getString("action");
                if ("set_assist_gesture_constrained".equals(string)) {
                    mSysUiState.get().setFlag(8192, bundle.getBoolean("should_constrain", false)).commitUpdate(0);
                } else if ("show_global_actions".equals(string)) {
                    try {
                        mWindowManagerService.showGlobalActions();
                    } catch (RemoteException e) {
                        Log.e("AssistManagerGoogle", "showGlobalActions failed", e);
                    }
                } else {
                    mNgaMessageHandler.processBundle(bundle, mOnProcessBundle);
                }
            }
        });
    }

    @Override
    public void onInvocationProgress(int i, float f) {
        if (f == 0.0f || f == 1.0f) {
            mCheckAssistantStatus = true;
            if (i == 2) {
                checkSqueezeGestureStatus();
            }
        }
        if (mCheckAssistantStatus) {
            mAssistantPresenceHandler.requestAssistantPresenceUpdate();
            mCheckAssistantStatus = false;
        }
        if (i != 2 || mSqueezeSetUp) {
            mUiController.onInvocationProgress(i, f);
        }
    }

    @Override
    public void onGestureCompletion(float f) {
        mCheckAssistantStatus = true;
        mUiController.onGestureCompletion(f / mContext.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void logStartAssistLegacy(int i, int i2) {
        MetricsLogger.action(new LogMaker(1716).setType(1).setSubtype(((mAssistantPresenceHandler.isNgaAssistant() ? 1 : 0) << 8) | toLoggingSubType(i, i2)));
    }

    private class SettingsObserver extends ContentObserver {

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIXEL_NAV_ANIMATION),
                    false, this, UserHandle.USER_ALL);
            update();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            update();
        }

        public void update() {
            mAllowAnimation = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.PIXEL_NAV_ANIMATION, 1, UserHandle.USER_CURRENT) == 1;
        }
    }

    public void addOpaEnabledListener(OpaEnabledListener opaEnabledListener) {
        mOpaEnabledReceiver.addOpaEnabledListener(opaEnabledListener);
    }

    public boolean isActiveAssistantNga() {
        return mNgaIsAssistant;
    }

    public void dispatchOpaEnabledState() {
        mOpaEnabledReceiver.dispatchOpaEnabledState();
    }

    private void checkSqueezeGestureStatus() {
        boolean z = false;
        if (Settings.Secure.getInt(mContext.getContentResolver(), "assist_gesture_setup_complete", 0) == 1) {
            z = true;
        }
        mSqueezeSetUp = z;
    }
}

