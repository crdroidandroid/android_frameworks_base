/*
 * Copyright (C) 2021 The Android Open Source Project
 *           (C) 2022 Paranoid Android
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

package com.android.systemui.qs.tiles.dialog

import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.View

import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.policy.BluetoothController

import javax.inject.Inject

private const val TAG = "BluetoothDialogFactory"
private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

/**
 * Factory to create [BluetoothDialog] objects.
 */
@SysUISingleton
class BluetoothDialogFactory @Inject constructor(
    @Main private val handler: Handler,
    private val context: Context,
    private val dialogLaunchAnimator: DialogLaunchAnimator,
    private val activityStarter: ActivityStarter,
    private val bluetoothController: BluetoothController
) {
    private var bluetoothDialog: BluetoothDialog? = null

    /** Creates a [BluetoothDialog]. The dialog will be animated from [view] if it is not null. */
    fun create(
        aboveStatusBar: Boolean,
        view: View?
    ) {
        if (bluetoothDialog != null) {
            logD {
                "BluetoothDialog is showing, do not create it twice."
            }
            return
        }
        bluetoothDialog = BluetoothDialog(
            context,
            this,
            aboveStatusBar,
            handler,
            activityStarter,
            dialogLaunchAnimator,
            bluetoothController
        ).also {
            if (view != null) {
                dialogLaunchAnimator.showFromView(it, view, animateBackgroundBoundsChange = true)
            } else {
                it.show()
            }
        }
    }

    fun destroyDialog() {
        logD {
            "destroyDialog"
        }
        bluetoothDialog = null
    }

    private inline fun logD(msg: () -> String) {
        if (DEBUG) {
            Log.d(TAG, msg())
        }
    }
}
