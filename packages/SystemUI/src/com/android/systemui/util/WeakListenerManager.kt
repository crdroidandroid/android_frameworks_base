/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.util

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

class WeakListenerManager<T> {

    private val listeners = ArrayList<WeakReference<T>>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var onActive: (() -> Unit)? = null
    private var onInactive: (() -> Unit)? = null
    private var isActive = false

    private var cleanupScheduled = false
    private val cleanupRunnable = Runnable {
        synchronized(listeners) {
            listeners.removeAll { it.get() == null }
            cleanupScheduled = false
        }
    }

    fun addListener(listener: T) {
        synchronized(listeners) {
            if (listeners.any { it.get() === listener }) return
            listeners.add(WeakReference(listener))
            scheduleCleanup()

            if (!isActive && listeners.any { it.get() != null }) {
                isActive = true
                onActive?.invoke()
            }
        }
    }

    fun removeListener(listener: T) {
        synchronized(listeners) {
            listeners.removeAll { it.get() == null || it.get() === listener }

            if (isActive && listeners.none { it.get() != null }) {
                isActive = false
                onInactive?.invoke()
            }
        }
    }

    fun notifyOnMain(action: (T) -> Unit) {
        val snapshot = synchronized(listeners) {
            listeners.mapNotNull { it.get() }
        }

        if (snapshot.isEmpty()) return

        mainHandler.post {
            snapshot.forEach(action)
        }

        scheduleCleanup()
    }

    // meh, lazy to rewrite other calls to this :) 
    fun notify(action: (T) -> Unit) = notifyOnMain(action)

    fun setLifecycleCallbacks(onActive: (() -> Unit)?, onInactive: (() -> Unit)?) {
        this.onActive = onActive
        this.onInactive = onInactive
    }

    fun size(): Int = synchronized(listeners) {
        listeners.count { it.get() != null }
    }

    fun isEmpty(): Boolean = size() == 0

    private fun scheduleCleanup() {
        if (!cleanupScheduled) {
            cleanupScheduled = true
            mainHandler.postDelayed(cleanupRunnable, 5000)
        }
    }
}
