/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.data.source

import android.app.ActivityManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Packages of the task on screen, so its own events can step aside. */
@SysUISingleton
class ForegroundAppTracker @Inject constructor() {

    private val _packages = MutableStateFlow<Set<String>>(emptySet())
    val packages: StateFlow<Set<String>> = _packages.asStateFlow()

    @Volatile private var listening = false

    private val listener =
        object : TaskStackChangeListener {
            override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
                if (listening) update(taskInfo)
            }

            override fun onTaskRemoved(taskId: Int) {
                if (listening) refresh()
            }
        }

    fun startListening() {
        if (listening) return
        listening = true
        TaskStackChangeListeners.getInstance().registerTaskStackListener(listener)
        refresh()
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(listener)
        _packages.value = emptySet()
    }

    private fun refresh() {
        update(ActivityManagerWrapper.getInstance().runningTask)
    }

    private fun update(task: ActivityManager.RunningTaskInfo?) {
        _packages.value =
            if (task == null) emptySet()
            else setOfNotNull(task.topActivity?.packageName, task.baseActivity?.packageName)
    }
}
