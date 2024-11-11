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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ElementContentScope
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.ui.binder.DeviceEntryIconViewBinder
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryBackgroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryForegroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.TouchHandlingViewLogger
import com.android.systemui.log.dagger.LongPressTouchLog
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.keyguard.ui.composable.elements.BaseLockscreenElement.ElementSource
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementProvider
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shared.customization.data.SensorLocation
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.tuner.TunerService
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import com.google.android.msdl.domain.MSDLPlayer
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle

@SysUISingleton
class LockIconElementProvider
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val windowManager: WindowManager,
    private val windowRootViewBlurInteractor: WindowRootViewBlurInteractor,
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
        override val source = ElementSource.STANDARD

        @Composable
        override fun LockscreenScope<ElementContentScope>.LockscreenElement() {
            LockIcon()
        }
    }

    @Composable
    fun LockIcon(modifier: Modifier = Modifier, overrideColor: Color? = null) {
        val lockIconBounds = rememberLockIconBounds()
        val (disposable, setDisposable) = remember { mutableStateOf<DisposableHandle?>(null) }

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
                        setDisposable(
                            DeviceEntryIconViewBinder.bind(
                                applicationScope,
                                mainDispatcher,
                                windowRootViewBlurInteractor,this,
                                deviceEntryIconViewModel.get(),
                                deviceEntryForegroundViewModel.get(),
                                deviceEntryBackgroundViewModel.get(),
                                falsingManager.get(),
                                vibratorHelper.get(),
                                msdlPlayer.get(),
                                overrideColor,
                            )
                        )
                    }
            },
            modifier =
                modifier.layout { measurable, _ ->
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
            onRelease = { disposable?.dispose() },
        )
    }

    /**
     * Returns the remembered bounds of the lock icon, in window view coordinates.
     *
     * On devices that support UDFPS (under-display fingerprint sensor), the bounds of the icon are
     * the same as the bounds of the sensor.
     *
     * The bounds will have new values as the UDFPS location changes (something that definitely
     * happens shortly after device boot) or the window view bounds change.
     */
    @Composable
    private fun rememberLockIconBounds(): IntRect {
        val context = LocalContext.current
        val isUdfpsSupported: Boolean by
            deviceEntryIconViewModel.get().isUdfpsSupported.collectAsStateWithLifecycle()
        val udfpsLocation: SensorLocation? by
            deviceEntryIconViewModel.get().udfpsLocation.collectAsStateWithLifecycle()

        val bottomPaddingPx =
            with(LocalDensity.current) {
                dimensionResource(clocksR.dimen.lock_icon_margin_bottom).roundToPx()
            }
        val windowViewBounds = windowManager.currentWindowMetrics.bounds

        val bounds: IntRect by
            remember(context, isUdfpsSupported, udfpsLocation, windowViewBounds) {
                derivedStateOf {
                    var widthPx = windowViewBounds.right.toFloat()
                    if (featureFlags.isEnabled(Flags.LOCKSCREEN_ENABLE_LANDSCAPE)) {
                        val insets = windowManager.currentWindowMetrics.windowInsets
                        // Assumed to be initially neglected as there are no left or right insets in
                        // portrait. However, on landscape, these insets need to included when
                        // calculating the midpoint.
                        @Suppress("DEPRECATION")
                        widthPx -=
                            (insets.systemWindowInsetLeft + insets.systemWindowInsetRight).toFloat()
                    }

                    val finalUdfpsLocation = udfpsLocation
                    if (isUdfpsSupported && finalUdfpsLocation != null) {
                        IntRect(
                            center =
                                IntOffset(
                                    x = finalUdfpsLocation.centerX.fastRoundToInt(),
                                    y = finalUdfpsLocation.centerY.fastRoundToInt(),
                                ),
                            radius = finalUdfpsLocation.radius.toInt(),
                        )
                    } else {
                        val defaultDensity =
                            DisplayMetrics.DENSITY_DEVICE_STABLE.toFloat() /
                                DisplayMetrics.DENSITY_DEFAULT.toFloat()
                        val lockIconRadiusPx = (defaultDensity * 36).toInt()

                        val scaleFactor = deviceEntryIconViewModel.get().scaleFactor
                        val heightPx = windowViewBounds.bottom.toFloat()

                        IntRect(
                            center =
                                IntOffset(
                                    x = (widthPx / 2).toInt(),
                                    y =
                                        (heightPx -
                                                ((bottomPaddingPx + lockIconRadiusPx) *
                                                    scaleFactor))
                                            .toInt(),
                                ),
                            radius = (lockIconRadiusPx * scaleFactor).toInt(),
                        )
                    }
                }
            }

        return bounds
    }

    companion object {
        private const val TAG = "LockSection"
    }
}
