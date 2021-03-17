package org.protonaosp.systemui

import android.content.res.AssetManager
import com.android.systemui.SystemUIFactory

class CustomSystemUIFactory : SystemUIFactory() {
    // ML back gesture provider
    override fun createBackGestureTfClassifierProvider(am: AssetManager, modelName: String) =
        CustomBackGestureTfClassifierProvider(am, modelName)
}
