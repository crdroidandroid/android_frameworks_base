/*
 * Copyright (C) 2023-2024 risingOS Android Project
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
package com.android.systemui.weather

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView

class WeatherTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextView(context, attrs, defStyle) {

    // WeatherViewController cancels its scope in removeObserver(), so it can't be
    // reused after a detach. Create a fresh one for every attach.
    private var weatherViewController: WeatherViewController? = null

    init {
        visibility = View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        weatherViewController?.removeObserver()
        weatherViewController = WeatherViewController(
            context = context,
            weatherIcon = ImageView(context),
            weatherTemp = this,
            weatherInfoView = this,
        ).also { it.init() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        weatherViewController?.removeObserver()
        weatherViewController = null
    }
}
