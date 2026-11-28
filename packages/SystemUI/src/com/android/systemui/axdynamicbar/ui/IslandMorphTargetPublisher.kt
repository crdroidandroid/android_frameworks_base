/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import com.android.systemui.LauncherProxyService
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Tells launcher to close apps with waiting events into the island rather than their icon. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@SysUISingleton
class IslandMorphTargetPublisher
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    @Application private val context: Context,
    private val viewModel: AxDynamicBarChipViewModel,
    private val launcherProxyService: Lazy<LauncherProxyService>,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            viewModel.interactor.eventOwnerPackages
                .flatMapLatest { packages ->
                    if (packages.isEmpty()) flowOf(null)
                    else viewModel.morphTarget.map { rect -> rect?.let { packages to it } }
                }
                .debounce(PUBLISH_DELAY_MS)
                .distinctUntilChanged()
                .collect { target ->
                    launcherProxyService.get().onIslandTargetChanged(
                        target?.first?.toTypedArray() ?: emptyArray(),
                        target?.second,
                        displayRotation(),
                    )
                }
        }
    }

    private companion object {
        const val PUBLISH_DELAY_MS = 250L
    }

    private fun displayRotation(): Int =
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
}
