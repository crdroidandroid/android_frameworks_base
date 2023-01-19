package com.android.systemui.keyguard.domain.quickaffordance

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.R
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.containeddrawable.ContainedDrawable
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** Google TV Remote controls quick affordance data source. */
@SysUISingleton
class RemoteControlsKeyguardQuickAffordanceConfig
@Inject
constructor(
    @Application context: Context,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor
) : KeyguardQuickAffordanceConfig {

    private val appContext = context.applicationContext

    override val state: Flow<KeyguardQuickAffordanceConfig.State> = conflatedCallbackFlow {
        val callback =
            object : KeyguardUpdateMonitorCallback() {
                override fun onKeyguardVisibilityChanged(showing: Boolean) {
                    trySendWithFailureLogging(state(), TAG)
                }
            }

        keyguardUpdateMonitor.registerCallback(callback)

        awaitClose {
            keyguardUpdateMonitor.removeCallback(callback)
        }
    }

    override fun onQuickAffordanceClicked(
        animationController: ActivityLaunchAnimator.Controller?,
    ): KeyguardQuickAffordanceConfig.OnClickedResult {
        return KeyguardQuickAffordanceConfig.OnClickedResult.StartActivity(
            intent =
                Intent()
                    .setClassName("com.google.android.videos", "com.google.android.apps.play.movies.common.remote.RemoteDevicesListActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            canShowWhileLocked = true,
        )
    }

    private fun isGoogleTvAppAvailable(): Boolean {
        try {
            appContext.packageManager.getPackageInfo("com.google.android.videos", 0);
            return true;
        }
        catch (e: PackageManager.NameNotFoundException) {
            return false;
        }
    }

    private fun state(): KeyguardQuickAffordanceConfig.State {
        return if (isGoogleTvAppAvailable()) {
            KeyguardQuickAffordanceConfig.State.Visible(
                icon = ContainedDrawable.WithResource(R.drawable.ic_remote),
                contentDescriptionResourceId = R.string.accessibility_remote_button,
            )
        } else {
            KeyguardQuickAffordanceConfig.State.Hidden
        }
    }

    companion object {
        private const val TAG = "RemoteControlsKeyguardQuickAffordanceConfig"
    }
}
