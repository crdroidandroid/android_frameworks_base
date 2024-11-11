/*
 * Copyright (C) 2024 crDroid Android Project
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
package com.android.systemui.biometrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat

/**
 * Draws udfps fingerprint if sensor isn't illuminating.
 */
class UdfpsFpIconDrawable(context: Context) : UdfpsIconDrawable(context) {
    override fun draw(canvas: Canvas) {
        val udfpsDrawable = getUdfpsDrawable()
        udfpsDrawable?.apply {
            setBounds(bounds)
            draw(canvas)
        }
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }
}
