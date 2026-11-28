package com.android.systemui.axdynamicbar.domain

import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.EVENT_TYPE_IDS
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray

@SysUISingleton
class AxDynamicBarSettings @Inject constructor(
    @Main private val mainHandler: Handler,
    private val secureSettings: SecureSettings,
) {
    companion object {
        const val KEY_ENABLED = Settings.Secure.AX_DYNAMIC_BAR_ENABLED
        const val KEY_EVENTS = Settings.Secure.AX_DYNAMIC_BAR_EVENTS
        const val KEY_KEYGUARD_ENABLED = Settings.Secure.AX_DYNAMIC_BAR_KEYGUARD_ENABLED
        const val KEY_KEYGUARD_BATTERY_CHIP_MODE =
            Settings.Secure.AX_DYNAMIC_BAR_KEYGUARD_BATTERY_CHIP_MODE
        const val KEY_COMPACT_NOTIFICATIONS = Settings.Secure.AX_DYNAMIC_BAR_COMPACT_NOTIFICATIONS
        const val KEY_HAPTICS = Settings.Secure.AX_DYNAMIC_BAR_HAPTICS
        const val KEY_CHIP_MORPH = Settings.Secure.AX_DYNAMIC_BAR_CHIP_MORPH
        const val KEY_CENTER_ISLAND = Settings.Secure.AX_DYNAMIC_BAR_CENTER_ISLAND
        const val KEY_CARD_STYLE = Settings.Secure.AX_DYNAMIC_BAR_CARD_STYLE
        const val KEY_SHADE_COLOR_STYLE = Settings.Secure.SHADE_COLOR_STYLE
    }

    private val _isEnabled = MutableStateFlow(false)
    @get:JvmName("getIsEnabled") val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isKeyguardEnabled = MutableStateFlow(true)
    val isKeyguardEnabled: StateFlow<Boolean> = _isKeyguardEnabled.asStateFlow()

    private val _keyguardBatteryChipMode = MutableStateFlow(1)
    val keyguardBatteryChipMode: StateFlow<Int> = _keyguardBatteryChipMode.asStateFlow()

    private val _compactNotifications = MutableStateFlow(true)
    val compactNotifications: StateFlow<Boolean> = _compactNotifications.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(true)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val _chipMorphEnabled = MutableStateFlow(true)
    val chipMorphEnabled: StateFlow<Boolean> = _chipMorphEnabled.asStateFlow()

    private val _centerIsland = MutableStateFlow(false)
    val centerIsland: StateFlow<Boolean> = _centerIsland.asStateFlow()

    private val _cardStyle = MutableStateFlow(0)
    val cardStyle: StateFlow<Int> = _cardStyle.asStateFlow()

    private val _shadeColorStyle = MutableStateFlow(0)
    val shadeColorStyle: StateFlow<Int> = _shadeColorStyle.asStateFlow()

    private val _disabledEventTypes = MutableStateFlow<Set<String>>(emptySet())
    val disabledEventTypes: StateFlow<Set<String>> = _disabledEventTypes.asStateFlow()

    /** Whether the lock screen chip shows media, making the lock screen player redundant. */
    val coversKeyguardMedia: Flow<Boolean> =
        combine(_isEnabled, _isKeyguardEnabled, _disabledEventTypes) { on, kg, disabled ->
                on && kg && "media" !in disabled
            }
            .distinctUntilChanged()

    fun isKeyguardMediaCovered(): Boolean =
        _isEnabled.value && _isKeyguardEnabled.value && "media" !in _disabledEventTypes.value

    init {
        refresh()
    }

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        refresh()
        secureSettings.registerContentObserverForUserSync(
            KEY_ENABLED,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_EVENTS,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_KEYGUARD_ENABLED,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_KEYGUARD_BATTERY_CHIP_MODE,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_COMPACT_NOTIFICATIONS,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_HAPTICS,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_CHIP_MORPH,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_CENTER_ISLAND,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_CARD_STYLE,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_SHADE_COLOR_STYLE,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
    }

    fun destroy() {
        if (!initialized) return
        initialized = false
        secureSettings.getContentResolver().unregisterContentObserver(settingsObserver)
    }

    private fun refresh() {
        _isEnabled.value =
            secureSettings.getIntForUser(KEY_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        _isKeyguardEnabled.value =
            secureSettings.getIntForUser(KEY_KEYGUARD_ENABLED, 1, UserHandle.USER_CURRENT) == 1
        _keyguardBatteryChipMode.value =
            secureSettings.getIntForUser(KEY_KEYGUARD_BATTERY_CHIP_MODE, 1, UserHandle.USER_CURRENT)
        _compactNotifications.value =
            secureSettings.getIntForUser(KEY_COMPACT_NOTIFICATIONS, 1, UserHandle.USER_CURRENT) == 1
        _hapticsEnabled.value =
            secureSettings.getIntForUser(KEY_HAPTICS, 1, UserHandle.USER_CURRENT) == 1
        _chipMorphEnabled.value =
            secureSettings.getIntForUser(KEY_CHIP_MORPH, 1, UserHandle.USER_CURRENT) == 1
        _centerIsland.value =
            secureSettings.getIntForUser(KEY_CENTER_ISLAND, 0, UserHandle.USER_CURRENT) == 1
        _cardStyle.value =
            secureSettings.getIntForUser(KEY_CARD_STYLE, 0, UserHandle.USER_CURRENT)
        _shadeColorStyle.value =
            secureSettings.getIntForUser(KEY_SHADE_COLOR_STYLE, 0, UserHandle.USER_CURRENT)

        val json = secureSettings.getStringForUser(KEY_EVENTS, UserHandle.USER_CURRENT) ?: ""
        _disabledEventTypes.value =
            try {
                if (json.isBlank()) emptySet()
                else {
                    val arr = JSONArray(json)
                    (0 until arr.length()).mapNotNull { arr.optString(it) }.toSet()
                }
            } catch (_: Exception) {
                emptySet()
            }
    }

    fun isEventEnabled(event: IslandEvent): Boolean {
        val typeId = EVENT_TYPE_IDS[event::class.java] ?: return true
        return typeId !in _disabledEventTypes.value
    }

    fun isNotificationEventsActive(): Boolean =
        _isEnabled.value && "notification" !in _disabledEventTypes.value

    fun isKeyguardBiometricUnlockEventsActive(): Boolean =
        _isEnabled.value && _isKeyguardEnabled.value &&
            "biometric_unlock" !in _disabledEventTypes.value
}
