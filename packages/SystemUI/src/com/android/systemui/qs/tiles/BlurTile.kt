/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.view.CrossWindowBlurListeners
import com.android.internal.logging.MetricsLogger
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.qs.QSTile.Icon
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SettingObserver
import javax.inject.Inject

class BlurTile
@Inject
constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    globalSettings: GlobalSettings,
) :
    QSTileImpl<BooleanState>(
        host,
        uiEventLogger,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger,
    ) {

    companion object {
        const val TILE_SPEC = "blur"
    }

    private var tileIcon: Icon? = null

    private val setting =
        object :
            SettingObserver(
                globalSettings,
                mHandler,
                Settings.Global.DISABLE_WINDOW_BLURS,
                0,
            ) {
            override fun handleValueChanged(value: Int, observedChange: Boolean) {
                handleRefreshState(value)
            }
        }

    override fun handleDestroy() {
        super.handleDestroy()
        setting.setListening(false)
    }

    override fun isAvailable(): Boolean = CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED

    override fun newTileState(): BooleanState =
        BooleanState().apply { handlesLongClick = false }

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        setting.setListening(listening)
    }

    override fun handleUserSwitch(newUserId: Int) {
        handleRefreshState(setting.value)
    }

    override fun handleClick(expandable: Expandable?) {
        val isEnabled = setting.value == 0
        Settings.Global.putInt(
            mContext.contentResolver,
            Settings.Global.DISABLE_WINDOW_BLURS,
            if (isEnabled) 1 else 0,
        )
        refreshState()
    }

    override fun getLongClickIntent(): Intent? = null

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_blur_label)

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        val value = arg as? Int ?: setting.value
        val enable = value == 0
        if (tileIcon == null) {
            tileIcon = maybeLoadResourceIcon(R.drawable.ic_qs_blur)
        }
        state.icon = tileIcon
        state.value = enable
        state.label = mContext.getString(R.string.quick_settings_blur_label)
        state.state = if (enable) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        state.contentDescription = mContext.getString(R.string.quick_settings_blur_label)
    }

    override fun getMetricsCategory(): Int = MetricsLogger.VIEW_UNKNOWN
}
