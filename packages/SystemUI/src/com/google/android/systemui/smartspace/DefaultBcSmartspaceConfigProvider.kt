package com.google.android.systemui.smartspace

import com.android.systemui.plugins.BcSmartspaceConfigPlugin

class DefaultBcSmartspaceConfigProvider : BcSmartspaceConfigPlugin {
    override val isDefaultDateWeatherDisabled: Boolean
        get() = true

    override val isViewPager2Enabled: Boolean
        get() = false

    override val isSwipeEventLoggingEnabled: Boolean
        get() = false
}
