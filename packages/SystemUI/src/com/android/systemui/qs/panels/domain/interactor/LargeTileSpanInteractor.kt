/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.qs.panels.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.data.repository.LargeTileSpanRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class LargeTileSpanInteractor @Inject constructor(repo: LargeTileSpanRepository) {
    val useExtraLargeTiles: Flow<Boolean> = repo.useExtraLargeTiles

    val tileMaxWidth: Flow<Int> = repo.tileMaxWidth

    val defaultTileMaxWidth: Int = repo.defaultTileMaxWidth

    val classicStyle: Flow<Boolean> = repo.classicStyle
}
