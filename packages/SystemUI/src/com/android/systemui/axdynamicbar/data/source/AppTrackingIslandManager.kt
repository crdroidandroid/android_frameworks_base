package com.android.systemui.axdynamicbar.data.source

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.IActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.RemoteException
import android.os.UserHandle
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
        private const val MAX_LAUNCHER_CACHE = 128
    }

    private val _appSwitchEvent = MutableStateFlow<IslandEvent.AppSwitch?>(null)
    val appSwitchEvent: StateFlow<IslandEvent.AppSwitch?> = _appSwitchEvent.asStateFlow()

    private val recentApps = mutableListOf<IslandEvent.RecentApp>()
    @Volatile private var listening = false
    private var launcherPackage: String? = null
    private val activityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }
    private var switchesSincePrune = 0

    @Volatile private var currentForegroundPkg: String? = null

    private fun emitEvent() {
        val event =
            if (recentApps.isNotEmpty())
                IslandEvent.AppSwitch(
                    recentApps = recentApps.toList(),
                    previousApp = _appSwitchEvent.value?.previousApp,
                )
            else null
        logEvent("emit", event)
        _appSwitchEvent.value = event
    }

    private val listener =
        object : TaskStackChangeListener {
            override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
                if (!listening) return
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
                if (!hasLauncherIntent(pkg)) return
                if (pkg == currentForegroundPkg) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "skip duplicate front pkg=$pkg task=${taskInfo.taskId}")
                    }
                    return
                }

                val leavingPkg = currentForegroundPkg
                currentForegroundPkg = pkg

                val known = synchronized(recentApps) { recentApps.firstOrNull { it.packageName == pkg } }

                val appName =
                    known?.appName
                        ?: try {
                            context.packageManager
                                .getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0))
                                .toString()
                        } catch (_: Exception) {
                            pkg
                        }

                val appIcon =
                    known?.appIcon
                        ?: try {
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
                    if (!listening) return
                    recentApps.removeAll { it.packageName == pkg }
                    recentApps.add(0, app)
                    while (recentApps.size > MAX_RECENT) recentApps.removeLast()

                    if (++switchesSincePrune >= MAX_RECENT) {
                        switchesSincePrune = 0
                        try {
                            val runningTaskIds =
                                activityManager.getRunningTasks(MAX_RECENT + 12)
                                    .mapTo(HashSet()) { it.taskId }
                            recentApps.removeAll { it.taskId !in runningTaskIds }
                        } catch (e: Exception) {
                            Log.w(TAG, "Unable to prune recent tasks", e)
                        }
                    }

                    val newPreviousApp =
                        if (leavingPkg != null && leavingPkg != pkg) {
                            recentApps.find { it.packageName == leavingPkg }
                        } else {
                            _appSwitchEvent.value?.previousApp
                        }

                    val event =
                        if (recentApps.isNotEmpty())
                            IslandEvent.AppSwitch(
                                recentApps = recentApps.toList(),
                                previousApp = newPreviousApp,
                            )
                        else null
                    logEvent("front pkg=$pkg leaving=$leavingPkg", event)
                    _appSwitchEvent.value = event
                }
            }

            override fun onTaskRemoved(taskId: Int) {
                if (!listening) return
                synchronized(recentApps) {
                    if (!listening) return
                    val removed = recentApps.removeAll { it.taskId == taskId }
                    if (removed) emitEvent()
                }
            }
        }

    fun startListening() {
        if (listening) return
        listening = true
        currentForegroundPkg = null
        switchesSincePrune = 0
        launcherPackage = resolveLauncherPackage()
        TaskStackChangeListeners.getInstance().registerTaskStackListener(listener)
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        currentForegroundPkg = null
        switchesSincePrune = 0
        synchronized(launcherIntentCache) { launcherIntentCache.clear() }
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(listener)
        synchronized(recentApps) {
            recentApps.clear()
            _appSwitchEvent.value = null
        }
    }

    fun clear() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "clear")
        }
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
        if (!listening || taskId < 0) return
        val tracked = synchronized(recentApps) {
            recentApps.firstOrNull { it.taskId == taskId }
                ?: _appSwitchEvent.value?.previousApp?.takeIf { it.taskId == taskId }
        } ?: return
        try {
            val running = activityManager.getRunningTasks(100)
                .firstOrNull { it.taskId == taskId } ?: return
            if (running.baseActivity?.packageName != tracked.packageName &&
                running.topActivity?.packageName != tracked.packageName
            ) return
            activityManager.moveTaskToFront(taskId, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to switch to task $taskId", e)
            synchronized(recentApps) {
                recentApps.removeAll { it.taskId == taskId }
                emitEvent()
            }
        }
    }

    fun killApp(taskId: Int) {
        synchronized(recentApps) {
            val app = recentApps.find { it.taskId == taskId } ?: return
            val pkg = app.packageName

            try {
                val atm = ActivityTaskManager.getService()
                if (atm.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_PINNED) {
                    Log.w(TAG, "Cannot kill app while in pinned lock task mode")
                    return
                }

                val am = ActivityManager.getService() as IActivityManager
                am.forceStopPackage(pkg, UserHandle.myUserId())
                am.removeTask(taskId)
            } catch (e: RemoteException) {
                Log.w(TAG, "Failed to kill app $pkg (task $taskId)", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Failed to kill app $pkg (task $taskId)", e)
            }

            recentApps.removeAll { it.taskId == taskId }
            if (pkg == currentForegroundPkg) currentForegroundPkg = null

            val newPreviousApp =
                if (_appSwitchEvent.value?.previousApp?.taskId == taskId) null
                else _appSwitchEvent.value?.previousApp

            val event =
                if (recentApps.isNotEmpty())
                    IslandEvent.AppSwitch(
                        recentApps = recentApps.toList(),
                        previousApp = newPreviousApp,
                    )
                else null
            logEvent("kill task=$taskId pkg=$pkg", event)
            _appSwitchEvent.value = event
        }
    }

    private val launcherIntentCache =
        object : LinkedHashMap<String, Boolean>(MAX_LAUNCHER_CACHE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                size > MAX_LAUNCHER_CACHE
        }

    private fun hasLauncherIntent(pkg: String): Boolean = synchronized(launcherIntentCache) {
        launcherIntentCache.getOrPut(pkg) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
            try {
                context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
            } catch (_: Exception) {
                false
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

    private fun logEvent(reason: String, event: IslandEvent.AppSwitch?) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        Log.d(
            TAG,
            "$reason emit=${event != null} " +
                "count=${event?.recentApps?.size ?: 0} " +
                "top=${event?.recentApps?.firstOrNull()?.packageName} " +
                "prev=${event?.previousApp?.packageName}",
        )
    }
}
