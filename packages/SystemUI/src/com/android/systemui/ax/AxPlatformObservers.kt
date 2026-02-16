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

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ExecutorContentObserver
import android.hardware.SensorPrivacyManager
import android.hardware.usb.UsbManager
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.RemoteException
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.axion.platform.AxPlatformClient
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.plugins.keyguard.ui.clocks.CalendarSimpleData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockData
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.quicklook.QuickLookClient
import com.android.systemui.screenrecord.ScreenRecordUxController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.connectivity.AccessPointController
import com.android.systemui.statusbar.connectivity.MobileDataIndicators
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.connectivity.SignalCallback
import com.android.systemui.statusbar.connectivity.WifiIndicators
import com.android.systemui.statusbar.phone.ManagedProfileController
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.DataSaverController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.HotspotController
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.LocationController
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.statusbar.policy.RotationLockController
import com.android.systemui.statusbar.policy.CastController
import com.android.systemui.statusbar.policy.CastDevice
import com.android.systemui.statusbar.policy.SecurityController
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.wifitrackerlib.WifiEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lineageos.app.ProfileManager
import lineageos.hardware.LineageHardwareManager
import lineageos.providers.LineageSettings
import java.util.concurrent.Executor
import javax.inject.Inject

@SysUISingleton
class AxPlatformObservers @Inject constructor(
    private val context: Context,
    @Main private val mainExecutor: Executor,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val secureSettings: SecureSettings,
    private val stateManager: AxPlatformStateManager,
    private val featureController: AxPlatformFeatureController,
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
    private val notificationMediaManager: NotificationMediaManager,
    private val nextAlarmController: NextAlarmController,
    private val quickLookClient: QuickLookClient,
    private val configurationController: ConfigurationController,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val batteryInteractor: BatteryInteractor,
    private val sensorPrivacyController: IndividualSensorPrivacyController,
    private val managedProfileController: ManagedProfileController,
    private val securityController: SecurityController,
    private val castController: CastController,
    private val screenRecordUxController: ScreenRecordUxController
) {

    private val resolver: ContentResolver = context.contentResolver
    private var lastMediaState = -1
    private var lastMediaTrack: String? = null
    private var lastMediaArtist: String? = null
    private var lastMediaPackage: String? = null

    fun registerAll() {
        registerControllerCallbacks()
        registerSettingsObservers()
        registerNfc()
        registerBatteryFlow()
        registerSensorPrivacy()
        registerWorkProfile()
        registerUsbTether()
        registerDream()
        registerReadingMode()
        registerPowerShare()
        registerCaffeine()
        registerVpn()
        registerCast()
        registerProfiles()
        registerSmartPixels()
        registerScreenRecord()
        registerAmbientIndication()
    }

    private fun registerControllerCallbacks() {
        networkController.addCallback(signalCallback)
        bluetoothController.addCallback(bluetoothCallback)
        hotspotController.addCallback(hotspotCallback)
        flashlightController.addCallback(flashlightCallback)
        locationController.addCallback(locationCallback)
        rotationLockController.addCallback(rotationCallback)
        batteryController.addCallback(batteryCallback)
        zenModeController.addCallback(zenCallback)
        dataSaverController.addCallback(dataSaverCallback)
        notificationMediaManager.addCallback(mediaListener)
        nextAlarmController.addCallback(nextAlarmCallback)
        quickLookClient.addCallback(quickLookCallback)
        configurationController.addCallback(configurationListener)
        statusBarStateController.addCallback(dozeCallback)
        keyguardStateController.addCallback(keyguardCallback)
        accessPointController.addAccessPointCallback(accessPointCallback)
        securityController.addCallback(vpnCallback)
        castController.addCallback(castCallback)
    }

    private fun registerSettingsObservers() {
        stateManager.observeSecure(Settings.Secure.DOZE_ALWAYS_ON, AxPlatformClient.FEATURE_AOD)
        stateManager.observeSecure(
            Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
            AxPlatformClient.FEATURE_COLOR_INVERSION
        )
        stateManager.observeSecure(
            Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED,
            AxPlatformClient.FEATURE_COLOR_CORRECTION
        )
        stateManager.observeSecure(
            AxPlatformFeatureController.SETTING_REDUCE_BRIGHT,
            AxPlatformClient.FEATURE_REDUCE_BRIGHTNESS
        )
        stateManager.observeSecure(
            AxPlatformFeatureController.SETTING_NIGHT_DISPLAY,
            AxPlatformClient.FEATURE_NIGHT_LIGHT
        )
        stateManager.observeSecure(
            AxPlatformFeatureController.SETTING_ONE_HANDED,
            AxPlatformClient.FEATURE_ONE_HANDED_MODE
        )
        stateManager.observeGlobal(
            Settings.Global.AIRPLANE_MODE_ON,
            AxPlatformClient.FEATURE_AIRPLANE_MODE
        )
        stateManager.observeGlobal(
            Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
            AxPlatformClient.FEATURE_HEADS_UP
        )
        stateManager.broadcastBool(
            AxPlatformClient.FEATURE_AUTO_SYNC,
            ContentResolver.getMasterSyncAutomatically()
        )
        ContentResolver.addStatusChangeListener(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS) {
            mainExecutor.execute {
                stateManager.broadcastBool(
                    AxPlatformClient.FEATURE_AUTO_SYNC,
                    ContentResolver.getMasterSyncAutomatically()
                )
            }
        }
    }

    private fun registerNfc() {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context) ?: return
        stateManager.broadcastBool(AxPlatformClient.FEATURE_NFC, nfcAdapter.isEnabled)
        broadcastDispatcher.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                stateManager.broadcastBool(AxPlatformClient.FEATURE_NFC, nfcAdapter.isEnabled)
            }
        }, IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED))
    }

    private fun registerBatteryFlow() {
        scope.launch {
            combine(
                batteryInteractor.level,
                batteryInteractor.isPluggedIn,
                batteryInteractor.isCharging,
                batteryInteractor.powerSave,
                batteryInteractor.batteryTimeRemainingEstimate
            ) { level, pluggedIn, charging, powerSave, estimate ->
                Bundle().apply {
                    putInt("level", level ?: -1)
                    putBoolean("isPluggedIn", pluggedIn)
                    putBoolean("isCharging", charging)
                    putBoolean("powerSave", powerSave)
                    putString("batteryTimeRemainingEstimate", estimate?.toString() ?: "")
                }
            }.flowOn(bgDispatcher).collect { bundle ->
                withContext(mainDispatcher) {
                    bundle.putBoolean("wireless", batteryController.isWirelessCharging)
                    stateManager.broadcastState(AxPlatformClient.KEY_BATTERY, bundle)
                }
            }
        }
    }

    private fun registerSensorPrivacy() {
        sensorPrivacyController.addCallback(sensorPrivacyCallback)
        if (sensorPrivacyController.supportsSensorToggle(SensorPrivacyManager.Sensors.CAMERA)) {
            stateManager.broadcastBool(
                AxPlatformClient.FEATURE_CAMERA_PRIVACY,
                sensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.CAMERA)
            )
        }
        if (sensorPrivacyController.supportsSensorToggle(SensorPrivacyManager.Sensors.MICROPHONE)) {
            stateManager.broadcastBool(
                AxPlatformClient.FEATURE_MIC_PRIVACY,
                sensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.MICROPHONE)
            )
        }
    }

    private fun registerWorkProfile() {
        managedProfileController.addCallback(managedProfileCallback)
        broadcastWorkProfileState()
    }

    private fun registerUsbTether() {
        broadcastDispatcher.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val connected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false)
                val rndis = intent.getBooleanExtra(UsbManager.USB_FUNCTION_RNDIS, false)
                val ncm = intent.getBooleanExtra(UsbManager.USB_FUNCTION_NCM, false)
                val tethering = rndis || ncm
                stateManager.broadcastState(
                    AxPlatformClient.FEATURE_USB_TETHER,
                    Bundle().apply {
                        putBoolean("enabled", tethering)
                        putBoolean("active", tethering)
                        putBoolean("usbConnected", connected)
                    }
                )
            }
        }, IntentFilter(UsbManager.ACTION_USB_STATE))
    }

    private fun registerDream() {
        featureController.dreamManager ?: return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        }
        broadcastDispatcher.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                broadcastDreamState()
            }
        }, filter)
        secureSettings.registerContentObserverSync(
            Settings.Secure.SCREENSAVER_ENABLED,
            object : ExecutorContentObserver(mainExecutor) {
                override fun onChange(selfChange: Boolean) {
                    broadcastDreamState()
                }
            }
        )
        broadcastDreamState()
    }

    private fun registerReadingMode() {
        val hw = featureController.lineageHardware ?: return
        if (!hw.isSupported(LineageHardwareManager.FEATURE_READING_ENHANCEMENT)) return
        stateManager.broadcastBool(
            AxPlatformClient.FEATURE_READING_MODE,
            hw.get(LineageHardwareManager.FEATURE_READING_ENHANCEMENT)
        )
    }

    private fun registerPowerShare() {
        if (!batteryController.isReverseSupported) return
        batteryController.addCallback(powerShareCallback)
        stateManager.broadcastBool(
            AxPlatformClient.FEATURE_POWER_SHARE,
            batteryController.isReverseOn
        )
    }

    private val signalCallback = object : SignalCallback {
        override fun setWifiIndicators(wifiIndicators: WifiIndicators) {
            val connected = wifiIndicators.statusIcon?.visible == true
            val enabled = wifiIndicators.enabled
            stateManager.broadcastState(AxPlatformClient.FEATURE_WIFI, Bundle().apply {
                putBoolean("enabled", enabled)
                putBoolean("active", enabled)
                putBoolean("connected", connected)
                putString("ssid", wifiIndicators.description ?: "")
                putString("statusLabel", wifiIndicators.statusLabel ?: "")
                putBoolean("isDefault", connected && !wifiIndicators.isTransient)
            })
        }

        override fun setMobileDataIndicators(mobileDataIndicators: MobileDataIndicators) {
            val isEnabled = networkController.mobileDataController?.isMobileDataEnabled == true
            stateManager.broadcastState(
                AxPlatformClient.FEATURE_MOBILE_DATA,
                Bundle().apply {
                    putBoolean("enabled", isEnabled)
                    putBoolean("active", isEnabled)
                    putString(
                        "type",
                        mobileDataIndicators.typeContentDescription?.toString() ?: ""
                    )
                    putString("description", mobileDataIndicators.qsDescription?.toString() ?: "")
                    putBoolean("roaming", mobileDataIndicators.roaming)
                    putBoolean("isDefault", mobileDataIndicators.isDefault)
                    putInt("subId", mobileDataIndicators.subId)
                    putBoolean("activityIn", mobileDataIndicators.activityIn)
                    putBoolean("activityOut", mobileDataIndicators.activityOut)
                    putInt("level", mobileDataIndicators.level)
                }
            )
        }

        override fun setNoSims(show: Boolean, simDetected: Boolean) {
            if (!simDetected) {
                stateManager.broadcastState(
                    AxPlatformClient.FEATURE_MOBILE_DATA,
                    Bundle().apply {
                        putBoolean("available", false)
                        putBoolean("enabled", false)
                        putBoolean("active", false)
                    }
                )
            }
        }
    }

    private val accessPointCallback = object : AccessPointController.AccessPointCallback {
        override fun onAccessPointsChanged(accessPoints: List<WifiEntry>) {
            featureController.latestAccessPoints = accessPoints.toList()
            val networks = ArrayList<Bundle>()
            accessPoints.forEach { ap ->
                if (ap.getLevel() != -1) {
                    networks.add(Bundle().apply {
                        putString("title", ap.getTitle())
                        putString("key", ap.getKey())
                        putBoolean("isConnected",
                            ap.getConnectedState() == WifiEntry.CONNECTED_STATE_CONNECTED)
                        putBoolean("isSaved", ap.isSaved())
                        putInt("level", ap.getLevel())
                        putInt("security", ap.getSecurity())
                    })
                }
            }
            stateManager.broadcastState(AxPlatformClient.KEY_WIFI_SCAN, Bundle().apply {
                putParcelableArrayList("networks", networks)
            })
        }

        override fun onSettingsActivityTriggered(intent: Intent?) {}
        override fun onWifiScan(isScan: Boolean) {}
    }

    private fun getAllBluetoothDevices() = featureController.getAllBluetoothDevices()

    private val bluetoothCallback = object : BluetoothController.Callback {
        override fun onBluetoothStateChange(enabled: Boolean) = broadcastBluetoothState(enabled)
        override fun onBluetoothDevicesChanged() =
            broadcastBluetoothState(bluetoothController.isBluetoothEnabled)
    }

    private fun broadcastBluetoothState(enabled: Boolean) {
        val devices = getAllBluetoothDevices()
        val deviceBundles = ArrayList<Bundle>()
        devices.forEach { device ->
            deviceBundles.add(Bundle().apply {
                putString("name", device.getName())
                putString("address", device.getAddress())
                putBoolean("isConnected", device.isConnected())
                putInt("connectionState", device.getMaxConnectionState())
                putInt("bondState", device.getBondState())
                putInt("batteryLevel", device.getBatteryLevel())
            })
        }
        val hasConnected = devices.any { it.isConnected() }
        stateManager.broadcastState(AxPlatformClient.FEATURE_BLUETOOTH, Bundle().apply {
            putBoolean("enabled", enabled)
            putBoolean("active", enabled)
            putBoolean("hasConnectedDevice", hasConnected)
            putParcelableArrayList("devices", deviceBundles)
        })
    }

    private val hotspotCallback = object : HotspotController.Callback {
        override fun onHotspotChanged(enabled: Boolean, numDevices: Int) {
            stateManager.broadcastState(AxPlatformClient.FEATURE_HOTSPOT, Bundle().apply {
                putBoolean("enabled", enabled)
                putBoolean("active", enabled)
                putInt("numDevices", numDevices)
            })
        }
    }

    private val flashlightCallback = object : FlashlightController.FlashlightListener {
        override fun onFlashlightChanged(enabled: Boolean) {
            stateManager.broadcastState(AxPlatformClient.FEATURE_FLASHLIGHT, Bundle().apply {
                putBoolean("enabled", enabled)
                putBoolean("active", enabled)
                putBoolean("available", true)
            })
        }

        override fun onFlashlightError() {}

        override fun onFlashlightAvailabilityChanged(available: Boolean) {
            if (!available) {
                stateManager.broadcastState(AxPlatformClient.FEATURE_FLASHLIGHT, Bundle().apply {
                    putBoolean("enabled", false)
                    putBoolean("active", false)
                    putBoolean("available", false)
                })
            }
        }
    }

    private val rotationCallback =
        object : RotationLockController.RotationLockControllerCallback {
            override fun onRotationLockStateChanged(
                rotationLocked: Boolean,
                affordanceVisible: Boolean
            ) {
                stateManager.broadcastState(AxPlatformClient.FEATURE_ROTATION, Bundle().apply {
                    putBoolean("locked", rotationLocked)
                    putBoolean("active", !rotationLocked)
                })
            }
        }

    private val locationCallback = object : LocationController.LocationChangeCallback {
        override fun onLocationSettingsChanged(locationEnabled: Boolean) {
            stateManager.broadcastBool(AxPlatformClient.FEATURE_LOCATION, locationEnabled)
        }
    }

    private val batteryCallback = object : BatteryController.BatteryStateChangeCallback {
        override fun onPowerSaveChanged(isPowerSave: Boolean) {
            stateManager.broadcastBool(AxPlatformClient.FEATURE_BATTERY_SAVER, isPowerSave)
        }
    }

    private val zenCallback = object : ZenModeController.Callback {
        override fun onZenChanged(zen: Int) {
            stateManager.broadcastState(AxPlatformClient.FEATURE_ZEN, Bundle().apply {
                putInt("mode", zen)
                putBoolean("active", zen != 0)
            })
        }
    }

    private val dataSaverCallback = object : DataSaverController.Listener {
        override fun onDataSaverChanged(isDataSaving: Boolean) {
            stateManager.broadcastBool(AxPlatformClient.FEATURE_DATA_SAVER, isDataSaving)
        }
    }

    private val sensorPrivacyCallback =
        object : IndividualSensorPrivacyController.Callback {
            override fun onSensorBlockedChanged(sensor: Int, blocked: Boolean) {
                when (sensor) {
                    SensorPrivacyManager.Sensors.CAMERA ->
                        stateManager.broadcastBool(
                            AxPlatformClient.FEATURE_CAMERA_PRIVACY, blocked
                        )
                    SensorPrivacyManager.Sensors.MICROPHONE ->
                        stateManager.broadcastBool(
                            AxPlatformClient.FEATURE_MIC_PRIVACY, blocked
                        )
                }
            }
        }

    private val managedProfileCallback = object : ManagedProfileController.Callback {
        override fun onManagedProfileChanged() = broadcastWorkProfileState()
        override fun onManagedProfileRemoved() {
            stateManager.broadcastState(
                AxPlatformClient.FEATURE_WORK_PROFILE,
                Bundle().apply {
                    putBoolean("enabled", false)
                    putBoolean("active", false)
                    putBoolean("available", false)
                }
            )
        }
    }

    private fun broadcastWorkProfileState() {
        val hasProfile = managedProfileController.hasActiveProfile()
        val enabled = hasProfile && managedProfileController.isWorkModeEnabled
        stateManager.broadcastState(
            AxPlatformClient.FEATURE_WORK_PROFILE,
            Bundle().apply {
                putBoolean("enabled", enabled)
                putBoolean("active", enabled)
                putBoolean("available", hasProfile)
            }
        )
    }

    private fun broadcastDreamState() {
        val isDreaming = try {
            featureController.dreamManager?.isDreaming == true
        } catch (e: RemoteException) { false }
        val isEnabled = stateManager.getSecureBool(Settings.Secure.SCREENSAVER_ENABLED)
        stateManager.broadcastState(
            AxPlatformClient.FEATURE_DREAM,
            Bundle().apply {
                putBoolean("enabled", isEnabled)
                putBoolean("active", isDreaming)
            }
        )
    }

    private val powerShareCallback = object : BatteryController.BatteryStateChangeCallback {
        override fun onReverseChanged(isReverse: Boolean, level: Int, name: String?) {
            stateManager.broadcastState(
                AxPlatformClient.FEATURE_POWER_SHARE,
                Bundle().apply {
                    putBoolean("enabled", isReverse)
                    putBoolean("active", isReverse)
                }
            )
        }
    }

    private val mediaListener = object : NotificationMediaManager.MediaListener {
        override fun onPrimaryMetadataOrStateChanged(
            metadata: MediaMetadata?,
            @PlaybackState.State state: Int
        ) {
            val isPlaying = state == PlaybackState.STATE_PLAYING
            val packageName = notificationMediaManager.mediaNotificationKey
                ?.split("|")?.getOrNull(1) ?: ""
            val track = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""

            if (state == lastMediaState && track == lastMediaTrack
                && artist == lastMediaArtist && packageName == lastMediaPackage) return

            lastMediaState = state
            lastMediaTrack = track
            lastMediaArtist = artist
            lastMediaPackage = packageName

            stateManager.broadcastState(AxPlatformClient.KEY_MEDIA, Bundle().apply {
                putString("track", track)
                putString("artist", artist)
                putString("album", metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
                putBoolean("isPlaying", isPlaying)
                putInt("state", state)
                putLong("duration", metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
                putString("packageName", packageName)
            })
        }
    }

    private val nextAlarmCallback = NextAlarmController.NextAlarmChangeCallback { alarm ->
        if (alarm != null) {
            stateManager.broadcastState(AxPlatformClient.KEY_ALARM, Bundle().apply {
                putLong("triggerTime", alarm.triggerTime)
                putString("packageName", alarm.showIntent?.creatorPackage ?: "")
            })
        } else {
            stateManager.broadcastState(AxPlatformClient.KEY_ALARM, Bundle())
        }
    }

    private val quickLookCallback = object : QuickLookClient.Callback {
        override fun onClockDataChanged(data: ClockData) {
            val cal = data.calendar
            if (cal != CalendarSimpleData.EMPTY) {
                stateManager.broadcastState(AxPlatformClient.KEY_CALENDAR, Bundle().apply {
                    putLong("id", cal.id)
                    putString("title", cal.title ?: "")
                    putLong("startTime", cal.startTime)
                    putLong("endTime", cal.endTime)
                    putString("location", cal.location ?: "")
                })
            } else {
                stateManager.broadcastState(AxPlatformClient.KEY_CALENDAR, Bundle())
            }
        }
    }

    private val configurationListener = object : ConfigurationController.ConfigurationListener {
        override fun onConfigChanged(newConfig: Configuration) = exportConfigInfo(newConfig)
        override fun onDensityOrFontScaleChanged() = exportConfigInfo(null)
        override fun onUiModeChanged() {
            exportConfigInfo(null)
            val config = context.resources.configuration
            stateManager.broadcastBool(
                AxPlatformClient.FEATURE_DARK_MODE,
                AxPlatformFeatureController.isDarkMode(config)
            )
        }
        override fun onThemeChanged() = exportConfigInfo(null)
        override fun onLocaleListChanged() = exportConfigInfo(null)
        override fun onLayoutDirectionChanged(isLayoutRtl: Boolean) = exportConfigInfo(null)
        override fun onOrientationChanged(orientation: Int) = exportConfigInfo(null)
    }

    private fun exportConfigInfo(config: Configuration?) {
        val currentConfig = config ?: context.resources.configuration
        stateManager.broadcastState(AxPlatformClient.KEY_CONFIG, Bundle().apply {
            putInt("uiMode", currentConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            putBoolean("isDarkMode", AxPlatformFeatureController.isDarkMode(currentConfig))
            putInt("orientation", currentConfig.orientation)
            putFloat("fontScale", currentConfig.fontScale)
            putInt("densityDpi", currentConfig.densityDpi)
            putInt("smallestScreenWidthDp", currentConfig.smallestScreenWidthDp)
            putInt("screenWidthDp", currentConfig.screenWidthDp)
            putInt("screenHeightDp", currentConfig.screenHeightDp)
            putString("locale", currentConfig.locales.get(0)?.toLanguageTag() ?: "")
            putInt("layoutDirection", currentConfig.layoutDirection)
        })
    }

    private val dozeCallback = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) = exportKeyguardInfo()
        override fun onDozingChanged(isDozing: Boolean) = exportDozeInfo()
        override fun onPulsingChanged(pulsing: Boolean) = exportDozeInfo()
        override fun onDozeAmountChanged(linear: Float, eased: Float) = exportDozeInfo()
    }

    private val keyguardCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardGoingAwayChanged() = exportKeyguardInfo()
        override fun onKeyguardShowingChanged() = exportKeyguardInfo()
    }

    private fun exportKeyguardInfo() {
        val state = statusBarStateController.state
        stateManager.broadcastState(AxPlatformClient.KEY_KEYGUARD, Bundle().apply {
            putInt("state", state)
            putBoolean("isKeyguard", state == StatusBarState.KEYGUARD)
            putBoolean("isUnlocked", state == StatusBarState.SHADE)
            putBoolean("isShadeLocked", state == StatusBarState.SHADE_LOCKED)
            putBoolean("isShowing", keyguardStateController.isShowing)
            putBoolean("isGoingAway", keyguardStateController.isKeyguardGoingAway)
        })
    }

    private fun exportDozeInfo() {
        val aodEnabled = stateManager.getState(AxPlatformClient.FEATURE_AOD).getBoolean("enabled", false)
        stateManager.broadcastState(AxPlatformClient.KEY_DOZE, Bundle().apply {
            putBoolean("isDozing", statusBarStateController.isDozing)
            putBoolean("isPulsing", statusBarStateController.isPulsing)
            putFloat("dozeAmount", statusBarStateController.dozeAmount)
            putBoolean("aodEnabled", aodEnabled)
            putBoolean("isAodPowerSave", batteryController.isAodPowerSave)
        })
    }

    private fun registerCaffeine() {
        stateManager.broadcastBool(
            AxPlatformClient.FEATURE_CAFFEINE,
            featureController.wakeLock.isHeld
        )
        broadcastDispatcher.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (featureController.wakeLock.isHeld) {
                    featureController.wakeLock.release()
                    stateManager.broadcastBool(AxPlatformClient.FEATURE_CAFFEINE, false)
                }
            }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    private val vpnCallback = object : SecurityController.SecurityControllerCallback {
        override fun onStateChanged() {
            stateManager.broadcastState(
                AxPlatformClient.FEATURE_VPN,
                Bundle().apply {
                    val enabled = securityController.isVpnEnabled
                    putBoolean("enabled", enabled)
                    putBoolean("active", enabled)
                    putString("name", securityController.primaryVpnName ?: "")
                }
            )
        }
    }

    private fun registerVpn() {
        stateManager.broadcastState(
            AxPlatformClient.FEATURE_VPN,
            Bundle().apply {
                val enabled = securityController.isVpnEnabled
                putBoolean("enabled", enabled)
                putBoolean("active", enabled)
                putString("name", securityController.primaryVpnName ?: "")
            }
        )
    }

    private val castCallback = object : CastController.Callback {
        override fun onCastDevicesChanged() = broadcastCastState()
    }

    private fun registerCast() {
        broadcastCastState()
    }

    private fun broadcastCastState() {
        val devices = castController.castDevices
        val active = devices.any { it.state == CastDevice.CastState.Connected }
        val connecting = devices.any { it.state == CastDevice.CastState.Connecting }
        val activeName = devices.firstOrNull { it.isCasting }?.name
        stateManager.broadcastState(
            AxPlatformClient.FEATURE_CAST,
            Bundle().apply {
                putBoolean("enabled", active || connecting)
                putBoolean("active", active)
                putBoolean("connecting", connecting)
                putString("deviceName", activeName ?: "")
            }
        )
    }

    private fun registerProfiles() {
        val profileManager = try {
            ProfileManager.getInstance(context)
        } catch (e: Exception) { return }

        val activeProfile = profileManager.activeProfile
        stateManager.broadcastState(
            AxPlatformClient.FEATURE_PROFILES,
            Bundle().apply {
                val enabled = profilesEnabled()
                putBoolean("enabled", enabled)
                putBoolean("active", enabled)
                putString("profileName", activeProfile?.name ?: "")
            }
        )

        val filter = IntentFilter().apply {
            addAction(ProfileManager.INTENT_ACTION_PROFILE_SELECTED)
            addAction(ProfileManager.INTENT_ACTION_PROFILE_UPDATED)
        }
        broadcastDispatcher.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val profile = profileManager.activeProfile
                stateManager.broadcastState(
                    AxPlatformClient.FEATURE_PROFILES,
                    Bundle().apply {
                        val enabled = profilesEnabled()
                        putBoolean("enabled", enabled)
                        putBoolean("active", enabled)
                        putString("profileName", profile?.name ?: "")
                    }
                )
            }
        }, filter)

        val uri = LineageSettings.System.getUriFor(
            LineageSettings.System.SYSTEM_PROFILES_ENABLED
        )
        resolver.registerContentObserver(uri, false, object : ExecutorContentObserver(mainExecutor) {
            override fun onChange(selfChange: Boolean) {
                val profile = profileManager.activeProfile
                stateManager.broadcastState(
                    AxPlatformClient.FEATURE_PROFILES,
                    Bundle().apply {
                        val enabled = profilesEnabled()
                        putBoolean("enabled", enabled)
                        putBoolean("active", enabled)
                        putString("profileName", profile?.name ?: "")
                    }
                )
            }
        }, UserHandle.USER_CURRENT)
    }

    private fun profilesEnabled(): Boolean =
        LineageSettings.System.getIntForUser(
            resolver,
            LineageSettings.System.SYSTEM_PROFILES_ENABLED,
            1, UserHandle.USER_CURRENT
        ) == 1

    private fun registerSmartPixels() {
        stateManager.observeSecure(
            AxPlatformFeatureController.SETTING_SMART_PIXELS,
            AxPlatformClient.FEATURE_SMART_PIXELS
        )
    }

    private val screenRecordCallback = object : ScreenRecordUxController.StateChangeCallback {
        override fun onCountdown(millisUntilFinished: Long) = broadcastScreenRecordState()
        override fun onCountdownEnd() = broadcastScreenRecordState()
        override fun onRecordingStart() = broadcastScreenRecordState()
        override fun onRecordingEnd() = broadcastScreenRecordState()
    }

    private fun registerScreenRecord() {
        screenRecordUxController.addCallback(screenRecordCallback)
        broadcastScreenRecordState()
    }

    private fun broadcastScreenRecordState() {
        val recording = screenRecordUxController.isRecording
        val starting = screenRecordUxController.isStarting
        stateManager.broadcastState(
            AxPlatformClient.FEATURE_SCREEN_RECORD,
            Bundle().apply {
                putBoolean("enabled", recording || starting)
                putBoolean("active", recording)
                putBoolean("starting", starting)
            }
        )
    }

    private fun registerAmbientIndication() {
        val filter = IntentFilter().apply {
            addAction(ACTION_AMBIENT_SHOW)
            addAction(ACTION_AMBIENT_EXPAND)
            addAction(ACTION_AMBIENT_HIDE)
        }
        context.registerReceiverAsUser(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val action = intent.action ?: return
                    val bundle = Bundle().apply {
                        putString("action", action)
                        intent.extras?.let { putAll(it) }
                    }
                    stateManager.broadcastState(AxPlatformClient.KEY_NOW_PLAYING, bundle)
                }
            },
            UserHandle.ALL,
            filter,
            PERMISSION_AMBIENT_INDICATION,
            null,
            Context.RECEIVER_EXPORTED,
        )
    }

    companion object {
        private const val TAG = "AxPlatformObservers"

        private const val ACTION_AMBIENT_SHOW =
            "com.google.android.ambientindication.action.AMBIENT_INDICATION_SHOW"
        private const val ACTION_AMBIENT_EXPAND =
            "com.google.android.ambientindication.action.AMBIENT_INDICATION_EXPAND"
        private const val ACTION_AMBIENT_HIDE =
            "com.google.android.ambientindication.action.AMBIENT_INDICATION_HIDE"
        private const val PERMISSION_AMBIENT_INDICATION =
            "com.google.android.ambientindication.permission.AMBIENT_INDICATION"
    }
}
