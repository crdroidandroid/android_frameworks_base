package com.crdroid.android.systemui.smartspace

import android.content.ComponentName
import android.content.Context

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.FeatureFlags

import com.google.android.systemui.smartspace.KeyguardMediaViewController
import com.google.android.systemui.smartspace.KeyguardZenAlarmViewController

import javax.inject.Inject

@SysUISingleton
class KeyguardSmartspaceController @Inject constructor(
    private val context: Context,
    private val featureFlags: FeatureFlags,
    private val zenController: KeyguardZenAlarmViewController,
    private val mediaController: KeyguardMediaViewController,
) {
    init {
        if (!featureFlags.isSmartspaceEnabled()) {
            context.packageManager.setComponentEnabledSetting(ComponentName("com.android.systemui", "com.crdroid.android.systemui.keyguard.CrDroidKeyguardSliceProvider"), 1, 1)
        } else {
            mediaController.init()
            zenController.init()
        }
    }
}
