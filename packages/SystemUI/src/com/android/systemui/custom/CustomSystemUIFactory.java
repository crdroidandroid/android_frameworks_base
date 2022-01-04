package com.android.systemui.custom;

import android.content.Context;

import com.android.systemui.custom.dagger.DaggerCustomGlobalRootComponent;
import com.android.systemui.custom.dagger.CustomGlobalRootComponent;

import com.android.systemui.dagger.GlobalRootComponent;
import com.android.systemui.SystemUIFactory;

public class CustomSystemUIFactory extends SystemUIFactory {
    @Override
    protected GlobalRootComponent buildGlobalRootComponent(Context context) {
        return DaggerCustomGlobalRootComponent.builder()
                .context(context)
                .build();
    }
}
