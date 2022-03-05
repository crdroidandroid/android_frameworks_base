package com.crdroid.android.systemui.columbus;

import static com.crdroid.android.systemui.Dependency.*;

import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserManager;
import android.os.Vibrator;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import com.android.internal.logging.UiEventLogger;

import com.google.android.systemui.columbus.ColumbusContentObserver;
import com.google.android.systemui.columbus.ColumbusService;
import com.google.android.systemui.columbus.ColumbusServiceWrapper;
import com.google.android.systemui.columbus.ColumbusSettings;
import com.google.android.systemui.columbus.ColumbusStructuredDataManager;
import com.google.android.systemui.columbus.ColumbusTargetRequestService;
import com.google.android.systemui.columbus.ContentResolverWrapper;
import com.google.android.systemui.columbus.PowerManagerWrapper;
import com.google.android.systemui.columbus.actions.Action;
import com.google.android.systemui.columbus.actions.DismissTimer;
import com.google.android.systemui.columbus.actions.LaunchApp;
import com.google.android.systemui.columbus.actions.LaunchOpa;
import com.google.android.systemui.columbus.actions.LaunchOverview;
import com.google.android.systemui.columbus.actions.ManageMedia;
import com.google.android.systemui.columbus.actions.OpenNotificationShade;
import com.google.android.systemui.columbus.actions.SettingsAction;
import com.google.android.systemui.columbus.actions.SilenceCall;
import com.google.android.systemui.columbus.actions.SnoozeAlarm;
import com.google.android.systemui.columbus.actions.TakeScreenshot;
import com.google.android.systemui.columbus.actions.UnpinNotifications;
import com.google.android.systemui.columbus.actions.UserAction;
import com.google.android.systemui.columbus.actions.UserSelectedAction;
import com.google.android.systemui.columbus.feedback.FeedbackEffect;
import com.google.android.systemui.columbus.feedback.HapticClick;
import com.google.android.systemui.columbus.feedback.UserActivity;
import com.google.android.systemui.columbus.gates.CameraVisibility;
import com.google.android.systemui.columbus.gates.ChargingState;
import com.google.android.systemui.columbus.gates.FlagEnabled;
import com.google.android.systemui.columbus.gates.Gate;
import com.google.android.systemui.columbus.gates.KeyguardProximity;
import com.google.android.systemui.columbus.gates.KeyguardVisibility;
import com.google.android.systemui.columbus.gates.PowerSaveState;
import com.google.android.systemui.columbus.gates.PowerState;
import com.google.android.systemui.columbus.gates.Proximity;
import com.google.android.systemui.columbus.gates.ScreenTouch;
import com.google.android.systemui.columbus.gates.SetupWizard;
import com.google.android.systemui.columbus.gates.SilenceAlertsDisabled;
import com.google.android.systemui.columbus.gates.SystemKeyPress;
import com.google.android.systemui.columbus.gates.TelephonyActivity;
import com.google.android.systemui.columbus.gates.UsbState;
import com.google.android.systemui.columbus.gates.VrMode;
import com.google.android.systemui.columbus.sensors.CHREGestureSensor;
import com.google.android.systemui.columbus.sensors.GestureController;
import com.google.android.systemui.columbus.sensors.GestureSensor;
import com.google.android.systemui.columbus.sensors.GestureSensorImpl;
import com.google.android.systemui.columbus.sensors.config.Adjustment;
import com.google.android.systemui.columbus.sensors.config.GestureConfiguration;
import com.google.android.systemui.columbus.sensors.config.LowSensitivitySettingAdjustment;
import com.google.android.systemui.columbus.sensors.config.SensorConfiguration;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.Recents;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.commandline.CommandRegistry;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.sensors.ProximitySensor;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Named;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;

@Module
public abstract class ColumbusModule {

    @Provides
    static ColumbusTargetRequestService provideColumbusTargetRequestService(Context context, UserTracker userTracker, ColumbusSettings columbusSettings, ColumbusStructuredDataManager columbusStructuredDataManager, UiEventLogger uiEventLogger, @Main Handler handler, @Background Looper looper) {
        return new ColumbusTargetRequestService(context, userTracker, columbusSettings, columbusStructuredDataManager, uiEventLogger, handler, looper);
    }

    @Provides
    @SysUISingleton
    static ColumbusStructuredDataManager provideColumbusStructuredDataManager(Context context, UserTracker userTracker, @Background Executor executor) {
        return new ColumbusStructuredDataManager(context, userTracker, executor);
    }

    @Provides
    @SysUISingleton
    static ContentResolverWrapper provideContentResolverWrapper(Context context) {
        return new ContentResolverWrapper(context);
    }

    @Provides
    @SysUISingleton
    static ColumbusServiceWrapper provideColumbusServiceWrapper(ColumbusSettings columbusSettings, Lazy<ColumbusService> lazy, Lazy<SettingsAction> lazyB, Lazy<ColumbusStructuredDataManager> lazyC) {
        return new ColumbusServiceWrapper(columbusSettings, lazy, lazyB, lazyC);
    }

    @Provides
    @SysUISingleton
    static ColumbusService provideColumbusService(List<Action> list, Set<FeedbackEffect> set, Set<Gate> setB, GestureController gestureController, PowerManagerWrapper powerManagerWrapper) {
        return new ColumbusService(list, set, setB, gestureController, powerManagerWrapper);
    }

    @Provides
    @SysUISingleton
    static PowerManagerWrapper providePowerManagerWrapper(Context context) {
        return new PowerManagerWrapper(context);
    }

    @Provides
    @SysUISingleton
    static ColumbusSettings provideColumbusSettings(Context context, UserTracker userTracker, ColumbusContentObserver.Factory factory) {
        return new ColumbusSettings(context, userTracker, factory);
    }

    @Provides
    @SysUISingleton
    static ColumbusContentObserver.Factory provideColumbusContentObserver(ContentResolverWrapper contentResolverWrapper, UserTracker userTracker, @Background Handler handler, @Main Executor executor) {
        return new ColumbusContentObserver.Factory(contentResolverWrapper, userTracker, handler, executor);
    }

    @Provides
    @SysUISingleton
    static com.google.android.systemui.columbus.feedback.AssistInvocationEffect provideAssistInvocationEffectColumbus(AssistManager assistManager) {
        return new com.google.android.systemui.columbus.feedback.AssistInvocationEffect(assistManager);
    }

    @Provides
    @SysUISingleton
    static UserActivity provideUserActivity(Lazy<PowerManager> lazy) {
        return new UserActivity(lazy);
    }

    @Provides
    @SysUISingleton
    static HapticClick provideHapticClick(Lazy<Vibrator> lazy) {
        return new HapticClick(lazy);
    }

    @Provides
    @SysUISingleton
    static KeyguardProximity provideKeyguardProximity(Context context, KeyguardVisibility keyguardVisibility, Proximity proximity) {
        return new KeyguardProximity(context, keyguardVisibility, proximity);
    }

    @Provides
    @SysUISingleton
    static KeyguardVisibility provideKeyguardVisibility(Context context, Lazy<KeyguardStateController> lazy) {
        return new KeyguardVisibility(context, lazy);
    }

    @Provides
    @SysUISingleton
    static ChargingState provideChargingState(Context context, Handler handler, @Named(COLUMBUS_TRANSIENT_GATE_DURATION) long j) {
        return new ChargingState(context, handler, j);
    }

    @Provides
    @SysUISingleton
    static UsbState provideUsbState(Context context, Handler handler, @Named(COLUMBUS_TRANSIENT_GATE_DURATION) long j) {
        return new UsbState(context, handler, j);
    }

    @Provides
    @SysUISingleton
    static PowerSaveState providePowerSaveState(Context context) {
        return new PowerSaveState(context);
    }

    @Provides
    @SysUISingleton
    static SilenceAlertsDisabled provideSilenceAlertsDisabled(Context context, ColumbusSettings columbusSettings) {
        return new SilenceAlertsDisabled(context, columbusSettings);
    }

    @Provides
    @SysUISingleton
    static FlagEnabled provideFlagEnabled(Context context, ColumbusSettings columbusSettings, Handler handler) {
        return new FlagEnabled(context, columbusSettings, handler);
    }

    @Provides
    @SysUISingleton
    static CameraVisibility provideCameraVisibility(Context context, List<Action> list, KeyguardVisibility keyguardVisibility, PowerState powerState, IActivityManager iActivityManager, Handler handler) {
        return new CameraVisibility(context, list, keyguardVisibility, powerState, iActivityManager, handler);
    }

    @Provides
    @SysUISingleton
    static SetupWizard provideSetupWizard(Context context, @Named(COLUMBUS_SETUP_WIZARD_ACTIONS) Set<Action> set, Lazy<DeviceProvisionedController> lazy) {
        return new SetupWizard(context, set, lazy);
    }

    @Provides
    @SysUISingleton
    static PowerState providePowerState(Context context, Lazy<WakefulnessLifecycle> lazy) {
        return new PowerState(context, lazy);
    }

    @Provides
    @SysUISingleton
    static SystemKeyPress provideSystemKeyPress(Context context, Handler handler, CommandQueue commandQueue, @Named(COLUMBUS_TRANSIENT_GATE_DURATION) long j, @Named(COLUMBUS_BLOCKING_SYSTEM_KEYS) Set<Integer> set) {
        return new SystemKeyPress(context, handler, commandQueue, j, set);
    }

    @Provides
    @SysUISingleton
    static ScreenTouch provideScreenTouch(Context context, PowerState powerState, Handler handler) {
        return new ScreenTouch(context, powerState, handler);
    }

    @Provides
    @SysUISingleton
    static com.google.android.systemui.columbus.gates.TelephonyActivity provideTelephonyActivityColumbus(Context context, Lazy<TelephonyManager> lazy, Lazy<TelephonyListenerManager> lazyB) {
        return new com.google.android.systemui.columbus.gates.TelephonyActivity(context, lazy, lazyB);
    }

    @Provides
    @SysUISingleton
    static Proximity provideProximity(Context context, ProximitySensor proximitySensor) {
        return new Proximity(context, proximitySensor);
    }

    @Provides
    @SysUISingleton
    static VrMode provideVrMode(Context context) {
        return new VrMode(context);
    }

    @Provides
    @SysUISingleton
    static GestureSensorImpl provideGestureSensorImpl(Context context, UiEventLogger uiEventLogger, Handler handler) {
        return new GestureSensorImpl(context, uiEventLogger, handler);
    }

    @Provides
    @SysUISingleton
    static GestureController provideGestureController(GestureSensor gestureSensor, @Named(COLUMBUS_SOFT_GATES) Set<Gate> set, CommandRegistry commandRegistry, UiEventLogger uiEventLogger) {
        return new GestureController(gestureSensor, set, commandRegistry, uiEventLogger);
    }

    @Provides
    @SysUISingleton
    static CHREGestureSensor provideCHREGestureSensor(Context context, UiEventLogger uiEventLogger, GestureConfiguration gestureConfiguration, StatusBarStateController statusBarStateController, WakefulnessLifecycle wakefulnessLifecycle, Handler handler) {
        return new CHREGestureSensor(context, uiEventLogger, gestureConfiguration, statusBarStateController, wakefulnessLifecycle, handler);
    }

    @Provides
    @SysUISingleton
    static GestureConfiguration provideGestureConfiguration(List<Adjustment> list, SensorConfiguration sensorConfiguration) {
        return new GestureConfiguration(list, sensorConfiguration);
    }

    @Provides
    @SysUISingleton
    static SensorConfiguration provideSensorConfiguration(Context context) {
        return new SensorConfiguration(context);
    }

    @Provides
    @SysUISingleton
    static LowSensitivitySettingAdjustment provideLowSensitivitySettingAdjustment(Context context, ColumbusSettings columbusSettings, SensorConfiguration sensorConfiguration) {
        return new LowSensitivitySettingAdjustment(context, columbusSettings, sensorConfiguration);
    }

    @Provides
    @SysUISingleton
    static SettingsAction provideSettingsActionColumbus(Context context, StatusBar statusBar, UiEventLogger uiEventLogger) {
        return new SettingsAction(context, statusBar, uiEventLogger);
    }

    @Provides
    @SysUISingleton
    static UserSelectedAction provideUserSelectedAction(Context context, ColumbusSettings columbusSettings, Map<String, UserAction> map, TakeScreenshot takeScreenshot, KeyguardStateController keyguardStateController, PowerManagerWrapper powerManagerWrapper, WakefulnessLifecycle wakefulnessLifecycle) {
        return new UserSelectedAction(context, columbusSettings, map, takeScreenshot, keyguardStateController, powerManagerWrapper, wakefulnessLifecycle);
    }

    @Provides
    @SysUISingleton
    static DismissTimer provideDismissTimer(Context context, SilenceAlertsDisabled silenceAlertsDisabled, IActivityManager iActivityManager) {
        return new DismissTimer(context, silenceAlertsDisabled, iActivityManager);
    }

    @Provides
    @SysUISingleton
    static com.google.android.systemui.columbus.actions.UnpinNotifications provideUnpinNotificationsColumbus(Context context, SilenceAlertsDisabled silenceAlertsDisabled, Optional<HeadsUpManager> optional) {
        return new com.google.android.systemui.columbus.actions.UnpinNotifications(context, silenceAlertsDisabled, optional);
    }

    @Provides
    @SysUISingleton
    static ManageMedia provideManageMedia(Context context, AudioManager audioManager, UiEventLogger uiEventLogger) {
        return new ManageMedia(context, audioManager, uiEventLogger);
    }

    @Provides
    @SysUISingleton
    static LaunchApp provideLaunchApp(Context context, LauncherApps launcherApps, ActivityStarter activityStarter, StatusBarKeyguardViewManager statusBarKeyguardViewManager, IActivityManager iActivityManager, UserManager userManager, ColumbusSettings columbusSettings, KeyguardVisibility keyguardVisibility, KeyguardUpdateMonitor keyguardUpdateMonitor, @Main Handler handler, @Background Handler handlerB, Executor executor, UiEventLogger uiEventLogger, UserTracker userTracker) {
        return new LaunchApp(context, launcherApps, activityStarter, statusBarKeyguardViewManager, iActivityManager, userManager, columbusSettings, keyguardVisibility, keyguardUpdateMonitor, handler, handlerB, executor, uiEventLogger, userTracker);
    }

    @Provides
    @SysUISingleton
    static com.google.android.systemui.columbus.actions.SilenceCall provideSilenceCallColumbus(Context context, SilenceAlertsDisabled silenceAlertsDisabled, Lazy<TelecomManager> lazy, Lazy<TelephonyManager> lazyB, Lazy<TelephonyListenerManager> lazyC) {
        return new com.google.android.systemui.columbus.actions.SilenceCall(context, silenceAlertsDisabled, lazy, lazyB, lazyC);
    }

    @Provides
    @SysUISingleton
    static LaunchOverview provideLaunchOverview(Context context, Recents recents, UiEventLogger uiEventLogger) {
        return new LaunchOverview(context, recents, uiEventLogger);
    }

    @Provides
    @SysUISingleton
    static com.google.android.systemui.columbus.actions.LaunchOpa provideLaunchOpaColumbus(Context context, StatusBar statusBar, Set<FeedbackEffect> set, AssistManager assistManager, Lazy<KeyguardManager> lazy, TunerService tunerService, ColumbusContentObserver.Factory factory, UiEventLogger uiEventLogger) {
        return new com.google.android.systemui.columbus.actions.LaunchOpa(context, statusBar, set, assistManager, lazy, tunerService, factory, uiEventLogger);
    }

    @Provides
    @SysUISingleton
    static com.google.android.systemui.columbus.actions.SnoozeAlarm provideSnoozeAlarmColumbus(Context context, SilenceAlertsDisabled silenceAlertsDisabled, IActivityManager iActivityManager) {
        return new com.google.android.systemui.columbus.actions.SnoozeAlarm(context, silenceAlertsDisabled, iActivityManager);
    }

    @Provides
    @SysUISingleton
    static OpenNotificationShade provideOpenNotificationShade(Context context, Lazy<NotificationShadeWindowController> lazy, Lazy<StatusBar> lazyB, UiEventLogger uiEventLogger) {
        return new OpenNotificationShade(context, lazy, lazyB, uiEventLogger);
    }

    @Provides
    @SysUISingleton
    static TakeScreenshot provideTakeScreenshot(Context context, Handler handler, UiEventLogger uiEventLogger) {
        return new TakeScreenshot(context, handler, uiEventLogger);
    }

    @Provides
    @SysUISingleton
    @Named(COLUMBUS_TRANSIENT_GATE_DURATION)
    static long provideTransientGateDuration() {
        return 500;
    }

    @Provides
    @SysUISingleton
    @ElementsIntoSet
    static Set<Gate> provideColumbusGates(FlagEnabled flagEnabled, ChargingState chargingState, UsbState usbState, KeyguardProximity keyguardProximity, SetupWizard setupWizard, SystemKeyPress systemKeyPress, TelephonyActivity telephonyActivity, VrMode vrMode, CameraVisibility cameraVisibility, PowerSaveState powerSaveState, PowerState powerState, ScreenTouch screenTouch) {
        return new HashSet(Arrays.asList(flagEnabled, chargingState, usbState, keyguardProximity, setupWizard, systemKeyPress, telephonyActivity, vrMode, cameraVisibility, powerSaveState, powerState, screenTouch));
    }

    @Provides
    @SysUISingleton
    @ElementsIntoSet
    @Named(COLUMBUS_SOFT_GATES)
    static Set<Gate> provideColumbusSoftGates(ChargingState chargingState, UsbState usbState, SystemKeyPress systemKeyPress, ScreenTouch screenTouch) {
        return new HashSet(Arrays.asList(chargingState, usbState, systemKeyPress, screenTouch));
    }

    @Provides
    @SysUISingleton
    static Map<String, UserAction> provideUserSelectedActions(LaunchOpa launchOpa, ManageMedia manageMedia, TakeScreenshot takeScreenshot, LaunchOverview launchOverview, OpenNotificationShade openNotificationShade, LaunchApp launchApp) {
        Map<String, UserAction> result = new HashMap<>();
        result.put("assistant", launchOpa);
        result.put("media", manageMedia);
        result.put("screenshot", takeScreenshot);
        result.put("overview", launchOverview);
        result.put("notifications", openNotificationShade);
        result.put("launch", launchApp);
        return result;
    }

    @Provides
    @SysUISingleton
    static GestureSensor provideGestureSensor(Context context, ColumbusSettings columbusSettings, Lazy<CHREGestureSensor> gestureSensor, Lazy<GestureSensorImpl> apSensor) {
        if (columbusSettings.useApSensor() || !context.getPackageManager().hasSystemFeature("android.hardware.context_hub")) {
            Log.i("Columbus/Module", "Creating AP sensor");
            return apSensor.get();
        }
        Log.i("Columbus/Module", "Creating CHRE sensor");
        return gestureSensor.get();
    }

    @Provides
    @SysUISingleton
    static List<Action> provideColumbusActions(@Named(COLUMBUS_FULL_SCREEN_ACTIONS) List<Action> fullScreenActions, UnpinNotifications unpinNotifications, UserSelectedAction userSelectedAction) {
        List<Action> result = new ArrayList<>(fullScreenActions);
        result.add(unpinNotifications);
        result.add(userSelectedAction);
        return result;
    }

    @Provides
    @SysUISingleton
    @Named(COLUMBUS_FULL_SCREEN_ACTIONS)
    static List<Action> provideFullscreenActions(DismissTimer dismissTimer, SnoozeAlarm snoozeAlarm, SilenceCall silenceCall, SettingsAction settingsAction) {
        return Arrays.asList(dismissTimer, snoozeAlarm, silenceCall, settingsAction);
    }

    @Provides
    @SysUISingleton
    @ElementsIntoSet
    static Set<FeedbackEffect> provideColumbusEffects(HapticClick hapticClick, UserActivity userActivity) {
        return new HashSet(Arrays.asList(hapticClick, userActivity));
    }

    @Provides
    @SysUISingleton
    static List<Adjustment> provideGestureAdjustments(LowSensitivitySettingAdjustment lowSensitivitySettingAdjustment) {
        return Collections.singletonList(lowSensitivitySettingAdjustment);
    }

    @Provides
    @SysUISingleton
    @Named(COLUMBUS_BLOCKING_SYSTEM_KEYS)
    @ElementsIntoSet
    static Set<Integer> provideBlockingSystemKeys() {
        return new HashSet(Arrays.asList(24, 25, 26));
    }

    @Provides
    @SysUISingleton
    @Named(COLUMBUS_SETUP_WIZARD_ACTIONS)
    @ElementsIntoSet
    static Set<Action> provideSetupWizardActions(SettingsAction settingsAction) {
        return new HashSet(Arrays.asList(settingsAction));
    }
}
