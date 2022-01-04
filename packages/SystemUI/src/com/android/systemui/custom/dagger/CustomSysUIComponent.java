package com.android.systemui.custom.dagger;

import com.android.systemui.dagger.DefaultComponentBinder;
import com.android.systemui.dagger.DependencyProvider;
import com.android.systemui.dagger.SystemUIBinder;
import com.android.systemui.dagger.SystemUIModule;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.SysUIComponent;

import dagger.Subcomponent;

@SysUISingleton
@Subcomponent(modules = {
        DefaultComponentBinder.class,
        DependencyProvider.class,
        SystemUIBinder.class,
        SystemUIModule.class,
        CustomSystemUIModule.class})
public interface CustomSysUIComponent extends SysUIComponent {
    @SysUISingleton
    @Subcomponent.Builder
    interface Builder extends SysUIComponent.Builder {
        CustomSysUIComponent build();
    }
}
