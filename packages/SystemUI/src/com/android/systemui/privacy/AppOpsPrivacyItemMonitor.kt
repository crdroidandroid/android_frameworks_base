/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.privacy

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.PermissionChecker
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.os.UserHandle
import com.android.internal.annotations.GuardedBy
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.systemui.appops.AppOpItem
import com.android.systemui.appops.AppOpsController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.Assert
import com.android.systemui.util.asIndenting
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.withIncreasedIndent
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Monitors privacy items backed by app ops:
 * - Mic & Camera
 * - Location
 *
 * If [PrivacyConfig.micCameraAvailable] / [PrivacyConfig.locationAvailable] are disabled, the
 * corresponding PrivacyItems will not be reported.
 */
@SysUISingleton
class AppOpsPrivacyItemMonitor
@Inject
constructor(
    private val appOpsController: AppOpsController,
    private val userTracker: UserTracker,
    private val privacyConfig: PrivacyConfig,
    @Background private val bgExecutor: DelayableExecutor,
    private val logger: PrivacyLogger,
    private val packageManager: PackageManager,
    private val activityManager: ActivityManager,
    private val context: Context,
    private val uiEventLogger: UiEventLogger,
) : PrivacyItemMonitor {

    @VisibleForTesting
    companion object {
        val OPS_MIC_CAMERA =
            intArrayOf(
                AppOpsManager.OP_CAMERA,
                AppOpsManager.OP_PHONE_CALL_CAMERA,
                AppOpsManager.OP_RECORD_AUDIO,
                AppOpsManager.OP_PHONE_CALL_MICROPHONE,
                AppOpsManager.OP_RECEIVE_AMBIENT_TRIGGER_AUDIO,
                AppOpsManager.OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO,
                AppOpsManager.OP_RECEIVE_SANDBOX_TRIGGER_AUDIO,
            )
        val OPS_LOCATION = intArrayOf(AppOpsManager.OP_FINE_LOCATION)
        val OPS = OPS_MIC_CAMERA + OPS_LOCATION
        val USER_INDEPENDENT_OPS =
            intArrayOf(AppOpsManager.OP_PHONE_CALL_CAMERA, AppOpsManager.OP_PHONE_CALL_MICROPHONE)
    }

    private val lock = Any()

    @GuardedBy("lock") private var callback: PrivacyItemMonitor.Callback? = null
    @GuardedBy("lock") private var micCameraAvailable = privacyConfig.micCameraAvailable
    @GuardedBy("lock") private var locationAvailable = privacyConfig.locationAvailable
    @GuardedBy("lock") private var listening = false

    // Various state needed for logging

    // True if the location indicator was ON, when location delivered to foreground, non-system apps
    @GuardedBy("lock") private var lastLocationIndicator = false

    // True if the location indicator was ON, when location delivered to foreground, background,
    // system apps
    @GuardedBy("lock") private var lastLocationIndicatorWithSystem = false

    // True if the location indicator was ON, when location delivered to foreground, system,
    // non-system apps
    @GuardedBy("lock") private var lastLocationIndicatorWithBackround = false

    // True if the location indicator was ON, when location delivered to all apps
    @GuardedBy("lock") private var lastLocationIndicatorWithSystemAndBackround = false

    // Keep track of the last MONITOR_HIGH_POWER_LOCATION appOp, since this is not included in the
    // PrivacyItems but needs to be tracked for logging purposes.
    @GuardedBy("lock") private var lastHighPowerLocationOp = false

    // The following keep track of whether a type of location client exists in the current round
    // of active appOps

    @GuardedBy("lock") private var hasHighPowerLocationAccess = false
    @GuardedBy("lock") private var hasSystemLocationAccess = false
    @GuardedBy("lock") private var hasBackgroundLocationAccess = false
    @GuardedBy("lock") private var hasNonSystemForegroundLocationAccess = false

    private val appOpsCallback =
        object : AppOpsController.Callback {
            override fun onActiveStateChanged(
                code: Int,
                uid: Int,
                packageName: String,
                active: Boolean,
            ) {
                synchronized(lock) {
                    // Check if we care about this code right now
                    if (code in OPS_MIC_CAMERA && !micCameraAvailable) {
                        return
                    }
                    if (code in OPS_LOCATION && !locationAvailable) {
                        return
                    }
                    // Hide incoming chip from sense caller package
                    if (packageName == "co.aospa.sense") {
                        return
                    }
                    if (
                        userTracker.userProfiles.any { it.id == UserHandle.getUserId(uid) } ||
                            code in USER_INDEPENDENT_OPS
                    ) {
                        logger.logUpdatedItemFromAppOps(code, uid, packageName, active)

                        if (code in OPS_LOCATION) {
                            val procInfo =
                                (activityManager.runningAppProcesses ?: emptyList()).find {
                                    it.uid == uid
                                }
                            val importance =
                                procInfo?.importance ?: -1 // Use -1 if process not found
                            logger.logLocationAppOps(
                                uid,
                                packageName,
                                importance,
                                !isBackgroundApp(uid),
                                isSystemApp(code, uid, packageName),
                            )
                        }

                        dispatchOnPrivacyItemsChanged()
                    }
                }
            }
        }

    @VisibleForTesting
    internal val userTrackerCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                onCurrentProfilesChanged()
            }

            override fun onProfilesChanged(profiles: List<UserInfo>) {
                onCurrentProfilesChanged()
            }
        }

    private val configCallback =
        object : PrivacyConfig.Callback {
            override fun onFlagLocationChanged(flag: Boolean) {
                onFlagChanged()
            }

            override fun onFlagMicCameraChanged(flag: Boolean) {
                onFlagChanged()
            }

            private fun onFlagChanged() {
                synchronized(lock) {
                    micCameraAvailable = privacyConfig.micCameraAvailable
                    locationAvailable = privacyConfig.locationAvailable
                    setListeningStateLocked()
                }
                dispatchOnPrivacyItemsChanged()
            }
        }

    init {
        privacyConfig.addCallback(configCallback)
    }

    override fun startListening(callback: PrivacyItemMonitor.Callback) {
        synchronized(lock) {
            this.callback = callback
            setListeningStateLocked()
        }
    }

    override fun stopListening() {
        synchronized(lock) {
            this.callback = null
            setListeningStateLocked()
        }
    }

    /**
     * Updates listening status based on whether there are callbacks and the indicators are enabled.
     *
     * Always listen to all OPS so we don't have to figure out what we should be listening to. We
     * still have to filter anyway. Updates are filtered in the callback.
     *
     * This is only called from private (add/remove)Callback and from the config listener, all in
     * main thread.
     */
    @GuardedBy("lock")
    private fun setListeningStateLocked() {
        val shouldListen = callback != null && (micCameraAvailable || locationAvailable)
        if (listening == shouldListen) {
            return
        }

        listening = shouldListen
        if (shouldListen) {
            appOpsController.addCallback(OPS, appOpsCallback)
            userTracker.addCallback(userTrackerCallback, bgExecutor)
            onCurrentProfilesChanged()
        } else {
            appOpsController.removeCallback(OPS, appOpsCallback)
            userTracker.removeCallback(userTrackerCallback)
        }
    }

    override fun getActivePrivacyItems(): List<PrivacyItem> {
        val activeAppOps = appOpsController.getActiveAppOps(true)
        val currentUserProfiles = userTracker.userProfiles

        // TODO(b/419834493): Consider refactoring this into a Flow that could be configured to run
        // on a bg context.
        Assert.isNotMainThread()
        val items =
            synchronized(lock) {
                    activeAppOps
                        .filter {
                            currentUserProfiles.any { user ->
                                user.id == UserHandle.getUserId(it.uid)
                            } || it.code in USER_INDEPENDENT_OPS
                        }
                        .filter { shouldDisplayLocationOp(it) }
                        .mapNotNull { toPrivacyItemLocked(it) }
                }
                .distinct()

        if (locationAvailable) {
            // Types of location accesses were stored when iterating through the app ops in
            // #shouldDisplayLocationOp and now they will be logged and the state will be cleared
            logLocationAccesses()

            // Keep track of the current privacy items in order to determine whether to log the next
            // round of privacy item changes.
            val locationOp =
                activeAppOps
                    .filter {
                        currentUserProfiles.any { user -> user.id == UserHandle.getUserId(it.uid) }
                    }
                    .filter { item -> item.code == AppOpsManager.OP_FINE_LOCATION }
                    .distinct()
            val locationOpBySystem = locationOp.any { item -> isSystemApp(item) }
            val locationOpByBackground = locationOp.any { item -> isBackgroundApp(item.uid) }
            synchronized(lock) {
                lastLocationIndicator = items.any { it.privacyType == PrivacyType.TYPE_LOCATION }
                lastLocationIndicatorWithSystem = lastLocationIndicator || locationOpBySystem
                lastLocationIndicatorWithBackround = lastLocationIndicator || locationOpByBackground
                lastLocationIndicatorWithSystemAndBackround =
                    lastLocationIndicator || locationOpBySystem || locationOpByBackground
                lastHighPowerLocationOp =
                    activeAppOps.any { it.code == AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION }
            }
        }

        return items
    }

    /**
     * Log which appOps would cause the location indicator to show in various situations. This
     * should only be logged if the location indicator was not already showing because the op would
     * not result in a change in the indicator display.
     */
    private fun logLocationAccesses() {
        // TODO(b/419834493): Add logbuffer logging here for bugreport debugging.
        synchronized(lock) {
            logLocationIndicatorEvent(
                lastState = lastHighPowerLocationOp,
                currentState = hasHighPowerLocationAccess,
                onEvent = LocationIndicatorEvent.LOCATION_INDICATOR_MONITOR_HIGH_POWER,
                offEvent = LocationIndicatorEvent.LOCATION_INDICATOR_MONITOR_HIGH_POWER_OFF,
            )
            logLocationIndicatorEvent(
                lastState = lastLocationIndicator,
                currentState = hasNonSystemForegroundLocationAccess,
                onEvent = LocationIndicatorEvent.LOCATION_INDICATOR_NON_SYSTEM_APP,
                offEvent = LocationIndicatorEvent.LOCATION_INDICATOR_NON_SYSTEM_APP_OFF,
            )

            // No background access
            val hasSystemAccess = hasNonSystemForegroundLocationAccess || hasSystemLocationAccess
            logLocationIndicatorEvent(
                lastState = lastLocationIndicatorWithSystem,
                currentState = hasSystemAccess,
                onEvent = LocationIndicatorEvent.LOCATION_INDICATOR_SYSTEM_APP,
                offEvent = LocationIndicatorEvent.LOCATION_INDICATOR_SYSTEM_APP_OFF,
            )

            // No system access
            val hasBackgroundAccess =
                hasNonSystemForegroundLocationAccess || hasBackgroundLocationAccess
            logLocationIndicatorEvent(
                lastState = lastLocationIndicatorWithBackround,
                currentState = hasBackgroundAccess,
                onEvent = LocationIndicatorEvent.LOCATION_INDICATOR_BACKGROUND_APP,
                offEvent = LocationIndicatorEvent.LOCATION_INDICATOR_BACKGROUND_APP_OFF,
            )

            val hasAllAccess =
                hasNonSystemForegroundLocationAccess ||
                    hasSystemLocationAccess ||
                    hasBackgroundLocationAccess
            logLocationIndicatorEvent(
                lastLocationIndicatorWithSystemAndBackround,
                hasAllAccess,
                LocationIndicatorEvent.LOCATION_INDICATOR_ALL_APP,
                LocationIndicatorEvent.LOCATION_INDICATOR_ALL_APP_OFF,
            )

            hasHighPowerLocationAccess = false
            hasSystemLocationAccess = false
            hasBackgroundLocationAccess = false
            hasNonSystemForegroundLocationAccess = false
        }
    }

    private fun logLocationIndicatorEvent(
        lastState: Boolean,
        currentState: Boolean,
        onEvent: LocationIndicatorEvent,
        offEvent: LocationIndicatorEvent,
    ) {
        if (lastState != currentState) {
            uiEventLogger.log(if (currentState) onEvent else offEvent)
        }
    }

    @GuardedBy("lock")
    private fun privacyItemForAppOpEnabledLocked(code: Int): Boolean {
        if (code in OPS_LOCATION) {
            return locationAvailable
        } else if (code in OPS_MIC_CAMERA) {
            return micCameraAvailable
        } else {
            return false
        }
    }

    @GuardedBy("lock")
    private fun toPrivacyItemLocked(appOpItem: AppOpItem): PrivacyItem? {
        if (!privacyItemForAppOpEnabledLocked(appOpItem.code)) {
            return null
        }
        val type: PrivacyType =
            when (appOpItem.code) {
                AppOpsManager.OP_PHONE_CALL_CAMERA,
                AppOpsManager.OP_CAMERA -> PrivacyType.TYPE_CAMERA
                AppOpsManager.OP_FINE_LOCATION -> PrivacyType.TYPE_LOCATION
                AppOpsManager.OP_PHONE_CALL_MICROPHONE,
                AppOpsManager.OP_RECEIVE_AMBIENT_TRIGGER_AUDIO,
                AppOpsManager.OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO,
                AppOpsManager.OP_RECEIVE_SANDBOX_TRIGGER_AUDIO,
                AppOpsManager.OP_RECORD_AUDIO -> PrivacyType.TYPE_MICROPHONE
                else -> return null
            }
        // Hide incoming chip from sense caller package
        if (appOpItem.packageName == "co.aospa.sense") {
            return null
        }
        val app = PrivacyApplication(appOpItem.packageName, appOpItem.uid)
        return PrivacyItem(type, app, appOpItem.timeStartedElapsed, appOpItem.isDisabled)
    }

    private fun onCurrentProfilesChanged() {
        val currentUserIds = userTracker.userProfiles.map { it.id }
        logger.logCurrentProfilesChanged(currentUserIds)
        dispatchOnPrivacyItemsChanged()
    }

    private fun dispatchOnPrivacyItemsChanged() {
        val cb = synchronized(lock) { callback }
        if (cb != null) {
            bgExecutor.execute { cb.onPrivacyItemsChanged() }
        }
    }

    /**
     * Only display the location privacy item if a non-system, foreground client accessed location
     *
     * This method has the side effects of updating [hasSystemLocationAccess],
     * [hasBackgroundLocationAccess], [hasNonSystemForegroundLocationAccess], and
     * [hasHighPowerLocationAccess]
     */
    private fun shouldDisplayLocationOp(item: AppOpItem): Boolean {
        if (!locationAvailable) {
            // This is to avoid unnecessary work in updating haXXXLocationAccess booleans, although
            // updating them does nothing since logLocationAccess() is not invoked in this case.
            // Note that the logic for "filtering locationOps by locationAvailable" is in
            // toPrivacyItemLocked(), not this method.
            return true
        }

        if (item.code == AppOpsManager.OP_FINE_LOCATION) {
            val isSystem = isSystemApp(item)
            val isBackground = isBackgroundApp(item.uid)
            if (isSystem) {
                synchronized(lock) { hasSystemLocationAccess = true }
            }
            if (isBackground) {
                synchronized(lock) { hasBackgroundLocationAccess = true }
            }
            val result = !isSystem && !isBackground
            if (result) {
                synchronized(lock) { hasNonSystemForegroundLocationAccess = true }
            }
            return result
        }
        if (item.code == AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION) {
            synchronized(lock) { hasHighPowerLocationAccess = true }
        }
        return true
    }

    /**
     * Returns true if the package is a system app.
     *
     * <p>TODO(b/422799135): refactor isSystemApp() and isBackgroundApp(). Before this is fixed,
     * make sure to update PermissionUsageHelper when changing this method.
     */
    private fun isSystemApp(item: AppOpItem): Boolean {
        return isSystemApp(item.code, item.uid, item.packageName)
    }
    private fun isSystemApp(code: Int, uid: Int, packageName: String): Boolean {
        val user = UserHandle.getUserHandleForUid(uid)

        // Don't show apps belonging to background users except managed users.
        var foundUser = false
        for (profile in userTracker.userProfiles) {
            if (profile.userHandle == user) {
                foundUser = true
            }
        }
        if (!foundUser) {
            return true
        }

        val permission = AppOpsManager.opToPermission(code)
        val permissionFlags: Int =
            packageManager.getPermissionFlags(permission, packageName, user)
        val isSystem =
            if (
                PermissionChecker.checkPermissionForPreflight(
                    context,
                    permission,
                    PermissionChecker.PID_UNKNOWN,
                    uid,
                    packageName,
                ) == PermissionChecker.PERMISSION_GRANTED
            ) {
                ((permissionFlags and PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED) ==
                    0)
            } else {
                (permissionFlags and PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED) == 0
            }
        return isSystem
    }

    /**
     * Returns true if it is a background app
     *
     * <p>TODO(b/422799135): refactor isSystemApp() and isBackgroundApp(). Before this is fixed,
     * make sure to update PermissionUsageHelper when changing this method.
     */
    private fun isBackgroundApp(uid: Int): Boolean {
        for (processInfo in activityManager.runningAppProcesses) {
            if (processInfo.uid == uid) {
                return (processInfo.importance >
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)
            }
        }
        return false
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val ipw = pw.asIndenting()
        ipw.println("AppOpsPrivacyItemMonitor:")
        ipw.withIncreasedIndent {
            synchronized(lock) {
                ipw.println("Listening: $listening")
                ipw.println("micCameraAvailable: $micCameraAvailable")
                ipw.println("locationAvailable: $locationAvailable")
                ipw.println("Callback: $callback")
            }
            ipw.println("Current user ids: ${userTracker.userProfiles.map { it.id }}")
        }
        ipw.flush()
    }

    /** Enum for events which prompt the location indicator to appear. */
    enum class LocationIndicatorEvent(private val id: Int) : UiEventLogger.UiEventEnum {
        // Copied from LocationControllerImpl.java
        @UiEvent(doc = "Location indicator shown for high power access")
        LOCATION_INDICATOR_MONITOR_HIGH_POWER(935),
        @UiEvent(doc = "Location indicator hidden for high power access")
        LOCATION_INDICATOR_MONITOR_HIGH_POWER_OFF(2417),
        // Copied from LocationControllerImpl.java
        @UiEvent(
            doc =
                "Location indicator shown for system, non-system, foreground app access (i.e., excluding background)"
        )
        LOCATION_INDICATOR_SYSTEM_APP(936),
        @UiEvent(
            doc =
                "Location indicator hidden for system, non-system, foreground app access (i.e., excluding background)"
        )
        LOCATION_INDICATOR_SYSTEM_APP_OFF(2418),
        // Copied from LocationControllerImpl.java
        @UiEvent(
            doc =
                "Location indicator shown for non-system, foreground app access (i.e., excluding system and background)"
        )
        LOCATION_INDICATOR_NON_SYSTEM_APP(937),
        @UiEvent(
            doc =
                "Location indicator hidden for non-system, foreground app access (i.e., excluding system and background)"
        )
        LOCATION_INDICATOR_NON_SYSTEM_APP_OFF(2419),
        @UiEvent(
            doc =
                "Location indicator shown for non-system, foreground, background app access (i.e., excluding system)"
        )
        LOCATION_INDICATOR_BACKGROUND_APP(2325),
        @UiEvent(
            doc =
                "Location indicator hidden for non-system, foreground, background app access (i.e., excluding system)"
        )
        LOCATION_INDICATOR_BACKGROUND_APP_OFF(2420),
        @UiEvent(doc = "Location indicator shown for all access") LOCATION_INDICATOR_ALL_APP(2354),
        @UiEvent(doc = "Location indicator hidden for all access")
        LOCATION_INDICATOR_ALL_APP_OFF(2421);

        override fun getId(): Int {
            return id
        }
    }
}
