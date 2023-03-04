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

import android.app.AppLockData
import android.app.AppLockManager.DEFAULT_BIOMETRICS_ALLOWED
import android.app.AppLockManager.DEFAULT_HIDE_IN_LAUNCHER
import android.app.AppLockManager.DEFAULT_PROTECT_APP
import android.app.AppLockManager.DEFAULT_REDACT_NOTIFICATION
import android.app.AppLockManager.DEFAULT_TIMEOUT
import android.os.FileUtils
import android.os.FileUtils.S_IRWXU
import android.os.FileUtils.S_IRWXG
import android.util.ArrayMap
import android.util.Slog

import java.io.File
import java.io.IOException

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val APP_LOCK_DIR_NAME = "app_lock"
private const val APP_LOCK_CONFIG_FILE = "app_lock_config.json"

private const val CURRENT_VERSION = 3

// Only in version 0
private const val KEY_PACKAGES = "packages"
private const val KEY_SECURE_NOTIFICATION = "secure_notification"

// From version 1 and up. Non existent version key
// is considered as version 0
private const val KEY_VERSION = "version"
private const val KEY_TIMEOUT = "timeout"
private const val KEY_APP_LOCK_DATA_LIST = "app_lock_data_list"
private const val KEY_PACKAGE_NAME = "package_name"
private const val KEY_PROTECT_APP = "protect_app"
private const val KEY_REDACT_NOTIFICATION = "redact_notification"
private const val KEY_BIOMETRICS_ALLOWED = "biometrics_allowed"

// From version 2 and up.
private const val KEY_HIDE_FROM_LAUNCHER = "hide_from_launcher"

/**
 * Container for app lock configuration. Also handles logic of reading
 * and writing configuration to disk, serialized as a JSON file.
 * All operations must be synchronized with an external lock.
 *
 * @hide
 */
internal class AppLockConfig(dataDir: File) {

    private val appLockDir = File(dataDir, APP_LOCK_DIR_NAME)
    private val appLockConfigFile = File(appLockDir, APP_LOCK_CONFIG_FILE)

    private val appLockDataMap = ArrayMap<String, AppLockData>()

    var appLockTimeout: Long = DEFAULT_TIMEOUT
    var biometricsAllowed = DEFAULT_BIOMETRICS_ALLOWED

    init {
        appLockDir.mkdirs()
        FileUtils.setPermissions(appLockDir, S_IRWXU or S_IRWXG, -1, -1)
    }

    /**
     * Add an application to [appLockDataMap].
     *
     * @param packageName the package name of the application.
     * @return true if package was added, false if already exists.
     */
    fun addPackageToMap(packageName: String): Boolean {
        return if (!appLockDataMap.containsKey(packageName)) {
            appLockDataMap[packageName] =
                AppLockData(
                    packageName,
                    DEFAULT_PROTECT_APP,
                    DEFAULT_REDACT_NOTIFICATION,
                    DEFAULT_HIDE_IN_LAUNCHER
                )
            true
        } else {
            false
        }
    }

    /**
     * Remove an application from [appLockDataMap].
     *
     * @param packageName the package name of the application.
     * @return true if package was removed, false otherwise.
     */
    fun removePackageFromMap(packageName: String): Boolean {
        return if (appLockDataMap.containsKey(packageName)) {
            appLockDataMap.remove(packageName) != null
            true
        } else {
            false
        }
    }

    /**
     * Set notifications as protected or not for an application
     * in [appLockDataMap].
     *
     * @param packageName the package name of the application.
     * @param shouldProtectApp whether to protect app or not.
     * @return true if config was changed, false otherwise.
     */
    fun setShouldProtectApp(packageName: String, shouldProtectApp: Boolean): Boolean {
        addPackageToMap(packageName)
        return appLockDataMap[packageName]?.let {
            if (it.shouldProtectApp != shouldProtectApp) {
                appLockDataMap[packageName] = AppLockData(
                    it.packageName,
                    shouldProtectApp,
                    it.shouldRedactNotification,
                    it.hideFromLauncher
                )
                true
            } else {
                false
            }
        } ?: run {
            Slog.e(TAG, "Attempt to protect app for package $packageName that is not in list")
            false
        }
    }

    /**
     * Check whether app is protected or not for an application
     * in [appLockDataMap].
     *
     * @param packageName the package name of the application.
     * @return true if app is protected by app lock,
     *     false otherwise.
     */
    fun shouldProtectApp(packageName: String): Boolean {
        return appLockDataMap[packageName]?.shouldProtectApp == true
    }

    /**
     * Get all the packages protected with app lock.
     *
     * @return a unique list of package names.
     */
    fun getAppLockDataList(): List<AppLockData> {
        return appLockDataMap.values.toList()
    }

    /**
     * Get all the packages protected with app lock.
     *
     * @return a unique list of package names.
     */
    fun getAppLockAppList(): List<String> {
        return appLockDataMap.keys.toList()
    }

    /**
     * Get all the packages protected with app lock.
     *
     * @return a unique list of package names.
     */
    fun getAppLockHiddenAppList(): List<String> {
        return appLockDataMap.filterValues { it.hideFromLauncher }.keys.toList()
    }

    /**
     * Set notifications as protected or not for an application
     * in [appLockDataMap].
     *
     * @param packageName the package name of the application.
     * @param shouldRedactNotification whether to redact notification or not.
     * @return true if config was changed, false otherwise.
     */
    fun setShouldRedactNotification(packageName: String, shouldRedactNotification: Boolean): Boolean {
        addPackageToMap(packageName)
        return appLockDataMap[packageName]?.let {
            if (it.shouldRedactNotification != shouldRedactNotification) {
                appLockDataMap[packageName] = AppLockData(
                    it.packageName,
                    it.shouldProtectApp,
                    shouldRedactNotification,
                    it.hideFromLauncher
                )
                true
            } else {
                false
            }
        } ?: run {
            Slog.e(TAG, "Attempt to redact notifications for package $packageName that is not in list")
            false
        }
    }

    /**
     * Check whether notifications are protected or not for an application
     * in [appLockDataMap].
     *
     * @param packageName the package name of the application.
     * @return true if notification contents are redacted in app locked state,
     *     false otherwise.
     */
    fun shouldRedactNotification(packageName: String): Boolean {
        return appLockDataMap[packageName]?.shouldRedactNotification == true
    }

    /**
     * Mark an application as hidden from launcher in [appLockDataMap].
     *
     * @param packageName the package name of the application.
     * @param hide the parameter value in [AppLockData].
     * @return true if hidden state was changed, false otherwise.
     */
    fun hidePackage(packageName: String, hide: Boolean): Boolean {
        addPackageToMap(packageName)
        return appLockDataMap[packageName]?.let {
            if (it.hideFromLauncher != hide) {
                appLockDataMap[packageName] = AppLockData(
                    it.packageName,
                    it.shouldProtectApp,
                    it.shouldRedactNotification,
                    hide
                )
                true
            } else {
                false
            }
        } ?: run {
            Slog.e(TAG, "Attempt to hide app for package $packageName that is not in list")
            false
        }
    }

    /**
     * Check whether app is hidden in launcher or not for an application
     * in [appLockDataMap].
     *
     * @param packageName the package name of the application.
     * @return true if app is hidden in launcher by app lock,
     *     false otherwise.
     */
    fun shouldHideApp(packageName: String): Boolean {
        return appLockDataMap[packageName]?.hideFromLauncher == true
    }

    /**
     * Parse contents from [appLockConfigFile].
     */
    fun read() {
        reset()
        if (!appLockConfigFile.isFile) {
            Slog.i(TAG, "No configuration saved")
            return
        }
        try {
            appLockConfigFile.inputStream().bufferedReader().use {
                val rootObject = JSONObject(it.readText())

                val version = rootObject.optInt(KEY_VERSION, 0)
                if (version != CURRENT_VERSION) {
                    migrateData(rootObject, version)
                }

                appLockTimeout = rootObject.optLong(KEY_TIMEOUT, DEFAULT_TIMEOUT)
                biometricsAllowed = rootObject.optBoolean(KEY_BIOMETRICS_ALLOWED, DEFAULT_BIOMETRICS_ALLOWED)
                val appLockDataList = rootObject.optJSONArray(KEY_APP_LOCK_DATA_LIST) ?: return@use
                for (i in 0 until appLockDataList.length()) {
                    val appLockData = appLockDataList.getJSONObject(i)
                    val packageName = appLockData.getString(KEY_PACKAGE_NAME)
                    appLockDataMap[packageName] = AppLockData(
                        packageName,
                        appLockData.getBoolean(KEY_PROTECT_APP),
                        appLockData.getBoolean(KEY_REDACT_NOTIFICATION),
                        appLockData.getBoolean(KEY_HIDE_FROM_LAUNCHER)
                    )
                }
            }
        } catch(e: IOException) {
            Slog.wtf(TAG, "Failed to read config file", e)
        } catch(e: JSONException) {
            Slog.wtf(TAG, "Failed to parse config file", e)
        }
        logD {
            "readConfig: data = $appLockDataMap, " +
            "timeout = $appLockTimeout, " +
            "biometricsAllowed = $biometricsAllowed"
        }
    }

    private fun reset() {
        appLockDataMap.clear()
        appLockTimeout = DEFAULT_TIMEOUT
        biometricsAllowed = DEFAULT_BIOMETRICS_ALLOWED
    }

    private fun migrateData(jsonData: JSONObject, dataVersion: Int) {
        Slog.i(TAG, "Migrating data from version $dataVersion")
        when (dataVersion) {
            0 -> {
                val packageObject = jsonData.remove(KEY_PACKAGES) as? JSONObject
                if (packageObject != null) {
                    val appLockDataList = JSONArray()
                    packageObject.keys().forEach { pkg ->
                        val isSecure = packageObject.getJSONObject(pkg)
                            .optBoolean(KEY_SECURE_NOTIFICATION, DEFAULT_REDACT_NOTIFICATION)
                        appLockDataList.put(
                            JSONObject()
                                .put(KEY_PACKAGE_NAME, pkg)
                                .put(KEY_REDACT_NOTIFICATION, isSecure)
                        )
                    }
                    jsonData.put(KEY_APP_LOCK_DATA_LIST, appLockDataList)
                }
            }
            1 -> {
                val appLockDataList = jsonData.optJSONArray(KEY_APP_LOCK_DATA_LIST)
                if (appLockDataList != null) {
                    val size = appLockDataList.length()
                    if (size > 0) {
                        val backupList = mutableListOf<JSONObject>()
                        for (i in (size - 1)..0) {
                            val appData = appLockDataList.getJSONObject(i)
                                .put(KEY_HIDE_FROM_LAUNCHER, DEFAULT_HIDE_IN_LAUNCHER)
                            backupList.add(appData)
                            appLockDataList.remove(i)
                        }
                        backupList.forEach {
                            appLockDataList.put(it)
                        }
                        jsonData.put(KEY_APP_LOCK_DATA_LIST, appLockDataList)
                    }
                }
            }
            2 -> {
                val appLockDataList = jsonData.optJSONArray(KEY_APP_LOCK_DATA_LIST)
                if (appLockDataList != null) {
                    val size = appLockDataList.length()
                    if (size > 0) {
                        val backupList = mutableListOf<JSONObject>()
                        for (i in (size - 1)..0) {
                            var isProtect = DEFAULT_PROTECT_APP
                            if (appLockDataList.getJSONObject(i).has(KEY_PROTECT_APP)) {
                                isProtect = appLockDataList.getJSONObject(i)
                                    .getBoolean(KEY_PROTECT_APP)
                            }
                            val appData = appLockDataList.getJSONObject(i)
                                .put(KEY_PROTECT_APP, isProtect)
                            backupList.add(appData)
                            appLockDataList.remove(i)
                        }
                        backupList.forEach {
                            appLockDataList.put(it)
                        }
                        jsonData.put(KEY_APP_LOCK_DATA_LIST, appLockDataList)
                    }
                }
            }
            else -> throw IllegalArgumentException("Unknown data version $dataVersion")
        }
        val nextVersion = dataVersion + 1
        if (nextVersion != CURRENT_VERSION) {
            migrateData(jsonData, nextVersion)
        }
    }

    /**
     * Write contents to [appLockConfigFile].
     */
    fun write() {
        logD {
            "Writing data to file"
        }
        val rootObject = JSONObject()
        try {
            rootObject.put(KEY_VERSION, CURRENT_VERSION)
            rootObject.put(KEY_TIMEOUT, appLockTimeout)
            rootObject.put(KEY_BIOMETRICS_ALLOWED, biometricsAllowed)
            rootObject.put(
                KEY_APP_LOCK_DATA_LIST,
                JSONArray(
                    appLockDataMap.values.map {
                        JSONObject().apply {
                            put(KEY_PACKAGE_NAME, it.packageName)
                            put(KEY_PROTECT_APP, it.shouldProtectApp)
                            put(KEY_REDACT_NOTIFICATION, it.shouldRedactNotification)
                            put(KEY_HIDE_FROM_LAUNCHER, it.hideFromLauncher)
                        }
                    }
                )
            )
        } catch(e: JSONException) {
            Slog.wtf(TAG, "Failed to create json configuration", e)
            return
        }
        try {
            appLockConfigFile.outputStream().bufferedWriter().use {
                val flattenedString = rootObject.toString(4)
                logD {
                    "flattenedString = $flattenedString"
                }
                it.write(flattenedString, 0, flattenedString.length)
                it.flush()
            }
        } catch(e: IOException) {
            Slog.wtf(TAG, "Failed to write config to file", e)
        }
    }
}
