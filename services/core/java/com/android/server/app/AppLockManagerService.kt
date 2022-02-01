/*
 * Copyright (C) 2022 FlamingoOS Project
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

package com.android.server.app

import android.Manifest
import android.annotation.RequiresPermission
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManagerInternal
import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.app.AlarmManager
import android.app.AppLockData
import android.app.AppLockManager
import android.app.IAppLockManagerService
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.pm.PackageManagerInternal
import android.os.Binder
import android.os.Environment
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.os.UserHandle
import android.util.ArrayMap
import android.util.ArraySet
import android.util.Log
import android.util.Slog

import com.android.internal.R
import com.android.internal.annotations.GuardedBy
import com.android.server.LocalServices
import com.android.server.SystemService
import com.android.server.notification.NotificationManagerInternal
import com.android.server.pm.UserManagerInternal
import com.android.server.wm.ActivityInterceptorCallback.ActivityInterceptorInfo
import com.android.server.wm.ActivityTaskManagerInternal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal val TAG = AppLockManagerService::class.simpleName

private const val ACTION_APP_LOCK_TIMEOUT = "com.android.server.app.AppLockManagerService.APP_LOCK_TIMEOUT"
private const val SETTINGS_PACKAGE = "com.android.settings"

internal inline fun logD(crossinline msg: () -> String) {
    if (Log.isLoggable(TAG, Log.DEBUG)) {
        Slog.d(TAG, msg())
    }
}

/**
 * Service to manage per app lock.
 *
 * @hide
 */
class AppLockManagerService(
    private val context: Context
) : IAppLockManagerService.Stub() {

    private val localService = LocalService()
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    private val currentUserId: Int
        get() = activityManagerInternal.currentUserId

    private var isDeviceSecure = false

    private val mutex = Mutex()

    @GuardedBy("mutex")
    private val userConfigMap = ArrayMap<Int, AppLockConfig>()

    @GuardedBy("mutex")
    private val topPackages = ArraySet<String>()

    @GuardedBy("mutex")
    private val unlockedPackages = ArraySet<String>()

    private val biometricUnlocker: BiometricUnlocker by lazy {
        BiometricUnlocker(context)
    }

    private val atmInternal: ActivityTaskManagerInternal by lazy {
        LocalServices.getService(ActivityTaskManagerInternal::class.java)
    }

    private val notificationManagerInternal: NotificationManagerInternal by lazy {
        LocalServices.getService(NotificationManagerInternal::class.java)
    }

    private val keyguardManager: KeyguardManager by lazy {
        context.getSystemService(KeyguardManager::class.java)
    }

    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(AlarmManager::class.java)
    }

    private val userManagerInternal: UserManagerInternal by lazy {
        LocalServices.getService(UserManagerInternal::class.java)
    }

    private val activityManagerInternal: ActivityManagerInternal by lazy {
        LocalServices.getService(ActivityManagerInternal::class.java)
    }

    private val packageManager: PackageManager by lazy {
        context.packageManager
    }

    private val pmInternal: PackageManagerInternal by lazy {
        LocalServices.getService(PackageManagerInternal::class.java)
    }

    private var deviceLocked = false

    private val alarmsMutex = Mutex()

    @GuardedBy("alarmsMutex")
    private val scheduledAlarms = ArrayMap<String, PendingIntent>()

    private val whiteListedSystemApps: List<String> by lazy {
        val systemPackages = pmInternal.getInstalledApplications(
            PackageManager.MATCH_SYSTEM_ONLY.toLong(),
            currentUserId,
            Process.myUid()
        ).map { it.packageName }
        context.resources.getStringArray(R.array.config_appLockAllowedSystemApps).filter {
            systemPackages.contains(it)
        }
    }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_PACKAGE_REMOVED) return
            val userId = getSendingUserId()
            if (userId != currentUserId) {
                logD {
                    "Ignoring package removal broadcast from user $userId"
                }
                return
            }
            val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false /* defaultValue */)
            if (isReplacing) {
                logD {
                    "Ignoring package update broadcast"
                }
                return
            }
            val packageName = intent.data?.schemeSpecificPart ?: run {
                Slog.e(TAG, "Failed to get package name")
                return
            }
            serviceScope.launch {
                val config = mutex.withLock {
                    userConfigMap[userId] ?: run {
                        Slog.e(TAG, "Config unavailable for user $userId")
                        return@launch
                    }
                }
                mutex.withLock {
                    if (!config.isPackageProtected(packageName)) {
                        logD {
                            "Package $packageName not in the list, ignoring"
                        }
                        return@launch
                    }
                }
                logD {
                    "Package $packageName uninstalled, cleaning up"
                }
                alarmsMutex.withLock {
                    scheduledAlarms.remove(packageName)?.let {
                        alarmManager.cancel(it)
                    }
                }
                mutex.withLock {
                    unlockedPackages.remove(packageName)
                    if (config.removePackage(packageName)) {
                        withContext(Dispatchers.IO) {
                            config.write()
                        }
                    }
                }
            }
        }
    }

    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskStackChanged() {
            logD {
                "onTaskStackChanged"
            }
            serviceScope.launch {
                val currentTopPackages = atmInternal.topVisibleActivities.map {
                    it.activityToken
                }.filter {
                    atmInternal.isVisibleActivity(it)
                }.map {
                    atmInternal.getActivityName(it)?.packageName
                }.filterNotNull().toSet()
                logD {
                    "currentTopPackages = $currentTopPackages"
                }
                // We should return early if current top packages
                // are empty to avoid doing anything absurd.
                if (currentTopPackages.isEmpty()) return@launch
                val packagesToLock = mutex.withLock {
                    logD {
                        "topPackages = $topPackages"
                    }
                    val packages = topPackages.filter {
                        !currentTopPackages.contains(it) && unlockedPackages.contains(it)
                    }.toSet()
                    topPackages.clear()
                    topPackages.addAll(currentTopPackages)
                    return@withLock packages
                }
                packagesToLock.forEach {
                    scheduleLockAlarm(it)
                }
                alarmsMutex.withLock {
                    currentTopPackages.forEach { pkg ->
                        scheduledAlarms.remove(pkg)?.let {
                            logD {
                                "Cancelling timeout alarm for $pkg"
                            }
                            alarmManager.cancel(it)
                        }
                    }
                }
                currentTopPackages.forEach {
                    checkAndUnlockPackage(it)
                }
            }
        }

        override fun onActivityUnpinned() {
            logD {
                "onActivityUnpinned"
            }
            onTaskStackChanged()
        }
    }

    private fun scheduleLockAlarm(pkg: String) {
        logD {
            "scheduleLockAlarm, package = $pkg"
        }
        serviceScope.launch {
            alarmsMutex.withLock {
                if (scheduledAlarms.containsKey(pkg)) {
                    logD {
                        "Alarm already scheduled for package $pkg"
                    }
                    return@launch
                }
            }
            val timeout = mutex.withLock {
                userConfigMap[currentUserId]?.appLockTimeout
            } ?: run {
                Slog.e(TAG, "Failed to retrieve user config for $currentUserId")
                return@launch
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                pkg.hashCode(),
                Intent(ACTION_APP_LOCK_TIMEOUT).apply {
                    putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + timeout,
                pendingIntent
            )
            alarmsMutex.withLock {
                scheduledAlarms[pkg] = pendingIntent
            }
        }
    }

    private fun checkAndUnlockPackage(pkg: String) {
        if (!isDeviceSecure) return
        serviceScope.launch {
            mutex.withLock {
                if (unlockedPackages.contains(pkg)) return@launch
                val config = userConfigMap[currentUserId] ?: run {
                    Slog.e(TAG, "Config unavailable for user $currentUserId")
                    return@launch
                }
                if (!config.isPackageProtected(pkg)) return@launch
            }
            logD {
                "$pkg is locked out, asking user to unlock"
            }
            unlockInternal(pkg, currentUserId,
                onSuccess = {
                    serviceScope.launch {
                        mutex.withLock {
                            unlockedPackages.add(pkg)
                        }
                    }
                },
                onCancel = {
                    // Send user to home on cancel
                    context.mainExecutor.execute {
                        atmInternal.startHomeActivity(currentUserId,
                            "unlockInternal#onCancel")
                    }
                }
            )
        }
    }

    private val lockAlarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_APP_LOCK_TIMEOUT) return
            logD {
                "Lock alarm received"
            }
            val packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME) ?: return
            logD {
                "$packageName timed out"
            }
            serviceScope.launch {
                mutex.withLock {
                    if (topPackages.contains(packageName)) {
                        logD {
                            "$packageName is currently in foreground, skipping lock"
                        }
                        // Mark it as unlocked, since it actually is
                        unlockedPackages.add(packageName)
                        return@withLock
                    }
                    unlockedPackages.remove(packageName)
                }
                alarmsMutex.withLock {
                    scheduledAlarms.remove(packageName)
                }
                val isContentSecure = mutex.withLock {
                    userConfigMap[currentUserId]?.shouldRedactNotification(packageName) ?: run {
                        Slog.e(TAG, "Config unavailable for user $currentUserId")
                        return@launch
                    }
                }
                notificationManagerInternal.updateSecureNotifications(
                    packageName,
                    isContentSecure,
                    true /* isBubbleUpSuppressed */,
                    currentUserId
                )
            }
        }
    }

    private fun getActualUserId(userId: Int, tag: String): Int {
        return ActivityManager.handleIncomingUser(Binder.getCallingPid(),
            Binder.getCallingUid(), userId, false /* allowAll */,
            true /* requireFull */, tag, AppLockManagerService::class.qualifiedName)
    }

    private inline fun <R> clearAndExecute(crossinline block: () -> R): R {
        val ident = Binder.clearCallingIdentity()
        try {
            return block()
        } finally {
            Binder.restoreCallingIdentity(ident)
        }
    }

    private fun unlockInternal(
        pkg: String,
        userId: Int,
        onSuccess: () -> Unit,
        onCancel: () -> Unit,
    ) {
        clearAndExecute {
            if (!biometricUnlocker.canUnlock()) {
                Slog.e(TAG, "Application cannot be unlocked with biometrics or device credentials")
                return@clearAndExecute
            }
            biometricUnlocker.unlock(getLabelForPackage(pkg, userId), onSuccess, onCancel)
        }
    }

    private fun getLabelForPackage(pkg: String, userId: Int): String? =
        try {
            pmInternal.getApplicationInfo(
                pkg,
                PackageManager.MATCH_ALL.toLong(),
                Process.myUid(),
                userId,
            ).loadLabel(packageManager).toString()
        } catch(e: PackageManager.NameNotFoundException) {
            Slog.e(TAG, "Package $pkg not found")
            null
        }

    /**
     * Add an application to be protected.
     *
     * @param packageName the package name of the app to add.
     * @param userId the user id of the caller.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     * @throws [IllegalArgumentException] if package is a system app that
     *     is not whitelisted in [R.array.config_appLockAllowedSystemApps],
     *     or if package is not installed.
     */
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    override fun addPackage(packageName: String, userId: Int) {
        logD {
            "addPackage: packageName = $packageName, userId = $userId"
        }
        enforceCallingPermission("addPackage")
        checkPackage(packageName, userId)
        val actualUserId = getActualUserId(userId, "addPackage")
        serviceScope.launch {
            mutex.withLock {
                val config = userConfigMap[actualUserId] ?: run {
                    Slog.e(TAG, "addPackage requested by unknown user id $actualUserId")
                    return@withLock
                }
                if (!config.addPackage(packageName)) return@withLock
                // Collapse any active notifications or bubbles for the app.
                if (!topPackages.contains(packageName)) {
                    notificationManagerInternal.updateSecureNotifications(
                        packageName,
                        true /* isContentSecure */,
                        true /* isBubbleUpSuppressed */,
                        actualUserId
                    )
                }
                withContext(Dispatchers.IO) {
                    config.write()
                }
            }
        }
    }

    private fun checkPackage(pkg: String, userId: Int) {
        try {
            val aInfo = pmInternal.getApplicationInfo(
                pkg,
                PackageManager.MATCH_ALL.toLong(),
                Process.myUid(),
                userId
            )
            if (!aInfo.isSystemApp()) return
            if (!whiteListedSystemApps.contains(pkg))
                throw IllegalArgumentException("System package $pkg is not whitelisted")
        } catch(e: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("Package $pkg is not installed")
        }
    }

    /**
     * Remove an application from the protected packages list.
     *
     * @param packageName the package name of the app to remove.
     * @param userId the user id of the caller.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     */
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    override fun removePackage(packageName: String, userId: Int) {
        logD {
            "removePackage: packageName = $packageName, userId = $userId"
        }
        enforceCallingPermission("removePackage")
        val actualUserId = getActualUserId(userId, "removePackage")
        serviceScope.launch {
            mutex.withLock {
                val config = userConfigMap[actualUserId] ?: run {
                    Slog.e(TAG, "removePackage requested by unknown user id $actualUserId")
                    return@withLock
                }
                if (!config.removePackage(packageName)) return@withLock
                // Let active notifications be expanded since the app
                // is no longer protected.
                notificationManagerInternal.updateSecureNotifications(
                    packageName,
                    false /* isContentSecure */,
                    false /* isBubbleUpSuppressed */,
                    actualUserId
                )
                withContext(Dispatchers.IO) {
                    config.write()
                }
            }
        }
    }

    /**
     * Get the current auto lock timeout.
     *
     * @param userId the user id of the caller.
     * @return the timeout in milliseconds if configuration for
     *     current user exists, -1 otherwise.
     */
    override fun getTimeout(userId: Int): Long {
        logD {
            "getTimeout: userId = $userId"
        }
        val actualUserId = getActualUserId(userId, "getTimeout")
        return runBlocking {
            mutex.withLock {
                userConfigMap[actualUserId]?.let { it.appLockTimeout } ?: run {
                    Slog.e(TAG, "getTimeout requested by unknown user id $actualUserId")
                    -1L
                }
            }
        }
    }

    /**
     * Set auto lock timeout.
     *
     * @param timeout the timeout in milliseconds. Must be >= 5.
     * @param userId the user id of the caller.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     */
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    override fun setTimeout(timeout: Long, userId: Int) {
        logD {
            "setTimeout: timeout = $timeout, userId = $userId"
        }
        if (timeout < 5L) {
            throw IllegalArgumentException("Timeout must be greater than or equal to 5")
        }
        enforceCallingPermission("setTimeout")
        val actualUserId = getActualUserId(userId, "setTimeout")
        serviceScope.launch {
            mutex.withLock {
                val config = userConfigMap[actualUserId] ?: run {
                    Slog.e(TAG, "setTimeout requested by unknown user id $actualUserId")
                    return@withLock
                }
                if (config.appLockTimeout == timeout) return@withLock
                config.appLockTimeout = timeout
                withContext(Dispatchers.IO) {
                    config.write()
                }
            }
        }
    }

    /**
     * Get all the packages protected with app lock.
     *
     * @param userId the user id of the caller.
     * @return list of [AppLockData] of the protected apps.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     */
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    override fun getPackageData(userId: Int): List<AppLockData> {
        logD {
            "getPackages: userId = $userId"
        }
        enforceCallingPermission("getPackages")
        val actualUserId = getActualUserId(userId, "getPackages")
        return runBlocking {
            mutex.withLock {
                userConfigMap[actualUserId]?.getAppLockDataList() ?: run {
                    Slog.e(TAG, "getPackages requested by unknown user id $actualUserId")
                    emptyList()
                }
            }
        }
    }

    /**
     * Set whether notification content should be redacted for a package
     * in locked state.
     *
     * @param packageName the package name.
     * @param shouldRedactNotification true to hide notification content.
     * @param userId the user id of the caller.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     */
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    override fun setShouldRedactNotification(
        packageName: String,
        shouldRedactNotification: Boolean,
        userId: Int,
    ) {
        logD {
            "setShouldRedactNotification: packageName = $packageName, userId = $userId"
        }
        enforceCallingPermission("setShouldRedactNotification")
        val actualUserId = getActualUserId(userId, "setShouldRedactNotification")
        serviceScope.launch {
            mutex.withLock {
                val config = userConfigMap[actualUserId] ?: run {
                    Slog.e(TAG, "setShouldRedactNotification requested by unknown " +
                        "user id $actualUserId")
                    return@withLock
                }
                if (!config.setShouldRedactNotification(packageName, shouldRedactNotification)) {
                    return@withLock
                }
                val isLocked = !unlockedPackages.contains(packageName)
                    && !topPackages.contains(packageName)
                val shouldSecureContent = shouldRedactNotification && isLocked
                notificationManagerInternal.updateSecureNotifications(
                    packageName,
                    shouldSecureContent,
                    isLocked /* isBubbleUpSuppressed */,
                    actualUserId
                )
                withContext(Dispatchers.IO) {
                    config.write()
                }
            }
        }
    }

    /**
     * Set whether to allow unlocking with biometrics.
     *
     * @param biometricsAllowed whether to use biometrics.
     * @param userId the user id of the caller.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     */
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    override fun setBiometricsAllowed(biometricsAllowed: Boolean, userId: Int) {
        logD {
            "setBiometricsAllowed: biometricsAllowed = $biometricsAllowed, userId = $userId"
        }
        enforceCallingPermission("setBiometricsAllowed")
        val actualUserId = getActualUserId(userId, "setBiometricsAllowed")
        serviceScope.launch {
            mutex.withLock {
                val config = userConfigMap[actualUserId] ?: run {
                    Slog.e(TAG, "setBiometricsAllowed requested by unknown user id $actualUserId")
                    return@withLock
                }
                if (config.biometricsAllowed == biometricsAllowed) return@withLock
                config.biometricsAllowed = biometricsAllowed
                biometricUnlocker.biometricsAllowed = biometricsAllowed
                withContext(Dispatchers.IO) {
                    config.write()
                }
            }
        }
    }

    /**
     * Check whether biometrics is allowed for unlocking.
     *
     * @return true if biometrics will be used for unlocking, false otheriwse.
     */
    override fun isBiometricsAllowed(userId: Int): Boolean {
        logD {
            "isBiometricsAllowed: userId = $userId"
        }
        val actualUserId = getActualUserId(userId, "isBiometricsAllowed")
        return runBlocking {
            mutex.withLock {
                userConfigMap[actualUserId]?.let { it.biometricsAllowed } ?: run {
                    Slog.e(TAG, "isBiometricsAllowed requested by unknown user id $actualUserId")
                    AppLockManager.DEFAULT_BIOMETRICS_ALLOWED
                }
            }
        }
    }

    /**
     * Unlock a package following authentication with credentials.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param packageName the name of the package to unlock.
     * @param userId the user id of the caller.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     */
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    override fun unlockPackage(packageName: String, userId: Int) {
        logD {
            "unlockPackage: packageName = $packageName, userId = $userId"
        }
        enforceCallingPermission("unlockPackage")
        val actualUserId = getActualUserId(userId, "unlockPackage")
        serviceScope.launch {
            mutex.withLock {
                val config = userConfigMap[actualUserId] ?: run {
                    Slog.e(TAG, "unlockPackage requested by unknown user id $actualUserId")
                    return@launch
                }
                if (!config.isPackageProtected(packageName)) {
                    Slog.w(TAG, "Unlock requested for package $packageName " +
                        "that is not in list")
                    return@launch
                }
                unlockedPackages.add(packageName)
            }
            notificationManagerInternal.updateSecureNotifications(
                packageName,
                false /* isContentSecure */,
                false /* isBubbleUpSuppressed */,
                actualUserId
            )
        }
    }

    /**
     * Hide or unhide an application from launcher.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param packageName the name of the package to hide or unhide.
     * @param hide whether to hide or not.
     * @param userId the user id of the caller.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     */
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    override fun setPackageHidden(packageName: String, hide: Boolean, userId: Int) {
        logD {
            "setPackageHidden: packageName = $packageName, hide = $hide, userId = $userId"
        }
        enforceCallingPermission("setPackageHidden")
        val actualUserId = getActualUserId(userId, "setPackageHidden")
        serviceScope.launch {
            mutex.withLock {
                val config = userConfigMap[actualUserId] ?: run {
                    Slog.e(TAG, "setPackageHidden requested by unknown user id $userId")
                    return@withLock
                }
                if (!config.isPackageProtected(packageName)) {
                    Slog.w(TAG, "Hide requested for package $packageName " +
                        "that is not in list")
                    return@withLock
                }
                if (config.hidePackage(packageName, hide)) {
                    withContext(Dispatchers.IO) {
                        config.write()
                    }
                }
            }
        }
    }

    /**
     * Get the list of applications hidden from launcher.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param userId the user id of the caller.
     * @return list of package names of the hidden apps.
     * @throws [SecurityException] if caller does not have permission
     *     [Manifest.permissions.MANAGE_APP_LOCK].
     */
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    override fun getHiddenPackages(userId: Int): List<String> {
        enforceCallingPermission("getHiddenPackages")
        return localService.getHiddenPackages(userId).toList()
    }

    private fun enforceCallingPermission(msg: String) {
        context.enforceCallingPermission(Manifest.permission.MANAGE_APP_LOCK, msg)
    }

    private fun onStart() {
        LocalServices.addService(AppLockManagerServiceInternal::class.java, localService)
    }

    private fun onBootCompleted() {
        Slog.i(TAG, "onBootCompleted")
        context.registerReceiverAsUser(
            lockAlarmReceiver,
            UserHandle.SYSTEM,
            IntentFilter(ACTION_APP_LOCK_TIMEOUT),
            null /* broadcastPermission */,
            null /* scheduler */,
        )

        context.registerReceiverForAllUsers(
            packageChangeReceiver,
            IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply {
                addDataScheme(IntentFilter.SCHEME_PACKAGE)
            },
            null /* broadcastPermission */,
            null /* scheduler */,
        )

        ActivityTaskManager.getService().registerTaskStackListener(taskStackListener)
    }

    private fun onUserStarting(userId: Int) {
        Slog.i(TAG, "onUserStarting: userId = $userId")
        isDeviceSecure = keyguardManager.isDeviceSecure(userId)
        logD {
            "isDeviceSecure = $isDeviceSecure"
        }
        serviceScope.launch {
            mutex.withLock {
                if (userConfigMap.containsKey(userId)) return@withLock
                withContext(Dispatchers.IO) {
                    val config = AppLockConfig(Environment.getDataSystemDeDirectory(userId))
                    userConfigMap[userId] = config
                    config.read()
                    biometricUnlocker.biometricsAllowed = config.biometricsAllowed
                    verifyPackagesLocked(config)
                }
            }
        }
    }

    private fun verifyPackagesLocked(config: AppLockConfig) {
        val currentPackages = config.getAppLockDataList().map { it.packageName }
        var size = currentPackages.size
        if (size == 0) return
        val installedPackages = pmInternal.getInstalledApplications(
            PackageManager.MATCH_ALL.toLong(),
            currentUserId,
            Process.myUid()
        ).map { it.packageName }
        var changed = false
        logD {
            "Current packages = $currentPackages"
        }
        for (i in 0 until size) {
            val pkg = currentPackages[i]
            if (!installedPackages.contains(pkg)) {
                config.removePackage(pkg)
                size--
                changed = true
            }
        }
        logD {
            val filteredPackages = config.getAppLockDataList().map { it.packageName }
            "Filtered packages = $filteredPackages"
        }
        if (changed) {
            config.write()
        }
    }

    private fun onUserStopping(userId: Int): Job {
        Slog.i(TAG, "onUserStopping: userId = $userId")
        return serviceScope.launch {
            mutex.withLock {
                unlockedPackages.clear()
                userConfigMap[userId]?.let {
                    withContext(Dispatchers.IO) {
                        it.write()
                    }
                }
            }
        }
    }

    private fun onUserSwitching(oldUserId: Int, newUserId: Int) {
        Slog.i(TAG, "onUserSwitching: oldUserId = $oldUserId, newUserId = $newUserId")
        serviceScope.launch {
            if (oldUserId != UserHandle.USER_NULL) {
                onUserStopping(oldUserId).join()
            }
            onUserStarting(newUserId)
        }
    }

    private inner class LocalService : AppLockManagerServiceInternal {
        /**
         * Check whether user is valid and device is secure
         */
        private fun checkUserAndDeviceStatus(userId: Int): Boolean {
            if (userId < 0) {
                logD {
                    "Ignoring requireUnlock call for special user $userId"
                }
                return false
            }
            if (!isDeviceSecure) {
                logD {
                    "Device is not secure, app does not require unlock"
                }
                return false
            }
            val isManaged = clearAndExecute {
                userManagerInternal.isUserManaged(userId)
            }
            if (isManaged) {
                logD {
                    "User id $userId belongs to a work profile, ignoring requireUnlock"
                }
            }
            return !isManaged
        }

        override fun requireUnlock(packageName: String, userId: Int): Boolean {
            return requireUnlockInternal(packageName, userId, false /* ignoreLockState */)
        }

        private fun requireUnlockInternal(
            packageName: String,
            userId: Int,
            ignoreLockState: Boolean,
        ) : Boolean {
            if (!checkUserAndDeviceStatus(userId)) return false
            val isLocked = clearAndExecute {
                // If device is locked then there is no point in proceeding.
                !ignoreLockState && keyguardManager.isDeviceLocked()
            }
            if (isLocked) {
                logD {
                    "Device is locked, app does not require unlock"
                }
                return false
            }
            logD {
                "requireUnlock: packageName = $packageName"
            }
            val actualUserId = getActualUserId(userId, "requireUnlock")
            return runBlocking {
                mutex.withLock {
                    val config = userConfigMap[actualUserId] ?: run {
                        Slog.e(TAG, "requireUnlock queried by unknown user id $actualUserId")
                        return@withLock false
                    }
                    val requireUnlock = config.isPackageProtected(packageName) &&
                        !unlockedPackages.contains(packageName)
                    logD {
                        "requireUnlock = $requireUnlock"
                    }
                    return@withLock requireUnlock
                }
            }
        }

        override fun reportPasswordChanged(userId: Int) {
            logD {
                "reportPasswordChanged: userId = $userId"
            }
            if (userId != currentUserId) {
                logD {
                    "Ignoring password change event for user $userId"
                }
                return
            }
            isDeviceSecure = keyguardManager.isDeviceSecure(userId)
            logD {
                "isDeviceSecure = $isDeviceSecure"
            }
        }

        override fun shouldRedactNotification(
            packageName: String,
            userId: Int,
        ) : Boolean {
            if (!checkUserAndDeviceStatus(userId)) return false
            logD {
                "shouldRedactNotification: packageName = $packageName, userId = $userId"
            }
            val actualUserId = getActualUserId(userId, "shouldRedactNotification")
            if (!requireUnlockInternal(packageName, userId, true /* ignoreLockState */)) return false
            return runBlocking {
                mutex.withLock {
                    val config = userConfigMap[actualUserId] ?: run {
                        Slog.e(TAG, "shouldRedactNotification queried by " +
                            "unknown user id $actualUserId")
                        return@withLock false
                    }
                    val secure = config.shouldRedactNotification(packageName)
                    logD {
                        "Secure = $secure"
                    }
                    return@withLock secure
                }
            }
        }

        override fun notifyDeviceLocked(locked: Boolean, userId: Int) {
            logD {
                "Device locked = $locked for user $userId"
            }
            if (userId != currentUserId ||
                    !isDeviceSecure ||
                    deviceLocked == locked) return
            deviceLocked = locked
            serviceScope.launch {
                val config = mutex.withLock {
                    userConfigMap[currentUserId] ?: run {
                        Slog.e(TAG, "Config unavailable for user $currentUserId")
                        return@launch
                    }
                }
                if (deviceLocked) {
                    mutex.withLock {
                        if (unlockedPackages.isEmpty()) return@withLock
                        logD {
                            "Locking all packages"
                        }
                        unlockedPackages.clear()
                    }
                    alarmsMutex.withLock {
                        if (scheduledAlarms.isEmpty()) return@withLock
                        scheduledAlarms.values.forEach {
                            alarmManager.cancel(it)
                        }
                        scheduledAlarms.clear()
                    }
                } else {
                    mutex.withLock {
                        if (topPackages.isEmpty()) return@withLock
                        // If device is locked with an app in the foreground,
                        // even if it is removed from [unlockedPackages], it will
                        // still be shown when unlocked, so we need to start home
                        // activity as soon as such a condition is detected on unlock.
                        val shouldGoToHome = topPackages.any {
                            config.isPackageProtected(it) &&
                                !unlockedPackages.contains(it)
                        }
                        if (!shouldGoToHome) return@withLock
                        logD {
                            "Locking foreground package"
                        }
                        context.mainExecutor.execute {
                            atmInternal.startHomeActivity(currentUserId,
                                "Locked package in foreground")
                        }
                    }
                }
            }
        }

        override fun interceptActivity(info: ActivityInterceptorInfo): Intent? {
            val packageName = info.activityInfo.packageName
            logD {
                "interceptActivity, pkg = $packageName"
            }
            if (!requireUnlock(packageName, info.userId)) return null
            val target = IntentSender(
                atmInternal.getIntentSender(
                    ActivityManager.INTENT_SENDER_ACTIVITY,
                    info.callingPackage,
                    info.callingFeatureId,
                    info.callingPid,
                    info.userId,
                    null /* token */,
                    null /* resultCode */,
                    0 /* requestCode */,
                    arrayOf(info.intent),
                    arrayOf(info.resolvedType),
                    PendingIntent.FLAG_CANCEL_CURRENT or
                        PendingIntent.FLAG_ONE_SHOT or
                        PendingIntent.FLAG_IMMUTABLE,
                    ActivityOptions.makeBasic().toBundle()
                )
            )
            val intent = Intent(AppLockManager.ACTION_UNLOCK_APP)
                .setPackage(SETTINGS_PACKAGE)
                .apply {
                    putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(Intent.EXTRA_INTENT, target)
                    putExtra(Intent.EXTRA_USER_ID, info.userId)
                    putExtra(AppLockManager.EXTRA_PACKAGE_LABEL, info.activityInfo.loadLabel(packageManager))
                    putExtra(AppLockManager.EXTRA_ALLOW_BIOMETRICS, isBiometricsAllowed(info.userId))
                }
            return intent
        }

        override fun getHiddenPackages(userId: Int): Set<String> {
            logD {
                "getHiddenPackages: userId = $userId"
            }
            return runBlocking {
                val actualUserId = getActualUserId(userId, "getHiddenPackages")
                val config = mutex.withLock {
                    userConfigMap[actualUserId] ?: run {
                        Slog.e(TAG, "Config unavailable for user $userId")
                        return@runBlocking emptySet()
                    }
                }
                val list = config.getAppLockDataList()
                logD {
                    "data list = $list"
                }
                list.filter { it.hideFromLauncher }
                    .map { it.packageName }
                    .toSet()
            }
        }
    }

    class Lifecycle(context: Context) : SystemService(context) {
        private val service = AppLockManagerService(context)

        override fun onStart() {
            publishBinderService(Context.APP_LOCK_SERVICE, service)
            service.onStart()
        }

        override fun onBootPhase(phase: Int) {
            if (phase == PHASE_ACTIVITY_MANAGER_READY) {
                service.onBootCompleted()
            }
        }

        override fun onUserStarting(user: TargetUser) {
            service.onUserStarting(user.userIdentifier)
        }

        override fun onUserStopping(user: TargetUser) {
            service.onUserStopping(user.userIdentifier)
        }

        override fun onUserSwitching(from: TargetUser?, to: TargetUser) {
            service.onUserSwitching(
                from?.userIdentifier ?: UserHandle.USER_NULL,
                to.userIdentifier
            )
        }
    }
}
