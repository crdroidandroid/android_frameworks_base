/*
 * Copyright (C) 2025 AxionOS
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
import android.os.HandlerThread

class TaskWorkerManager private constructor() {

    companion object {
        val instance: TaskWorkerManager by lazy { TaskWorkerManager() }
    }

    val taskWorker: TaskWorker = TaskWorker("task_worker_default")

    class TaskWorker(taskWorkerTag: String) {

        private val handler: Handler

        init {
            val handlerThread = HandlerThread(taskWorkerTag)
            handlerThread.start()
            handler = Handler(handlerThread.looper)
        }

        fun post(runnable: Runnable) {
            handler.post(runnable)
        }

        fun postDelayed(runnable: Runnable, delay: Long) {
            handler.postDelayed(runnable, delay)
        }

        fun removeCallback(runnable: Runnable) {
            handler.removeCallbacks(runnable)
        }
    }
}
