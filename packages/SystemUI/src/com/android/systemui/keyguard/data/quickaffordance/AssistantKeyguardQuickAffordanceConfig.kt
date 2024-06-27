/*
 *  Copyright (C) 2023 The risingOS Android Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.app.StatusBarManager
import android.content.Context
import android.content.Intent
import com.android.systemui.R
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

import com.android.internal.util.crdroid.Utils

@SysUISingleton
class AssistantKeyguardQuickAffordanceConfig
@Inject
constructor(
    @Application private val context: Context
) : KeyguardQuickAffordanceConfig {

    override val key: String
        get() = BuiltInKeyguardQuickAffordanceKeys.ASSISTANT

    override fun pickerName(): String = context.getString(R.string.accessibility_assistant_button)

    override val pickerIconResourceId: Int
        get() = R.drawable.ic_assistant

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState>
        get() =
            flowOf(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon =
                        Icon.Resource(
                            R.drawable.ic_assistant,
                            ContentDescription.Resource(R.string.accessibility_assistant_button)
                        )
                )
            )

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState {
        return if (Utils.isPackageInstalled(context, "com.openai.chatgpt")) {
            super.getPickerScreenState()
        } else {
            KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice
        }
    }

    override fun onTriggered(
        expandable: Expandable?,
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        val intent = Intent().setClassName("com.openai.chatgpt", "com.openai.voice.assistant.AssistantActivity")
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.StartActivity(
            intent = intent,
            canShowWhileLocked = true,
        )
    }
}
