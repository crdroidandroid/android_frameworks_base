/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.edgelight

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.widget.FrameLayout
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@SysUISingleton
class EdgeLightViewController 
@Inject 
constructor(
    private val context: Context,
    private val listener: NotificationListener,
) : NotificationListener.NotificationHandler,
    ScrimUtils.ScrimEventListener {

    private val edgeLightView = EdgeLightView(context)
    private val settingsRepo = EdgeLightSettingsRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var currentSettings = settingsRepo.currentSettings()
    private var _pendingNotification: StatusBarNotification? = null

    private val pendingNotification: StatusBarNotification?
        get() = _pendingNotification.also { _pendingNotification = null }

    private val accentColor: Int
        get() = context.getColor(android.R.color.system_accent1_100)

    private var job: Job? = null

    private val dozing: Boolean
        get() = ScrimUtils.get().isDozing()

    private var lastNotificationKey: String? = null
    private var lastNotificationText: CharSequence? = null
    private var lastNotificationTime: Long = 0L
    private val deduplicationWindowMs = 5000L

    init {
        INSTANCE = this

        ScrimUtils.get().addListener(this)
        listener.addNotificationHandler(this)
        updateView()
    }

    private fun updateView(settings: EdgeLightSettings) {
        if (!settings.isEnabled) {
            edgeLightView.visible = false
            return
        }

        val color = when (settings.colorMode) {
            COLOR_MODE_CUSTOM -> settings.customColor
            else -> accentColor
        }

        edgeLightView.paintColor = color
    }

    fun getEdgeLightView(): FrameLayout = edgeLightView

    private fun getColor(sbn: StatusBarNotification?): Int =
        when (currentSettings.colorMode) {
            COLOR_MODE_CUSTOM -> currentSettings.customColor
            else -> sbn?.notification?.color ?: accentColor
        }

    private fun showEdgeLights(color: Int) {
        edgeLightView.apply {
            paintColor = color
            visible = true
            pulseRunning = true
        }
    }

    private fun updateView() {
        job?.cancel()
        job = scope.launch {
            val settings = settingsRepo.settingsFlow.first()
            currentSettings = settings
            updateView(settings)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!currentSettings.isEnabled || !dozing) return

        val currentKey = sbn.key
        val currentText = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        val now = System.currentTimeMillis()

        if (currentKey == lastNotificationKey &&
            currentText == lastNotificationText &&
            (now - lastNotificationTime < deduplicationWindowMs)) {
            return
        }

        lastNotificationKey = currentKey
        lastNotificationText = currentText
        lastNotificationTime = now

        _pendingNotification = sbn
    }

    override fun onDozingChanged() {
        if (!dozing) {
            edgeLightView.pulseRunning = false
        }
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        if (!showing) {
            edgeLightView.pulseRunning = false
            listener.removeNotificationHandler(this)
        } else {
            listener.addNotificationHandler(this)
            updateView()
        }
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        if (fadingAway) {
            edgeLightView.pulseRunning = false
        }
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        if (goingAway) {
            edgeLightView.pulseRunning = false
        }
    }

    override fun setPulsing(pulsing: Boolean) {
        if (!pulsing || !currentSettings.isEnabled || !dozing) return
        val sbn = pendingNotification
        val color = getColor(sbn)
        showEdgeLights(color)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {}
    override fun onNotificationsInitialized() {}
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {}

    companion object {
        private const val COLOR_MODE_CUSTOM = "custom"

        @Volatile
        private var INSTANCE: EdgeLightViewController? = null

        @JvmStatic
        fun get(context: Context): EdgeLightViewController {
            return INSTANCE ?: throw IllegalStateException(
                "EdgeLightViewController not initialized"
            )
        }
    }
}
