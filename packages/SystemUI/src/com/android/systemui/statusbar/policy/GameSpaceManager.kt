/*
 * Copyright (C) 2021 Chaldeaprjkt
 * Copyright (C) 2022-2024 crDroid Android Project
 * Copyright (C) 2025 AxionAOSP Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy

import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.RemoteException
import android.os.UserHandle
import android.provider.Settings

import com.android.systemui.dagger.SysUISingleton

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.awaitClose
import javax.inject.Inject

@SysUISingleton
class GameSpaceManager @Inject constructor(
    private val context: Context,
    private val keyguardStateController: KeyguardStateController,
) {
    private val taskManager by lazy { ActivityTaskManager.getService() }
    private val powerManager by lazy { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val coroutineScope = CoroutineScope(Dispatchers.Main.immediate)
    
    private val _activeGame = MutableStateFlow<String?>(null)
    val activeGame: StateFlow<String?> = _activeGame.asStateFlow()
    
    private val refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var observationJob: Job? = null

    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskStackChanged() = refresh()
        override fun onTaskRemoved(taskId: Int) = refresh()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> _activeGame.value = null
                Intent.ACTION_SCREEN_ON -> refresh()
            }
        }
    }

    private val keyguardCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardShowingChanged() {
            if (!keyguardStateController.isShowing) refresh()
        }
    }

    init {
        observe()
    }

    fun observe() {
        if (observationJob != null) return
        
        observationJob = coroutineScope.launch {
            merge(
                taskStackFlow(),
                screenInteractiveFlow(),
                keyguardStateFlow(),
                refreshTrigger
            ).collect {
                updateGameState()
            }
        }

        registerReceivers()
    }

    fun unobserve() {
        observationJob?.cancel()
        observationJob = null
        unregisterReceivers()
    }

    fun shouldSuppressFullScreenIntent() =
        Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.GAMESPACE_SUPPRESS_FULLSCREEN_INTENT, 0,
            UserHandle.USER_CURRENT
        ) == 1 && activeGame.value != null

    private fun refresh() {
        refreshTrigger.tryEmit(Unit)
    }

    private fun registerReceivers() {
        try {
            taskManager.registerTaskStackListener(taskStackListener)
            context.registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                Context.RECEIVER_NOT_EXPORTED
            )
            keyguardStateController.addCallback(keyguardCallback)
        } catch (e: Exception) {}
    }

    private fun unregisterReceivers() {
        try {
            taskManager.unregisterTaskStackListener(taskStackListener)
            context.unregisterReceiver(screenReceiver)
            keyguardStateController.removeCallback(keyguardCallback)
        } catch (e: Exception) {}
    }

    private fun taskStackFlow(): Flow<Unit> = callbackFlow {
        val listener = object : TaskStackListener() {
            override fun onTaskStackChanged() {
                trySend(Unit)
            }

            override fun onTaskRemoved(taskId: Int) {
                trySend(Unit)
            }
        }
        try {
            taskManager.registerTaskStackListener(listener)
            awaitClose { taskManager.unregisterTaskStackListener(listener) }
        } catch (e: Exception) {
            close(e)
        }
    }

    private fun screenInteractiveFlow(): Flow<Boolean> = callbackFlow {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.action?.let { action ->
                    trySend(action == Intent.ACTION_SCREEN_ON)
                }
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    private fun keyguardStateFlow(): Flow<Boolean> = callbackFlow {
        val callback = object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() {
                trySend(keyguardStateController.isShowing)
            }
        }
        keyguardStateController.addCallback(callback)
        awaitClose { keyguardStateController.removeCallback(callback) }
    }

    private suspend fun updateGameState() {
        if (!powerManager.isInteractive || keyguardStateController.isShowing) {
            _activeGame.value = null
            return
        }

        val currentPackage = getForegroundPackage() ?: run {
            _activeGame.value = null
            return
        }

        _activeGame.value = if (isInGameList(currentPackage)) currentPackage else null
        dispatchGameState()
    }

    private suspend fun getForegroundPackage(): String? = try {
        withContext(Dispatchers.IO) {
            taskManager.focusedRootTaskInfo?.topActivity?.packageName
        }
    } catch (e: RemoteException) {
        null
    }

    private fun isInGameList(packageName: String): Boolean {
        val games = Settings.System.getStringForUser(
            context.contentResolver,
            Settings.System.GAMESPACE_GAME_LIST,
            UserHandle.USER_CURRENT
        ) ?: return false

        return games.split(";").any { it.split("=").first() == packageName }
    }

    private fun dispatchGameState() {
        val action = if (_activeGame.value != null) ACTION_GAME_START else ACTION_GAME_STOP
        Intent(action).apply {
            setPackage(GAMESPACE_PACKAGE)
            component = ComponentName.unflattenFromString(RECEIVER_CLASS)
            putExtra(EXTRA_CALLER_NAME, context.packageName)
            _activeGame.value?.let { putExtra(EXTRA_ACTIVE_GAME, it) }
            addFlags(
                Intent.FLAG_RECEIVER_REPLACE_PENDING or
                        Intent.FLAG_RECEIVER_FOREGROUND or
                        Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
            )
            context.sendBroadcastAsUser(
                this, UserHandle.CURRENT, android.Manifest.permission.MANAGE_GAME_MODE
            )
        }
    }

    companion object {
        private const val ACTION_GAME_START = "io.chaldeaprjkt.gamespace.action.GAME_START"
        private const val ACTION_GAME_STOP = "io.chaldeaprjkt.gamespace.action.GAME_STOP"
        private const val GAMESPACE_PACKAGE = "io.chaldeaprjkt.gamespace"
        private const val RECEIVER_CLASS = "io.chaldeaprjkt.gamespace/.gamebar.GameBroadcastReceiver"
        private const val EXTRA_CALLER_NAME = "source"
        private const val EXTRA_ACTIVE_GAME = "package_name"
    }
}
