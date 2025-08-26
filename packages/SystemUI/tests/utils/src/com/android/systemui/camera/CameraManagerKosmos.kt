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

package com.android.systemui.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.android.systemui.kosmos.Kosmos
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.stubbing.Answer

/**
 * This will mock only some methods of [CameraManager] and throw [RuntimeExceptionAnswer] if other
 * methods are called. The mocked methods are [CameraManager.getCameraIdList],
 * [CameraManager.getCameraCharacteristics], [CameraManager.registerTorchCallback],
 * [CameraManager.unregisterTorchCallback], [CameraManager.getTorchStrengthLevel],
 * [CameraManager.setTorchMode], and [CameraManager.turnOnTorchWithStrengthLevel].
 */
val Kosmos.cameraManager: CameraManager by Kosmos.Fixture { mockCameraManager() }

class RuntimeExceptionAnswer : Answer<Any> {
    override fun answer(invocation: InvocationOnMock): Any {
        throw RuntimeException(invocation.method.name + " is not stubbed")
    }
}

private val callbacks: MutableSet<CameraManager.TorchCallback> = mutableSetOf()
private const val ID = "ID"
private const val DEFAULT_DEFAULT_LEVEL = 21
private const val DEFAULT_MAX_LEVEL = 45

private fun mockCameraManager(): CameraManager {
    val cameraManager = mock<CameraManager>(defaultAnswer = RuntimeExceptionAnswer())
    val cameraId = ID
    val max = DEFAULT_MAX_LEVEL
    var enabled = false
    var level = DEFAULT_DEFAULT_LEVEL

    callbacks.clear()

    doAnswer { _: InvocationOnMock ->
            val cameraIdList = arrayOf(cameraId)
            cameraIdList
        }
        .whenever(cameraManager)
        .cameraIdList

    doAnswer { _: InvocationOnMock -> mockCameraCharacteristics() }
        .whenever(cameraManager)
        .getCameraCharacteristics(anyString())

    doAnswer { invocation: InvocationOnMock ->
            val cb = invocation.getArgument<CameraManager.TorchCallback>(1)
            val newlyAdded = callbacks.add(cb)
            if (newlyAdded) {
                cb.onTorchModeChanged(cameraId, enabled)
            }
            Unit
        }
        .whenever(cameraManager)
        .registerTorchCallback(any(), any<CameraManager.TorchCallback>())

    doAnswer { invocation: InvocationOnMock ->
            val cb = invocation.getArgument<CameraManager.TorchCallback>(0)
            callbacks.remove(cb)
            Unit
        }
        .whenever(cameraManager)
        .unregisterTorchCallback(any<CameraManager.TorchCallback>())

    doAnswer { _: InvocationOnMock -> level }
        .whenever(cameraManager)
        .getTorchStrengthLevel(anyString())

    doAnswer { invocation: InvocationOnMock ->
            val camIdArg = invocation.getArgument<String>(0)
            val enableArg = invocation.getArgument<Boolean>(1)
            if (enabled == enableArg) return@doAnswer Unit
            enabled = enableArg
            if (!enableArg) {
                level = DEFAULT_DEFAULT_LEVEL
            }
            callbacks.forEach { it.onTorchModeChanged(camIdArg, enableArg) }
        }
        .whenever(cameraManager)
        .setTorchMode(anyString(), anyBoolean())

    doAnswer { invocation: InvocationOnMock ->
            val camIdArg = invocation.getArgument<String>(0)
            val levelArg = invocation.getArgument<Int>(1)
            val validRange = 1..max
            if (levelArg in validRange) {
                enabled = true
                level = levelArg
                callbacks.forEach { it.onTorchStrengthLevelChanged(camIdArg, level) }
            } else
                throw IllegalArgumentException(
                    "onTorchStrengthLevelChanged accepts" +
                        " levels in range $validRange but was called with $levelArg "
                )
        }
        .whenever(cameraManager)
        .turnOnTorchWithStrengthLevel(anyString(), anyInt())

    return cameraManager
}

/**
 * Overrides CameraManager.getCameraCharacteristics. Sends callbacks on onTorchModeUnavailable or on
 * torchModeChanged false
 */
fun Kosmos.injectCameraCharacteristics(
    flashAvailable: Boolean = true,
    direction: Int = CameraCharacteristics.LENS_FACING_BACK,
    defaultLevel: Int? = DEFAULT_DEFAULT_LEVEL,
    maxLevel: Int? = DEFAULT_MAX_LEVEL,
) {
    doAnswer { _: InvocationOnMock ->
            mockCameraCharacteristics(flashAvailable, direction, defaultLevel, maxLevel)
        }
        .whenever(cameraManager)
        .getCameraCharacteristics(anyString())

    if (flashAvailable) {
        callbacks.forEach { it.onTorchModeChanged(ID, false) }
    } else {
        callbacks.forEach { it.onTorchModeUnavailable(ID) }
    }
}

private fun mockCameraCharacteristics(
    flashAvailable: Boolean = true,
    direction: Int = CameraCharacteristics.LENS_FACING_BACK,
    defaultLevel: Int? = DEFAULT_DEFAULT_LEVEL,
    maxLevel: Int? = DEFAULT_MAX_LEVEL,
): CameraCharacteristics {
    val cc = mock<CameraCharacteristics>()
    whenever(cc.get(eq(CameraCharacteristics.FLASH_INFO_AVAILABLE))).thenReturn(flashAvailable)
    whenever(cc.get(eq(CameraCharacteristics.LENS_FACING))).thenReturn(direction)
    whenever(cc.get(eq(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL)))
        .thenReturn(defaultLevel)
    whenever(cc.get(eq(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)))
        .thenReturn(maxLevel)
    return cc
}
