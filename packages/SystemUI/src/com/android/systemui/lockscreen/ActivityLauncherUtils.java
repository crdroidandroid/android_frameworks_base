/*
     Copyright (C) 2024 the risingOS Android Project
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
package com.android.systemui.lockscreen;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.DeviceIdleManager;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.widget.Toast;

import androidx.annotation.StringRes;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;

import java.util.List;

public class ActivityLauncherUtils {

    private final static String PERSONALIZATIONS_ACTIVITY = "com.android.settings.Settings$personalizationSettingsLayoutActivity";
    private static final String SERVICE_PACKAGE = "org.omnirom.omnijaws";

    private final Context mContext;
    private final ActivityStarter mActivityStarter;
    private PackageManager mPackageManager;

    public ActivityLauncherUtils(Context context) {
        this.mContext = context;
        this.mActivityStarter = Dependency.get(ActivityStarter.class);
        mPackageManager = mContext.getPackageManager();
    }

    public String getInstalledMusicApp() {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_APP_MUSIC);
        final  List<ResolveInfo> musicApps = mPackageManager.queryIntentActivities(intent, 0);
        ResolveInfo musicApp = musicApps.isEmpty() ? null : musicApps.get(0);
        return musicApp != null ? musicApp.activityInfo.packageName : "";
    }

    public void launchAppIfAvailable(Intent launchIntent, @StringRes int appTypeResId) {
        final List<ResolveInfo> apps = mPackageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY);
        if (!apps.isEmpty()) {
            mActivityStarter.startActivity(launchIntent, true);
        } else {
            showNoDefaultAppFoundToast(appTypeResId);
        }
    }

    public void launchVoiceAssistant() {
        DeviceIdleManager dim = mContext.getSystemService(DeviceIdleManager.class);
        if (dim != null) {
            dim.endIdle("voice-search");
        }
        Intent voiceIntent = new Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE);
        voiceIntent.putExtra(RecognizerIntent.EXTRA_SECURE, true);
        mActivityStarter.startActivity(voiceIntent, true);
    }

    public void launchCamera() {
        final Intent launchIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        launchAppIfAvailable(launchIntent, R.string.camera);
    }

    public void launchTimer() {
        final Intent launchIntent = new Intent(AlarmClock.ACTION_SET_TIMER);
        launchAppIfAvailable(launchIntent, R.string.clock_timer);
    }

    public void launchCalculator() {
        final Intent launchIntent = new Intent();
        launchIntent.setAction(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_APP_CALCULATOR);
        launchAppIfAvailable(launchIntent, R.string.calculator);
    }
    
    public void launchSettingsComponent(String className) {
        if (mActivityStarter == null) return;
        Intent intent = className.equals(PERSONALIZATIONS_ACTIVITY) ? new Intent(Intent.ACTION_MAIN) : new Intent();
        intent.setComponent(new ComponentName("com.android.settings", className));
        mActivityStarter.startActivity(intent, true);
    }

    public void launchWeatherApp() {
        final Intent launchIntent = new Intent();
        launchIntent.setAction(Intent.ACTION_MAIN);
        launchIntent.setClassName(SERVICE_PACKAGE, SERVICE_PACKAGE + ".WeatherActivity");
        launchAppIfAvailable(launchIntent, R.string.omnijaws_weather);
    }
    
    public void launchMediaPlayerApp(String packageName) {
        if (!packageName.isEmpty()) {
            Intent launchIntent = mPackageManager.getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                mActivityStarter.startActivity(launchIntent, true);
            }
        }
    }
    
    public void startSettingsActivity() {
        if (mActivityStarter == null) return;
		mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS), true /* dismissShade */);
    }
    
    public void startIntent(Intent intent) {
        if (mActivityStarter == null) return;
        mActivityStarter.startActivity(intent, true /* dismissShade */);
    }

    private void showNoDefaultAppFoundToast(@StringRes int appTypeResId) {
        Toast.makeText(mContext, mContext.getString(appTypeResId) + " not found", Toast.LENGTH_SHORT).show();
    }
}
