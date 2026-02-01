/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.wallpapers.data.repository

import android.app.WallpaperInfo
import android.app.WallpaperManager
import android.app.WallpaperManager.FLAG_LOCK
import android.app.WallpaperManager.FLAG_SYSTEM
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.View
import com.android.internal.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.res.R as SysUIR
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shared.Flags.ambientAod
import com.android.systemui.shared.Flags.extendedWallpaperEffects
import com.android.systemui.user.data.model.SelectedUserModel
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.utils.coroutines.flow.mapLatestConflated
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** A repository storing information about the current wallpaper. */
interface WallpaperRepository {
    /** Emits the current user's current wallpaper. */
    val wallpaperInfo: StateFlow<WallpaperInfo?>

    /**
     * Emits the current user's lockscreen wallpaper. This will emit the same value as
     * [wallpaperInfo] if the wallpaper is shared between home and lock screen.
     */
    val lockscreenWallpaperInfo: StateFlow<WallpaperInfo?>

    /** Emits true if the current user's current wallpaper supports ambient mode. */
    val wallpaperSupportsAmbientMode: Flow<Boolean>

    /** Set rootView to get its windowToken afterwards */
    var rootView: View?

    /** some wallpapers require bounds to be sent from keyguard */
    val shouldSendFocalArea: StateFlow<Boolean>

    fun sendLockScreenLayoutChangeCommand(wallpaperFocalAreaBounds: RectF)

    fun sendTapCommand(tapPosition: PointF)
}

@SysUISingleton
class WallpaperRepositoryImpl
@Inject
constructor(
    @Background private val scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    broadcastDispatcher: BroadcastDispatcher,
    userRepository: UserRepository,
    private val wallpaperManager: WallpaperManager,
    private val context: Context,
    private val secureSettings: SecureSettings,
    @ShadeDisplayAware configurationInteractor: ConfigurationInteractor,
) : WallpaperRepository {
    private val wallpaperChanged: Flow<Unit> =
        broadcastDispatcher
            .broadcastFlow(IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), user = UserHandle.ALL)
            // The `combine` defining `wallpaperSupportsAmbientMode` will not run until both of the
            // input flows emit at least once. Since this flow is an input flow, it needs to emit
            // when it starts up to ensure that the `combine` will run if the user changes before we
            // receive a ACTION_WALLPAPER_CHANGED intent.
            // Note that the `selectedUser` flow does *not* need to emit on start because
            // [UserRepository.selectedUser] is a state flow which will automatically emit a value
            // on start.
            .onStart { emit(Unit) }

    private val selectedUser: Flow<SelectedUserModel> =
        userRepository.selectedUser
            // Only update the wallpaper status once the user selection has finished.
            .filter { it.selectionStatus == SelectionStatus.SELECTION_COMPLETE }

    override val wallpaperInfo: StateFlow<WallpaperInfo?> = getWallpaperInfo(FLAG_SYSTEM)
    override val lockscreenWallpaperInfo: StateFlow<WallpaperInfo?> = getWallpaperInfo(FLAG_LOCK)
    override val wallpaperSupportsAmbientMode: Flow<Boolean> =
        combine(
                secureSettings
                    .observerFlow(
                        UserHandle.USER_ALL,
                        Settings.Secure.DOZE_ALWAYS_ON_WALLPAPER_ENABLED,
                    )
                    .onStart { emit(Unit) },
                configurationInteractor.onAnyConfigurationChange,
                ::Pair,
            )
            .map {
                val wallpaperEnabled =
                    secureSettings.getInt(Settings.Secure.DOZE_ALWAYS_ON_WALLPAPER_ENABLED, 0) == 1
                wallpaperEnabled && configEnabled() && ambientAod()
            }
            .flowOn(bgDispatcher)

    override var rootView: View? = null

    override fun sendLockScreenLayoutChangeCommand(wallpaperFocalAreaBounds: RectF) {
        if (DEBUG) {
            Log.d(TAG, "sendLockScreenLayoutChangeCommand $wallpaperFocalAreaBounds")
        }
        wallpaperManager.sendWallpaperCommand(
            /* windowToken = */ rootView?.windowToken,
            /* action = */ WallpaperManager.COMMAND_LOCKSCREEN_LAYOUT_CHANGED,
            /* x = */ 0,
            /* y = */ 0,
            /* z = */ 0,
            /* extras = */ Bundle().apply {
                putFloat("wallpaperFocalAreaLeft", wallpaperFocalAreaBounds.left)
                putFloat("wallpaperFocalAreaRight", wallpaperFocalAreaBounds.right)
                putFloat("wallpaperFocalAreaTop", wallpaperFocalAreaBounds.top)
                putFloat("wallpaperFocalAreaBottom", wallpaperFocalAreaBounds.bottom)
            },
        )
    }

    override fun sendTapCommand(tapPosition: PointF) {
        if (DEBUG) {
            Log.d(TAG, "sendTapCommand $tapPosition")
        }

        wallpaperManager.sendWallpaperCommand(
            /* windowToken = */ rootView?.windowToken,
            /* action = */ WallpaperManager.COMMAND_LOCKSCREEN_TAP,
            /* x = */ tapPosition.x.toInt(),
            /* y = */ tapPosition.y.toInt(),
            /* z = */ 0,
            /* extras = */ Bundle(),
        )
    }

    override val shouldSendFocalArea =
        lockscreenWallpaperInfo
            .map {
                val focalAreaTarget = context.resources.getString(SysUIR.string.focal_area_target)
                val shouldSendNotificationLayout = it?.component?.className == focalAreaTarget
                if (DEBUG) {
                    Log.d(
                        TAG,
                        "shouldSendNotificationLayout:$shouldSendNotificationLayout " +
                            "wallpaperInfo:${it?.component?.className}",
                    )
                }
                shouldSendNotificationLayout
            }
            .stateIn(
                scope,
                if (extendedWallpaperEffects()) SharingStarted.Eagerly else WhileSubscribed(),
                initialValue = extendedWallpaperEffects(),
            )

    private fun configEnabled(): Boolean {
        // Some devices like foldables may only support AOD wallpaper on one screen. The override
        // config adds the ability to specify general device support but allow per screen config
        val sysuiOverride =
            when (
                context.resources.getInteger(SysUIR.integer.config_dozeSupportsAodWallpaperOverride)
            ) {
                0 -> false
                1 -> true
                else -> null
            }
        return if (sysuiOverride != null) sysuiOverride
        else context.resources.getBoolean(R.bool.config_dozeSupportsAodWallpaper)
    }

    private suspend fun getWallpaper(
        selectedUser: SelectedUserModel,
        which: Int = FLAG_SYSTEM,
    ): WallpaperInfo? {
        return withContext(bgDispatcher) {
            if (which == FLAG_LOCK && wallpaperManager.lockScreenWallpaperExists()) {
                wallpaperManager.getWallpaperInfo(FLAG_LOCK, selectedUser.userInfo.id)
            } else {
                wallpaperManager.getWallpaperInfoForUser(selectedUser.userInfo.id)
            }
        }
    }

    private fun getWallpaperInfo(which: Int): StateFlow<WallpaperInfo?> =
        if (!wallpaperManager.isWallpaperSupported) {
            MutableStateFlow(null).asStateFlow()
        } else {
            combine(wallpaperChanged, selectedUser, ::Pair)
                .mapLatestConflated { (_, selectedUser) -> getWallpaper(selectedUser, which) }
                .stateIn(
                    scope,
                    // Always be listening for wallpaper changes.
                    SharingStarted.Eagerly,
                    // The initial value is null, but it should get updated pretty quickly because
                    // the `combine` should immediately kick off a fetch.
                    initialValue = null,
                )
        }

    companion object {
        private val TAG = WallpaperRepositoryImpl::class.simpleName
        private val DEBUG = true
    }
}
