package com.google.android.systemui.assist;

import static android.view.Display.DEFAULT_DISPLAY;

import android.content.Context;
import android.metrics.LogMaker;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.provider.Settings;
import com.android.internal.app.AssistUtils;
import com.android.internal.app.IVoiceInteractionSessionListener;
import com.android.internal.logging.MetricsLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.assist.AssistHandleBehaviorController;
import com.android.systemui.assist.AssistLogger;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.assist.AssistantSessionEvent;
import com.android.systemui.assist.PhoneStateMonitor;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.model.SysUiState;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.NavigationModeController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.google.android.systemui.assist.uihints.AssistantPresenceHandler;
import com.google.android.systemui.assist.uihints.GoogleDefaultUiController;

import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

@Singleton
public class AssistManagerGoogle extends AssistManager {
    private final AssistantPresenceHandler mAssistantPresenceHandler;
    private boolean mCheckAssistantStatus = true;
    private boolean mGoogleIsAssistant;
    private int mNavigationMode;
    private final OpaEnabledReceiver mOpaEnabledReceiver;
    private AssistManager.UiController mUiController;
    private final Handler mUiHandler;

    @Override
    public boolean shouldShowOrb() {
        return false;
    }

    @Inject
    public AssistManagerGoogle(DeviceProvisionedController controller,
            Context context,
            AssistUtils assistUtils,
            AssistHandleBehaviorController handleController,
            CommandQueue commandQueue,
            PhoneStateMonitor phoneStateMonitor,
            OverviewProxyService overviewProxyService,
            ConfigurationController configurationController,
            Lazy<SysUiState> sysUiState,
            GoogleDefaultUiController defaultUiController,
            AssistLogger assistLogger,
            BroadcastDispatcher broadcastDispatcher,
            OpaEnabledDispatcher opaEnabledDispatcher,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            NavigationModeController navigationModeController,
            AssistantPresenceHandler assistantPresenceHandler,
            @Main Handler handler) {
        super(controller, context, assistUtils, handleController,
            commandQueue, phoneStateMonitor, overviewProxyService,
            configurationController, sysUiState, defaultUiController,
            assistLogger);
        mUiHandler = handler;
        mUiController = defaultUiController;
        mOpaEnabledReceiver = new OpaEnabledReceiver(mContext, broadcastDispatcher);
        addOpaEnabledListener(opaEnabledDispatcher);
        keyguardUpdateMonitor.registerCallback(new KeyguardUpdateMonitorCallback() {
            @Override
            public void onUserSwitching(int i) {
                mOpaEnabledReceiver.onUserSwitching(i);
            }
        });
        mNavigationMode = navigationModeController.addListener(new NavigationModeController.ModeChangedListener() {
            @Override
            public final void onNavigationModeChanged(int i) {
                mNavigationMode = i;
            }
        });
        mAssistantPresenceHandler = assistantPresenceHandler;
        assistantPresenceHandler.registerAssistantPresenceChangeListener(new AssistantPresenceHandler.AssistantPresenceChangeListener() {
            @Override
            public final void onAssistantPresenceChanged(boolean isGoogleAssistant) {
                if (mGoogleIsAssistant != isGoogleAssistant) {
                    mUiHandler.post(new Runnable() {
                        @Override
                        public final void run() {
                            mUiController.hide();
                        }
                    });
                    ((GoogleDefaultUiController) mUiController).setGoogleAssistant(isGoogleAssistant);
                    mGoogleIsAssistant = isGoogleAssistant;
                }
                mCheckAssistantStatus = false;
            }
        });
    }

    public boolean shouldUseHomeButtonAnimations() {
        return !QuickStepContract.isGesturalMode(mNavigationMode);
    }

    @Override
    protected void registerVoiceInteractionSessionListener() {
        mAssistUtils.registerVoiceInteractionSessionListener(new IVoiceInteractionSessionListener.Stub() {
            @Override
            public void onVoiceSessionShown() throws RemoteException {
                mAssistLogger.reportAssistantSessionEvent(AssistantSessionEvent.ASSISTANT_SESSION_UPDATE);
            }

            @Override
            public void onVoiceSessionHidden() throws RemoteException {
                mAssistLogger.reportAssistantSessionEvent(AssistantSessionEvent.ASSISTANT_SESSION_CLOSE);
            }

            @Override
            public void onSetUiHints(Bundle bundle) {
                String string = bundle.getString("action");
                if ("show_assist_handles".equals(string)) {
                    requestAssistHandles();
                } else if ("set_assist_gesture_constrained".equals(string)) {
                    mSysUiState.get()
                            .setFlag(8192, bundle.getBoolean(CONSTRAINED_KEY, false))
                            .commitUpdate(DEFAULT_DISPLAY);
                }else{
                    mUiHandler.post(new Runnable() {
                        @Override
                        public final void run() {
                            mAssistantPresenceHandler.requestAssistantPresenceUpdate();
                            mCheckAssistantStatus = false;
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onInvocationProgress(int i, float f) {
        if (f == 0.0f || f == 1.0f) {
            mCheckAssistantStatus = true;
        }
        if (mCheckAssistantStatus) {
            mAssistantPresenceHandler.requestAssistantPresenceUpdate();
            mCheckAssistantStatus = false;
        }
        if (i != 2) {
            mUiController.onInvocationProgress(i, f);
        }
    }

    @Override
    public void onGestureCompletion(float f) {
        mCheckAssistantStatus = true;
        mUiController.onGestureCompletion(f / mContext.getResources().getDisplayMetrics().density);
    }

    public void addOpaEnabledListener(OpaEnabledListener opaEnabledListener) {
        mOpaEnabledReceiver.addOpaEnabledListener(opaEnabledListener);
    }

    public void dispatchOpaEnabledState() {
        mOpaEnabledReceiver.dispatchOpaEnabledState();
    }
}
