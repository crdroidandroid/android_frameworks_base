package com.google.android.systemui.smartspace

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.text.format.DateFormat
import android.view.View
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.statusbar.policy.NextAlarmControllerImpl
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.statusbar.policy.domain.model.ZenModeInfo
import com.android.systemui.res.R;
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeyguardZenAlarmViewController
@Inject
constructor(
    val context: Context,
    val datePlugin: BcSmartspaceDataPlugin,
    val zenModeController: ZenModeController,
    val zenModeInteractor: ZenModeInteractor,
    val alarmManager: AlarmManager,
    val nextAlarmController: NextAlarmControllerImpl,
    val handler: Handler,
    val applicationScope: CoroutineScope,
    val bgDispatcher: CoroutineDispatcher,
) {
    lateinit var alarmImage: Drawable
    val smartspaceViews = mutableSetOf<BcSmartspaceDataPlugin.SmartspaceView>()

    private val nextAlarmCallback =
        NextAlarmController.NextAlarmChangeCallback {
            applicationScope.launch { updateNextAlarm() }
        }

    private val showNextAlarm = AlarmManager.OnAlarmListener { showAlarm(null) }

    val attachStateChangeListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                val smartspaceView = view as? BcSmartspaceDataPlugin.SmartspaceView ?: return
                if (smartspaceViews.add(smartspaceView)) {
                    view.repeatWhenAttached {
                        zenModeInteractor.mainActiveMode.collect { modeInfo ->
                            updateModeIcon(smartspaceView, modeInfo)
                        }
                    }
                }
                if (smartspaceViews.size == 1) {
                    nextAlarmController.addCallback(nextAlarmCallback)
                }
                applicationScope.launch { updateNextAlarm() }
            }

            override fun onViewDetachedFromWindow(view: View) {
                val smartspaceView = view as? BcSmartspaceDataPlugin.SmartspaceView ?: return
                smartspaceViews.remove(smartspaceView)
                if (smartspaceViews.isEmpty()) {
                    nextAlarmController.removeCallback(nextAlarmCallback)
                }
            }
        }

    suspend fun updateNextAlarm() {
        applicationScope.launch {
            alarmManager.cancel(showNextAlarm)
            val nextAlarmTime = getNextAlarmTime()
            if (nextAlarmTime > 0) {
                val triggerTime = nextAlarmTime - TimeUnit.HOURS.toMillis(12L)
                if (triggerTime > 0) {
                    alarmManager.setExact(
                        AlarmManager.RTC,
                        triggerTime,
                        "lock_screen_next_alarm",
                        showNextAlarm,
                        handler,
                    )
                }
            }
            showAlarm(nextAlarmTime)
        }
    }

    fun showAlarm(alarmTime: Long?): Job =
        applicationScope.launch {
            val time = alarmTime ?: getNextAlarmTime()
            val alarmString =
                if (time > 0 && time <= System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12L)) {
                    val pattern =
                        if (DateFormat.is24HourFormat(context, ActivityManager.getCurrentUser()))
                            "HH:mm"
                        else "h:mm"
                    DateFormat.format(pattern, time).toString()
                } else null

            smartspaceViews.forEach { view ->
                if (alarmString != null) {
                    view.setNextAlarm(alarmImage, alarmString)
                } else {
                    view.setNextAlarm(null, null)
                }
            }
        }

    // private suspend fun getNextAlarmTime(): Long = withContext(bgDispatcher) {
    //    val zenControllerImpl = zenModeController as? ZenModeControllerImpl ?: return@withContext
    // 0L
    //    zenControllerImpl.mAlarmManager.getNextAlarmClock(zenControllerImpl.mUserId)?.triggerTime
    // ?: 0L
    // }

    private suspend fun getNextAlarmTime(): Long =
        withContext(bgDispatcher) {
            val nextAlarm = alarmManager.getNextAlarmClock(ActivityManager.getCurrentUser())
            nextAlarm?.triggerTime ?: 0L
        }

    fun updateModeIcon(view: BcSmartspaceDataPlugin.SmartspaceView, zenModeInfo: ZenModeInfo?) {
        applicationScope.launch {
            if (zenModeInfo != null) {
                val description =
                    context.getString(R.string.active_mode_content_description, zenModeInfo.name)
                view.setDnd(zenModeInfo.icon.drawable, description)
            } else {
                view.setDnd(null, null)
            }
        }
    }
}
