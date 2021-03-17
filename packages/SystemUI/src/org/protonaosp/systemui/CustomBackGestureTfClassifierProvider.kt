/*
 * Copyright (C) 2021 The Proton AOSP Project
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

package org.protonaosp.systemui

import android.content.res.AssetManager
import com.android.systemui.navigationbar.gestural.BackGestureTfClassifierProvider
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CustomBackGestureTfClassifierProvider(
    am: AssetManager,
    private val modelName: String
) : BackGestureTfClassifierProvider() {
    // Don't bother to set up a MappedByteBuffer for 512 KiB of data
    private val interpreter = am.open("$modelName.tflite").use {
        val data = it.readBytes()
        Interpreter(ByteBuffer.allocateDirect(data.size).apply {
            order(ByteOrder.nativeOrder())
            put(data)
        })
    }

    override fun loadVocab(am: AssetManager) = am.open("$modelName.vocab").use { ins ->
        String(ins.readBytes()).lines().asSequence()
            .withIndex()
            .map { it.value to it.index }
            .toMap()
    }

    override fun predict(featuresVector: Array<Any>): Float {
        val confidenceTensor = floatArrayOf(0f)
        val tensors = mutableMapOf(0 to arrayOf(confidenceTensor))

        interpreter.runForMultipleInputsOutputs(featuresVector, tensors as Map<Int, Any>)
        return confidenceTensor[0]
    }

    override fun release() = interpreter.close()
    override fun isActive() = true
}
