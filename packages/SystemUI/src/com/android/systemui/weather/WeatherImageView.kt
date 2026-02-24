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
import android.view.View.MeasureSpec
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.res.R

class WeatherImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ImageView(context, attrs, defStyle) {

    private val maxSizePx: Int =
        context.resources.getDimension(R.dimen.weather_image_max_size).toInt()

    private val weatherViewController: WeatherViewController

    init {
        visibility = View.GONE

        val stubText = TextView(context)

        weatherViewController = WeatherViewController(
            context = context,
            weatherIcon = this,
            weatherTemp = stubText,
            weatherInfoView = this,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        weatherViewController.init()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        weatherViewController.removeObserver()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = maxSizePx.coerceAtMost(MeasureSpec.getSize(widthMeasureSpec))
        val height = maxSizePx.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(width, height)
    }
}
