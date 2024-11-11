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

package com.android.systemui.keyguard.ui.composable.elements

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.ElementContentScope
import com.android.systemui.biometrics.AuthController
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.ui.binder.DeviceEntryIconViewBinder
import com.android.systemui.keyguard.ui.composable.layout.LockIconAlignmentLines
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryBackgroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryForegroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.TouchHandlingViewLogger
import com.android.systemui.log.dagger.LongPressTouchLog
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementProvider
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.tuner.TunerService
import com.google.android.msdl.domain.MSDLPlayer
import dagger.Lazy
import javax.inject.Inject
import kotlin.collections.List
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

class LockIconElementProvider
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val windowManager: WindowManager,
    private val authController: AuthController,
    private val featureFlags: FeatureFlagsClassic,
    private val deviceEntryIconViewModel: Lazy<DeviceEntryIconViewModel>,
    private val deviceEntryForegroundViewModel: Lazy<DeviceEntryForegroundViewModel>,
    private val deviceEntryBackgroundViewModel: Lazy<DeviceEntryBackgroundViewModel>,
    private val falsingManager: Lazy<FalsingManager>,
    private val vibratorHelper: Lazy<VibratorHelper>,
    private val msdlPlayer: Lazy<MSDLPlayer>,
    @LongPressTouchLog private val logBuffer: LogBuffer,
    private val tunerService: TunerService,
) : LockscreenElementProvider {
    override val elements: List<LockscreenElement> by lazy { listOf(LockIconElement()) }

    private inner class LockIconElement : LockscreenElement {
        override val key = LockscreenElementKeys.LockIcon
        override val context = this@LockIconElementProvider.context

        @Composable
        override fun LockscreenScope<ElementContentScope>.LockscreenElement() {
            LockIcon()
        }
    }

    @Composable
    fun LockIcon(overrideColor: Color? = null, modifier: Modifier = Modifier) {
        val context = LocalContext.current

        AndroidView(
            factory = { context ->
                DeviceEntryIconView(
                        context,
                        null,
                        0,
                        TouchHandlingViewLogger(logBuffer, tag = TAG),
                        tunerService
                    )
                    .apply {
                        id = R.id.device_entry_icon_view
                        DeviceEntryIconViewBinder.bind(
                            applicationScope,
                            mainDispatcher,
                            this,
                            deviceEntryIconViewModel.get(),
                            deviceEntryForegroundViewModel.get(),
                            deviceEntryBackgroundViewModel.get(),
                            falsingManager.get(),
                            vibratorHelper.get(),
                            msdlPlayer.get(),
                            overrideColor,
                        )
                    }
            },
            modifier =
                modifier.layout { measurable, _ ->
                    val lockIconBounds = lockIconBounds(context)
                    val placeable =
                        measurable.measure(
                            Constraints.fixed(
                                width = lockIconBounds.width,
                                height = lockIconBounds.height,
                            )
                        )
                    layout(
                        width = placeable.width,
                        height = placeable.height,
                        alignmentLines =
                            mapOf(
                                LockIconAlignmentLines.Left to lockIconBounds.left,
                                LockIconAlignmentLines.Top to lockIconBounds.top,
                                LockIconAlignmentLines.Right to lockIconBounds.right,
                                LockIconAlignmentLines.Bottom to lockIconBounds.bottom,
                            ),
                    ) {
                        placeable.place(0, 0)
                    }
                },
        )
    }

    /**
     * Returns the bounds of the lock icon, in window view coordinates.
     *
     * On devices that support UDFPS (under-display fingerprint sensor), the bounds of the icon are
     * the same as the bounds of the sensor.
     */
    private fun lockIconBounds(context: Context): IntRect {
        val windowViewBounds = windowManager.currentWindowMetrics.bounds
        var widthPx = windowViewBounds.right.toFloat()
        if (featureFlags.isEnabled(Flags.LOCKSCREEN_ENABLE_LANDSCAPE)) {
            val insets = windowManager.currentWindowMetrics.windowInsets
            // Assumed to be initially neglected as there are no left or right insets in portrait.
            // However, on landscape, these insets need to included when calculating the midpoint.
            @Suppress("DEPRECATION")
            widthPx -= (insets.systemWindowInsetLeft + insets.systemWindowInsetRight).toFloat()
        }
        val defaultDensity =
            DisplayMetrics.DENSITY_DEVICE_STABLE.toFloat() /
                DisplayMetrics.DENSITY_DEFAULT.toFloat()
        val lockIconRadiusPx = (defaultDensity * 36).toInt()

        val udfpsLocation = authController.udfpsLocation
        val (center, radius) =
            if (authController.isUdfpsSupported && udfpsLocation != null) {
                Pair(
                    IntOffset(x = udfpsLocation.x, y = udfpsLocation.y),
                    authController.udfpsRadius.toInt(),
                )
            } else {
                val scaleFactor = authController.scaleFactor
                val bottomPaddingPx =
                    context.resources.getDimensionPixelSize(clocksR.dimen.lock_icon_margin_bottom)
                val heightPx = windowViewBounds.bottom.toFloat()

                Pair(
                    IntOffset(
                        x = (widthPx / 2).toInt(),
                        y =
                            (heightPx - ((bottomPaddingPx + lockIconRadiusPx) * scaleFactor))
                                .toInt(),
                    ),
                    (lockIconRadiusPx * scaleFactor).toInt(),
                )
            }

        return IntRect(center, radius)
    }

    companion object {
        private const val TAG = "LockSection"
    }
}
