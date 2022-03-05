package com.crdroid.android.systemui.elmyra;

import android.content.Context;

import com.crdroid.android.systemui.assist.AssistManagerGoogle;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.telephony.TelephonyListenerManager;

import com.google.android.systemui.elmyra.ServiceConfigurationGoogle;
import com.google.android.systemui.elmyra.actions.CameraAction;
import com.google.android.systemui.elmyra.actions.LaunchOpa;
import com.google.android.systemui.elmyra.actions.SettingsAction;
import com.google.android.systemui.elmyra.actions.SetupWizardAction;
import com.google.android.systemui.elmyra.actions.SilenceCall;
import com.google.android.systemui.elmyra.actions.UnpinNotifications;
import com.google.android.systemui.elmyra.feedback.AssistInvocationEffect;
import com.google.android.systemui.elmyra.feedback.OpaHomeButton;
import com.google.android.systemui.elmyra.feedback.OpaLockscreen;
import com.google.android.systemui.elmyra.feedback.SquishyNavigationButtons;
import com.google.android.systemui.elmyra.gates.TelephonyActivity;

import java.util.Optional;

import dagger.Module;
import dagger.Provides;

@Module
public class ElmyraModule {
    @Provides
    @SysUISingleton
    static ServiceConfigurationGoogle provideServiceConfigurationGoogle(
            Context context,
            AssistInvocationEffect assistInvocationEffect,
            LaunchOpa.Builder builder,
            SettingsAction.Builder builderB,
            CameraAction.Builder builderC,
            SetupWizardAction.Builder builderD,
            SquishyNavigationButtons squishyNavigationButtons,
            UnpinNotifications unpinNotifications,
            SilenceCall silenceCall,
            TelephonyActivity telephonyActivity) {
        return new ServiceConfigurationGoogle(
                context,
                assistInvocationEffect,
                builder,
                builderB,
                builderC,
                builderD,
                squishyNavigationButtons,
                unpinNotifications,
                silenceCall,
                telephonyActivity);
    }

    @Provides
    @SysUISingleton
    static AssistInvocationEffect provideAssistInvocationEffectElmyra(
            AssistManagerGoogle assistManagerGoogle,
            OpaHomeButton opaHomeButton,
            OpaLockscreen opaLockscreen) {
        return new AssistInvocationEffect(assistManagerGoogle, opaHomeButton, opaLockscreen);
    }

    @Provides
    @SysUISingleton
    static OpaHomeButton provideOpaHomeButton(KeyguardViewMediator keyguardViewMediator,
            StatusBar statusBar, NavigationModeController navigationModeController) {
        return new OpaHomeButton(keyguardViewMediator, statusBar, navigationModeController);
    }

    @Provides
    @SysUISingleton
    static OpaLockscreen provideOpaLockscreen(StatusBar statusBar,
            KeyguardStateController keyguardStateController) {
        return new OpaLockscreen(statusBar, keyguardStateController);
    }

    @Provides
    @SysUISingleton
    static SquishyNavigationButtons provideSquishyNavigationButtons(Context context,
            KeyguardViewMediator keyguardViewMediator, StatusBar statusBar,
            NavigationModeController navigationModeController) {
        return new SquishyNavigationButtons(context, keyguardViewMediator, statusBar,
                navigationModeController);
    }

    @Provides
    @SysUISingleton
    static TelephonyActivity provideTelephonyActivityElmyra(Context context,
            TelephonyListenerManager telephonyListenerManager) {
        return new TelephonyActivity(context, telephonyListenerManager);
    }

    @Provides
    @SysUISingleton
    static SetupWizardAction.Builder provideSetupWizardAction(Context context,
            StatusBar statusBar) {
        return new SetupWizardAction.Builder(context, statusBar);
    }

    @Provides
    @SysUISingleton
    static UnpinNotifications provideUnpinNotificationsElmyra(Context context,
            Optional<HeadsUpManager> optional) {
        return new UnpinNotifications(context, optional);
    }

    @Provides
    @SysUISingleton
    static LaunchOpa.Builder provideLaunchOpaElmyra(Context context, StatusBar statusBar) {
        return new LaunchOpa.Builder(context, statusBar);
    }

    @Provides
    @SysUISingleton
    static SilenceCall provideSilenceCallElmyra(Context context,
            TelephonyListenerManager telephonyListenerManager) {
        return new SilenceCall(context, telephonyListenerManager);
    }

    @Provides
    @SysUISingleton
    static SettingsAction.Builder provideSettingsActionElmyra(Context context,
            StatusBar statusBar) {
        return new SettingsAction.Builder(context, statusBar);
    }

    @Provides
    @SysUISingleton
    static CameraAction.Builder provideCameraAction(Context context, StatusBar statusBar) {
        return new CameraAction.Builder(context, statusBar);
    }
}
