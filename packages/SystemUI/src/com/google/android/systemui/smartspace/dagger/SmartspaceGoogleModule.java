package com.google.android.systemui.smartspace.dagger;

import com.android.systemui.dagger.SysUISingleton;

import com.google.android.systemui.smartspace.BcSmartspaceDataProvider;

import dagger.Module;
import dagger.Provides;

@Module
public abstract class SmartspaceGoogleModule {
    @Provides
    @SysUISingleton
    static BcSmartspaceDataProvider provideGlanceableHubBcSmartspaceDataPlugin() {
        return new BcSmartspaceDataProvider();
    }
}
