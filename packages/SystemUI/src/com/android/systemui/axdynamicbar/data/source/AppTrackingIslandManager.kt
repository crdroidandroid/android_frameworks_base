package com.android.systemui.axdynamicbar.data.source

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class AppTrackingIslandManager @Inject constructor(@Application private val context: Context) {
    companion object {
        private const val TAG = "AppTrackingIslandManager"
        private const val MAX_RECENT = 8
    }

    private val _appSwitchEvent = MutableStateFlow<IslandEvent.AppSwitch?>(null)
    val appSwitchEvent: StateFlow<IslandEvent.AppSwitch?> = _appSwitchEvent.asStateFlow()

    private val recentApps = mutableListOf<IslandEvent.RecentApp>()
    private var listening = false
    private var launcherPackage: String? = null
    private val activityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }
    private var switchesSincePrune = 0

    @Volatile private var currentForegroundPkg: String? = null

    private fun emitEvent() {
        _appSwitchEvent.value =
            if (recentApps.isNotEmpty())
                IslandEvent.AppSwitch(
                    recentApps = recentApps.toList(),
                    previousApp = _appSwitchEvent.value?.previousApp,
                )
            else null
    }

    private val listener =
        object : TaskStackChangeListener {
            override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
                val topActivity = taskInfo.topActivity ?: return
                val pkg = topActivity.packageName
                if (
                    pkg == launcherPackage ||
                        pkg == "com.android.systemui" ||
                        pkg == "com.android.launcher3"
                ) {
                    currentForegroundPkg = null
                    return
                }

                val leavingPkg = currentForegroundPkg
                currentForegroundPkg = pkg

                val appName =
                    try {
                        context.packageManager
                            .getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0))
                            .toString()
                    } catch (_: Exception) {
                        pkg
                    }

                val appIcon =
                    try {
                        context.packageManager.getApplicationIcon(pkg)
                    } catch (_: Exception) {
                        null
                    }

                val app =
                    IslandEvent.RecentApp(
                        packageName = pkg,
                        appName = appName,
                        appIcon = appIcon,
                        taskId = taskInfo.taskId,
                        lastActiveTime = System.currentTimeMillis(),
                    )

                synchronized(recentApps) {
                    recentApps.removeAll { it.packageName == pkg }
                    recentApps.add(0, app)
                    while (recentApps.size > MAX_RECENT) recentApps.removeLast()

                    if (++switchesSincePrune >= 3) {
                        switchesSincePrune = 0
                        val runningTaskIds =
                            activityManager.getRunningTasks(MAX_RECENT).map { it.taskId }.toSet()
                        recentApps.removeAll { it.taskId !in runningTaskIds }
                    }

                    val newPreviousApp =
                        if (leavingPkg != null && leavingPkg != pkg) {
                            recentApps.find { it.packageName == leavingPkg }
                        } else {
                            _appSwitchEvent.value?.previousApp
                        }

                    _appSwitchEvent.value =
                        if (recentApps.isNotEmpty())
                            IslandEvent.AppSwitch(
                                recentApps = recentApps.toList(),
                                previousApp = newPreviousApp,
                            )
                        else null
                }
            }

            override fun onTaskRemoved(taskId: Int) {
                synchronized(recentApps) {
                    val removed = recentApps.removeAll { it.taskId == taskId }
                    if (removed) emitEvent()
                }
            }
        }

    fun startListening() {
        if (listening) return
        listening = true
        launcherPackage = resolveLauncherPackage()
        TaskStackChangeListeners.getInstance().registerTaskStackListener(listener)
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(listener)
        synchronized(recentApps) {
            recentApps.clear()
            _appSwitchEvent.value = null
        }
    }

    fun clear() {
        _appSwitchEvent.value = null
    }

    fun refreshRecentApps() {
        synchronized(recentApps) {
            val runningTaskIds = activityManager.getRunningTasks(MAX_RECENT + 4).map { it.taskId }.toSet()
            val removed = recentApps.removeAll { it.taskId !in runningTaskIds }
            if (removed) emitEvent()
        }
    }

    fun switchToApp(taskId: Int) {
        try {
            activityManager.moveTaskToFront(taskId, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to switch to task $taskId", e)
            synchronized(recentApps) {
                recentApps.removeAll { it.taskId == taskId }
                emitEvent()
            }
        }
    }

    private fun resolveLauncherPackage(): String? =
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo =
                context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
}

