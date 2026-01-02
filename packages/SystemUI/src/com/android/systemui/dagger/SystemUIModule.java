/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.dagger;

import android.app.AlarmManager;
import android.app.INotificationManager;
import android.app.Service;
import android.app.backup.BackupManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.service.dreams.IDreamManager;
import android.view.Display;

import androidx.annotation.Nullable;

import com.android.app.displaylib.PerDisplayRepository;
import com.android.internal.statusbar.IStatusBarService;
import com.android.keyguard.dagger.ClockRegistryModule;
import com.android.keyguard.dagger.KeyguardBouncerComponent;
import com.android.systemui.BootCompleteCache;
import com.android.systemui.BootCompleteCacheImpl;
import com.android.systemui.CameraProtectionModule;
import com.android.systemui.CoreStartable;
import com.android.systemui.KairosCoreStartableModule;
import com.android.systemui.SystemUISecondaryUserService;
import com.android.systemui.activity.ActivityManagerModule;
import com.android.systemui.ambient.dagger.AmbientModule;
import com.android.systemui.appops.dagger.AppOpsModule;
import com.android.systemui.assist.AssistModule;
import com.android.systemui.authentication.AuthenticationModule;
import com.android.systemui.res.R;
import com.android.systemui.biometrics.FingerprintInteractiveToAuthProvider;
import com.android.systemui.biometrics.FingerprintReEnrollNotification;
import com.android.systemui.biometrics.UdfpsDisplayModeProvider;
import com.android.systemui.biometrics.dagger.BiometricsModule;
import com.android.systemui.biometrics.domain.BiometricsDomainLayerModule;
import com.android.systemui.bouncer.data.repository.BouncerRepositoryModule;
import com.android.systemui.bouncer.domain.interactor.BouncerInteractorModule;
import com.android.systemui.bouncer.ui.BouncerViewModule;
import com.android.systemui.brightness.dagger.ScreenBrightnessModule;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.classifier.FalsingModule;
import com.android.systemui.clipboardoverlay.dagger.ClipboardOverlayModule;
import com.android.systemui.common.data.CommonDataLayerModule;
import com.android.systemui.common.ui.ConfigurationModule;
import com.android.systemui.common.ui.data.repository.ConfigurationRepositoryModule;
import com.android.systemui.common.usagestats.data.CommonUsageStatsDataLayerModule;
import com.android.systemui.communal.dagger.CommunalModule;
import com.android.systemui.complication.dagger.ComplicationComponent;
import com.android.systemui.compose.ComposeModule;
import com.android.systemui.controls.dagger.ControlsModule;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.SystemUser;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.demomode.dagger.DemoModeModule;
import com.android.systemui.desktop.dagger.DesktopModule;
import com.android.systemui.deviceentry.DeviceEntryModule;
import com.android.systemui.display.DisplayModule;
import com.android.systemui.doze.dagger.DozeComponent;
import com.android.systemui.doze.dagger.RootDozeModule;
import com.android.systemui.dreams.dagger.DreamModule;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.FlagDependenciesModule;
import com.android.systemui.flags.FlagsModule;
import com.android.systemui.growth.dagger.GrowthModule;
import com.android.systemui.haptics.msdl.dagger.MSDLModule;
import com.android.systemui.inputdevice.InputDeviceModule;
import com.android.systemui.inputmethod.InputMethodModule;
import com.android.systemui.keyboard.KeyboardModule;
import com.android.systemui.keyevent.data.repository.KeyEventRepositoryModule;
import com.android.systemui.keyguard.data.quickaffordance.KeyguardDataQuickAffordanceModule;
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancesMetricsLogger;
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancesMetricsLoggerImpl;
import com.android.systemui.keyguard.ui.composable.LockscreenContent;
import com.android.systemui.lineage.LineageModule;
import com.android.systemui.log.dagger.LogModule;
import com.android.systemui.log.dagger.MonitorLog;
import com.android.systemui.log.table.TableLogBuffer;
import com.android.systemui.log.table.impl.TableLogBufferModule;
import com.android.systemui.lowlight.dagger.LowLightModule;
import com.android.systemui.lowlightclock.dagger.LowLightClockModule;
import com.android.systemui.media.NotificationMediaManager;
import com.android.systemui.mediaprojection.MediaProjectionModule;
import com.android.systemui.mediaprojection.appselector.MediaProjectionActivitiesModule;
import com.android.systemui.mediaprojection.taskswitcher.MediaProjectionTaskSwitcherModule;
import com.android.systemui.mediarouter.MediaRouterModule;
import com.android.systemui.model.SysUiState;
import com.android.systemui.motiontool.MotionToolModule;
import com.android.systemui.navigationbar.NavigationBarComponent;
import com.android.systemui.navigationbar.gestural.dagger.GestureModule;
import com.android.systemui.notetask.NoteTaskModule;
import com.android.systemui.people.PeopleModule;
import com.android.systemui.plugins.BcSmartspaceConfigPlugin;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.privacy.PrivacyModule;
import com.android.systemui.process.condition.SystemProcessCondition;
import com.android.systemui.qs.FgsManagerController;
import com.android.systemui.qs.FgsManagerControllerImpl;
import com.android.systemui.qs.footer.dagger.FooterActionsModule;
import com.android.systemui.qs.tiles.impl.qr.ui.model.QRCodeScannerModule;
import com.android.systemui.recents.Recents;
import com.android.systemui.recordissue.RecordIssueModule;
import com.android.systemui.retail.RetailModeModule;
import com.android.systemui.rotation.impl.RotationModule;
import com.android.systemui.rotationlock.DeviceStateAutoRotateModule.BoundsDeviceStateAutoRotateModule;
import com.android.systemui.scene.shared.model.SceneContainerConfig;
import com.android.systemui.scene.shared.model.SceneDataSource;
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator;
import com.android.systemui.scene.ui.view.WindowRootViewComponent;
import com.android.systemui.screencapture.common.ScreenCaptureModule;
import com.android.systemui.screenrecord.ScreenRecordModule;
import com.android.systemui.screenshot.dagger.ScreenshotModule;
import com.android.systemui.securelockdevice.dagger.SecureLockDeviceModule;
import com.android.systemui.security.data.repository.SecurityRepositoryModule;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeDisplayAwareModule;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolatorImpl;
import com.android.systemui.shared.condition.Monitor;
import com.android.systemui.smartspace.dagger.SmartspaceModule;
import com.android.systemui.startable.Dependencies;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.chips.StatusBarChipsModule;
import com.android.systemui.statusbar.connectivity.ConnectivityModule;
import com.android.systemui.statusbar.dagger.StatusBarModule;
import com.android.systemui.statusbar.disableflags.dagger.DisableFlagsModule;
import com.android.systemui.statusbar.domain.interactor.StatusBarRegionSamplingInteractorModule;
import com.android.systemui.statusbar.events.StatusBarEventsModule;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.featurepods.av.AvControlsChipModule;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinder;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider;
import com.android.systemui.statusbar.notification.people.PeopleHubModule;
import com.android.systemui.statusbar.notification.row.dagger.BundleRowComponent;
import com.android.systemui.statusbar.notification.row.dagger.ExpandableNotificationRowComponent;
import com.android.systemui.statusbar.notification.row.dagger.NotificationRowComponent;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.ConfigurationControllerModule;
import com.android.systemui.statusbar.phone.LetterboxModule;
import com.android.systemui.statusbar.pipeline.airplane.data.repository.impl.AirplaneModeDataLayerModule;
import com.android.systemui.statusbar.pipeline.airplane.shared.impl.AirplaneModeSharedModule;
import com.android.systemui.statusbar.pipeline.dagger.StatusBarPipelineModule;
import com.android.systemui.statusbar.policy.DeviceStateRotationLockSettingController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmControllerImpl;
import com.android.systemui.statusbar.policy.PolicyModule;
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.dagger.SmartRepliesInflationModule;
import com.android.systemui.statusbar.policy.dagger.StatusBarPolicyModule;
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor;
import com.android.systemui.statusbar.systemstatusicons.SystemStatusIconsModule;
import com.android.systemui.statusbar.ui.binder.StatusBarViewBinderModule;
import com.android.systemui.statusbar.window.StatusBarWindowModule;
import com.android.systemui.telephony.data.repository.TelephonyRepositoryModule;
import com.android.systemui.temporarydisplay.dagger.TemporaryDisplayModule;
import com.android.systemui.topui.TopUiController;
import com.android.systemui.topui.TopUiModule;
import com.android.systemui.touchpad.TouchpadModule;
import com.android.systemui.tuner.dagger.TunerModule;
import com.android.systemui.uimode.data.UiModeModule;
import com.android.systemui.user.UserModule;
import com.android.systemui.user.domain.UserDomainLayerModule;
import com.android.systemui.util.EventLogModule;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.SysUIConcurrencyModule;
import com.android.systemui.util.dagger.UtilModule;
import com.android.systemui.util.kotlin.SysUICoroutinesModule;
import com.android.systemui.util.reference.ReferenceModule;
import com.android.systemui.util.sensors.SensorModule;
import com.android.systemui.util.settings.SettingsProxy;
import com.android.systemui.util.settings.SettingsUtilModule;
import com.android.systemui.wallet.dagger.WalletModule;
import com.android.systemui.wmshell.BubblesManager;
import com.android.wm.shell.bubbles.Bubbles;

import com.google.android.systemui.smartspace.BcSmartspaceDataProvider;
import com.google.android.systemui.smartspace.DateSmartspaceDataProvider;
import com.google.android.systemui.smartspace.KeyguardMediaViewController;
import com.google.android.systemui.smartspace.KeyguardZenAlarmViewController;
import com.google.android.systemui.smartspace.WeatherSmartspaceDataProvider;
import com.google.android.systemui.smartspace.dagger.SmartspaceStartableModule;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;
import dagger.multibindings.Multibinds;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Named;

import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.CoroutineScope;

/**
 * A dagger module for injecting components of System UI that are required by System UI.
 *
 * If your feature can be excluded, subclassed, or re-implemented by a variant of SystemUI, put
 * your Dagger Module in {@link ReferenceSystemUIModule} and/or any variant modules that
 * rely on the feature.
 *
 * Adding an entry in this file means that _all_ variants of SystemUI will receive that code. They
 * may not appreciate that.
 */
@Module(includes = {
        ActivityManagerModule.class,
        AmbientModule.class,
        AppOpsModule.class,
        AirplaneModeDataLayerModule.class,
        AirplaneModeSharedModule.class,
        AssistModule.class,
        AuthenticationModule.class,
        AvControlsChipModule.class,
        BiometricsModule.class,
        BiometricsDomainLayerModule.class,
        BouncerInteractorModule.class,
        BouncerRepositoryModule.class,
        BouncerViewModule.class,
        CameraProtectionModule.class,
        ClipboardOverlayModule.class,
        ClockRegistryModule.class,
        CommunalModule.class,
        CommonDataLayerModule.class,
        ComposeModule.class,
        ConfigurationModule.class,
        ConfigurationRepositoryModule.class,
        CommonUsageStatsDataLayerModule.class,
        ConfigurationControllerModule.class,
        ConnectivityModule.class,
        ControlsModule.class,
        DemoModeModule.class,
        DesktopModule.class,
        DeviceEntryModule.class,
        DisableFlagsModule.class,
        DisplayModule.class,
        RootDozeModule.class,
        DreamModule.class,
        EventLogModule.class,
        FalsingModule.class,
        FlagsModule.class,
        FlagDependenciesModule.class,
        FooterActionsModule.class,
        KairosCoreStartableModule.class,
        GestureModule.class,
        GrowthModule.class,
        InputMethodModule.class,
        KeyEventRepositoryModule.class,
        KeyboardModule.class,
        KeyguardDataQuickAffordanceModule.class,
        LetterboxModule.class,
        LineageModule.class,
        LogModule.class,
        MediaProjectionActivitiesModule.class,
        MediaProjectionModule.class,
        MediaProjectionTaskSwitcherModule.class,
        MediaRouterModule.class,
        MotionToolModule.class,
        MSDLModule.class,
        PeopleHubModule.class,
        PeopleModule.class,
        PluginModule.class,
        PolicyModule.class,
        PrivacyModule.class,
        QRCodeScannerModule.class,
        RecordIssueModule.class,
        ReferenceModule.class,
        RetailModeModule.class,
        RotationModule.class,
        ScreenBrightnessModule.class,
        ScreenshotModule.class,
        SecureLockDeviceModule.class,
        SensorModule.class,
        SecurityRepositoryModule.class,
        ScreenCaptureModule.class,
        ScreenRecordModule.class,
        SettingsUtilModule.class,
        SmartRepliesInflationModule.class,
        SmartspaceModule.class,
            SmartspaceStartableModule.class,
        StatusBarEventsModule.class,
        StatusBarModule.class,
        StatusBarChipsModule.class,
        StatusBarPipelineModule.class,
        StatusBarPolicyModule.class,
        StatusBarRegionSamplingInteractorModule.class,
        StatusBarViewBinderModule.class,
        StatusBarWindowModule.class,
        SystemPropertiesFlagsModule.class,
        SystemStatusIconsModule.class,
        SysUIConcurrencyModule.class,
        SysUICoroutinesModule.class,
        CommonSystemUIUnfoldModule.class,
        TableLogBufferModule.class,
        TelephonyRepositoryModule.class,
        TemporaryDisplayModule.class,
        ShadeDisplayAwareModule.class,
        TopUiModule.class,
        TouchpadModule.class,
        TunerModule.class,
        UiModeModule.class,
        UserDomainLayerModule.class,
        UserModule.class,
        UtilModule.class,
        NoteTaskModule.class,
        WalletModule.class,
        LowLightModule.class,
        LowLightClockModule.class,
        PerDisplayRepositoriesModule.class,
        InputDeviceModule.class,
},
        subcomponents = {
                ComplicationComponent.class,
                DozeComponent.class,
                ExpandableNotificationRowComponent.class,
                KeyguardBouncerComponent.class,
                NavigationBarComponent.class,
                NotificationRowComponent.class,
                WindowRootViewComponent.class,
                BundleRowComponent.class,
        })
public abstract class SystemUIModule {

    @Multibinds
    @Dependencies
    abstract Map<Class<?>, Set<Class<? extends CoreStartable>>> startableDependencyMap();

    @Binds
    abstract BootCompleteCache bindBootCompleteCache(BootCompleteCacheImpl bootCompleteCache);

    /**
     *
     */
    @Binds
    public abstract ContextComponentHelper bindComponentHelper(
            ContextComponentResolver componentHelper);

    /**
     *
     */
    @Binds
    public abstract NotificationRowBinder bindNotificationRowBinder(
            NotificationRowBinderImpl notificationRowBinder);


    @SysUISingleton
    @Provides
    static SysUiState provideSysUiState(
            PerDisplayRepository<SysUiState> repository) {
        return repository.get(Display.DEFAULT_DISPLAY);
    }

    /**
     * Provides the monitor for SystemUI that requires the process running as the system user.
     */
    @SysUISingleton
    @Provides
    @SystemUser
    static Monitor provideSystemUserMonitor(@Main Executor executor,
            SystemProcessCondition systemProcessCondition, @MonitorLog TableLogBuffer logBuffer) {
        return new Monitor(executor, Collections.singleton(systemProcessCondition), logBuffer);
    }

    /** Provides the package name for SystemUI. */
    @SysUISingleton
    @Provides
    static BackupManager provideBackupManager(@Application Context context) {
        return new BackupManager(context);
    }

    @BindsOptionalOf
    abstract CommandQueue optionalCommandQueue();

    @BindsOptionalOf
    abstract HeadsUpManager optionalHeadsUpManager();

    @BindsOptionalOf
    abstract BcSmartspaceDataPlugin optionalBcSmartspaceDataPlugin();

    @BindsOptionalOf
    abstract BcSmartspaceConfigPlugin optionalBcSmartspaceConfigPlugin();

    @BindsOptionalOf
    @Named(SmartspaceModule.DATE_SMARTSPACE_DATA_PLUGIN)
    abstract BcSmartspaceDataPlugin optionalDateSmartspaceDataPlugin();

    @BindsOptionalOf
    @Named(SmartspaceModule.GLANCEABLE_HUB_SMARTSPACE_DATA_PLUGIN)
    abstract BcSmartspaceDataPlugin optionalGlanceableHubSmartspaceDataPlugin();

    @BindsOptionalOf
    @Named(SmartspaceModule.WEATHER_SMARTSPACE_DATA_PLUGIN)
    abstract BcSmartspaceDataPlugin optionalWeatherSmartspaceDataPlugin();

    @BindsOptionalOf
    abstract Recents optionalRecents();

    @BindsOptionalOf
    abstract CentralSurfaces optionalCentralSurfaces();

    @BindsOptionalOf
    abstract UdfpsDisplayModeProvider optionalUdfpsDisplayModeProvider();

    @BindsOptionalOf
    abstract FingerprintInteractiveToAuthProvider optionalFingerprintInteractiveToAuthProvider();

    @BindsOptionalOf
    abstract SystemStatusAnimationScheduler optionalSystemStatusAnimationScheduler();

    @BindsOptionalOf
    abstract FingerprintReEnrollNotification optionalFingerprintReEnrollNotification();

    @BindsOptionalOf
    abstract LockscreenContent optionalLockscreenContent();

    @BindsOptionalOf
    @BoundsDeviceStateAutoRotateModule
    abstract Optional<DeviceStateRotationLockSettingController>
            optionalDeviceStateRotationLockSettingController();

    // TODO: This should provided by the WM component

    /** Provides Optional of BubbleManager */
    @SysUISingleton
    @Provides
    static Optional<BubblesManager> provideBubblesManager(Context context,
            Optional<Bubbles> bubblesOptional,
            NotificationShadeWindowController notificationShadeWindowController,
            TopUiController topUiController,
            KeyguardStateController keyguardStateController,
            ShadeController shadeController,
            @Nullable IStatusBarService statusBarService,
            INotificationManager notificationManager,
            IDreamManager dreamManager,
            NotificationVisibilityProvider visibilityProvider,
            VisualInterruptionDecisionProvider visualInterruptionDecisionProvider,
            ZenModeController zenModeController,
            NotificationLockscreenUserManager notifUserManager,
            SensitiveNotificationProtectionController sensitiveNotificationProtectionController,
            CommonNotifCollection notifCollection,
            NotifPipeline notifPipeline,
            SysUiState sysUiState,
            FeatureFlags featureFlags,
            NotifPipelineFlags notifPipelineFlags,
            @Main Executor sysuiMainExecutor,
            @UiBackground Executor sysuiUiBgExecutor) {
        return Optional.ofNullable(BubblesManager.create(context,
                bubblesOptional,
                notificationShadeWindowController,
                topUiController,
                keyguardStateController,
                shadeController,
                statusBarService,
                notificationManager,
                dreamManager,
                visibilityProvider,
                visualInterruptionDecisionProvider,
                zenModeController,
                notifUserManager,
                sensitiveNotificationProtectionController,
                notifCollection,
                notifPipeline,
                sysUiState,
                featureFlags,
                notifPipelineFlags,
                sysuiMainExecutor,
                sysuiUiBgExecutor));
    }

    @Provides
    static KeyguardQuickAffordancesMetricsLogger providesKeyguardQuickAffordancesMetricsLogger() {
        return new KeyguardQuickAffordancesMetricsLoggerImpl();
    }

    @Binds
    abstract FgsManagerController bindFgsManagerController(FgsManagerControllerImpl impl);

    @Binds
    abstract LargeScreenShadeInterpolator largeScreensShadeInterpolator(
            LargeScreenShadeInterpolatorImpl impl);

    @Binds
    @IntoMap
    @ClassKey(SystemUISecondaryUserService.class)
    abstract Service bindsSystemUISecondaryUserService(SystemUISecondaryUserService service);

    @Provides
    @SysUISingleton
    static SceneDataSourceDelegator providesSceneDataSourceDelegator(
            @Application CoroutineScope applicationScope, SceneContainerConfig config) {
        return new SceneDataSourceDelegator(applicationScope, config);
    }

    @Provides
    @SysUISingleton
    static Optional<DeviceStateRotationLockSettingController>
            provideDeviceStateRotationLockSettingController(
            @BoundsDeviceStateAutoRotateModule
            Optional<Optional<DeviceStateRotationLockSettingController>> optionalOfOptional
    ) {
        return optionalOfOptional.orElseGet(Optional::empty);
    }

    @Binds
    abstract SceneDataSource bindSceneDataSource(SceneDataSourceDelegator delegator);

    @Provides
    static SettingsProxy.CurrentUserIdProvider provideCurrentUserId(UserTracker userTracker) {
        return userTracker::getUserId;
    }

    @Provides
    @SysUISingleton
    static KeyguardZenAlarmViewController provideKeyguardZenAlarmViewController(
            Context context,
            @Named(SmartspaceModule.DATE_SMARTSPACE_DATA_PLUGIN) BcSmartspaceDataPlugin datePlugin,
            ZenModeController zenModeController,
            ZenModeInteractor zenModeInteractor,
            AlarmManager alarmManager,
            NextAlarmControllerImpl nextAlarmController,
            @Main Handler handler,
            @Application CoroutineScope applicationScope,
            @Background CoroutineDispatcher bgDispatcher) {
        KeyguardZenAlarmViewController controller = new KeyguardZenAlarmViewController(
                context,
                datePlugin,
                zenModeController,
                zenModeInteractor,
                alarmManager,
                nextAlarmController,
                handler,
                applicationScope,
                bgDispatcher
        );
        controller.alarmImage = context.getResources().getDrawable(R.drawable.ic_access_alarms_big, null);
        return controller;
    }

    @Provides
    @SysUISingleton
    static KeyguardMediaViewController provideKeyguardMediaViewController(
            Context context,
            NotificationMediaManager mediaManager,
            @Named(SmartspaceModule.GLANCEABLE_HUB_SMARTSPACE_DATA_PLUGIN)
                    BcSmartspaceDataPlugin plugin,
            UserTracker userTracker,
            @Main DelayableExecutor uiExecutor) {
        KeyguardMediaViewController controller = new KeyguardMediaViewController(
                context, mediaManager, plugin, userTracker, uiExecutor);
        controller.mediaComponent = new ComponentName(context, KeyguardMediaViewController.class);
        return controller;
    }

    @Provides
    @SysUISingleton
    static BcSmartspaceDataPlugin provideBcSmartspaceDataPlugin() {
        return new BcSmartspaceDataProvider();
    }

    @Provides
    @SysUISingleton
    @Named(SmartspaceModule.DATE_SMARTSPACE_DATA_PLUGIN)
    static BcSmartspaceDataPlugin provideDateSmartspaceDataPlugin() {
        return new DateSmartspaceDataProvider();
    }

    @Provides
    @SysUISingleton
    @Named(SmartspaceModule.GLANCEABLE_HUB_SMARTSPACE_DATA_PLUGIN)
    static BcSmartspaceDataPlugin provideGlanceableHubSmartspaceDataPlugin() {
        return new BcSmartspaceDataProvider();
    }

    @Provides
    @SysUISingleton
    @Named(SmartspaceModule.WEATHER_SMARTSPACE_DATA_PLUGIN)
    static BcSmartspaceDataPlugin provideWeatherSmartspaceDataPlugin() {
        return new WeatherSmartspaceDataProvider();
    }

    @Provides
    @SysUISingleton
    static DateSmartspaceDataProvider provideDateSmartspaceDataProvider() {
        return new DateSmartspaceDataProvider();
    }

    @Provides
    @SysUISingleton
    static WeatherSmartspaceDataProvider provideWeatherSmartspaceDataProvider() {
        return new WeatherSmartspaceDataProvider();
    }
}
