/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.flashlight.domain.interactor

import android.app.ActivityManager
import android.hardware.camera2.CameraCharacteristics
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.camera.injectCameraCharacteristics
import com.android.systemui.flashlight.data.repository.startFlashlightRepository
import com.android.systemui.flashlight.domain.interactor.flashlightInteractor
import com.android.systemui.flashlight.shared.model.FlashlightModel
import com.android.systemui.flashlight.ui.dialog.FlashlightDialogDelegate
import com.android.systemui.flashlight.ui.dialog.mockFlashlightDialogDelegate
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.qs.tiles.base.domain.model.QSTileInputTestKtx.click
import com.android.systemui.qs.tiles.base.domain.model.QSTileInputTestKtx.toggleClick
import com.android.systemui.statusbar.policy.flashlightController
import com.android.systemui.statusbar.policy.mockFlashlightController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class FlashlightTileUserActionInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private lateinit var underTest: FlashlightTileUserActionInteractor

    @Before
    fun setup() {
        kosmos.flashlightController = kosmos.mockFlashlightController // to preserve old tests

        underTest = kosmos.flashlightTileUserActionInteractor
    }

    @DisableFlags(com.android.systemui.Flags.FLAG_FLASHLIGHT_STRENGTH)
    @Test
    fun flagOff_handleClickToEnable() =
        kosmos.runTest {
            assumeFalse(ActivityManager.isUserAMonkey())
            val stateBeforeClick = false

            underTest.handleInput(click(FlashlightModel.Available.Binary(stateBeforeClick)))

            verify(flashlightController).setFlashlight(!stateBeforeClick)
        }

    @EnableFlags(com.android.systemui.Flags.FLAG_FLASHLIGHT_STRENGTH)
    @Test
    fun flagOn_handleClickToEnable_whenBinary_doesNothing() =
        kosmos.runTest {
            assumeFalse(ActivityManager.isUserAMonkey())
            val state by collectLastValue(flashlightInteractor.state)
            injectCameraCharacteristics(
                true,
                CameraCharacteristics.LENS_FACING_BACK,
                DEFAULT_LEVEL,
                null,
            )
            startFlashlightRepository(true)

            assertThat(state).isEqualTo(FlashlightModel.Available.Binary(false))

            underTest.handleInput(click(FlashlightModel.Available.Binary(false)))

            assertThat(state).isEqualTo(FlashlightModel.Available.Binary(false))
            verify(mockFlashlightDialogDelegate, never()).showDialog()
        }

    @EnableFlags(com.android.systemui.Flags.FLAG_FLASHLIGHT_STRENGTH)
    @Test
    fun flagOn_handleClickToEnable_whenBinaryCompatLegacy_opensLegacyDialog() =
        kosmos.runTest {
            assumeFalse(ActivityManager.isUserAMonkey())
            val state by collectLastValue(flashlightInteractor.state)
            injectCameraCharacteristics(
                true,
                CameraCharacteristics.LENS_FACING_BACK,
                DEFAULT_LEVEL,
                null,
            )
            startFlashlightRepository(true)
            whenever(flashlightController.isStrengthControlSupported()).thenReturn(true)

            assertThat(state).isEqualTo(FlashlightModel.Available.Binary(false))

            underTest.handleInput(click(FlashlightModel.Available.Binary(false)))

            verify(flashlightController).setFlashlight(true)
            verify(mockFlashlightDialogDelegate)
                .showDialog(null, FlashlightDialogDelegate.SliderBackend.LEGACY)
        }

    @EnableFlags(com.android.systemui.Flags.FLAG_FLASHLIGHT_STRENGTH)
    @Test
    fun flagOn_handleClickToEnable_whenLevel_enablesAndOpensDialog() =
        kosmos.runTest {
            assumeFalse(ActivityManager.isUserAMonkey())
            val state by collectLastValue(flashlightInteractor.state)
            injectCameraCharacteristics(
                true,
                CameraCharacteristics.LENS_FACING_BACK,
                DEFAULT_LEVEL,
                MAX_LEVEL,
            )
            startFlashlightRepository(true)
            val expectedInitialState =
                FlashlightModel.Available.Level(false, DEFAULT_LEVEL, MAX_LEVEL)
            assertThat(state).isEqualTo(expectedInitialState)

            underTest.handleInput(click(expectedInitialState))

            assertThat(state)
                .isEqualTo(FlashlightModel.Available.Level(true, DEFAULT_LEVEL, MAX_LEVEL))
            verify(mockFlashlightDialogDelegate)
                .showDialog(null, FlashlightDialogDelegate.SliderBackend.REPOSITORY)
        }

    @DisableFlags(com.android.systemui.Flags.FLAG_FLASHLIGHT_STRENGTH)
    @Test
    fun flagOff_handleClickWhenEnabled_disables() =
        kosmos.runTest {
            assumeFalse(ActivityManager.isUserAMonkey())
            val stateBeforeClick = true

            underTest.handleInput(click(FlashlightModel.Available.Binary(stateBeforeClick)))

            verify(flashlightController).setFlashlight(!stateBeforeClick)
        }

    @EnableFlags(com.android.systemui.Flags.FLAG_FLASHLIGHT_STRENGTH)
    @Test
    fun flagOn_handleClickWhenEnabled_whenBinary_doesNothing() =
        kosmos.runTest {
            assumeFalse(ActivityManager.isUserAMonkey())
            val state by collectLastValue(flashlightInteractor.state)
            injectCameraCharacteristics(
                true,
                CameraCharacteristics.LENS_FACING_BACK,
                DEFAULT_LEVEL,
                null,
            )
            startFlashlightRepository(true)

            assertThat(state).isEqualTo(FlashlightModel.Available.Binary(false))
            flashlightInteractor.setEnabled(true)
            val enabledBinaryFlashlight = FlashlightModel.Available.Binary(true)
            assertThat(state).isEqualTo(enabledBinaryFlashlight)

            // at this point the stage is set for the actual test
            underTest.handleInput(click(enabledBinaryFlashlight))

            assertThat(state).isEqualTo(enabledBinaryFlashlight)
            verify(mockFlashlightDialogDelegate, never()).showDialog()
        }

    @EnableFlags(com.android.systemui.Flags.FLAG_FLASHLIGHT_STRENGTH)
    @Test
    fun flagOn_handleClickWhenEnabled_whenLevel_opensDialog_staysEnabled() =
        kosmos.runTest {
            assumeFalse(ActivityManager.isUserAMonkey())
            val state by collectLastValue(flashlightInteractor.state)
            injectCameraCharacteristics(
                true,
                CameraCharacteristics.LENS_FACING_BACK,
                DEFAULT_LEVEL,
                MAX_LEVEL,
            )
            startFlashlightRepository(true)
            assertThat(state)
                .isEqualTo(FlashlightModel.Available.Level(false, DEFAULT_LEVEL, MAX_LEVEL))

            flashlightInteractor.setEnabled(true)
            val enabledLevelFlashlight =
                FlashlightModel.Available.Level(true, DEFAULT_LEVEL, MAX_LEVEL)
            assertThat(state).isEqualTo(enabledLevelFlashlight)

            // at this point the stage is set for the actual test
            underTest.handleInput(click(enabledLevelFlashlight))

            assertThat(state).isEqualTo(enabledLevelFlashlight)
            verify(mockFlashlightDialogDelegate)
                .showDialog(null, FlashlightDialogDelegate.SliderBackend.REPOSITORY)
        }

    @DisableFlags(com.android.systemui.Flags.FLAG_FLASHLIGHT_STRENGTH)
    @Test
    fun flagOff_handleClickWhenUnavailable() =
        kosmos.runTest {
            assumeFalse(ActivityManager.isUserAMonkey())

            underTest.handleInput(click(FlashlightModel.Unavailable.Temporarily.CameraInUse))
            verify(flashlightController, never()).setFlashlight(anyBoolean())

            underTest.handleInput(click(FlashlightModel.Unavailable.Temporarily.NotFound))
            verify(flashlightController, never()).setFlashlight(anyBoolean())
        }

    @EnableFlags(com.android.systemui.Flags.FLAG_FLASHLIGHT_STRENGTH)
    @Test
    fun flagOn_handleClickWhenUnavailable_doesNotShowDialog() =
        kosmos.runTest {
            assumeFalse(ActivityManager.isUserAMonkey())
            val state by collectLastValue(flashlightInteractor.state)
            injectCameraCharacteristics(
                true,
                CameraCharacteristics.LENS_FACING_BACK,
                DEFAULT_LEVEL,
                MAX_LEVEL,
            )
            startFlashlightRepository(true)
            assertThat(state)
                .isEqualTo(FlashlightModel.Available.Level(false, DEFAULT_LEVEL, MAX_LEVEL))

            // stage is set, let's try all unavailable clicks
            underTest.handleInput(click(FlashlightModel.Unavailable.Temporarily.NotFound))
            underTest.handleInput(click(FlashlightModel.Unavailable.Temporarily.CameraInUse))
            underTest.handleInput(click(FlashlightModel.Unavailable.Temporarily.Loading))
            underTest.handleInput(click(FlashlightModel.Unavailable.Permanently.NotSupported))

            // none of them should trigger the dialog
            verify(mockFlashlightDialogDelegate, never()).showDialog()
        }

    @EnableFlags(com.android.systemui.Flags.FLAG_FLASHLIGHT_STRENGTH)
    @Test
    fun flagOn_handleToggleClickWhenAvailableBinary_togglesState() =
        kosmos.runTest {
            assumeFalse(ActivityManager.isUserAMonkey())
            val enabledBinaryFlashlight = FlashlightModel.Available.Binary(true)
            val disabledBinaryFlashlight = FlashlightModel.Available.Binary(false)
            val state by collectLastValue(flashlightInteractor.state)
            injectCameraCharacteristics(
                true,
                CameraCharacteristics.LENS_FACING_BACK,
                DEFAULT_LEVEL,
                null, // this puts us on Binary mode
            )
            startFlashlightRepository(true)
            assertThat(state).isEqualTo(disabledBinaryFlashlight)

            // stage is set, let's toggle twice and verify state toggles
            underTest.handleInput(toggleClick(state!!))
            assertThat(state).isEqualTo(enabledBinaryFlashlight)

            underTest.handleInput(toggleClick(state!!))
            assertThat(state).isEqualTo(disabledBinaryFlashlight)

            // none of them should trigger the dialog as we are only toggling
            verify(mockFlashlightDialogDelegate, never()).showDialog()
        }

    @EnableFlags(com.android.systemui.Flags.FLAG_FLASHLIGHT_STRENGTH)
    @Test
    fun flagOn_handleToggleClickWhenAvailableLevel_togglesState() =
        kosmos.runTest {
            assumeFalse(ActivityManager.isUserAMonkey())
            val enabledLevelFlashlight =
                FlashlightModel.Available.Level(true, DEFAULT_LEVEL, MAX_LEVEL)
            val disabledLevelFlashlight =
                FlashlightModel.Available.Level(false, DEFAULT_LEVEL, MAX_LEVEL)
            val state by collectLastValue(flashlightInteractor.state)
            injectCameraCharacteristics(
                true,
                CameraCharacteristics.LENS_FACING_BACK,
                DEFAULT_LEVEL,
                MAX_LEVEL,
            )
            startFlashlightRepository(true)
            assertThat(state).isEqualTo(disabledLevelFlashlight)

            // stage is set, let's toggle twice and verify state toggles
            underTest.handleInput(toggleClick(state!!))
            assertThat(state).isEqualTo(enabledLevelFlashlight)

            underTest.handleInput(toggleClick(state!!))
            assertThat(state).isEqualTo(disabledLevelFlashlight)

            // none of them should trigger the dialog as we are only toggling
            verify(mockFlashlightDialogDelegate, never()).showDialog()
        }

    private companion object {
        const val DEFAULT_LEVEL = 21
        const val MAX_LEVEL = 45
    }
}
