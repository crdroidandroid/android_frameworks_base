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

package com.android.systemui.flashlight.data.repository

import android.content.packageManager
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager.TorchCallback
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.camera.cameraManager
import com.android.systemui.camera.injectCameraCharacteristics
import com.android.systemui.flashlight.shared.model.FlashlightModel
import com.android.systemui.kosmos.advanceTimeBy
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@MediumTest
@EnableFlags(com.android.systemui.Flags.FLAG_FLASHLIGHT_STRENGTH)
@RunWith(AndroidJUnit4::class)
class FlashlightRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val underTest = kosmos.flashlightRepository

    @Test
    fun deviceDoesNotSupportCameraFlashlight_statePermanentlyUnavailable() =
        kosmos.runTest {
            val state by collectLastValue(underTest.state)

            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)
            startFlashlightRepository(false)

            assertThat(state).isEqualTo(FlashlightModel.Unavailable.Permanently.NotSupported)
            verify(cameraManager, never()).registerTorchCallback(any<Executor>(), any())
        }

    @Test
    fun flashSupportedButNotAvailable_stateTemporarilyFlashlightNotFound() =
        kosmos.runTest {
            val state by collectLastValue(underTest.state)
            injectCameraCharacteristics(false)
            startFlashlightRepository(true)

            assertThat(state).isEqualTo(FlashlightModel.Unavailable.Temporarily.NotFound)
            verify(cameraManager, times(1)).registerTorchCallback(any<Executor>(), any())
        }

    @Test
    fun onlyFrontFlashlight_stateTemporarilyFlashlightNotFound() =
        kosmos.runTest {
            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_FRONT)
            startFlashlightRepository(true)

            val state by collectLastValue(underTest.state)

            assertThat(state).isEqualTo(FlashlightModel.Unavailable.Temporarily.NotFound)
            verify(cameraManager, times(1)) // for fault-tolerance
                .registerTorchCallback(Mockito.any<Executor>(), Mockito.any())
        }

    @Test
    fun supportsFeatureAndHasBackFlashlightAndDoesNotSupportLevel_stateAvailableBinaryOff() =
        kosmos.runTest {
            injectCameraCharacteristics(
                true,
                CameraCharacteristics.LENS_FACING_BACK,
                maxLevel = BASE_TORCH_LEVEL,
            )

            startFlashlightRepository(true)

            val state by collectLastValue(underTest.state)

            assertThat(state).isEqualTo(FlashlightModel.Available.Binary(false))
        }

    @Test
    fun maxLevelMissing_stateIsBinary() =
        kosmos.runTest {
            injectCameraCharacteristics(
                true,
                CameraCharacteristics.LENS_FACING_BACK,
                maxLevel = null,
            )

            startFlashlightRepository(true)

            val state by collectLastValue(underTest.state)

            assertThat(state).isEqualTo(FlashlightModel.Available.Binary(false))

            underTest.setEnabled(true)

            assertThat(state).isEqualTo(FlashlightModel.Available.Binary(true))
        }

    @Test
    fun defaultLevelMissing_stateIsLevel() =
        kosmos.runTest {
            injectCameraCharacteristics(
                true,
                CameraCharacteristics.LENS_FACING_BACK,
                defaultLevel = null,
            )

            startFlashlightRepository(true)

            val state by collectLastValue(underTest.state)

            assertThat(state).isEqualTo(FlashlightModel.Available.Binary(false))

            underTest.setEnabled(true)

            assertThat(state).isEqualTo(FlashlightModel.Available.Binary(true))
        }

    @Test
    fun bothMaxAndDefaultLevelsMissing_stateIsBinary() =
        kosmos.runTest {
            injectCameraCharacteristics(
                true,
                CameraCharacteristics.LENS_FACING_BACK,
                defaultLevel = null,
                maxLevel = null,
            )

            startFlashlightRepository(true)

            val state by collectLastValue(underTest.state)

            assertThat(state).isEqualTo(FlashlightModel.Available.Binary(false))

            underTest.setEnabled(true)

            assertThat(state).isEqualTo(FlashlightModel.Available.Binary(true))
        }

    @Test
    fun setLevelOnBinary_stateRemainsBinary() =
        kosmos.runTest {
            injectCameraCharacteristics(
                true,
                CameraCharacteristics.LENS_FACING_BACK,
                maxLevel = BASE_TORCH_LEVEL,
            )

            startFlashlightRepository(true)

            val state by collectLastValue(underTest.state)

            assertThat(state).isEqualTo(FlashlightModel.Available.Binary(false))

            underTest.setLevel(DEFAULT_DEFAULT_LEVEL)

            assertThat(state).isEqualTo(FlashlightModel.Available.Binary(false))
        }

    @Test
    fun supportsFeatureAndHasBackFlashlightAndSupportsLevels_stateAvailableDisabledLevelDefault() =
        kosmos.runTest {
            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)

            startFlashlightRepository(true)

            val state by collectLastValue(underTest.state)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )
        }

    @Test
    fun changeLevel_stateUpdates() =
        kosmos.runTest {
            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)

            startFlashlightRepository(true)

            val state by collectLastValue(underTest.state)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )

            underTest.setLevel(DEFAULT_MAX_LEVEL)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(true, DEFAULT_MAX_LEVEL, DEFAULT_MAX_LEVEL)
                )
        }

    @Test
    fun enable_stateUpdates() =
        kosmos.runTest {
            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)

            startFlashlightRepository(true)

            val state by collectLastValue(underTest.state)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )

            underTest.setEnabled(true)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(true, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )
        }

    @Test
    fun setCallbackUnavailable_stateBecomesCameraInUse() =
        kosmos.runTest {
            val torchCallbackCaptor = ArgumentCaptor.forClass(TorchCallback::class.java)

            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)

            startFlashlightRepository(true)

            val state by collectLastValue(underTest.state)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )

            verify(cameraManager).registerTorchCallback(any(), torchCallbackCaptor.capture())

            torchCallbackCaptor.value.onTorchModeUnavailable(DEFAULT_ID)

            assertThat(state).isEqualTo(FlashlightModel.Unavailable.Temporarily.CameraInUse)
        }

    @Test
    fun setLevel0_throwsException() {
        assertThrows(IllegalArgumentException::class.java) {
            kosmos.runTest {
                injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)

                startFlashlightRepository(true)

                val state by collectLastValue(underTest.state)

                val disabledAtDefaultLevel =
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)

                assertThat(state).isEqualTo(disabledAtDefaultLevel)

                underTest.setLevel(0)
                assertThat(state).isEqualTo(disabledAtDefaultLevel)
            }
        }
    }

    @Test
    fun setLevelNegative_throwsException() {
        assertThrows(IllegalArgumentException::class.java) {
            kosmos.runTest {
                injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)

                startFlashlightRepository(true)

                val state by collectLastValue(underTest.state)

                val disabledAtDefaultLevel =
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)

                assertThat(state).isEqualTo(disabledAtDefaultLevel)

                underTest.setLevel(-1)

                assertThat(state).isEqualTo(disabledAtDefaultLevel)
            }
        }
    }

    @Test
    fun setLevelAboveMax_throwsException() {
        assertThrows(IllegalArgumentException::class.java) {
            kosmos.runTest {
                injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)

                startFlashlightRepository(true)

                val state by collectLastValue(underTest.state)

                val disabledAtDefaultLevel =
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                assertThat(state).isEqualTo(disabledAtDefaultLevel)

                underTest.setLevel(DEFAULT_MAX_LEVEL + 1)

                assertThat(state).isEqualTo(disabledAtDefaultLevel)
            }
        }
    }

    @Test
    fun enableThenDisable_stateUpdates() =
        kosmos.runTest {
            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)

            startFlashlightRepository(true)

            val state by collectLastValue(underTest.state)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )

            underTest.setEnabled(true)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(true, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )

            underTest.setEnabled(false)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )
        }

    @Test
    fun enableSetLevelDisable_stateUpdatesSetsToDisabledAndLastLevel() =
        kosmos.runTest {
            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)

            startFlashlightRepository(true)

            val state by collectLastValue(underTest.state)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )

            underTest.setEnabled(true)
            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(true, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )

            underTest.setLevel(DEFAULT_MAX_LEVEL)
            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(true, DEFAULT_MAX_LEVEL, DEFAULT_MAX_LEVEL)
                )

            underTest.setEnabled(false)
            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, DEFAULT_MAX_LEVEL, DEFAULT_MAX_LEVEL)
                )
        }

    @Test
    fun start_registersTorchCallback() =
        kosmos.runTest {
            val state by collectLastValue(underTest.state)

            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)
            startFlashlightRepository(true)
            runCurrent()
            verify(cameraManager).registerTorchCallback(any(), any<TorchCallback>())
        }

    @Test
    fun initOnlyNotStart_torchCallbackNotRegistered() =
        kosmos.runTest {
            val state by collectLastValue(underTest.state)

            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)
            runCurrent()
            verify(cameraManager, never()).registerTorchCallback(any(), any<TorchCallback>())
        }

    @Test
    fun startButNotSubscribe_torchCallbackNotRegistered() =
        kosmos.runTest {
            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)
            startFlashlightRepository(true)

            verify(cameraManager, never()).registerTorchCallback(any(), any<TorchCallback>())
        }

    @Test
    fun enabledTwice_onlyOneBackendCall() =
        kosmos.runTest {
            val state by collectLastValue(underTest.state)

            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)
            startFlashlightRepository(true)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )

            underTest.setEnabled(true)
            runCurrent()

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(true, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )

            underTest.setEnabled(true)
            runCurrent()

            verify(cameraManager, times(1)).turnOnTorchWithStrengthLevel(anyString(), anyInt())
        }

    @Test
    fun setLevelTheSameTwice_onlyOneBackendCall() =
        kosmos.runTest {
            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)

            val state by collectLastValue(underTest.state)

            startFlashlightRepository(true)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )

            underTest.setLevel(BASE_TORCH_LEVEL)
            runCurrent()

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(true, BASE_TORCH_LEVEL, DEFAULT_MAX_LEVEL)
                )

            underTest.setLevel(BASE_TORCH_LEVEL)
            runCurrent()

            verify(cameraManager, times(1)).turnOnTorchWithStrengthLevel(anyString(), anyInt())
        }

    @Test
    fun setLevelTemporaryDisableEnable_forgetsTemporaryLevel() =
        kosmos.runTest {
            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)

            val state by collectLastValue(underTest.state)
            val tempLevel = 2
            val permLevel = 3

            startFlashlightRepository(true)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )

            underTest.setLevel(permLevel)

            assertThat(state)
                .isEqualTo(FlashlightModel.Available.Level(true, permLevel, DEFAULT_MAX_LEVEL))

            underTest.setTemporaryLevel(tempLevel)

            assertThat(state)
                .isEqualTo(FlashlightModel.Available.Level(true, tempLevel, DEFAULT_MAX_LEVEL))

            underTest.setEnabled(false)

            assertThat(state)
                .isEqualTo(FlashlightModel.Available.Level(false, permLevel, DEFAULT_MAX_LEVEL))

            underTest.setEnabled(true)

            assertThat(state)
                .isEqualTo(FlashlightModel.Available.Level(true, permLevel, DEFAULT_MAX_LEVEL))
        }

    @Test
    fun setLevelTemporaryDisableEnable_remembersLevel() =
        kosmos.runTest {
            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)

            val state by collectLastValue(underTest.state)
            val permLevel = 3

            startFlashlightRepository(true)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )

            underTest.setLevel(permLevel)

            assertThat(state)
                .isEqualTo(FlashlightModel.Available.Level(true, permLevel, DEFAULT_MAX_LEVEL))

            underTest.setEnabled(false)

            assertThat(state)
                .isEqualTo(FlashlightModel.Available.Level(false, permLevel, DEFAULT_MAX_LEVEL))

            underTest.setEnabled(true)

            assertThat(state)
                .isEqualTo(FlashlightModel.Available.Level(true, permLevel, DEFAULT_MAX_LEVEL))
        }

    @Test
    fun initiallyFailLoadingThenFlashlightBecomesAvailable_afterCooldown_userEnables_connectsAndEnables() =
        kosmos.runTest {
            injectCameraCharacteristics(false, CameraCharacteristics.LENS_FACING_BACK)

            val state by collectLastValue(underTest.state)

            startFlashlightRepository(true)

            assertThat(state).isEqualTo(FlashlightModel.Unavailable.Temporarily.NotFound)

            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)
            advanceTimeBy(RECONNECT_COOLDOWN.inWholeMilliseconds + 1000)
            assertThat(state).isEqualTo(FlashlightModel.Unavailable.Temporarily.NotFound)

            underTest.setEnabled(true)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(true, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )
        }

    @Test
    fun initiallyFailLoadingThenFlashlightBecomesAvailable_afterCooldown_userSetsLevel_connectsAndSetLevel() =
        kosmos.runTest {
            injectCameraCharacteristics(false, CameraCharacteristics.LENS_FACING_BACK)

            val state by collectLastValue(underTest.state)

            startFlashlightRepository(true)

            assertThat(state).isEqualTo(FlashlightModel.Unavailable.Temporarily.NotFound)

            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)
            advanceTimeBy(RECONNECT_COOLDOWN.inWholeMilliseconds + 1000)

            assertThat(state).isEqualTo(FlashlightModel.Unavailable.Temporarily.NotFound)

            underTest.setLevel(BASE_TORCH_LEVEL)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(true, BASE_TORCH_LEVEL, DEFAULT_MAX_LEVEL)
                )
        }

    @Test
    fun initiallyFailLoadingThenFlashlightBecomesAvailable_afterCooldown_torchModeEnabledCallbackFromSystem_connectsAndSetLevel() =
        kosmos.runTest {
            injectCameraCharacteristics(false, CameraCharacteristics.LENS_FACING_BACK)

            val state by collectLastValue(underTest.state)

            startFlashlightRepository(true)

            assertThat(state).isEqualTo(FlashlightModel.Unavailable.Temporarily.NotFound)

            advanceTimeBy(RECONNECT_COOLDOWN.inWholeMilliseconds + 1000)
            runCurrent()

            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)
            runCurrent()

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )
        }

    @Test
    fun initiallyFailLoadingThenFlashlightBecomesAvailable_beforeCooldown_userSetsLevel_doesNotConnect() =
        kosmos.runTest {
            injectCameraCharacteristics(false, CameraCharacteristics.LENS_FACING_BACK)

            val state by collectLastValue(underTest.state)

            startFlashlightRepository(true)

            assertThat(state).isEqualTo(FlashlightModel.Unavailable.Temporarily.NotFound)

            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)
            advanceTimeBy(RECONNECT_COOLDOWN.inWholeMilliseconds - 1000)

            underTest.setLevel(BASE_TORCH_LEVEL)

            assertThat(state).isEqualTo(FlashlightModel.Unavailable.Temporarily.NotFound)
        }

    @Test
    fun deviceSupportsFlashlight_whenFalse_matchesPackageManager() =
        kosmos.runTest {
            startFlashlightRepository(false)

            runCurrent()

            assertThat(packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
                .isFalse()
            assertThat(underTest.deviceSupportsFlashlight).isFalse()
        }

    @Test
    fun deviceSupportsFlashlight_whenTrue_matchesPackageManager() =
        kosmos.runTest {
            startFlashlightRepository(true)

            runCurrent()

            assertThat(packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
                .isTrue()
            assertThat(underTest.deviceSupportsFlashlight).isTrue()
        }

    @Test
    fun setLevelDisableSwitchUser_secondUserHasItsOwnDefaultLevel() =
        kosmos.runTest {
            fakeUserRepository.setUserInfos(USER_INFOS)
            fakeUserRepository.setSelectedUserInfo(USER_INFOS[0])
            injectCameraCharacteristics(true, CameraCharacteristics.LENS_FACING_BACK)

            val state by collectLastValue(underTest.state)
            val permLevelForUser1 = 3

            startFlashlightRepository(true)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, DEFAULT_DEFAULT_LEVEL, DEFAULT_MAX_LEVEL)
                )

            underTest.setLevel(permLevelForUser1)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(true, permLevelForUser1, DEFAULT_MAX_LEVEL)
                )

            underTest.setEnabled(false)

            assertThat(state)
                .isEqualTo(
                    FlashlightModel.Available.Level(false, permLevelForUser1, DEFAULT_MAX_LEVEL)
                )

            fakeUserRepository.setSelectedUserInfo(USER_INFOS[1])

            underTest.setEnabled(true)

            assertThat(state)
                .isNotEqualTo(
                    FlashlightModel.Available.Level(true, permLevelForUser1, DEFAULT_MAX_LEVEL)
                )
        }

    companion object {
        private const val BASE_TORCH_LEVEL = 1
        private const val DEFAULT_DEFAULT_LEVEL = 21
        private const val DEFAULT_MAX_LEVEL = 45
        private const val DEFAULT_ID = "ID"
        private val RECONNECT_COOLDOWN = 30.seconds
        private val USER_INFOS =
            listOf(
                UserInfo(/* id= */ 100, /* name= */ "First user", /* flags= */ 0),
                UserInfo(/* id= */ 101, /* name= */ "Second user", /* flags= */ 0),
            )
    }
}
