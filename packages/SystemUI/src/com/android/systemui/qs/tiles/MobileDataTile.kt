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

package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.internal.logging.MetricsLogger
import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.asQSTileIcon
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfigProvider
import com.android.systemui.qs.tiles.base.shared.model.QSTileState
import com.android.systemui.qs.tiles.impl.cell.domain.interactor.MobileDataTileDataInteractor
import com.android.systemui.qs.tiles.impl.cell.domain.interactor.MobileDataTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.cell.domain.model.MobileDataTileModel
import com.android.systemui.qs.tiles.impl.cell.ui.mapper.MobileDataTileMapper
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Quick settings tile: Mobile Data */
class MobileDataTile
@Inject
constructor(
    private val host: QSHost,
    private val uiEventLogger: QsEventLogger,
    @Background private val backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    private val falsingManager: FalsingManager,
    private val metricsLogger: MetricsLogger,
    private val statusBarStateController: StatusBarStateController,
    private val activityStarter: ActivityStarter,
    private val qsLogger: QSLogger,
    private val qsTileConfigProvider: QSTileConfigProvider,
    private val dataInteractor: MobileDataTileDataInteractor,
    private val tileMapper: MobileDataTileMapper,
    private val userActionInteractor: MobileDataTileUserActionInteractor,
) :
    QSTileImpl<QSTile.State?>(
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

    private lateinit var tileState: QSTileState
    private val config = qsTileConfigProvider.getConfig(TILE_SPEC)

    init {
        lifecycle.coroutineScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dataInteractor.tileData().collect { refreshState(it) }
            }
        }
    }

    override fun getTileLabel(): CharSequence = mContext.getString(config.uiConfig.labelRes)

    override fun newTileState(): QSTile.State? {
        return QSTile.State().apply { state = Tile.STATE_INACTIVE }
    }

    override fun handleClick(expandable: Expandable?) {
        lifecycle.coroutineScope.launch { userActionInteractor.handleClick(expandable) }
    }

    override fun handleSecondaryClick(expandable: Expandable?) {
        userActionInteractor.handleSecondaryClick(expandable)
    }

    override fun getLongClickIntent(): Intent = userActionInteractor.longClickIntent

    override fun handleUpdateState(state: QSTile.State?, arg: Any?) {
        val model = arg as? MobileDataTileModel ?: return
        tileState = tileMapper.map(config, model)

        state?.apply {
            this.state = tileState.activationState.legacyState
            icon =
                tileState.icon?.asQSTileIcon()
                    ?: run {
                        // Fallback: Use SignalDrawable
                        val packedSignalState: Int = SignalDrawable.getState(0, 4, false)
                        val signalDrawableInstance = SignalDrawable(mContext, mainHandler)
                        signalDrawableInstance.level = packedSignalState
                        DrawableIcon(signalDrawableInstance)
                    }
            label = tileState.label
            secondaryLabel = tileState.secondaryLabel
            contentDescription = tileState.contentDescription
            expandedAccessibilityClassName = tileState.expandedAccessibilityClassName
            handlesSecondaryClick =
                tileState.supportedActions.contains(QSTileState.UserAction.TOGGLE_CLICK)
            handlesLongClick =
                tileState.supportedActions.contains(QSTileState.UserAction.LONG_CLICK)
        }
    }

    override fun isAvailable(): Boolean {
        return dataInteractor.isAvailable()
    }

    companion object {
        const val TILE_SPEC = "cell"
    }
}
