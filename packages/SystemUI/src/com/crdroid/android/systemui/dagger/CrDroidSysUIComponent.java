package com.crdroid.android.systemui.dagger;

import com.android.systemui.dagger.DefaultComponentBinder;
import com.android.systemui.dagger.DependencyProvider;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.SystemUIBinder;
import com.android.systemui.dagger.SysUIComponent;
import com.android.systemui.dagger.SystemUIModule;

import com.crdroid.android.systemui.columbus.ColumbusModule;
import com.crdroid.android.systemui.keyguard.CrDroidKeyguardSliceProvider;
import com.crdroid.android.systemui.elmyra.ElmyraModule;
import com.crdroid.android.systemui.smartspace.KeyguardSmartspaceController;

import dagger.Subcomponent;

@SysUISingleton
@Subcomponent(modules = {
        ColumbusModule.class,
        DefaultComponentBinder.class,
        DependencyProvider.class,
        ElmyraModule.class,
        CrDroidSystemUIBinder.class,
        SystemUIModule.class,
        CrDroidSystemUIModule.class})
public interface CrDroidSysUIComponent extends SysUIComponent {
    @SysUISingleton
    @Subcomponent.Builder
    interface Builder extends SysUIComponent.Builder {
        CrDroidSysUIComponent build();
    }

    /**
     * Member injection into the supplied argument.
     */
    void inject(CrDroidKeyguardSliceProvider keyguardSliceProvider);

    @SysUISingleton
    KeyguardSmartspaceController createKeyguardSmartspaceController();
}
