package com.android.systemui.volume.panel.component.appvolume.ui.viewmodel

import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.plugins.activityStarter
import com.android.systemui.volume.domain.interactor.audioVolumeInteractor
import com.android.systemui.volume.panel.component.appvolume.domain.interactor.appVolumePanelGlobalStateInteractor
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.appVolumeSliderViewModelFactory

var Kosmos.appVolumePanelViewModel: AppVolumePanelViewModel by
    Kosmos.Fixture { appVolumePanelViewModelFactory.create(applicationCoroutineScope) }

val Kosmos.appVolumePanelViewModelFactory: AppVolumePanelViewModel.Factory by
    Kosmos.Fixture {
        AppVolumePanelViewModel.Factory(
            audioVolumeInteractor,
            appVolumeSliderViewModelFactory,
            activityStarter,
            appVolumePanelGlobalStateInteractor,
            broadcastDispatcher,
        )
    }
