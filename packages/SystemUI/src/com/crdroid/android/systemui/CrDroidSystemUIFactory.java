package com.crdroid.android.systemui;

import android.content.Context;
import android.content.res.AssetManager;

import com.crdroid.android.systemui.dagger.CrDroidGlobalRootComponent;
import com.crdroid.android.systemui.dagger.CrDroidSysUIComponent;
import com.crdroid.android.systemui.dagger.DaggerCrDroidGlobalRootComponent;

import com.android.systemui.SystemUIFactory;
import com.android.systemui.dagger.GlobalRootComponent;
import com.android.systemui.navigationbar.gestural.BackGestureTfClassifierProvider;
import com.android.systemui.screenshot.ScreenshotNotificationSmartActionsProvider;

import com.google.android.systemui.gesture.BackGestureTfClassifierProviderGoogle;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

public class CrDroidSystemUIFactory extends SystemUIFactory {
    @Override
    protected GlobalRootComponent buildGlobalRootComponent(Context context) {
        return DaggerCrDroidGlobalRootComponent.builder()
                .context(context)
                .build();
    }

    @Override
    public BackGestureTfClassifierProvider createBackGestureTfClassifierProvider(AssetManager am, String modelName) {
        return new BackGestureTfClassifierProviderGoogle(am, modelName);
    }

    @Override
    public void init(Context context, boolean fromTest) throws ExecutionException, InterruptedException {
        super.init(context, fromTest);
        if (shouldInitializeComponents()) {
            ((CrDroidSysUIComponent) getSysUIComponent()).createKeyguardSmartspaceController();
        }
    }
}
