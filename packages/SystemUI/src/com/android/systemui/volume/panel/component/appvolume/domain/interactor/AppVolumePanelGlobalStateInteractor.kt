package com.android.systemui.volume.panel.component.appvolume.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.volume.panel.component.appvolume.shared.model.AppVolumePanelGlobalState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@SysUISingleton
class AppVolumePanelGlobalStateInteractor @Inject constructor() {

    private val mutableGlobalState = MutableStateFlow(AppVolumePanelGlobalState())
    val globalState: StateFlow<AppVolumePanelGlobalState> = mutableGlobalState.asStateFlow()

    fun setVisible(isVisible: Boolean) {
        mutableGlobalState.update { it.copy(isVisible = isVisible) }
    }
}
