package com.android.systemui.axdynamicbar.data.source

import android.hardware.biometrics.BiometricSourceType
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class BiometricIslandManager
@Inject
constructor(private val keyguardUpdateMonitor: KeyguardUpdateMonitor) {
    private var listening = false
    private val _biometricEvent = MutableStateFlow<IslandEvent.BiometricUnlock?>(null)
    val biometricEvent: StateFlow<IslandEvent.BiometricUnlock?> = _biometricEvent.asStateFlow()

    var onBiometricUnlock: ((IslandEvent.BiometricUnlock) -> Unit)? = null

    private val callback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onBiometricAuthenticated(
                userId: Int,
                biometricSourceType: BiometricSourceType,
                isStrongBiometric: Boolean,
            ) {
                val name =
                    when (biometricSourceType) {
                        BiometricSourceType.FINGERPRINT -> "Fingerprint"
                        BiometricSourceType.FACE -> "Face"
                        BiometricSourceType.IRIS -> "Iris"
                        else -> "Biometric"
                    }
                val event =
                    IslandEvent.BiometricUnlock(
                        sourceType = biometricSourceType.ordinal,
                        sourceName = name,
                    )
                _biometricEvent.value = event
                onBiometricUnlock?.invoke(event)
            }
        }

    fun startListening() {
        if (listening) return
        listening = true
        keyguardUpdateMonitor.registerCallback(callback)
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        keyguardUpdateMonitor.removeCallback(callback)
        _biometricEvent.value = null
    }

    fun clear() {
        _biometricEvent.value = null
    }
}

