package com.android.systemui.axdynamicbar.data.source

import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flashlight.domain.interactor.FlashlightInteractor
import com.android.systemui.flashlight.flags.FlashlightStrength
import com.android.systemui.flashlight.shared.model.FlashlightModel
import com.android.systemui.statusbar.policy.FlashlightController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SysUISingleton
class TorchIslandManager
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val flashlightController: FlashlightController,
    private val flashlightInteractor: FlashlightInteractor,
) {
    private val _torchEvent = MutableStateFlow<IslandEvent.Torch?>(null)
    val torchEvent: StateFlow<IslandEvent.Torch?> = _torchEvent.asStateFlow()

    private var listening = false
    private var levelJob: Job? = null

    private val listener =
        object : FlashlightController.FlashlightListener {
            override fun onFlashlightChanged(enabled: Boolean) {
                if (enabled) {
                    _torchEvent.value = IslandEvent.Torch()
                    startLevelObserver()
                } else {
                    stopLevelObserver()
                    _torchEvent.value = null
                }
            }

            override fun onFlashlightError() {
                stopLevelObserver()
                _torchEvent.value = null
            }

            override fun onFlashlightAvailabilityChanged(available: Boolean) {
                if (!available) {
                    stopLevelObserver()
                    _torchEvent.value = null
                }
            }
        }

    private fun startLevelObserver() {
        if (!FlashlightStrength.isEnabled) return
        levelJob?.cancel()
        levelJob =
            scope.launch {
                flashlightInteractor.state.collect { model ->
                    when (model) {
                        is FlashlightModel.Available.Level -> {
                            if (model.enabled) {
                                _torchEvent.value =
                                    IslandEvent.Torch(level = model.level, maxLevel = model.max)
                            }
                        }
                        is FlashlightModel.Available.Binary -> {
                            _torchEvent.value =
                                if (model.enabled) IslandEvent.Torch() else null
                        }
                        is FlashlightModel.Unavailable -> {
                            _torchEvent.value = null
                        }
                    }
                }
            }
    }

    private fun stopLevelObserver() {
        levelJob?.cancel()
        levelJob = null
    }

    fun startListening() {
        if (listening) return
        listening = true
        flashlightController.addCallback(listener)
        if (flashlightController.isEnabled) {
            _torchEvent.value = IslandEvent.Torch()
            startLevelObserver()
        }
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        flashlightController.removeCallback(listener)
        stopLevelObserver()
        _torchEvent.value = null
    }

    fun clear() {
        stopLevelObserver()
        _torchEvent.value = null
    }

    fun toggleTorch() {
        val enabled = !flashlightController.isEnabled
        if (FlashlightStrength.isEnabled) {
            flashlightInteractor.setEnabled(enabled)
        } else {
            flashlightController.setFlashlight(enabled)
        }
    }

    fun setLevel(level: Int) {
        if (FlashlightStrength.isEnabled) flashlightInteractor.setLevel(level)
    }

    fun setLevelTemporary(level: Int) {
        if (FlashlightStrength.isEnabled) flashlightInteractor.setTemporaryLevel(level)
    }
}

