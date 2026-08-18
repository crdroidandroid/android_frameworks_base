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

package com.android.systemui.keyguard.ui.composable.elements

import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementProvider
import com.google.android.systemui.keyguard.ui.composable.elements.GoogleAmbientIndicationElementProvider
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet

@Module
interface ElementProviderModule {
    @Binds
    @IntoSet
    fun rootElementProvider(impl: LockscreenRootElementProvider): LockscreenElementProvider

    @Binds
    @IntoSet
    fun statusBarElementProvider(impl: StatusBarElementProvider): LockscreenElementProvider

    @Binds
    @IntoSet
    fun upperRegionElementProvider(
        impl: LockscreenUpperRegionElementProvider
    ): LockscreenElementProvider

    @Binds
    @IntoSet
    fun notificationStackElementProvider(
        impl: NotificationStackElementProvider
    ): LockscreenElementProvider

    @Binds
    @IntoSet
    fun aodNotificationIconElementProvider(
        impl: AodNotificationIconsElementProvider
    ): LockscreenElementProvider

    @Binds
    @IntoSet
    fun aodPromotedNotificationElementProvider(
        impl: AodPromotedNotificationAreaElementProvider
    ): LockscreenElementProvider

    @Binds
    @IntoSet
    fun lowerRegionElementProvider(
        impl: LockscreenLowerRegionElementProvider
    ): LockscreenElementProvider

    @Binds
    @IntoSet
    fun lockIconElementProvider(impl: LockIconElementProvider): LockscreenElementProvider

    @Binds
    @IntoSet
    fun shortcutElementProvider(impl: ShortcutElementProvider): LockscreenElementProvider

    @Binds
    @IntoSet
    fun indicationAreaElementProvider(
        impl: IndicationAreaElementProvider
    ): LockscreenElementProvider

    @Binds
    @IntoSet
    fun settingsMenuElementProvider(impl: SettingsMenuElementProvider): LockscreenElementProvider

    @Binds
    @IntoSet
    fun smartspaceElementProvider(impl: SmartspaceElementProvider): LockscreenElementProvider

    @Binds
    @IntoSet
    fun clockRegionElementProvider(impl: ClockRegionElementProvider): LockscreenElementProvider

    @Binds @IntoSet fun mediaElementProvider(impl: MediaElementProvider): LockscreenElementProvider

    @Binds
    @IntoSet
    fun ambientIndicationAreaProvider(
        impl: AmbientIndicationAreaProvider
    ): LockscreenElementProvider

    @Binds
    @IntoSet
    fun googleAmbientIndicationElementProvider(
        impl: GoogleAmbientIndicationElementProvider
    ): LockscreenElementProvider
}
