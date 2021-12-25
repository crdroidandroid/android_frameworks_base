package com.crdroid.android.systemui.gamedashboard;

import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.logging.UiEventLogger;

import com.android.wm.shell.tasksurfacehelper.TaskSurfaceHelper;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.navigationbar.NavigationBarOverlayController;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.screenrecord.RecordingController;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.statusbar.policy.ConfigurationController;

import com.google.android.systemui.gamedashboard.GameMenuActivity;
import com.google.android.systemui.gamedashboard.EntryPointController;
import com.google.android.systemui.gamedashboard.FpsController;
import com.google.android.systemui.gamedashboard.GameDashboardUiEventLogger;
import com.google.android.systemui.gamedashboard.GameModeDndController;
import com.google.android.systemui.gamedashboard.ScreenRecordController;
import com.google.android.systemui.gamedashboard.ShortcutBarController;
import com.google.android.systemui.gamedashboard.ToastController;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

import java.util.Optional;
import java.util.concurrent.Executor;

@Module
public abstract class GameDashboardModule {

    @Binds
    abstract NavigationBarOverlayController bindEntryPointController(EntryPointController entryPointController);


    @Provides
    @SysUISingleton
    static GameMenuActivity provideGameMenuActivity(Context context, EntryPointController entryPointController, ActivityStarter activityStarter, ShortcutBarController shortcutBarController, GameModeDndController gameModeDndController, LayoutInflater layoutInflater, Handler handler, GameDashboardUiEventLogger gameDashboardUiEventLogger) {
        return new GameMenuActivity(context, entryPointController, activityStarter, shortcutBarController, gameModeDndController, layoutInflater, handler, gameDashboardUiEventLogger);
    }

    @Provides
    @SysUISingleton
    static EntryPointController provideEntryPointController(Context context, AccessibilityManager accessibilityManager, BroadcastDispatcher broadcastDispatcher, CommandQueue commandQueue, GameModeDndController gameModeDndController, Handler handler, NavigationModeController navigationModeController, OverviewProxyService overviewProxyService, PackageManager packageManager, ShortcutBarController shortcutBarController, ToastController toastController, GameDashboardUiEventLogger gameDashboardUiEventLogger, Optional<TaskSurfaceHelper> optional) {
        return new EntryPointController(context, accessibilityManager, broadcastDispatcher, commandQueue, gameModeDndController, handler, navigationModeController, overviewProxyService, packageManager, shortcutBarController, toastController, gameDashboardUiEventLogger, optional);
    }

    @Provides
    @SysUISingleton
    static ShortcutBarController provideShortcutBarController(Context context, WindowManager windowManager, FpsController fpsController, ConfigurationController configurationController, Handler handler, ScreenRecordController screenRecordController, Optional<TaskSurfaceHelper> optional, GameDashboardUiEventLogger gameDashboardUiEventLogger, ToastController toastController) {
        return new ShortcutBarController(context, windowManager, fpsController, configurationController, handler, screenRecordController, optional, gameDashboardUiEventLogger, toastController);
    }

    @Provides
    @SysUISingleton
    static FpsController provideFpsController(@Main Executor executor) {
        return new FpsController(executor);
    }

    @Provides
    @SysUISingleton
    static GameDashboardUiEventLogger provideGameDashboardUiEventLogger(UiEventLogger uiEventLogger) {
        return new GameDashboardUiEventLogger(uiEventLogger);
    }

    @Provides
    @SysUISingleton
    static GameModeDndController provideGameModeDndController(Context context, NotificationManager notificationManager, BroadcastDispatcher broadcastDispatcher) {
        return new GameModeDndController(context, notificationManager, broadcastDispatcher);
    }

    @Provides
    @SysUISingleton
    static ScreenRecordController provideScreenRecordController(RecordingController recordingController, Handler handler, KeyguardDismissUtil keyguardDismissUtil, UserContextProvider userContextProvider, ToastController toastController) {
        return new ScreenRecordController(recordingController, handler, keyguardDismissUtil, userContextProvider, toastController);
    }

    @Provides
    @SysUISingleton
    static ToastController provideToastController(Context context, ConfigurationController configurationController, WindowManager windowManager, UiEventLogger uiEventLogger, NavigationModeController navigationModeController) {
        return new ToastController(context, configurationController, windowManager, uiEventLogger, navigationModeController);
    }

}
