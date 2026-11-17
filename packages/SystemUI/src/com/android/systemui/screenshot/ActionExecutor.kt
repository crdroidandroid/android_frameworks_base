/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot

import android.app.ActivityOptions
import android.app.BroadcastOptions
import android.app.ExitTransitionCoordinator
import android.app.ExitTransitionCoordinator.ExitTransitionCallbacks
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.os.UserHandle
import android.util.Log
import android.util.Pair
import android.view.Window
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.app.ChooserActivity
import com.android.systemui.clipboardoverlay.ClipboardListener.EXTRA_SUPPRESS_OVERLAY
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.user.utils.UserScopedService
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope

class ActionExecutor
@AssistedInject
constructor(
    private val context: Context,
    private val intentExecutor: ActionIntentExecutor,
    private val userRepository: UserRepository,
    private val clipboardManager: UserScopedService<ClipboardManager>,
    @Application private val applicationScope: CoroutineScope,
    @Assisted val window: Window,
    @Assisted val viewProxy: ScreenshotShelfViewProxy,
    @Assisted val finishDismiss: () -> Unit,
) {

    var isPendingSharedTransition = false
        private set

    fun resetPendingSharedTransition() {
        isPendingSharedTransition = false
    }

    private val currentUserHandle: UserHandle
        get() = userRepository.getSelectedUserInfo().userHandle

    fun startSharedTransition(intent: Intent, user: UserHandle, overrideTransition: Boolean) {
        isPendingSharedTransition = true
        viewProxy.fadeForSharedTransition()
        val windowTransition = createWindowTransition()
        applicationScope.launch("$TAG#launchIntentAsync") {
            intentExecutor.launchIntent(
                intent,
                user,
                overrideTransition,
                windowTransition.first,
                windowTransition.second,
            )
        }
    }

    fun sendPendingIntent(pendingIntent: PendingIntent) {
        try {
            val options = BroadcastOptions.makeBasic()
            options.setInteractive(true)
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
            pendingIntent.send(options.toBundle())
            viewProxy.requestDismissal(null)
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "Intent cancelled", e)
        }
    }

    // TODO(b/458072887): Fix the image data being pasteable as text when right-clicking.
    // Copying the screenshot to clipboard avoids the Clipboard overlay UI.
    fun copyScreenshotToClipboard(uri: Uri) {
        val clipData = ClipData.newUri(context.contentResolver, "Screenshot", uri)
        clipData.description.extras =
            PersistableBundle().apply { putBoolean(EXTRA_SUPPRESS_OVERLAY, true) }
        clipboardManager.forUser(currentUserHandle).setPrimaryClip(clipData)
        viewProxy.requestDismissal(null)
    }

    /**
     * Supplies the necessary bits for the shared element transition to share sheet. Note that once
     * called, the action intent to share must be sent immediately after.
     */
    private fun createWindowTransition(): Pair<ActivityOptions, ExitTransitionCoordinator> {
        val callbacks: ExitTransitionCallbacks =
            object : ExitTransitionCallbacks {
                override fun isReturnTransitionAllowed(): Boolean {
                    return false
                }

                override fun hideSharedElements() {
                    isPendingSharedTransition = false
                    finishDismiss.invoke()
                }

                override fun onFinish() {}
            }
        val transition =
            ActivityOptions.startSharedElementAnimation(
                window,
                callbacks,
                null,
                Pair.create(
                    viewProxy.screenshotPreview,
                    ChooserActivity.FIRST_IMAGE_PREVIEW_TRANSITION_NAME,
                ),
            )
        transition.first.launchDisplayId = window.context.displayId
        return transition
    }

    @AssistedFactory
    interface Factory {
        fun create(
            window: Window,
            viewProxy: ScreenshotShelfViewProxy,
            finishDismiss: (() -> Unit),
        ): ActionExecutor
    }

    companion object {
        private const val TAG = "ActionExecutor"
    }
}
