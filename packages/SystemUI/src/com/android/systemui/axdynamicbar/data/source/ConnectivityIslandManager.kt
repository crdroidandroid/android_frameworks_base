package com.android.systemui.axdynamicbar.data.source

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SysUISingleton
class ConnectivityIslandManager
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val bluetoothController: BluetoothController,
    private val hotspotController: HotspotController,
    private val vpnInteractor: VpnInteractor,
    @Application private val context: Context,
) {
    companion object {
        private const val TAG = "ConnectivityIslandManager"
        private const val DEVICE_IMAGE_SIZE_PX = 256
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
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var imageDevice: BluetoothDevice? = null
    private var imageUri: Uri? = null
    private var imageJob: Job? = null
    private var metadataListenerRegistered = false
    private val metadataListener =
        BluetoothAdapter.OnMetadataChangedListener { device, key, _ ->
            if (btListening && device == imageDevice) {
                when (key) {
                    BluetoothDevice.METADATA_MAIN_ICON -> loadDeviceImage(device, force = true)
                    BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY,
                    BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY,
                    BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY -> refreshDeviceBatteries(device)
                }
            }
        }

    private val bluetoothCallback =
        object : BluetoothController.Callback {
            override fun onBluetoothStateChange(enabled: Boolean) {
                if (!btListening) return
                if (!enabled) {
                    previousBtAddresses.clear()
                    clearBluetooth()
                }
            }

            override fun onBluetoothDevicesChanged() {
                if (!btListening) return
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
                    _bluetoothEvent.value =
                        IslandEvent.Bluetooth(
                            deviceName = device.getName() ?: "Unknown Device",
                            batteryLevel = device.getBatteryLevel(),
                            address = device.getAddress(),
                            deviceIcon = iconPair?.first,
                            deviceTypeLabel = iconPair?.second ?: "",
                        )
                    watchDeviceImage(device.device)
                } else {
                    val current = _bluetoothEvent.value
                    if (current != null && current.address !in currentAddresses) {
                        clearBluetooth()
                    } else if (current != null) {
                        devices.firstOrNull { it.getAddress() == current.address }?.let { device ->
                            _bluetoothEvent.value =
                                current.copy(
                                    deviceName = device.getName() ?: current.deviceName,
                                    batteryLevel = device.getBatteryLevel(),
                                )
                            refreshDeviceBatteries(device.device)
                            loadDeviceImage(device.device)
                        }
                    }
                }
            }
        }

    private fun watchDeviceImage(device: BluetoothDevice) {
        stopDeviceImage()
        imageDevice = device
        try {
            metadataListenerRegistered =
                bluetoothAdapter?.addOnMetadataChangedListener(
                    device,
                    context.mainExecutor,
                    metadataListener,
                ) == true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to observe Bluetooth device metadata", e)
        }
        refreshDeviceBatteries(device)
        loadDeviceImage(device)
    }

    private fun refreshDeviceBatteries(device: BluetoothDevice) {
        if (!btListening || device != imageDevice) return
        val current = _bluetoothEvent.value ?: return
        if (current.address != device.address) return
        _bluetoothEvent.value =
            current.copy(
                leftBatteryLevel =
                    readBatteryMetadata(device, BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY),
                rightBatteryLevel =
                    readBatteryMetadata(device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY),
                caseBatteryLevel =
                    readBatteryMetadata(device, BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY),
            )
    }

    private fun readBatteryMetadata(device: BluetoothDevice, key: Int): Int? =
        try {
            device
                .getMetadata(key)
                ?.toString(Charsets.UTF_8)
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it in 0..100 }
        } catch (_: RuntimeException) {
            null
        }

    private fun loadDeviceImage(device: BluetoothDevice, force: Boolean = false) {
        if (!btListening || device != imageDevice) return
        val uri =
            try {
                device
                    .getMetadata(BluetoothDevice.METADATA_MAIN_ICON)
                    ?.toString(Charsets.UTF_8)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Uri::parse)
                    ?.takeIf { it.scheme == "content" || it.scheme == "android.resource" }
            } catch (_: RuntimeException) {
                null
            }
        if (
            !force &&
                uri == imageUri &&
                (imageJob?.isActive == true || _bluetoothEvent.value?.deviceImage != null)
        ) {
            return
        }
        imageJob?.cancel()
        imageUri = uri
        _bluetoothEvent.value = _bluetoothEvent.value?.copy(deviceImage = null)
        if (uri == null) return
        imageJob =
            applicationScope.launch(Dispatchers.Main.immediate) {
                val artwork =
                    withContext(backgroundDispatcher) {
                        try {
                            if (uri.scheme == "content") {
                                try {
                                    context.contentResolver.takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
                                } catch (_: SecurityException) {}
                            }
                            val source = ImageDecoder.createSource(context.contentResolver, uri)
                            val bitmap =
                                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                                    val largest = maxOf(info.size.width, info.size.height)
                                    if (largest > DEVICE_IMAGE_SIZE_PX) {
                                        val scale = DEVICE_IMAGE_SIZE_PX.toFloat() / largest
                                        decoder.setTargetSize(
                                            (info.size.width * scale).toInt().coerceAtLeast(1),
                                            (info.size.height * scale).toInt().coerceAtLeast(1),
                                        )
                                    }
                                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                }
                            BitmapDrawable(context.resources, bitmap)
                        } catch (e: Exception) {
                            Log.w(TAG, "Unable to load Bluetooth device artwork", e)
                            null
                        }
                    }
                val current = _bluetoothEvent.value
                if (
                    btListening &&
                        imageDevice == device &&
                        imageUri == uri &&
                        current != null &&
                        current.address == device.address
                ) {
                    _bluetoothEvent.value = current.copy(deviceImage = artwork)
                }
            }
    }

    private fun stopDeviceImage() {
        imageJob?.cancel()
        imageJob = null
        imageDevice?.let { device ->
            if (metadataListenerRegistered) {
                try {
                    bluetoothAdapter?.removeOnMetadataChangedListener(device, metadataListener)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Unable to remove Bluetooth metadata listener", e)
                }
            }
        }
        metadataListenerRegistered = false
        imageDevice = null
        imageUri = null
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
        clearBluetooth()
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
            clearBluetooth()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disconnect Bluetooth", e)
        }
    }

    fun clearBluetooth() {
        stopDeviceImage()
        _bluetoothEvent.value = null
    }

    fun clearHotspot() {
        _hotspotEvent.value = null
    }

    fun clearVpn() {
        _vpnEvent.value = null
    }
}
