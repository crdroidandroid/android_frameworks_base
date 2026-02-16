/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.ax

import android.app.UiModeManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.SensorPrivacyManager
import android.hardware.display.ColorDisplayManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.android.internal.util.ScreenshotHelper
import android.media.projection.StopReason
import android.net.TetheringManager
import android.nfc.NfcAdapter
import android.os.PowerManager
import android.os.RemoteException
import android.os.ServiceManager
import android.os.UserHandle
import android.provider.Settings
import android.service.dreams.IDreamManager
import android.util.Log
import com.android.axion.platform.AxPlatformClient
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.connectivity.AccessPointController
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.phone.ManagedProfileController
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.CastController
import com.android.systemui.statusbar.policy.DataSaverController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.HotspotController
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import com.android.systemui.statusbar.policy.LocationController
import com.android.systemui.statusbar.policy.RotationLockController
import com.android.systemui.screenrecord.ScreenRecordUxController
import com.android.systemui.statusbar.policy.SecurityController
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.wifitrackerlib.WifiEntry
import lineageos.app.ProfileManager
import lineageos.hardware.LineageHardwareManager
import lineageos.providers.LineageSettings
import javax.inject.Inject

@SysUISingleton
class AxPlatformFeatureController @Inject constructor(
    private val context: Context,
    private val stateManager: AxPlatformStateManager,
    private val networkController: NetworkController,
    private val accessPointController: AccessPointController,
    private val bluetoothController: BluetoothController,
    private val hotspotController: HotspotController,
    private val flashlightController: FlashlightController,
    private val locationController: LocationController,
    private val rotationLockController: RotationLockController,
    private val batteryController: BatteryController,
    private val zenModeController: ZenModeController,
    private val dataSaverController: DataSaverController,
    private val localBluetoothManager: LocalBluetoothManager?,
    private val sensorPrivacyController: IndividualSensorPrivacyController,
    private val managedProfileController: ManagedProfileController,
    private val securityController: SecurityController,
    private val castController: CastController,
    private val screenRecordUxController: ScreenRecordUxController,
    powerManager: PowerManager
) {

    internal val wakeLock: PowerManager.WakeLock =
        powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "AxPlatform:Caffeine")
    private val profileManager: ProfileManager? = try {
        ProfileManager.getInstance(context)
    } catch (e: Exception) { null }

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)
    private val uiModeManager: UiModeManager = context.getSystemService(UiModeManager::class.java)
    private val colorDisplayManager: ColorDisplayManager =
        context.getSystemService(ColorDisplayManager::class.java)
    private val tetheringManager: TetheringManager? =
        context.getSystemService(TetheringManager::class.java)
    internal val dreamManager: IDreamManager? = try {
        IDreamManager.Stub.asInterface(ServiceManager.getService("dreams"))
    } catch (e: Exception) { null }
    internal val lineageHardware: LineageHardwareManager? = try {
        LineageHardwareManager.getInstance(context)
    } catch (e: Exception) { null }

    private val screenshotHelper = ScreenshotHelper(context)
    private val screenshotHandler = Handler(Looper.getMainLooper())

    var latestAccessPoints: List<WifiEntry> = emptyList()

    val supportedFeatures: Array<String> by lazy {
        buildList {
            addAll(BASE_FEATURES)
            if (nfcAdapter != null)
                add(AxPlatformClient.FEATURE_NFC)
            if (sensorPrivacyController.supportsSensorToggle(SensorPrivacyManager.Sensors.CAMERA))
                add(AxPlatformClient.FEATURE_CAMERA_PRIVACY)
            if (sensorPrivacyController.supportsSensorToggle(SensorPrivacyManager.Sensors.MICROPHONE))
                add(AxPlatformClient.FEATURE_MIC_PRIVACY)
            add(AxPlatformClient.FEATURE_WORK_PROFILE)
            if (tetheringManager?.isTetheringSupported == true)
                add(AxPlatformClient.FEATURE_USB_TETHER)
            if (dreamManager != null)
                add(AxPlatformClient.FEATURE_DREAM)
            if (lineageHardware?.isSupported(LineageHardwareManager.FEATURE_READING_ENHANCEMENT) == true)
                add(AxPlatformClient.FEATURE_READING_MODE)
            if (batteryController.isReverseSupported)
                add(AxPlatformClient.FEATURE_POWER_SHARE)
            add(AxPlatformClient.FEATURE_CAFFEINE)
            add(AxPlatformClient.FEATURE_VPN)
            add(AxPlatformClient.FEATURE_CAST)
            add(AxPlatformClient.FEATURE_SMART_PIXELS)
            if (profileManager != null)
                add(AxPlatformClient.FEATURE_PROFILES)
            add(AxPlatformClient.FEATURE_SCREEN_RECORD)
            add(AxPlatformClient.FEATURE_SCREENSHOT)
        }.toTypedArray()
    }

    fun toggle(feature: String) {
        when (feature) {
            AxPlatformClient.FEATURE_WIFI -> {
                val current = stateManager.getState(feature).getBoolean("enabled", false)
                networkController.setWifiEnabled(!current)
            }
            AxPlatformClient.FEATURE_MOBILE_DATA -> {
                val ctrl = networkController.mobileDataController ?: return
                ctrl.isMobileDataEnabled = !ctrl.isMobileDataEnabled
            }
            AxPlatformClient.FEATURE_BLUETOOTH ->
                bluetoothController.setBluetoothEnabled(!bluetoothController.isBluetoothEnabled)
            AxPlatformClient.FEATURE_HOTSPOT -> {
                val current = stateManager.getState(feature).getBoolean("enabled", false)
                hotspotController.setHotspotEnabled(!current)
            }
            AxPlatformClient.FEATURE_FLASHLIGHT -> {
                if (flashlightController.hasFlashlight())
                    flashlightController.setFlashlight(!flashlightController.isEnabled)
            }
            AxPlatformClient.FEATURE_LOCATION ->
                locationController.setLocationEnabled(!locationController.isLocationEnabled)
            AxPlatformClient.FEATURE_ROTATION ->
                rotationLockController.setRotationLocked(
                    !rotationLockController.isRotationLocked, TAG
                )
            AxPlatformClient.FEATURE_BATTERY_SAVER ->
                batteryController.setPowerSaveMode(!batteryController.isPowerSave)
            AxPlatformClient.FEATURE_ZEN -> {
                val current = zenModeController.zen
                zenModeController.setZen(if (current == 0) 1 else 0, null, TAG)
            }
            AxPlatformClient.FEATURE_DATA_SAVER ->
                dataSaverController.setDataSaverEnabled(!dataSaverController.isDataSaverEnabled)
            AxPlatformClient.FEATURE_AOD ->
                stateManager.toggleSecure(Settings.Secure.DOZE_ALWAYS_ON)
            AxPlatformClient.FEATURE_AIRPLANE_MODE -> {
                val enabled = !stateManager.getGlobalBool(Settings.Global.AIRPLANE_MODE_ON)
                stateManager.setGlobalBool(Settings.Global.AIRPLANE_MODE_ON, enabled)
                context.sendBroadcastAsUser(
                    Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", enabled),
                    UserHandle.ALL
                )
            }
            AxPlatformClient.FEATURE_NFC -> nfcAdapter?.let {
                if (it.isEnabled) it.disable() else it.enable()
            }
            AxPlatformClient.FEATURE_DARK_MODE ->
                uiModeManager.setNightModeActivated(
                    !isDarkMode(context.resources.configuration)
                )
            AxPlatformClient.FEATURE_NIGHT_LIGHT ->
                colorDisplayManager.setNightDisplayActivated(
                    !colorDisplayManager.isNightDisplayActivated
                )
            AxPlatformClient.FEATURE_COLOR_INVERSION ->
                stateManager.toggleSecure(Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED)
            AxPlatformClient.FEATURE_COLOR_CORRECTION ->
                stateManager.toggleSecure(Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED)
            AxPlatformClient.FEATURE_REDUCE_BRIGHTNESS ->
                stateManager.toggleSecure(SETTING_REDUCE_BRIGHT)
            AxPlatformClient.FEATURE_ONE_HANDED_MODE ->
                stateManager.toggleSecure(SETTING_ONE_HANDED)
            AxPlatformClient.FEATURE_HEADS_UP ->
                stateManager.toggleGlobal(Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED)
            AxPlatformClient.FEATURE_AUTO_SYNC ->
                ContentResolver.setMasterSyncAutomatically(
                    !ContentResolver.getMasterSyncAutomatically()
                )
            AxPlatformClient.FEATURE_CAMERA_PRIVACY ->
                sensorPrivacyController.setSensorBlocked(
                    SensorPrivacyManager.Sources.QS_TILE,
                    SensorPrivacyManager.Sensors.CAMERA,
                    !sensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.CAMERA)
                )
            AxPlatformClient.FEATURE_MIC_PRIVACY ->
                sensorPrivacyController.setSensorBlocked(
                    SensorPrivacyManager.Sources.QS_TILE,
                    SensorPrivacyManager.Sensors.MICROPHONE,
                    !sensorPrivacyController.isSensorBlocked(
                        SensorPrivacyManager.Sensors.MICROPHONE
                    )
                )
            AxPlatformClient.FEATURE_WORK_PROFILE ->
                managedProfileController.setWorkModeEnabled(
                    !managedProfileController.isWorkModeEnabled
                )
            AxPlatformClient.FEATURE_USB_TETHER -> {
                val current = stateManager.getState(feature).getBoolean("active", false)
                tetheringManager?.setUsbTethering(!current)
            }
            AxPlatformClient.FEATURE_DREAM -> try {
                dreamManager?.let { if (it.isDreaming) it.awaken() else it.dream() }
            } catch (e: RemoteException) {
                Log.w(TAG, "Dream toggle failed", e)
            }
            AxPlatformClient.FEATURE_READING_MODE -> lineageHardware?.let {
                val current = it.get(LineageHardwareManager.FEATURE_READING_ENHANCEMENT)
                it.set(LineageHardwareManager.FEATURE_READING_ENHANCEMENT, !current)
                stateManager.broadcastBool(feature, !current)
            }
            AxPlatformClient.FEATURE_POWER_SHARE ->
                batteryController.setReverseState(!batteryController.isReverseOn)
            AxPlatformClient.FEATURE_CAFFEINE -> {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                } else {
                    wakeLock.acquire(CAFFEINE_DURATION_MS)
                }
                stateManager.broadcastBool(feature, wakeLock.isHeld)
            }
            AxPlatformClient.FEATURE_VPN -> {
                if (securityController.isVpnEnabled) {
                    securityController.disconnectPrimaryVpn()
                }
            }
            AxPlatformClient.FEATURE_CAST -> {
                val active = castController.castDevices.firstOrNull { it.isCasting }
                active?.let { castController.stopCasting(it, StopReason.STOP_QS_TILE) }
            }
            AxPlatformClient.FEATURE_PROFILES -> {
                val current = profilesEnabled()
                setProfilesEnabled(!current)
                stateManager.broadcastBool(feature, !current)
            }
            AxPlatformClient.FEATURE_SMART_PIXELS ->
                stateManager.toggleSecure(SETTING_SMART_PIXELS)
            AxPlatformClient.FEATURE_SCREEN_RECORD -> {
                if (screenRecordUxController.isStarting) {
                    screenRecordUxController.cancelCountdown()
                } else if (screenRecordUxController.isRecording) {
                    screenRecordUxController.stopRecording(StopReason.STOP_QS_TILE)
                } else {
                    screenRecordUxController.createScreenRecordDialog(null).show()
                }
            }
            AxPlatformClient.FEATURE_SCREENSHOT -> {
                screenshotHandler.postDelayed({
                    screenshotHelper.takeScreenshot(
                        WindowManager.TAKE_SCREENSHOT_FULLSCREEN,
                        WindowManager.ScreenshotSource.SCREENSHOT_OTHER,
                        screenshotHandler,
                        null
                    )
                }, SCREENSHOT_DELAY_MS)
            }
            else -> Log.w(TAG, "Unknown toggle: $feature")
        }
    }

    fun setEnabled(feature: String, enabled: Boolean) {
        when (feature) {
            AxPlatformClient.FEATURE_WIFI -> networkController.setWifiEnabled(enabled)
            AxPlatformClient.FEATURE_MOBILE_DATA ->
                networkController.mobileDataController?.let { it.isMobileDataEnabled = enabled }
            AxPlatformClient.FEATURE_BLUETOOTH -> bluetoothController.setBluetoothEnabled(enabled)
            AxPlatformClient.FEATURE_HOTSPOT -> hotspotController.setHotspotEnabled(enabled)
            AxPlatformClient.FEATURE_FLASHLIGHT -> {
                if (flashlightController.hasFlashlight()) flashlightController.setFlashlight(enabled)
            }
            AxPlatformClient.FEATURE_LOCATION -> locationController.setLocationEnabled(enabled)
            AxPlatformClient.FEATURE_ROTATION ->
                rotationLockController.setRotationLocked(!enabled, TAG)
            AxPlatformClient.FEATURE_BATTERY_SAVER -> batteryController.setPowerSaveMode(enabled)
            AxPlatformClient.FEATURE_ZEN ->
                zenModeController.setZen(if (enabled) 1 else 0, null, TAG)
            AxPlatformClient.FEATURE_DATA_SAVER -> dataSaverController.setDataSaverEnabled(enabled)
            AxPlatformClient.FEATURE_AOD ->
                stateManager.setSecureBool(Settings.Secure.DOZE_ALWAYS_ON, enabled)
            AxPlatformClient.FEATURE_AIRPLANE_MODE -> {
                stateManager.setGlobalBool(Settings.Global.AIRPLANE_MODE_ON, enabled)
                context.sendBroadcastAsUser(
                    Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", enabled),
                    UserHandle.ALL
                )
            }
            AxPlatformClient.FEATURE_NFC -> nfcAdapter?.let {
                if (enabled) it.enable() else it.disable()
            }
            AxPlatformClient.FEATURE_DARK_MODE -> uiModeManager.setNightModeActivated(enabled)
            AxPlatformClient.FEATURE_NIGHT_LIGHT ->
                colorDisplayManager.setNightDisplayActivated(enabled)
            AxPlatformClient.FEATURE_COLOR_INVERSION ->
                stateManager.setSecureBool(
                    Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, enabled
                )
            AxPlatformClient.FEATURE_COLOR_CORRECTION ->
                stateManager.setSecureBool(
                    Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, enabled
                )
            AxPlatformClient.FEATURE_REDUCE_BRIGHTNESS ->
                stateManager.setSecureBool(SETTING_REDUCE_BRIGHT, enabled)
            AxPlatformClient.FEATURE_ONE_HANDED_MODE ->
                stateManager.setSecureBool(SETTING_ONE_HANDED, enabled)
            AxPlatformClient.FEATURE_HEADS_UP ->
                stateManager.setGlobalBool(
                    Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, enabled
                )
            AxPlatformClient.FEATURE_AUTO_SYNC ->
                ContentResolver.setMasterSyncAutomatically(enabled)
            AxPlatformClient.FEATURE_CAMERA_PRIVACY ->
                sensorPrivacyController.setSensorBlocked(
                    SensorPrivacyManager.Sources.QS_TILE,
                    SensorPrivacyManager.Sensors.CAMERA,
                    enabled
                )
            AxPlatformClient.FEATURE_MIC_PRIVACY ->
                sensorPrivacyController.setSensorBlocked(
                    SensorPrivacyManager.Sources.QS_TILE,
                    SensorPrivacyManager.Sensors.MICROPHONE,
                    enabled
                )
            AxPlatformClient.FEATURE_WORK_PROFILE ->
                managedProfileController.setWorkModeEnabled(enabled)
            AxPlatformClient.FEATURE_USB_TETHER -> tetheringManager?.setUsbTethering(enabled)
            AxPlatformClient.FEATURE_DREAM -> try {
                dreamManager?.let { if (enabled) it.dream() else it.awaken() }
            } catch (e: RemoteException) {
                Log.w(TAG, "Dream setEnabled failed", e)
            }
            AxPlatformClient.FEATURE_READING_MODE -> lineageHardware?.let {
                it.set(LineageHardwareManager.FEATURE_READING_ENHANCEMENT, enabled)
                stateManager.broadcastBool(feature, enabled)
            }
            AxPlatformClient.FEATURE_POWER_SHARE ->
                batteryController.setReverseState(enabled)
            AxPlatformClient.FEATURE_CAFFEINE -> {
                if (enabled && !wakeLock.isHeld) {
                    wakeLock.acquire(CAFFEINE_DURATION_MS)
                } else if (!enabled && wakeLock.isHeld) {
                    wakeLock.release()
                }
                stateManager.broadcastBool(feature, wakeLock.isHeld)
            }
            AxPlatformClient.FEATURE_VPN -> {
                if (!enabled && securityController.isVpnEnabled) {
                    securityController.disconnectPrimaryVpn()
                }
            }
            AxPlatformClient.FEATURE_CAST -> {
                if (!enabled) {
                    castController.castDevices.firstOrNull { it.isCasting }
                        ?.let { castController.stopCasting(it, StopReason.STOP_QS_TILE) }
                }
            }
            AxPlatformClient.FEATURE_PROFILES -> {
                setProfilesEnabled(enabled)
                stateManager.broadcastBool(feature, enabled)
            }
            AxPlatformClient.FEATURE_SMART_PIXELS ->
                stateManager.setSecureBool(SETTING_SMART_PIXELS, enabled)
            AxPlatformClient.FEATURE_SCREEN_RECORD -> {
                if (!enabled && screenRecordUxController.isRecording) {
                    screenRecordUxController.stopRecording(StopReason.STOP_QS_TILE)
                }
            }
            AxPlatformClient.FEATURE_SCREENSHOT -> {
                if (enabled) toggle(feature)
            }
            else -> Log.w(TAG, "Unknown setEnabled: $feature")
        }
    }

    fun setValue(feature: String, value: Int) {
        when (feature) {
            AxPlatformClient.FEATURE_ZEN -> {
                if (zenModeController.zen != value)
                    zenModeController.setZen(value, null, TAG)
            }
            else -> Log.w(TAG, "Unknown setValue: $feature")
        }
    }

    fun performAction(feature: String, param: String) {
        when (feature) {
            AxPlatformClient.ACTION_WIFI_CONNECT ->
                latestAccessPoints
                    .find { it.getKey() == param || it.getTitle() == param }
                    ?.let { accessPointController.connect(it) }
            AxPlatformClient.ACTION_BT_CONNECT ->
                getAllBluetoothDevices().find { it.getAddress() == param }?.let {
                    if (it.isConnected()) it.disconnect() else it.connect(true)
                }
            else -> Log.w(TAG, "Unknown performAction: $feature")
        }
    }

    fun getAllBluetoothDevices(): Collection<CachedBluetoothDevice> =
        localBluetoothManager?.cachedDeviceManager?.cachedDevicesCopy
            ?: bluetoothController.connectedDevices

    private fun profilesEnabled(): Boolean =
        LineageSettings.System.getIntForUser(
            context.contentResolver,
            LineageSettings.System.SYSTEM_PROFILES_ENABLED,
            1, UserHandle.USER_CURRENT
        ) == 1

    private fun setProfilesEnabled(enabled: Boolean) {
        LineageSettings.System.putIntForUser(
            context.contentResolver,
            LineageSettings.System.SYSTEM_PROFILES_ENABLED,
            if (enabled) 1 else 0, UserHandle.USER_CURRENT
        )
    }

    companion object {
        private const val TAG = "AxPlatformFeatureCtrl"
        private const val CAFFEINE_DURATION_MS = 5L * 60 * 1000
        private const val SCREENSHOT_DELAY_MS = 500L
        const val SETTING_NIGHT_DISPLAY = "night_display_activated"
        const val SETTING_REDUCE_BRIGHT = "reduce_bright_colors_activated"
        const val SETTING_ONE_HANDED = "one_handed_mode_enabled"
        const val SETTING_SMART_PIXELS = "ax_smart_pixel_filter_enabled"

        fun isDarkMode(config: Configuration): Boolean =
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        private val BASE_FEATURES = arrayOf(
            AxPlatformClient.FEATURE_WIFI,
            AxPlatformClient.FEATURE_MOBILE_DATA,
            AxPlatformClient.FEATURE_BLUETOOTH,
            AxPlatformClient.FEATURE_HOTSPOT,
            AxPlatformClient.FEATURE_FLASHLIGHT,
            AxPlatformClient.FEATURE_LOCATION,
            AxPlatformClient.FEATURE_ROTATION,
            AxPlatformClient.FEATURE_BATTERY_SAVER,
            AxPlatformClient.FEATURE_ZEN,
            AxPlatformClient.FEATURE_AOD,
            AxPlatformClient.FEATURE_DATA_SAVER,
            AxPlatformClient.FEATURE_AIRPLANE_MODE,
            AxPlatformClient.FEATURE_DARK_MODE,
            AxPlatformClient.FEATURE_NIGHT_LIGHT,
            AxPlatformClient.FEATURE_COLOR_INVERSION,
            AxPlatformClient.FEATURE_COLOR_CORRECTION,
            AxPlatformClient.FEATURE_REDUCE_BRIGHTNESS,
            AxPlatformClient.FEATURE_ONE_HANDED_MODE,
            AxPlatformClient.FEATURE_HEADS_UP,
            AxPlatformClient.FEATURE_AUTO_SYNC
        )
    }
}
