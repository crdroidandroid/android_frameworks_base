/*
     Copyright (C) 2024 the risingOS Android Project
     Licensed under the Apache License, Version 2.0 (the "License");
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at
          http://www.apache.org/licenses/LICENSE-2.0
     Unless required by applicable law or agreed to in writing, software
     distributed under the License is distributed on an "AS IS" BASIS,
     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     See the License for the specific language governing permissions and
     limitations under the License.
*/
package com.android.systemui.lockscreen

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

import com.android.internal.jank.InteractionJankMonitor

import com.android.systemui.Dependency
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.media.dialog.MediaOutputDialogManager

class LockScreenWidgets(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

    private val mViewController: LockScreenWidgetsController? = LockScreenWidgetsController(this)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mViewController?.registerCallbacks()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mViewController?.unregisterCallbacks()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        mViewController?.initViews()
    }

    fun showMediaDialog(view: View, lastMediaPackage: String) {
        val packageName = lastMediaPackage.takeIf { it.isNotEmpty() } ?: return
        Dependency.get(MediaOutputDialogManager::class.java)
            .createAndShowWithController(
                packageName,
                true,
                Expandable.fromView(view).dialogController()
            )
    }

    private fun Expandable.dialogController(): DialogTransitionAnimator.Controller? {
        return dialogTransitionController(
            cuj =
                DialogCuj(
                    InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                    MediaOutputDialogManager.INTERACTION_JANK_TAG
                )
        )
    }

}
