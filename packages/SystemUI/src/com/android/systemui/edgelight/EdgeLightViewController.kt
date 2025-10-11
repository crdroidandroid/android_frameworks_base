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
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.widget.FrameLayout

import com.android.settingslib.Utils

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.util.ScrimUtils
import com.android.internal.util.ContrastColorUtil

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

    private val wallpaperManager = context.getSystemService(WallpaperManager::class.java)!!

    private var currentSettings = settingsRepo.currentSettings()

    private var job: Job? = null

    private val dozing: Boolean
        get() = ScrimUtils.get().isDozing()

    private var lastNotificationKey: String? = null
    private var lastNotificationText: CharSequence? = null
    private var lastNotificationTime: Long = 0L
    private val deduplicationWindowMs = 3000L
    private var lastNotifColor: Int = Color.TRANSPARENT

    init {
        INSTANCE = this

        ScrimUtils.get().addListener(this)
        updateView()
    }

    fun getEdgeLightView(): FrameLayout = edgeLightView

    private fun getColor(): Int =
        when (currentSettings.colorMode) {
            COLOR_MODE_ACCENT -> Utils.getColorAccentDefaultColor(context)
            COLOR_MODE_CUSTOM -> currentSettings.customColor
            COLOR_MODE_WALLPAPER -> wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    ?.primaryColor?.toArgb() ?: Utils.getColorAccentDefaultColor(context)
            COLOR_MODE_NOTIFICATION -> lastNotifColor
            else -> Utils.getColorAccentDefaultColor(context)
        }

    private fun updateView() {
        job?.cancel()
        job = scope.launch {
            currentSettings = settingsRepo.settingsFlow.first()
            if (!currentSettings.isEnabled) {
                edgeLightView.pulseRunning = false
                edgeLightView.visible = false
            } else {
                edgeLightView.paintColor = getColor()
                edgeLightView.userPulseCount = currentSettings.pulseCount
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!currentSettings.isEnabled || !dozing
                || currentSettings.colorMode != COLOR_MODE_NOTIFICATION) return

        val currentKey = sbn.key
        val currentText = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        val now = System.currentTimeMillis()

        if (currentKey == lastNotificationKey &&
            currentText == lastNotificationText &&
            (now - lastNotificationTime <= deduplicationWindowMs)) {
            return
        }

        lastNotificationKey = currentKey
        lastNotificationText = currentText
        lastNotificationTime = now

        val notifColor = sbn?.notification?.color ?: Color.TRANSPARENT
        val accent = Utils.getColorAccentDefaultColor(context)

        lastNotifColor = when {
            notifColor == Color.TRANSPARENT || notifColor == 0 -> accent
            ContrastColorUtil.isColorDark(notifColor) -> accent
            else -> notifColor
        }
        edgeLightView.paintColor = lastNotifColor
    }

    override fun onDozingChanged() {
        if (!currentSettings.isEnabled) return
        if (!dozing) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
        }
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        if (!currentSettings.isEnabled) return
        if (!showing) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
            listener.removeNotificationHandler(this)
        } else {
            listener.addNotificationHandler(this)
            updateView()
        }
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        if (!currentSettings.isEnabled) return
        if (fadingAway) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
        }
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        if (!currentSettings.isEnabled) return
        if (goingAway) {
            edgeLightView.pulseRunning = false
            edgeLightView.visible = false
        }
    }

    override fun setPulsing(pulsing: Boolean) {
        if (!currentSettings.isEnabled || !pulsing || !dozing) return
        edgeLightView.apply {
            visible = true
            pulseRunning = true
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {}
    override fun onNotificationsInitialized() {}
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {}

    companion object {
        private const val COLOR_MODE_ACCENT = "accent"
        private const val COLOR_MODE_NOTIFICATION = "notification"
        private const val COLOR_MODE_WALLPAPER = "wallpaper"
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
