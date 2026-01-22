/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.window.data.repository

import com.android.systemui.kosmos.Kosmos
import kotlinx.coroutines.flow.MutableStateFlow

val Kosmos.fakeWindowRootViewBlurRepository: FakeWindowRootViewBlurRepository by
    Kosmos.Fixture { FakeWindowRootViewBlurRepository() }

val Kosmos.windowRootViewBlurRepository: WindowRootViewBlurRepository by
    Kosmos.Fixture { fakeWindowRootViewBlurRepository }

class FakeWindowRootViewBlurRepository : WindowRootViewBlurRepository {
    override val blurRequestedByShade: MutableStateFlow<Float> = MutableStateFlow(0.0f)
    override val scaleRequestedByShade: MutableStateFlow<Float> = MutableStateFlow(1.0f)
    override val isBlurSupported: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isTranslucentSupported: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override var blurAppliedListener: BlurAppliedListener? = null
    override val trackingShadeMotion: MutableStateFlow<Boolean> = MutableStateFlow(false)
}
