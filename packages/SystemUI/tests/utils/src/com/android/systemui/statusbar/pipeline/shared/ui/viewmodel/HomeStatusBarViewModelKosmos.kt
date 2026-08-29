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

package com.android.systemui.statusbar.pipeline.shared.ui.viewmodel

import android.content.testableContext
import com.android.systemui.desktop.domain.interactor.desktopInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardOcclusionInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.backgroundScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.log.table.tableLogBufferFactory
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.shade.domain.interactor.shadeDisplaysInteractor
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel.shareToAppChipViewModel
import com.android.systemui.statusbar.chips.ui.viewmodel.ongoingActivityChipsViewModel
import com.android.systemui.statusbar.chips.uievents.statusBarChipsUiEventLogger
import com.android.systemui.statusbar.events.domain.interactor.systemStatusEventAnimationInteractor
import com.android.systemui.statusbar.featurepods.popups.ui.viewmodel.statusBarPopupChipsViewModelFactory
import com.android.systemui.statusbar.layout.ui.viewmodel.appHandlesViewModelFactory
import com.android.systemui.statusbar.layout.ui.viewmodel.multiDisplayStatusBarContentInsetsViewModelStore
import com.android.systemui.statusbar.layout.ui.viewmodel.statusBarBoundsViewModelFactory
import com.android.systemui.statusbar.notification.domain.interactor.activeNotificationsInteractor
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.connectedDisplaysStatusBarNotificationIconViewStoreFactory
import com.android.systemui.statusbar.notification.stack.domain.interactor.headsUpNotificationInteractor
import com.android.systemui.statusbar.phone.domain.interactor.darkIconInteractor
import com.android.systemui.statusbar.phone.domain.interactor.lightsOutInteractor
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.batteryViewModelBasedOnSettingFactory
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.batteryWithPercentViewModelFactory
import com.android.systemui.statusbar.pipeline.shared.domain.interactor.homeStatusBarIconBlockListInteractor
import com.android.systemui.statusbar.pipeline.shared.domain.interactor.homeStatusBarInteractor
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinder
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl
import com.android.systemui.statusbar.policy.configurationController
import com.android.systemui.statusbar.systemstatusicons.ui.viewmodel.systemStatusIconsViewModelFactory

var Kosmos.homeStatusBarViewBinder: HomeStatusBarViewBinder by
    Kosmos.Fixture {
        HomeStatusBarViewBinderImpl(
            connectedDisplaysStatusBarNotificationIconViewStoreFactory,
            configurationController,
        )
    }

var Kosmos.homeStatusBarViewModel: HomeStatusBarViewModel by
    Kosmos.Fixture { homeStatusBarViewModelFactory.invoke(testableContext.displayId) }

var Kosmos.defaultDisplayHomeStatusBarViewModelFactory:
    HomeStatusBarViewModel.HomeStatusBarViewModelFactory by
    Kosmos.Fixture {
        object : HomeStatusBarViewModel.HomeStatusBarViewModelFactory {
            override fun create(): HomeStatusBarViewModel {
                return homeStatusBarViewModelFactory.invoke(testableContext.displayId)
            }
        }
    }

var Kosmos.homeStatusBarViewModelFactory: (Int) -> HomeStatusBarViewModel by
    Kosmos.Fixture {
        { displayId ->
            HomeStatusBarViewModelImpl(
                displayId,
                batteryWithPercentViewModelFactory,
                batteryViewModelBasedOnSettingFactory,
                systemStatusIconsViewModelFactory,
                statusBarBoundsViewModelFactory,
                appHandlesViewModelFactory,
                tableLogBufferFactory,
                homeStatusBarInteractor,
                homeStatusBarIconBlockListInteractor,
                lightsOutInteractor,
                activeNotificationsInteractor,
                desktopInteractor,
                darkIconInteractor,
                headsUpNotificationInteractor,
                keyguardTransitionInteractor,
                keyguardInteractor,
                statusBarOperatorNameViewModel,
                sceneInteractor,
                keyguardOcclusionInteractor,
                shadeInteractor,
                shareToAppChipViewModel,
                ongoingActivityChipsViewModel,
                statusBarPopupChipsViewModelFactory,
                systemStatusEventAnimationInteractor,
                multiDisplayStatusBarContentInsetsViewModelStore,
                backgroundScope,
                testDispatcher,
                { shadeDisplaysInteractor },
                uiEventLogger = statusBarChipsUiEventLogger,
            )
        }
    }
