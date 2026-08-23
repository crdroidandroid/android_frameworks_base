package com.android.systemui.volume.panel.component.appvolume.ui.viewmodel

import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.volume.VolumePanelDialogReceiver
import com.android.systemui.volume.panel.component.appvolume.domain.interactor.AppVolumePanelGlobalStateInteractor
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AppVolumeSliderViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/** Controls the dedicated per-app volume bottom sheet. */
class AppVolumePanelViewModel(
    private val scope: CoroutineScope,
    audioVolumeInteractor: AudioVolumeInteractor,
    private val appVolumeSliderViewModelFactory: AppVolumeSliderViewModel.Factory,
    private val activityStarter: ActivityStarter,
    private val appVolumePanelGlobalStateInteractor: AppVolumePanelGlobalStateInteractor,
    broadcastDispatcher: BroadcastDispatcher,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    val sliderViewModels: StateFlow<List<AppVolumeSliderViewModel>> =
        audioVolumeInteractor.appVolumeSessions
            .map { sessions -> sessions.filter { it.isActive }.map { it.packageName }.distinct() }
            .distinctUntilChanged()
            .transformLatest { packageNames ->
                coroutineScope {
                    emit(
                        packageNames.map { packageName ->
                            appVolumeSliderViewModelFactory.create(packageName, this)
                        }
                    )
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        broadcastDispatcher
            .broadcastFlow(IntentFilter(VolumePanelDialogReceiver.DISMISS_ACTION))
            .onEach { onDoneClicked() }
            .launchIn(scope)
    }

    fun onDoneClicked() {
        appVolumePanelGlobalStateInteractor.setVisible(false)
    }

    fun onSettingsClicked() {
        activityStarter.startActivityDismissingKeyguard(
            /* intent = */ Intent(Settings.ACTION_SOUND_SETTINGS),
            /* onlyProvisioned = */ false,
            /* dismissShade = */ true,
            /* disallowEnterPictureInPictureWhileLaunching = */ false,
            /* callback = */ { onDoneClicked() },
            /* flags = */ Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            /* animationController = */ null,
            /* userHandle = */ null,
        )
    }

    class Factory
    @Inject
    constructor(
        private val audioVolumeInteractor: AudioVolumeInteractor,
        private val appVolumeSliderViewModelFactory: AppVolumeSliderViewModel.Factory,
        private val activityStarter: ActivityStarter,
        private val appVolumePanelGlobalStateInteractor: AppVolumePanelGlobalStateInteractor,
        private val broadcastDispatcher: BroadcastDispatcher,
    ) {
        fun create(coroutineScope: CoroutineScope): AppVolumePanelViewModel {
            return AppVolumePanelViewModel(
                coroutineScope,
                audioVolumeInteractor,
                appVolumeSliderViewModelFactory,
                activityStarter,
                appVolumePanelGlobalStateInteractor,
                broadcastDispatcher,
            )
        }
    }
}
