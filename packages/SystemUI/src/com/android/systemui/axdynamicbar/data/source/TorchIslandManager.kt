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
    private var isRetainingOffFromSlider = false

    val isEnabled: Boolean
        get() = flashlightController.isEnabled

    private val listener =
        object : FlashlightController.FlashlightListener {
            override fun onFlashlightChanged(enabled: Boolean) {
                if (enabled) {
                    isRetainingOffFromSlider = false
                    _torchEvent.value = IslandEvent.Torch()
                    startLevelObserver()
                } else {
                    if (isRetainingOffFromSlider) {
                        // Keep the event so the user can slide it back up
                    } else {
                        clear()
                    }
                }
            }

            override fun onFlashlightError() {
                clear()
            }

            override fun onFlashlightAvailabilityChanged(available: Boolean) {
                if (!available) {
                    clear()
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
                            } else {
                                if (isRetainingOffFromSlider) {
                                    _torchEvent.value =
                                        IslandEvent.Torch(level = 0, maxLevel = model.max)
                                } else {
                                    _torchEvent.value = null
                                }
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
            isRetainingOffFromSlider = false
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
        isRetainingOffFromSlider = false
        stopLevelObserver()
        _torchEvent.value = null
    }

    fun toggleTorch() {
        isRetainingOffFromSlider = false
        val enabled = !flashlightController.isEnabled
        if (FlashlightStrength.isEnabled) {
            flashlightInteractor.setEnabled(enabled)
        } else {
            flashlightController.setFlashlight(enabled)
        }
    }

    fun setLevel(level: Int) {
        if (FlashlightStrength.isEnabled) {
            setSliderLevel(level, persist = true)
        }
    }

    fun setLevelTemporary(level: Int) {
        if (FlashlightStrength.isEnabled) {
            setSliderLevel(level, persist = false)
        }
    }

    private fun setSliderLevel(level: Int, persist: Boolean) {
        if (level == 0) {
            isRetainingOffFromSlider = true
            flashlightInteractor.setEnabled(false)
            return
        }

        isRetainingOffFromSlider = false
        if (shouldEnableTorchForCurrentLevel(level)) {
            flashlightInteractor.setEnabled(true)
        } else if (persist) {
            flashlightInteractor.setLevel(level)
        } else {
            flashlightInteractor.setTemporaryLevel(level)
        }
    }

    private fun shouldEnableTorchForCurrentLevel(level: Int): Boolean {
        if (flashlightController.isEnabled) return false
        // setLevel() enables the torch unless this matches the disabled state's remembered level.
        val currentLevel =
            (flashlightInteractor.state.value as? FlashlightModel.Available.Level)?.level
                ?: return false
        return currentLevel == level
    }
}
