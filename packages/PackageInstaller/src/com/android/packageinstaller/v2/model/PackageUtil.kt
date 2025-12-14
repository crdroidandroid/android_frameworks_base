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

package com.android.packageinstaller.v2.model

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.android.packageinstaller.v2.model.PackageUtil.getAppSnippet
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize

object PackageUtil {
    private val LOG_TAG = PackageUtil::class.java.simpleName
    private const val DOWNLOADS_AUTHORITY = "downloads"
    private const val SPLIT_BASE_APK_SUFFIX = "base.apk"
    private const val SPLIT_APK_SUFFIX = ".apk"
    const val localLogv = false

    const val ARGS_MESSAGE: String = "message"

    /**
     * Determines if the UID belongs to the system downloads provider and returns the
     * [ApplicationInfo] of the provider
     *
     * @param uid UID of the caller
     * @return [ApplicationInfo] of the provider if a downloads provider exists, it is a
     * system app, and its UID matches with the passed UID, null otherwise.
     */
    @JvmStatic
    fun getSystemDownloadsProviderInfo(pm: PackageManager, uid: Int): ApplicationInfo? {
        // Check if there are currently enabled downloads provider on the system.
        val providerInfo = pm.resolveContentProvider(DOWNLOADS_AUTHORITY, 0)
            ?: return null
        val appInfo = providerInfo.applicationInfo
        return if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) && uid == appInfo.uid) {
            appInfo
        } else null
    }

    /**
     * Get the maximum target sdk for a UID.
     *
     * @param context The context to use
     * @param uid The UID requesting the install/uninstall
     * @return The maximum target SDK or -1 if the uid does not match any packages.
     */
    @JvmStatic
    fun getMaxTargetSdkVersionForUid(context: Context, uid: Int): Int {
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)
        var targetSdkVersion = -1
        if (packages != null) {
            for (packageName in packages) {
                try {
                    val info = pm.getApplicationInfo(packageName!!, 0)
                    targetSdkVersion = maxOf(targetSdkVersion, info.targetSdkVersion)
                } catch (e: PackageManager.NameNotFoundException) {
                    // Ignore and try the next package
                }
            }
        }
        return targetSdkVersion
    }

    @JvmStatic
    fun canPackageQuery(context: Context, callingUid: Int, packageUri: Uri): Boolean {
        val pm = context.packageManager
        val info = pm.resolveContentProvider(
            packageUri.authority!!,
            PackageManager.ComponentInfoFlags.of(0)
        ) ?: return false
        val targetPackage = info.packageName
        val callingPackages = pm.getPackagesForUid(callingUid) ?: return false
        for (callingPackage in callingPackages) {
            try {
                if (pm.canPackageQuery(callingPackage!!, targetPackage)) {
                    return true
                }
            } catch (e: PackageManager.NameNotFoundException) {
                // no-op
            }
        }
        return false
    }

    /**
     * @param context the [Context] object
     * @param permission the permission name to check
     * @param callingUid the UID of the caller who's permission is being checked
     * @return `true` if the callingUid is granted the said permission
     */
    @JvmStatic
    fun isPermissionGranted(context: Context, permission: String, callingUid: Int): Boolean {
        return (context.checkPermission(permission, -1, callingUid)
                == PackageManager.PERMISSION_GRANTED)
    }

    /**
     * @param pm the [PackageManager] object
     * @param permission the permission name to check
     * @param packageName the name of the package who's permission is being checked
     * @return `true` if the package is granted the said permission
     */
    @JvmStatic
    fun isPermissionGranted(pm: PackageManager, permission: String, packageName: String): Boolean {
        return pm.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * @param context the [Context] object
     * @param callingUid the UID of the caller who's permission is being checked
     * @return `true` if the callingUid is granted the documents permission
     */
    @JvmStatic
    fun isDocumentsManager(context: Context, callingUid: Int): Boolean {
        return isPermissionGranted(context, Manifest.permission.MANAGE_DOCUMENTS, callingUid)
    }

    /**
     * @param context the [Context] object
     * @param callingUid the UID of the caller of Pia
     * @param isTrustedSource indicates whether install request is coming from a privileged app
     * that has passed EXTRA_NOT_UNKNOWN_SOURCE as `true` in the installation intent, or an app that
     * has the [INSTALL_PACKAGES][Manifest.permission.INSTALL_PACKAGES] permission granted.
     *
     * @return `true` if the package is a trusted source, or has declared the
     * [REQUEST_INSTALL_PACKAGES][Manifest.permission.REQUEST_INSTALL_PACKAGES] in its manifest.
     */
    @JvmStatic
    fun isInstallPermissionGrantedOrRequested(
        context: Context,
        callingUid: Int,
        isTrustedSource: Boolean,
    ): Boolean {
        if (!isTrustedSource) {
            val targetSdkVersion = getMaxTargetSdkVersionForUid(context, callingUid)
            if (targetSdkVersion < 0) {
                // Invalid calling uid supplied. Abort install.
                Log.e(LOG_TAG, "Cannot get target SDK version for uid $callingUid")
                return false
            } else if (targetSdkVersion >= Build.VERSION_CODES.O
                && !isUidRequestingPermission(
                    context.packageManager, callingUid, Manifest.permission.REQUEST_INSTALL_PACKAGES
                )
            ) {
                Log.e(
                    LOG_TAG, "Requesting uid " + callingUid + " needs to declare permission "
                            + Manifest.permission.REQUEST_INSTALL_PACKAGES
                )
                return false
            }
        }
        return true
    }

    /**
     * @param pm the [PackageManager] object
     * @param uid the UID of the caller who's permission is being checked
     * @param permission the permission name to check
     * @return `true` if the caller is requesting the said permission in its Manifest
     */
    fun isUidRequestingPermission(
        pm: PackageManager,
        uid: Int,
        permission: String,
    ): Boolean {
        val packageNames = pm.getPackagesForUid(uid) ?: return false
        for (packageName in packageNames) {
            val packageInfo: PackageInfo = try {
                pm.getPackageInfo(packageName!!, PackageManager.GET_PERMISSIONS)
            } catch (e: PackageManager.NameNotFoundException) {
                // Ignore and try the next package
                continue
            }
            if (packageInfo.requestedPermissions != null
                && listOf(*packageInfo.requestedPermissions!!).contains(permission)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * @param pi the [PackageInstaller] object to use
     * @param originatingUid the UID of the package performing a session based install
     * @param sessionId ID of the install session
     * @return `true` if the caller is the session owner
     */
    @JvmStatic
    fun isCallerSessionOwner(pi: PackageInstaller, callingUid: Int, sessionId: Int): Boolean {
        if (callingUid == Process.ROOT_UID) {
            return true
        }
        val sessionInfo = pi.getSessionInfo(sessionId) ?: return false
        val installerUid = sessionInfo.getInstallerUid()
        return callingUid == installerUid
    }

    /**
     * Generates a stub [PackageInfo] object for the given packageName
     */
    @JvmStatic
    fun generateStubPackageInfo(packageName: String?): PackageInfo {
        val info = PackageInfo()
        val aInfo = ApplicationInfo()
        info.applicationInfo = aInfo
        info.applicationInfo!!.packageName = packageName
        info.packageName = info.applicationInfo!!.packageName
        return info
    }

    /**
     * Generates an [AppSnippet] containing an appIcon and appLabel from the
     * [PackageInstaller.SessionInfo] object
     */
    @JvmStatic
    fun getAppSnippet(context: Context, info: PackageInstaller.SessionInfo): AppSnippet {
        val pm = context.packageManager
        val label = info.getAppLabel()
        val icon = if (info.getAppIcon() != null) BitmapDrawable(
            context.resources,
            info.getAppIcon()
        ) else pm.defaultActivityIcon
        val largeIconSize = getLargeIconSize(context)
        return AppSnippet(label, icon, largeIconSize)
    }

    /**
     * Generates an [AppSnippet] containing an appIcon and appLabel from the
     * [PackageInfo] object
     */
    @JvmStatic
    fun getAppSnippet(context: Context, pkgInfo: PackageInfo): AppSnippet {
        val largeIconSize = getLargeIconSize(context)
        return pkgInfo.applicationInfo?.let { getAppSnippet(context, it) } ?: run {
            AppSnippet(
                pkgInfo.packageName, context.packageManager.defaultActivityIcon, largeIconSize
            )
        }
    }

    /**
     * Generates an [AppSnippet] containing an appIcon and appLabel from the
     * [ApplicationInfo] object
     */
    @JvmStatic
    fun getAppSnippet(context: Context, appInfo: ApplicationInfo): AppSnippet {
        val pm = context.packageManager
        val label = pm.getApplicationLabel(appInfo)
        val icon = pm.getApplicationIcon(appInfo)
        val largeIconSize = getLargeIconSize(context)
        return AppSnippet(label, icon, largeIconSize)
    }

    /**
     * Generates an [AppSnippet] containing an appIcon and appLabel from the
     * supplied APK file
     */
    @JvmStatic
    fun getAppSnippet(context: Context, pkgInfo: PackageInfo, sourceFile: File): AppSnippet {
        val largeIconSize = getLargeIconSize(context)
        pkgInfo.applicationInfo?.let {
            val appInfoFromFile = processAppInfoForFile(it, sourceFile)
            val label = getAppLabelFromFile(context, appInfoFromFile)
            val icon = getAppIconFromFile(context, appInfoFromFile)
            return AppSnippet(label, icon, largeIconSize)
        } ?: run {
            return AppSnippet(
                pkgInfo.packageName, context.packageManager.defaultActivityIcon, largeIconSize
            )
        }
    }

    /**
     * Generates an [AppSnippet] containing specified appIcon and appLabel
     */
    @JvmStatic
    fun getAppSnippet(context: Context, label: CharSequence?, icon: Drawable?): AppSnippet {
        val largeIconSize = getLargeIconSize(context)
        return AppSnippet(label, icon, largeIconSize)
    }

    private fun getLargeIconSize(context: Context): Int {
        val am = context.getSystemService<ActivityManager>(ActivityManager::class.java)
        return am.launcherLargeIconSize
    }

    /**
     * Utility method to load application label
     *
     * @param context context of package that can load the resources
     * @param appInfo ApplicationInfo object of package whose resources are to be loaded
     */
    private fun getAppLabelFromFile(context: Context, appInfo: ApplicationInfo): CharSequence? {
        val pm = context.packageManager
        var label: CharSequence? = null
        // Try to load the label from the package's resources. If an app has not explicitly
        // specified any label, just use the package name.
        if (appInfo.labelRes != 0) {
            try {
                label = appInfo.loadLabel(pm)
            } catch (e: Resources.NotFoundException) {
            }
        }
        if (label == null) {
            label = if (appInfo.nonLocalizedLabel != null) appInfo.nonLocalizedLabel
            else appInfo.packageName
        }
        return label
    }

    /**
     * Utility method to load application icon
     *
     * @param context context of package that can load the resources
     * @param appInfo ApplicationInfo object of package whose resources are to be loaded
     */
    private fun getAppIconFromFile(context: Context, appInfo: ApplicationInfo): Drawable? {
        val pm = context.packageManager
        var icon: Drawable? = null
        // Try to load the icon from the package's resources. If an app has not explicitly
        // specified any resource, just use the default icon for now.
        try {
            if (appInfo.icon != 0) {
                try {
                    icon = appInfo.loadIcon(pm)
                } catch (e: Resources.NotFoundException) {
                }
            }
            if (icon == null) {
                icon = context.packageManager.defaultActivityIcon
            }
        } catch (e: OutOfMemoryError) {
            Log.i(LOG_TAG, "Could not load app icon", e)
        }
        return icon
    }

    private fun processAppInfoForFile(appInfo: ApplicationInfo, sourceFile: File): ApplicationInfo {
        val archiveFilePath = sourceFile.absolutePath
        appInfo.publicSourceDir = archiveFilePath
        if (appInfo.splitNames != null && appInfo.splitSourceDirs == null) {
            val files = sourceFile.parentFile?.listFiles()
            val splits = appInfo.splitNames!!
                .mapNotNull { findFilePath(files, "$it.apk") }
                .toTypedArray()

            appInfo.splitSourceDirs = splits
            appInfo.splitPublicSourceDirs = splits
        }
        return appInfo
    }

    private fun findFilePath(files: Array<File>?, postfix: String): String? {
        files?.let {
            for (file in it) {
                val path = file.absolutePath
                if (path.endsWith(postfix)) {
                    return path
                }
            }
        }
        return null
    }

    /**
     * @return the packageName corresponding to a UID.
     */
    @JvmStatic
    fun getPackageNameForUid(context: Context, uid: Int, preferredPkgName: String?): String? {
        if (uid == Process.INVALID_UID) {
            return null
        }
        // If the sourceUid belongs to the system downloads provider, we explicitly return the
        // name of the Download Manager package. This is because its UID is shared with multiple
        // packages, resulting in uncertainty about which package will end up first in the list
        // of packages associated with this UID
        val pm = context.packageManager
        val systemDownloadProviderInfo = getSystemDownloadsProviderInfo(pm, uid)
        if (systemDownloadProviderInfo != null) {
            return systemDownloadProviderInfo.packageName
        }

        val packagesForUid = pm.getPackagesForUid(uid) ?: return null
        if (packagesForUid.size > 1) {
            Log.i(LOG_TAG, "Multiple packages found for source uid $uid")
            if (preferredPkgName != null) {
                for (packageName in packagesForUid) {
                    if (packageName == preferredPkgName) {
                        return packageName
                    }
                }
            }
        }
        return packagesForUid[0]
    }

    /**
     * Utility method to get package information for a given [File]
     */
    @JvmStatic
    fun getPackageInfo(context: Context, sourceFile: File, flags: Int): PackageInfo? {
        var filePath = sourceFile.absolutePath
        if (filePath.endsWith(SPLIT_BASE_APK_SUFFIX)) {
            val dir = sourceFile.parentFile
            try {
                Files.list(dir.toPath()).use { list ->
                    val count: Long = list
                        .filter { name: Path -> name.endsWith(SPLIT_APK_SUFFIX) }
                        .limit(2)
                        .count()
                    if (count > 1) {
                        // split apks, use file directory to get archive info
                        filePath = dir.path
                    }
                }
            } catch (ignored: Exception) {
                // No access to the parent directory, proceed to read app snippet
                // from the base apk only
            }
        }
        return try {
            context.packageManager.getPackageArchiveInfo(filePath, flags)
        } catch (ignored: Exception) {
            null
        }
    }

    /**
     * Is a profile part of a user?
     *
     * @param userManager The user manager
     * @param userHandle The handle of the user
     * @param profileHandle The handle of the profile
     *
     * @return If the profile is part of the user or the profile parent of the user
     */
    @JvmStatic
    fun isProfileOfOrSame(
        userManager: UserManager,
        userHandle: UserHandle,
        profileHandle: UserHandle?,
    ): Boolean {
        if (profileHandle == null) {
            return false
        }
        return if (userHandle == profileHandle) {
            true
        } else userManager.getProfileParent(profileHandle) != null
                && userManager.getProfileParent(profileHandle) == userHandle
    }

    /**
     * Utility method to get the application label from the package name
     */
    @JvmStatic
    fun getApplicationLabel(context: Context, packageName: String): CharSequence? {
        return try {
            val appInfo = packageName.let {
                context.packageManager.getApplicationInfo(
                    it, PackageManager.ApplicationInfoFlags.of(0)
                )
            }
            appInfo.let { context.packageManager.getApplicationLabel(it) }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * @return If the device supports the material design in the package installer
     */
    @JvmStatic
    fun isMaterialDesignEnabled(context: Context): Boolean {
        var result: Boolean
        try {
            result = android.content.pm.Flags.usePiaV2()
        } catch (_: Resources.NotFoundException) {
            return false
        }

        return result
    }

    /**
     * The class to hold an incoming package's icon and label.
     * See [getAppSnippet]
     */
    @Parcelize
    data class AppSnippet(
        var label: CharSequence?,
        var icon: Drawable?,
        var iconSize: Int,
    ) : Parcelable {
        private companion object : Parceler<AppSnippet> {
            override fun AppSnippet.write(dest: Parcel, flags: Int) {
                dest.writeString(label.toString())

                val bmp = getBitmapFromDrawable(icon!!)
                dest.writeBlob(getBytesFromBitmap(bmp))
                bmp.recycle()

                dest.writeInt(iconSize)
            }

            @SuppressLint("UseKtx")
            override fun create(parcel: Parcel): AppSnippet {
                val label = parcel.readString()

                val b: ByteArray = parcel.readBlob()!!
                val bmp: Bitmap? = BitmapFactory.decodeByteArray(b, 0, b.size)
                val icon = BitmapDrawable(Resources.getSystem(), bmp)

                val iconSize = parcel.readInt()

                return AppSnippet(label.toString(), icon, iconSize)
            }
        }

        @SuppressLint("UseKtx")
        private fun getBitmapFromDrawable(drawable: Drawable): Bitmap {
            // Create an empty bitmap with the dimensions of our drawable
            val bmp = Bitmap.createBitmap(
                drawable.intrinsicWidth,
                drawable.intrinsicHeight, Bitmap.Config.ARGB_8888
            )
            // Associate it with a canvas. This canvas will draw the icon on the bitmap
            val canvas = Canvas(bmp)
            // Draw the drawable in the canvas. The canvas will ultimately paint the drawable in the
            // bitmap held within
            drawable.draw(canvas)

            // Scale it down if the icon is too large
            if ((bmp.getWidth() > iconSize * 2) || (bmp.getHeight() > iconSize * 2)) {
                val scaledBitmap = Bitmap.createScaledBitmap(bmp, iconSize, iconSize, true)
                if (scaledBitmap != bmp) {
                    bmp.recycle()
                }
                return scaledBitmap
            }
            return bmp
        }

        private fun getBytesFromBitmap(bmp: Bitmap): ByteArray? {
            var baos = ByteArrayOutputStream()
            baos.use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            return baos.toByteArray()
        }

        override fun toString(): String {
            return "AppSnippet[label = $label, hasIcon = ${icon != null}]"
        }
    }
}
