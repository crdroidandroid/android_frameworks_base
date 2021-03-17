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
import com.android.systemui.SystemUIFactory

class CustomSystemUIFactory : SystemUIFactory() {
    // ML back gesture provider
    override fun createBackGestureTfClassifierProvider(am: AssetManager, modelName: String) =
        CustomBackGestureTfClassifierProvider(am, modelName)
}
