package org.protonaosp.systemui

import android.content.res.AssetManager
import com.android.systemui.statusbar.phone.BackGestureTfClassifierProvider
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

    override fun loadVocab(am: AssetManager): Map<String, Int> {
        am.open("$modelName.vocab").use { ins ->
            return String(ins.readBytes()).lines().asSequence()
                .withIndex()
                .map { it.value to it.index }
                .toMap()
        }
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
