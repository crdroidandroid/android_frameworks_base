package com.android.systemui.volume.dialog.appvolume.ui.viewmodel

import com.android.systemui.volume.dialog.appvolume.domain.VolumeDialogAppVolumeButtonInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** ViewModel for managing the app volume button in the volume dialog. */
class VolumeDialogAppVolumeButtonViewModel
@Inject
constructor(private val interactor: VolumeDialogAppVolumeButtonInteractor) {
    val isVisible: StateFlow<Boolean> = interactor.isVisible

    fun onButtonClicked() {
        interactor.onButtonClicked()
    }
}
