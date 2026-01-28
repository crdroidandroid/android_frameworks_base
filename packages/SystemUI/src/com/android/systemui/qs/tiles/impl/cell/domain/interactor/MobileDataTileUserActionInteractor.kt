/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.cell.domain.interactor

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.domain.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.domain.model.QSTileInput
import com.android.systemui.qs.tiles.base.shared.model.QSTileUserAction
import com.android.systemui.qs.tiles.impl.cell.domain.model.MobileDataTileModel
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class MobileDataTileUserActionInteractor
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val mobileConnectionsRepository: MobileConnectionsRepository,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
    private val systemUIDialogFactory: SystemUIDialog.Factory,
    @Main val mainDispatcher: CoroutineDispatcher,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
) : QSTileUserActionInteractor<MobileDataTileModel> {
    val longClickIntent = Intent(Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS)

    override suspend fun handleInput(input: QSTileInput<MobileDataTileModel>) {
        when (input.action) {
            is QSTileUserAction.Click -> {
                handleClick(input.action.expandable)
            }
            is QSTileUserAction.LongClick -> {
                qsTileIntentUserActionHandler.handle(input.action.expandable, longClickIntent)
            }
            is QSTileUserAction.ToggleClick -> {
                handleSecondaryClick(input.action.expandable)
            }
            else -> {}
        }
    }

    suspend fun handleClick(expandable: Expandable?) {
        val activeRepo = mobileConnectionsRepository.activeMobileDataRepository.value ?: return
        // If mobile data is disabled, show a confirmation dialog to turn it on.
        if (!activeRepo.dataEnabled.value) {
            withContext(mainDispatcher) { showEnableConfirmationDialog(expandable) }
        } else {
            // Otherwise, just turn it off without a dialog.
            activeRepo.setDataEnabled(false)
        }
    }

    fun handleSecondaryClick(expandable: Expandable?) {
        val activeRepo = mobileConnectionsRepository.activeMobileDataRepository.value ?: return
        // If mobile data is disabled, turn it on.
        if (!activeRepo.dataEnabled.value) {
            activeRepo.setDataEnabled(true)
        } else {
            // Otherwise, just turn it off.
            activeRepo.setDataEnabled(false)
        }
    }

    private fun showEnableConfirmationDialog(expandable: Expandable?) {
        val dialog: SystemUIDialog = systemUIDialogFactory.create()
        dialog.setTitle(context.getString(R.string.mobile_data_enable_title))
        dialog.setMessage(context.getString(R.string.mobile_data_enable_message))

        dialog.setPositiveButton(R.string.mobile_data_enable_turn_on) { _, _ ->
            mobileConnectionsRepository.activeMobileDataRepository.value?.setDataEnabled(true)
        }

        dialog.setNegativeButton(android.R.string.cancel) { _, _ -> /* Do nothing */ }

        val controller = expandable?.dialogTransitionController()
        if (controller != null) {
            // If we have a controller, show the dialog using the animator.
            dialogTransitionAnimator.show(dialog, controller)
        } else {
            // Otherwise, show the dialog without the custom animation.
            dialog.show()
        }
    }
}
