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

package com.android.settingslib.spaprivileged.model.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureFlags
import android.content.pm.FeatureFlagsImpl
import android.content.pm.Flags
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.content.pm.ResolveInfo
import android.util.Log
import com.android.internal.R
import com.android.settingslib.spaprivileged.framework.common.userManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.runBlocking

/** The repository to load the App List data. */
interface AppListRepository {
    /** Loads the list of [ApplicationInfo]. */
    suspend fun loadApps(
        userId: Int,
        loadInstantApps: Boolean = false,
        matchAnyUserForAdmin: Boolean = false,
    ): List<ApplicationInfo>

    /** Gets the flow of predicate that could used to filter system app. */
    fun showSystemPredicate(
        userIdFlow: Flow<Int>,
        showSystemFlow: Flow<Boolean>,
    ): Flow<(app: ApplicationInfo) -> Boolean>

    /** Gets the system app package names. */
    fun getSystemPackageNamesBlocking(userId: Int): Set<String>

    /** Loads the list of [ApplicationInfo], and filter base on `isSystemApp`. */
    suspend fun loadAndFilterApps(userId: Int, isSystemApp: Boolean): List<ApplicationInfo>

    /** Loads the list of [ApplicationInfo], and choose whether to exclude system apps or not. */
    suspend fun loadAndMaybeExcludeSystemApps(
        userId: Int,
        excludeSystemApp: Boolean,
    ): List<ApplicationInfo> = listOf()
}

/** Util for app list repository. */
object AppListRepositoryUtil {
    /** Gets the system app package names. */
    @JvmStatic
    fun getSystemPackageNames(context: Context, userId: Int): Set<String> =
        AppListRepositoryImpl(context).getSystemPackageNamesBlocking(userId)
}

internal class AppListRepositoryImplHelper(
    private val context: Context,
    private val featureFlags: FeatureFlags,
) {
    private val packageManager = context.packageManager
    private val userManager = context.userManager

    constructor(context: Context) : this(context, FeatureFlagsImpl())

    suspend fun loadApps(
        userId: Int,
        loadInstantApps: Boolean,
        matchAnyUserForAdmin: Boolean,
    ): List<ApplicationInfo> =
        try {
            coroutineScope {
                // TODO(b/382016780): to be removed after flag cleanup.
                val hiddenSystemModulesDeferred = async { packageManager.getHiddenSystemModules() }
                val hideWhenDisabledPackagesDeferred = async {
                    context.resources.getStringArray(R.array.config_hideWhenDisabled_packageNames)
                }
                val installedApplicationsAsUser =
                    getInstalledApplications(userId, matchAnyUserForAdmin)

                // TODO(b/382016780): to be removed after flag cleanup.
                val hiddenSystemModules = hiddenSystemModulesDeferred.await()
                val hideWhenDisabledPackages = hideWhenDisabledPackagesDeferred.await()
                installedApplicationsAsUser.filter { app ->
                    app.isInAppList(loadInstantApps, hiddenSystemModules, hideWhenDisabledPackages)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadApps failed", e)
            emptyList()
        }

    private suspend fun getInstalledApplications(
        userId: Int,
        matchAnyUserForAdmin: Boolean,
    ): List<ApplicationInfo> {
        val disabledComponentsFlag =
            (PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS)
                .toLong()
        val archivedPackagesFlag: Long =
            if (isArchivingEnabled(featureFlags)) PackageManager.MATCH_ARCHIVED_PACKAGES else 0L
        val regularFlags = ApplicationInfoFlags.of(disabledComponentsFlag or archivedPackagesFlag)
        return if (!matchAnyUserForAdmin || !userManager.getUserInfo(userId).isAdmin) {
            packageManager.getInstalledApplicationsAsUser(regularFlags, userId)
        } else {
            coroutineScope {
                val deferredPackageNamesInChildProfiles =
                    userManager
                        .getProfileIdsWithDisabled(userId)
                        .filter { it != userId }
                        .map {
                            async {
                                packageManager
                                    .getInstalledApplicationsAsUser(regularFlags, it)
                                    .map { it.packageName }
                            }
                        }
                val adminFlags =
                    ApplicationInfoFlags.of(
                        PackageManager.MATCH_ANY_USER.toLong() or regularFlags.value
                    )
                val allInstalledApplications =
                    packageManager.getInstalledApplicationsAsUser(adminFlags, userId)
                val packageNamesInChildProfiles =
                    deferredPackageNamesInChildProfiles.awaitAll().flatten().toSet()
                // If an app is for a child profile and not installed on the owner, not display as
                // 'not installed for this user' in the owner. This will prevent duplicates of work
                // only apps showing up in the personal profile.
                allInstalledApplications.filter {
                    it.installed || it.packageName !in packageNamesInChildProfiles
                }
            }
        }
    }

    private fun isArchivingEnabled(featureFlags: FeatureFlags) = featureFlags.archiving()

    fun getSystemPackageNamesBlocking(userId: Int) = runBlocking {
        loadAndFilterApps(userId = userId, isSystemApp = true).map { it.packageName }.toSet()
    }

    suspend fun loadAndFilterApps(userId: Int, isSystemApp: Boolean) = coroutineScope {
        val loadAppsDeferred = async { loadApps(userId, false, false) }
        val homeOrLauncherPackages = loadHomeOrLauncherPackages(userId)
        loadAppsDeferred.await().filter { app ->
            isSystemApp(app, homeOrLauncherPackages) == isSystemApp
        }
    }

    suspend fun loadAndMaybeExcludeSystemApps(userId: Int, excludeSystemApp: Boolean) =
        coroutineScope {
            val loadAppsDeferred = async { loadApps(userId, false, false) }
            if (excludeSystemApp) {
                val homeOrLauncherPackages = loadHomeOrLauncherPackages(userId)
                loadAppsDeferred.await().filter { app -> !isSystemApp(app, homeOrLauncherPackages) }
            } else {
                loadAppsDeferred.await()
            }
        }

    internal suspend fun loadHomeOrLauncherPackages(userId: Int): Set<String> {
        val launchIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        // If we do not specify MATCH_DIRECT_BOOT_AWARE or MATCH_DIRECT_BOOT_UNAWARE, system will
        // derive and update the flags according to the user's lock state. When the user is locked,
        // components with ComponentInfo#directBootAware == false will be filtered. We should
        // explicitly include both direct boot aware and unaware component here.
        val flags =
            PackageManager.ResolveInfoFlags.of(
                (PackageManager.MATCH_DISABLED_COMPONENTS or
                        PackageManager.MATCH_DIRECT_BOOT_AWARE or
                        PackageManager.MATCH_DIRECT_BOOT_UNAWARE)
                    .toLong()
            )
        return coroutineScope {
            val launcherActivities = async {
                packageManager.queryIntentActivitiesAsUser(launchIntent, flags, userId)
            }
            val homeActivities = ArrayList<ResolveInfo>()
            packageManager.getHomeActivities(homeActivities)
            (launcherActivities.await() + homeActivities)
                .map { it.activityInfo.packageName }
                .toSet()
        }
    }

    private fun isSystemApp(app: ApplicationInfo, homeOrLauncherPackages: Set<String>): Boolean =
        app.isSystemApp && !app.isUpdatedSystemApp && app.packageName !in homeOrLauncherPackages

    // TODO(b/382016780): to be removed after flag cleanup.
    private fun PackageManager.getHiddenSystemModules(): Set<String> {
        val moduleInfos = getInstalledModules(0).filter { it.isHidden }
        val hiddenApps = moduleInfos.mapNotNull { it.packageName }.toMutableSet()
        if (Flags.provideInfoOfApkInApex()) {
            hiddenApps += moduleInfos.flatMap { it.apkInApexPackageNames }
        }
        return hiddenApps
    }

    companion object {
        private const val TAG = "AppListRepositoryImplHelper"

        // TODO(b/382016780): to be removed after flag cleanup.
        private fun ApplicationInfo.isInAppList(
            showInstantApps: Boolean,
            hiddenSystemModules: Set<String>,
            hideWhenDisabledPackages: Array<String>,
        ) =
            when {
                !showInstantApps && isInstantApp -> false
                isResourceOverlay -> false
                !Flags.removeHiddenModuleUsage() && (packageName in hiddenSystemModules) -> false
                packageName in hideWhenDisabledPackages -> enabled && !isDisabledUntilUsed
                enabled -> true
                else -> enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
    }
}

/** This constructor is visible for tests only in order to override `featureFlags`. */
class AppListRepositoryImpl(context: Context, private val featureFlags: FeatureFlags) :
    AppListRepository {
    private val helper = AppListRepositoryImplHelper(context, featureFlags)

    constructor(context: Context) : this(context, FeatureFlagsImpl())

    companion object {
        @JvmStatic
        @Volatile
        var useCaching = false
            set(value) {
                if (!value) {
                    appsCache.clear()
                    homeOrLauncherPackagesCache.clear()
                }
                field = value
            }

        private val appsCache = ConcurrentHashMap<AppsCacheKey, Deferred<List<ApplicationInfo>>>()

        private data class AppsCacheKey(
            val userId: Int,
            val loadInstantApps: Boolean,
            val matchAnyUserForAdmin: Boolean,
        )

        private val homeOrLauncherPackagesCache = ConcurrentHashMap<Int, Deferred<Set<String>>>()
    }

    private suspend fun <K, V> getOrAsync(
        cache: ConcurrentHashMap<K, Deferred<V>>,
        key: K,
        block: suspend () -> V,
    ): Deferred<V> = coroutineScope {
        cache.computeIfAbsent(key) { this@coroutineScope.async { block() } }
    }

    override suspend fun loadApps(
        userId: Int,
        loadInstantApps: Boolean,
        matchAnyUserForAdmin: Boolean,
    ): List<ApplicationInfo> {
        if (!useCaching) {
            return helper.loadApps(userId, loadInstantApps, matchAnyUserForAdmin)
        }
        val key = AppsCacheKey(userId, loadInstantApps, matchAnyUserForAdmin)
        return getOrAsync(appsCache, key) {
                helper.loadApps(userId, loadInstantApps, matchAnyUserForAdmin)
            }
            .await()
    }

    override fun showSystemPredicate(
        userIdFlow: Flow<Int>,
        showSystemFlow: Flow<Boolean>,
    ): Flow<(app: ApplicationInfo) -> Boolean> =
        userIdFlow.combine(showSystemFlow, ::showSystemPredicate)

    override fun getSystemPackageNamesBlocking(userId: Int): Set<String> {
        if (!useCaching) {
            return helper.getSystemPackageNamesBlocking(userId)
        }
        return runBlocking {
            loadAndFilterApps(userId = userId, isSystemApp = true).map { it.packageName }.toSet()
        }
    }

    override suspend fun loadAndFilterApps(
        userId: Int,
        isSystemApp: Boolean,
    ): List<ApplicationInfo> {
        if (!useCaching) {
            return helper.loadAndFilterApps(userId, isSystemApp)
        }
        return coroutineScope {
            val loadAppsDeferred = async { loadApps(userId) }
            val homeOrLauncherPackages = loadHomeOrLauncherPackages(userId)
            loadAppsDeferred.await().filter { app ->
                isSystemApp(app, homeOrLauncherPackages) == isSystemApp
            }
        }
    }

    override suspend fun loadAndMaybeExcludeSystemApps(
        userId: Int,
        excludeSystemApp: Boolean,
    ): List<ApplicationInfo> {
        if (!useCaching) {
            return helper.loadAndMaybeExcludeSystemApps(userId, excludeSystemApp)
        }
        return coroutineScope {
            val loadAppsDeferred = async { loadApps(userId) }
            if (excludeSystemApp) {
                val homeOrLauncherPackages = loadHomeOrLauncherPackages(userId)
                loadAppsDeferred.await().filter { app -> !isSystemApp(app, homeOrLauncherPackages) }
            } else {
                loadAppsDeferred.await()
            }
        }
    }

    private suspend fun showSystemPredicate(
        userId: Int,
        showSystem: Boolean,
    ): (app: ApplicationInfo) -> Boolean {
        if (showSystem) return { true }
        val homeOrLauncherPackages = loadHomeOrLauncherPackages(userId)
        return { app -> !isSystemApp(app, homeOrLauncherPackages) }
    }

    private suspend fun loadHomeOrLauncherPackages(userId: Int): Set<String> {
        if (!useCaching) {
            return helper.loadHomeOrLauncherPackages(userId)
        }
        return getOrAsync(homeOrLauncherPackagesCache, userId) {
                helper.loadHomeOrLauncherPackages(userId)
            }
            .await()
    }

    private fun isSystemApp(app: ApplicationInfo, homeOrLauncherPackages: Set<String>): Boolean =
        app.isSystemApp && !app.isUpdatedSystemApp && app.packageName !in homeOrLauncherPackages
}
