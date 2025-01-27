/*
     Copyright (C) 2023-2025 the risingOS Android Project
     Licensed under the Apache License, Version 2.0 (the "License");
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at
          http://www.apache.org/licenses/LICENSE-2.0
     Unless required by applicable law or agreed to in writing, software
     distributed under the License is distributed on an "AS IS" BASIS,
     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     See the License for the specific language governing permissions and
     limitations under the License.
*/
package com.android.systemui.lockscreen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.os.DeviceIdleManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.annotation.StringRes
import com.android.systemui.Dependency
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R

class ActivityLauncherUtils(private val context: Context) {

    companion object {
        private const val PERSONALIZATIONS_ACTIVITY = "com.android.settings.Settings\$mistifySettingsLayoutActivity"
        private const val SERVICE_PACKAGE = "org.omnirom.omnijaws"
    }

    private val activityStarter: ActivityStarter? = Dependency.get(ActivityStarter::class.java)
    private val packageManager: PackageManager = context.packageManager

    fun getInstalledMusicApp(): String {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_MUSIC)
        }
        val musicApps = packageManager.queryIntentActivities(intent, 0)
        return musicApps.firstOrNull()?.activityInfo?.packageName.orEmpty()
    }

    fun launchAppIfAvailable(launchIntent: Intent, @StringRes appTypeResId: Int) {
        val apps = packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
        if (apps.isNotEmpty()) {
            activityStarter?.startActivity(launchIntent, true)
        } else {
            showNoDefaultAppFoundToast(appTypeResId)
        }
    }

    fun launchVoiceAssistant() {
        val dim = context.getSystemService(DeviceIdleManager::class.java)
        dim?.endIdle("voice-search")
        val voiceIntent = Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE).apply {
            putExtra(RecognizerIntent.EXTRA_SECURE, true)
        }
        activityStarter?.startActivity(voiceIntent, true)
    }

    fun launchCamera() {
        val launchIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        launchAppIfAvailable(launchIntent, R.string.camera)
    }

    fun launchTimer() {
        val launchIntent = Intent(AlarmClock.ACTION_SET_TIMER)
        launchAppIfAvailable(launchIntent, R.string.clock_timer)
    }

    fun launchCalculator() {
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_CALCULATOR)
        }
        launchAppIfAvailable(launchIntent, R.string.calculator)
    }

    fun launchSettingsComponent(className: String) {
        val intent = if (className == PERSONALIZATIONS_ACTIVITY) {
            Intent(Intent.ACTION_MAIN)
        } else {
            Intent().setComponent(ComponentName("com.android.settings", className))
        }
        activityStarter?.startActivity(intent, true)
    }

    fun launchWeatherApp() {
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(SERVICE_PACKAGE, "$SERVICE_PACKAGE.WeatherActivity")
        }
        launchAppIfAvailable(launchIntent, R.string.omnijaws_weather)
    }

    fun launchWalletApp() {
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.walletnfcrel")?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        launchIntent?.let {
            launchAppIfAvailable(it, R.string.google_wallet)
        }
    }

    fun launchMediaPlayerApp(packageName: String) {
        if (packageName.isNotEmpty()) {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            launchIntent?.let {
                activityStarter?.startActivity(it, true)
            }
        }
    }

    fun launchQrScanner() {
        try {
            val qrScannerComponent = context.resources.getString(
                com.android.internal.R.string.config_defaultQrCodeComponent
            )
            val intent = if (qrScannerComponent.isNotEmpty()) {
                Intent().apply {
                    component = ComponentName.unflattenFromString(qrScannerComponent)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent().apply {
                    component = ComponentName(
                        "com.google.android.googlequicksearchbox",
                        "com.google.android.apps.search.lens.LensActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            startIntent(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to launch QR Scanner", Toast.LENGTH_SHORT).show()
        }
    }

    fun startSettingsActivity() {
        val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS)
        activityStarter?.startActivity(settingsIntent, true)
    }

    fun startIntent(intent: Intent) {
        activityStarter?.startActivity(intent, true)
    }

    private fun showNoDefaultAppFoundToast(@StringRes appTypeResId: Int) {
        Toast.makeText(context, context.getString(appTypeResId) + " not found", Toast.LENGTH_SHORT).show()
    }
}
