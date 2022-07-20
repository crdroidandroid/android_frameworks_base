package com.google.android.systemui.smartspace

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Handler
import android.text.format.DateFormat
import android.view.View
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.res.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.statusbar.policy.ZenModeController
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@SysUISingleton
class KeyguardZenAlarmViewController @Inject constructor(
    val context: Context,
    val plugin: BcSmartspaceDataPlugin,
    val zenModeController: ZenModeController,
    val alarmManager: AlarmManager,
    val nextAlarmController: NextAlarmController,
    val handler: Handler
) {
    val alarmImage = context.getResources().getDrawable(R.drawable.ic_access_alarms_big, null)
    val smartspaceViews = LinkedHashSet<BcSmartspaceDataPlugin.SmartspaceView>()
	val zenModeCallback = object : ZenModeController.Callback {
        override fun onZenChanged(i: Int) {
            updateDnd()
        }
    }
    
    val nextAlarmCallback = object : NextAlarmController.NextAlarmChangeCallback {
            override fun onNextAlarmChanged(alarmClockInfo: AlarmManager.AlarmClockInfo?) {
                updateNextAlarm()
            }
        }
    
    val dndImage: Drawable = loadDndImage()
    
    fun init() {
        plugin.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                smartspaceViews.add(v as BcSmartspaceDataPlugin.SmartspaceView)
                if (smartspaceViews.size === 1) {
                    zenModeController.addCallback(zenModeCallback)
                    nextAlarmController.addCallback(nextAlarmCallback)
                }
                refresh()
            }

            override fun onViewDetachedFromWindow(v: View) {
                smartspaceViews.remove(v as BcSmartspaceDataPlugin.SmartspaceView)
                if (smartspaceViews.isEmpty()) {
                    zenModeController.removeCallback(zenModeCallback)
                    nextAlarmController.removeCallback(nextAlarmCallback)
                }
            }
        })
        updateNextAlarm()
    }

    fun refresh() {
        updateDnd()
        updateNextAlarm()
    }

    private fun loadDndImage(): Drawable {
        val drawable: Drawable = context.getResources().getDrawable(R.drawable.stat_sys_dnd, null)
        return drawable
    }

    fun updateDnd() {
        if (zenModeController.getZen() !== 0) {
            val string: String =
                context.getResources().getString(R.string.accessibility_quick_settings_dnd)
            for (smartspaceView in smartspaceViews) {
                smartspaceView.setDnd(dndImage, string)
            }
            return
        }
        for (smartspaceView in smartspaceViews) {
            smartspaceView.setDnd(null, null)
        }
    }

    fun updateNextAlarm() {
        alarmManager.cancel(object : AlarmManager.OnAlarmListener {
            override fun onAlarm() {
                showAlarm()
            }
        })
        val nextAlarm: Long = zenModeController.getNextAlarm()
        if (nextAlarm > 0) {
            val millis: Long = nextAlarm - TimeUnit.HOURS.toMillis(12L)
            if (millis > 0) {
                alarmManager.setExact(
                    1,
                    millis,
                    "lock_screen_next_alarm",
                    object : AlarmManager.OnAlarmListener {
                        override fun onAlarm() {
                            showAlarm()
                        }
                    },
                    handler
                )
            }
        }
        showAlarm()
    }

    fun showAlarm() {
        val nextAlarm: Long = zenModeController.getNextAlarm()
        if (nextAlarm > 0 && withinNHours(nextAlarm, 12L)) {
            val obj: String = DateFormat.format(
                if (DateFormat.is24HourFormat(
                        context,
                        ActivityManager.getCurrentUser()
                    )
                ) "HH:mm" else "h:mm", nextAlarm
            ).toString()
            for (smartspaceView in smartspaceViews) {
                smartspaceView.setNextAlarm(alarmImage, obj)
            }
            return
        }
        for (smartspaceView in smartspaceViews) {
            smartspaceView.setNextAlarm(null, null)
        }
    }

    private fun withinNHours(j: Long, j2: Long): Boolean {
        return j <= System.currentTimeMillis() + TimeUnit.HOURS.toMillis(j2)
    }
}
