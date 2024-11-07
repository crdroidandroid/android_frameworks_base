/*
 * Copyright (C) 2024 The RisingOS Android Project
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
 *
 */

package com.android.systemui.animation.view

import android.content.Context
import android.util.AttributeSet
import com.android.systemui.animation.LaunchableView
import com.android.systemui.animation.LaunchableViewDelegate
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

/** A custom [ExtendedFloatingActionButton] that implements [LaunchableView]. */
open class LaunchableFAB : ExtendedFloatingActionButton, LaunchableView {

    private val delegate: LaunchableViewDelegate
    private val MAX_SIZE_PX = 64

    constructor(context: Context) : this(context, null)
    
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        delegate = LaunchableViewDelegate(
            this,
            superSetVisibility = { visibility -> super.setVisibility(visibility) }
        )
        setIconSize(MAX_SIZE_PX)
    }

    override fun setShouldBlockVisibilityChanges(block: Boolean) {
        delegate.setShouldBlockVisibilityChanges(block)
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        delegate.setVisibility(visibility)
    }
}
