package com.crdroid.android.systemui;

import android.content.Context;

import com.crdroid.android.systemui.dagger.CrDroidGlobalRootComponent;
import com.crdroid.android.systemui.dagger.DaggerCrDroidGlobalRootComponent;

import com.android.systemui.SystemUIFactory;
import com.android.systemui.dagger.GlobalRootComponent;

public class CrDroidSystemUIFactory extends SystemUIFactory {
    @Override
    protected GlobalRootComponent buildGlobalRootComponent(Context context) {
        return DaggerCrDroidGlobalRootComponent.builder()
                .context(context)
                .build();
    }
}
