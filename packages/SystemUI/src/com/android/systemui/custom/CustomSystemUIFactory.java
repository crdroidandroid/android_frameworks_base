package com.android.systemui.custom;

import android.content.Context;
import android.content.res.AssetManager;

import com.android.systemui.custom.dagger.DaggerCustomGlobalRootComponent;
import com.android.systemui.custom.dagger.CustomGlobalRootComponent;
import com.android.systemui.custom.dagger.CustomSysUIComponent;
import com.android.systemui.dagger.GlobalRootComponent;
import com.android.systemui.navigationbar.gestural.BackGestureTfClassifierProvider;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.screenshot.ScreenshotNotificationSmartActionsProvider;
import com.google.android.systemui.gesture.BackGestureTfClassifierProviderGoogle;

import java.util.concurrent.ExecutionException;

public class CustomSystemUIFactory extends SystemUIFactory {
    @Override
    protected GlobalRootComponent buildGlobalRootComponent(Context context) {
        return DaggerCustomGlobalRootComponent.builder()
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
            ((CustomSysUIComponent) getSysUIComponent()).createKeyguardSmartspaceController();
        }
    }
}
