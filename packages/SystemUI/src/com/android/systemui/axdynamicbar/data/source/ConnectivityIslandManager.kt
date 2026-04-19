package com.android.systemui.axdynamicbar.data.source

import android.util.Log
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.HotspotController
import com.android.systemui.statusbar.policy.vpn.domain.interactor.VpnInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SysUISingleton
class ConnectivityIslandManager
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val bluetoothController: BluetoothController,
    private val hotspotController: HotspotController,
    private val vpnInteractor: VpnInteractor,
) {
    companion object {
        private const val TAG = "ConnectivityIslandManager"
    }

    private val _bluetoothEvent = MutableStateFlow<IslandEvent.Bluetooth?>(null)
    val bluetoothEvent: StateFlow<IslandEvent.Bluetooth?> = _bluetoothEvent.asStateFlow()

    private val _hotspotEvent = MutableStateFlow<IslandEvent.Hotspot?>(null)
    val hotspotEvent: StateFlow<IslandEvent.Hotspot?> = _hotspotEvent.asStateFlow()

    private val _vpnEvent = MutableStateFlow<IslandEvent.Vpn?>(null)
    val vpnEvent: StateFlow<IslandEvent.Vpn?> = _vpnEvent.asStateFlow()

    private val previousBtAddresses = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var listening = false
    private var wasVpnEnabled = false
    private var vpnJob: Job? = null

    private val bluetoothCallback =
        object : BluetoothController.Callback {
            override fun onBluetoothStateChange(enabled: Boolean) {
                if (!enabled) {
                    previousBtAddresses.clear()
                    _bluetoothEvent.value = null
                }
            }

            override fun onBluetoothDevicesChanged() {
                val devices = bluetoothController.connectedDevices
                val currentAddresses = devices.map { it.getAddress() }.toSet()
                val newlyConnected = devices.filter { it.getAddress() !in previousBtAddresses }
                previousBtAddresses.clear()
                previousBtAddresses.addAll(currentAddresses)

                if (newlyConnected.isNotEmpty()) {
                    val device = newlyConnected.first()
                    val iconPair =
                        try {
                            device.getDrawableWithDescription()
                        } catch (_: Exception) {
                            null
                        }
                    val event =
                        IslandEvent.Bluetooth(
                            deviceName = device.getName() ?: "Unknown Device",
                            batteryLevel = device.getBatteryLevel(),
                            address = device.getAddress(),
                            deviceIcon = iconPair?.first,
                            deviceTypeLabel = iconPair?.second ?: "",
                        )
                    _bluetoothEvent.value = event
                } else {

                    val current = _bluetoothEvent.value
                    if (current != null && current.address !in currentAddresses) {
                        _bluetoothEvent.value = null
                    }
                }
            }
        }

    private val hotspotCallback =
        object : HotspotController.Callback {
            override fun onHotspotChanged(enabled: Boolean, numDevices: Int) {
                if (enabled) {
                    _hotspotEvent.value = IslandEvent.Hotspot(numDevices = numDevices)
                } else {
                    _hotspotEvent.value = null
                }
            }
        }

    private fun startVpnListener() {
        vpnJob?.cancel()
        vpnJob =
            applicationScope.launch(backgroundDispatcher) {
                vpnInteractor.vpnState.collect { state ->
                    if (state.isEnabled) {
                        val existing = _vpnEvent.value
                        _vpnEvent.value =
                            if (existing != null) {
                                existing.copy(
                                    isBranded = state.isBranded,
                                    isValidated = state.isValidated,
                                )
                            } else {
                                IslandEvent.Vpn(
                                    isBranded = state.isBranded,
                                    isValidated = state.isValidated,
                                )
                            }
                    } else {
                        _vpnEvent.value = null
                    }
                    wasVpnEnabled = state.isEnabled
                }
            }
    }

    private var btListening = false
    private var hotspotListening = false
    private var vpnListening = false

    fun startBluetooth() {
        if (btListening) return
        btListening = true
        previousBtAddresses.clear()
        bluetoothController.connectedDevices.forEach { previousBtAddresses.add(it.getAddress()) }
        bluetoothController.addCallback(bluetoothCallback)
    }

    fun stopBluetooth() {
        if (!btListening) return
        btListening = false
        bluetoothController.removeCallback(bluetoothCallback)
        previousBtAddresses.clear()
        _bluetoothEvent.value = null
    }

    fun startHotspot() {
        if (hotspotListening) return
        hotspotListening = true
        if (hotspotController.isHotspotEnabled) {
            _hotspotEvent.value = IslandEvent.Hotspot(hotspotController.getNumConnectedDevices())
        }
        hotspotController.addCallback(hotspotCallback)
    }

    fun stopHotspot() {
        if (!hotspotListening) return
        hotspotListening = false
        hotspotController.removeCallback(hotspotCallback)
        _hotspotEvent.value = null
    }

    fun startVpn() {
        if (vpnListening) return
        vpnListening = true
        wasVpnEnabled = false
        startVpnListener()
    }

    fun stopVpn() {
        if (!vpnListening) return
        vpnListening = false
        vpnJob?.cancel()
        vpnJob = null
        _vpnEvent.value = null
    }

    fun startListening() {
        if (listening) return
        listening = true
        startBluetooth()
        startHotspot()
        startVpn()
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        stopBluetooth()
        stopHotspot()
        stopVpn()
    }

    fun disconnectBluetooth(address: String) {
        if (address.isEmpty()) return
        try {
            bluetoothController.connectedDevices.find { it.getAddress() == address }?.disconnect()
            _bluetoothEvent.value = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disconnect Bluetooth", e)
        }
    }

    fun clearBluetooth() {
        _bluetoothEvent.value = null
    }

    fun clearHotspot() {
        _hotspotEvent.value = null
    }

    fun clearVpn() {
        _vpnEvent.value = null
    }
}

