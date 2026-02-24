package com.android.systemui.axdynamicbar.ui

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

@SysUISingleton
class AxDynamicBarManager
@Inject
constructor(
    private val viewModel: AxDynamicBarChipViewModel,
    private val expandedPanel: AxDynamicBarExpandedPanel,
) : CoreStartable {

    override fun start() {
        viewModel.interactor.init()
        expandedPanel.init()
    }
}

