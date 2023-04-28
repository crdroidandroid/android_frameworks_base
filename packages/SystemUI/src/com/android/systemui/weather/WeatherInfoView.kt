/*
 * Copyright (C) 2025 the AxionAOSP Project
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
import androidx.constraintlayout.widget.ConstraintLayout
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.res.R

class WeatherInfoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : ConstraintLayout(context, attrs, defStyle) {

    private lateinit var weatherIcon: ImageView
    private lateinit var weatherTemp: TextView

    private lateinit var controller: WeatherViewController
    
    fun init() {
        weatherIcon = findViewById(R.id.weather_icon)
        weatherTemp = findViewById(R.id.weather_temp)

        controller = WeatherViewController(
            context,
            weatherIcon,
            weatherTemp,
            this
        )

        controller.init()
    }

    fun cleanup() {
        controller.removeObserver()
    }
}
