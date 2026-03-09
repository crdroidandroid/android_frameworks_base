/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.smartpixel.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.service.quicksettings.Tile
import com.google.android.material.materialswitch.MaterialSwitch
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.smartpixel.domain.SmartPixelSettings
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.settings.SecureSettings
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Provider

class SmartPixelTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val secureSettings: SecureSettings,
    private val keyguardStateController: KeyguardStateController,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val dialogDelegateProvider: Provider<SmartPixelDialogDelegate>,
    @Main private val mainExecutor: Executor,
) : QSTileImpl<BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler, falsingManager,
    metricsLogger, statusBarStateController, activityStarter, qsLogger,
) {
    companion object {
        const val TILE_SPEC = "smart_pixels"
        private const val INTERACTION_JANK_TAG = "smart_pixels"
    }

    override fun newTileState(): BooleanState {
        val state = BooleanState()
        state.handlesLongClick = true
        return state
    }

    override fun handleClick(expandable: Expandable?) {
        val newState = !mState.value
        secureSettings.putIntForUser(
            SmartPixelSettings.KEY_ENABLED,
            if (newState) 1 else 0,
            UserHandle.USER_CURRENT,
        )
        refreshState(newState)
    }

    override fun handleLongClick(expandable: Expandable?) {
        val animateFromExpandable = expandable != null && !keyguardStateController.isShowing

        val runnable = Runnable {
            val dialog: SystemUIDialog = dialogDelegateProvider.get().createDialog()
            if (animateFromExpandable) {
                val controller = expandable?.dialogTransitionController(
                    DialogCuj(
                        InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                        INTERACTION_JANK_TAG,
                    )
                )
                controller?.let { dialogTransitionAnimator.show(dialog, it) } ?: dialog.show()
            } else {
                dialog.show()
            }
        }

        mainExecutor.execute {
            mActivityStarter.executeRunnableDismissingKeyguard(
                runnable,
                null,
                true,
                true,
                false,
            )
        }
    }

    override fun getLongClickIntent(): Intent? = null

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_smart_pixels_label)

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        val enabled = if (arg is Boolean) {
            arg
        } else {
            secureSettings.getIntForUser(
                SmartPixelSettings.KEY_ENABLED, 0, UserHandle.USER_CURRENT,
            ) == 1
        }
        state.value = enabled
        state.label = mContext.getString(R.string.quick_settings_smart_pixels_label)
        state.secondaryLabel = mContext.getString(
            if (enabled) R.string.quick_settings_smart_pixels_on
            else R.string.quick_settings_smart_pixels_off,
        )
        state.contentDescription = state.label
        state.expandedAccessibilityClassName = MaterialSwitch::class.java.name
        state.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        state.icon = ResourceIcon.get(
            if (enabled) R.drawable.qs_smart_pixels_icon_on
            else R.drawable.qs_smart_pixels_icon_off
        )
    }

    override fun isAvailable(): Boolean = true

    override fun getMetricsCategory(): Int = MetricsEvent.QS_PANEL
}
