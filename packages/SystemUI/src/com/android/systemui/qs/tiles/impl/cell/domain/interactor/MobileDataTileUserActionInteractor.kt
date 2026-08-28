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
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.TransitionAnimator
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.domain.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.domain.model.QSTileInput
import com.android.systemui.qs.tiles.base.shared.model.QSTileUserAction
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.qs.tiles.impl.cell.domain.model.MobileDataTileModel
import com.android.systemui.res.R
import com.android.systemui.settings.UserFileManager
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.connectivity.AccessPointController
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class MobileDataTileUserActionInteractor
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val mobileConnectionsRepository: MobileConnectionsRepository,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
    private val systemUIDialogFactory: SystemUIDialog.Factory,
    @Main val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundContext: CoroutineContext,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val internetDialogManager: InternetDialogManager,
    private val accessPointController: AccessPointController,
    userFileManager: UserFileManager,
) : QSTileUserActionInteractor<MobileDataTileModel> {
    val longClickIntent = Intent(Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS)

    private val sharedPreferences =
        userFileManager.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE, context.userId)

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
        }
    }

    suspend fun handleClick(expandable: Expandable?) {
        withContext(mainDispatcher) {
            internetDialogManager.create(
                aboveStatusBar = true,
                accessPointController.canConfigMobileData(),
                accessPointController.canConfigWifi(),
                expandable,
            )
        }
    }

    suspend fun handleSecondaryClick(expandable: Expandable?) {
        val activeRepo = getDataRepo() ?: return
        // If mobile data is disabled, show a confirmation dialog to turn it on.
        if (!activeRepo.dataEnabled.value) {
            if (withContext(backgroundContext) { isDontAskAgainEnabled() }) {
                // The user asked not to be prompted again; just turn data on.
                activeRepo.setDataEnabled(true)
            } else {
                withContext(mainDispatcher) { showEnableConfirmationDialog(expandable) }
            }
        } else {
            // Otherwise, just turn it off without a dialog.
            activeRepo.setDataEnabled(false)
        }
    }

    private fun isDontAskAgainEnabled(): Boolean {
        return sharedPreferences.getBoolean(PREF_DONT_ASK_AGAIN, false)
    }

    private fun showEnableConfirmationDialog(expandable: Expandable?) {
        val dialog: SystemUIDialog = systemUIDialogFactory.create()
        dialog.setTitle(context.getString(R.string.mobile_data_enable_title))
        dialog.setMessage(context.getString(R.string.mobile_data_enable_message))
        dialog.setIcon(R.drawable.ic_swap_vert)

        val view =
            LayoutInflater.from(context)
                .inflate(R.layout.mobile_data_tile_confirm_dialog, null as ViewGroup?)
        val checkBox = view.requireViewById<CheckBox>(R.id.mobile_data_dialog_checkbox)
        dialog.setView(view)

        dialog.setPositiveButton(R.string.mobile_data_enable_turn_on) { _, _ ->
            if (checkBox.isChecked) {
                sharedPreferences.edit().putBoolean(PREF_DONT_ASK_AGAIN, true).apply()
            }
            getDataRepo()?.setDataEnabled(true)
        }

        dialog.setNegativeButton(android.R.string.cancel, null, true)

        val controller = expandable?.dialogTransitionController()
        if (controller != null) {
            // If we have a controller, show the dialog using the animator.
            if (TransitionAnimator.dynamicTargetResolutionEnabled()) {
                dialogTransitionAnimator.show(
                    dialog,
                    expandable::dialogTransitionController,
                    controller.cuj,
                )
            } else {
                dialogTransitionAnimator.show(dialog, controller)
            }
        } else {
            // Otherwise, show the dialog without the custom animation.
            dialog.show()
        }
    }

    private fun getDataRepo(): MobileConnectionRepository? {
        return mobileConnectionsRepository.defaultDataSubId.value?.let {
            mobileConnectionsRepository.getRepoForSubId(it)
        }
    }

    companion object {
        private const val PREFS_FILE = "mobile_data_tile"
        private const val PREF_DONT_ASK_AGAIN = "mobile_data_dont_ask_again"
    }
}
