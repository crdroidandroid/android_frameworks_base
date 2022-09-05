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

package com.android.systemui.volume.dialog.dagger.module

import com.android.systemui.volume.dialog.appvolume.ui.binder.VolumeDialogAppVolumeButtonViewBinder
import com.android.systemui.volume.dialog.captions.ui.binder.VolumeDialogCaptionsButtonViewBinder
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.ringer.data.repository.VolumeDialogRingerFeedbackRepository
import com.android.systemui.volume.dialog.ringer.data.repository.VolumeDialogRingerFeedbackRepositoryImpl
import com.android.systemui.volume.dialog.ringer.ui.binder.VolumeDialogRingerViewBinder
import com.android.systemui.volume.dialog.settings.ui.binder.VolumeDialogSettingsButtonViewBinder
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderComponent
import com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSlidersViewBinder
import com.android.systemui.volume.dialog.ui.binder.ViewBinder
import dagger.Binds
import dagger.Module
import dagger.Provides

/** Dagger module for volume dialog code in the volume package */
@Module(subcomponents = [VolumeDialogSliderComponent::class])
interface VolumeDialogModule {

    @Binds
    fun bindVolumeDialogRingerFeedbackRepository(
        ringerFeedbackRepository: VolumeDialogRingerFeedbackRepositoryImpl
    ): VolumeDialogRingerFeedbackRepository

    companion object {

        @Provides
        @VolumeDialog
        fun provideViewBinders(
            slidersViewBinder: VolumeDialogSlidersViewBinder,
            ringerViewBinder: VolumeDialogRingerViewBinder,
            settingsButtonViewBinder: VolumeDialogSettingsButtonViewBinder,
            captionsButtonViewBinder: VolumeDialogCaptionsButtonViewBinder,
            appVolumeButtonViewBinder: VolumeDialogAppVolumeButtonViewBinder,
        ): List<ViewBinder> =
            listOf(
                slidersViewBinder,
                ringerViewBinder,
                settingsButtonViewBinder,
                captionsButtonViewBinder,
                appVolumeButtonViewBinder,
            )
    }
}
