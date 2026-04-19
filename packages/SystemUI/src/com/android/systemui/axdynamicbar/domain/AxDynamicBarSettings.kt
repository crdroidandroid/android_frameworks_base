package com.android.systemui.axdynamicbar.domain

import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.EVENT_TYPE_IDS
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

@SysUISingleton
class AxDynamicBarSettings @Inject constructor(
    @Main private val mainHandler: Handler,
    private val secureSettings: SecureSettings,
) {
    companion object {
        const val KEY_ENABLED = "ax_dynamic_bar_enabled"
        const val KEY_EVENTS = "ax_dynamic_bar_events"
        const val KEY_KEYGUARD_ENABLED = "ax_dynamic_bar_keyguard_enabled"
    }

    private val _isEnabled = MutableStateFlow(false)
    @get:JvmName("getIsEnabled") val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isKeyguardEnabled = MutableStateFlow(true)
    val isKeyguardEnabled: StateFlow<Boolean> = _isKeyguardEnabled.asStateFlow()

    private val _disabledEventTypes = MutableStateFlow<Set<String>>(emptySet())
    val disabledEventTypes: StateFlow<Set<String>> = _disabledEventTypes.asStateFlow()

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
}
