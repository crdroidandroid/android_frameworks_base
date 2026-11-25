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

package com.android.systemui.statusbar.chips.ui.viewmodel

import android.graphics.RectF
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayAware
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.PerDisplaySingleton
import com.android.systemui.display.domain.interactor.DisplayStateInteractor
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.screencapture.record.domain.interactor.ScreenCaptureRecordFeaturesInteractor
import com.android.systemui.statusbar.chips.StatusBarChipLogTags.pad
import com.android.systemui.statusbar.chips.StatusBarChipToHunAnimation
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.call.ui.viewmodel.CallChipViewModel
import com.android.systemui.statusbar.chips.casttootherdevice.ui.viewmodel.CastToOtherDeviceChipViewModel
import com.android.systemui.statusbar.chips.notification.ui.viewmodel.NotifChipsViewModel
import com.android.systemui.statusbar.chips.screenrecord.ui.viewmodel.ScreenRecordChipViewModel
import com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel.ShareToAppChipViewModel
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.notification.shared.StatusBarHeadline
import com.android.systemui.util.kotlin.filterValuesNotNull
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * View model deciding which ongoing activity chip to show in the status bar.
 *
 * There may be multiple ongoing activities at the same time, but we can only ever show one chip at
 * any one time (for now). This class decides which ongoing activity to show if there are multiple.
 */
@PerDisplaySingleton
class OngoingActivityChipsViewModel
@Inject
constructor(
    @DisplayAware scope: CoroutineScope,
    screenRecordChipViewModel: ScreenRecordChipViewModel,
    shareToAppChipViewModel: ShareToAppChipViewModel,
    castToOtherDeviceChipViewModel: CastToOtherDeviceChipViewModel,
    callChipViewModel: CallChipViewModel,
    notifChipsViewModel: NotifChipsViewModel,
    @DisplayAware displayStateInteractor: DisplayStateInteractor,
    private val screenCaptureRecordFeaturesInteractor: ScreenCaptureRecordFeaturesInteractor,
    private val chipsRefiners: Set<@JvmSuppressWildcards OngoingActivityChipsRefiner>,
    @StatusBarChipsLog private val logger: LogBuffer,
) {
    private enum class ChipType {
        ScreenRecord,
        ShareToApp,
        CastToOtherDevice,
        Call,
        Notification,
    }

    /** Model that helps us internally track the various chip states from each of the types. */
    @Deprecated("Since StatusBarChipsModernization, this isn't used anymore")
    private sealed interface InternalChipModel {
        /**
         * Represents that we've internally decided to show the chip with type [type] with the given
         * [model] information.
         */
        data class Active(val type: ChipType, val model: OngoingActivityChipModel.Active) :
            InternalChipModel

        /**
         * Represents that all chip types would like to be hidden. Each value specifies *how* that
         * chip type should get hidden.
         */
        data class Inactive(
            val screenRecord: OngoingActivityChipModel.Inactive,
            val shareToApp: OngoingActivityChipModel.Inactive,
            val castToOtherDevice: OngoingActivityChipModel.Inactive,
            val call: OngoingActivityChipModel.Inactive,
            val notifs: OngoingActivityChipModel.Inactive,
        ) : InternalChipModel
    }

    private data class ChipBundle(
        val screenRecord: OngoingActivityChipModel = OngoingActivityChipModel.Inactive(),
        val shareToApp: OngoingActivityChipModel = OngoingActivityChipModel.Inactive(),
        val castToOtherDevice: OngoingActivityChipModel = OngoingActivityChipModel.Inactive(),
        val call: OngoingActivityChipModel = OngoingActivityChipModel.Inactive(),
        val notifs: List<OngoingActivityChipModel.Active> = emptyList(),
    )

    /** Bundles all the incoming chips into one object to easily pass to various flows. */
    private val incomingChipBundle =
        if (screenCaptureRecordFeaturesInteractor.isLargeScreenScreencaptureEnabled) {
                combine(
                    shareToAppChipViewModel.chip,
                    castToOtherDeviceChipViewModel.chip,
                    callChipViewModel.chip,
                    notifChipsViewModel.chips,
                ) { shareToApp, castToOtherDevice, call, notifs ->
                    logChips(
                        OngoingActivityChipModel.Inactive(),
                        shareToApp,
                        castToOtherDevice,
                        call,
                        notifs,
                    )
                    ChipBundle(
                        shareToApp = shareToApp,
                        castToOtherDevice = castToOtherDevice,
                        call = call,
                        notifs = notifs,
                    )
                }
            } else {
                combine(
                    screenRecordChipViewModel.chip,
                    shareToAppChipViewModel.chip,
                    castToOtherDeviceChipViewModel.chip,
                    callChipViewModel.chip,
                    notifChipsViewModel.chips,
                ) { screenRecord, shareToApp, castToOtherDevice, call, notifs ->
                    logChips(screenRecord, shareToApp, castToOtherDevice, call, notifs)
                    ChipBundle(
                        screenRecord = screenRecord,
                        shareToApp = shareToApp,
                        castToOtherDevice = castToOtherDevice,
                        call = call,
                        notifs = notifs,
                    )
                }
            }
            // Some of the chips could have timers in them and we don't want the start time for
            // those timers to get reset for any reason. So, as soon as any subscriber has requested
            // the chip information, we maintain it forever by using [SharingStarted.Lazily].
            // See b/347726238.
            .stateIn(scope, SharingStarted.Lazily, ChipBundle())

    private fun logChips(
        screenRecord: OngoingActivityChipModel,
        shareToApp: OngoingActivityChipModel,
        castToOtherDevice: OngoingActivityChipModel,
        call: OngoingActivityChipModel,
        notifs: List<OngoingActivityChipModel.Active>,
    ) {
        logger.log(
            TAG,
            LogLevel.INFO,
            {
                str1 = screenRecord.logName
                str2 = shareToApp.logName
                str3 = castToOtherDevice.logName
            },
            { "Chips: ScreenRecord=$str1 > ShareToApp=$str2 > CastToOther=$str3..." },
        )
        logger.log(
            TAG,
            LogLevel.INFO,
            {
                str1 = call.logName
                str2 = notifs.map { it.logName }.toString()
            },
            { "... > Call=$str1 > Notifs=$str2" },
        )
    }

    private fun OngoingActivityChipModel.Active.shouldSquish(): Boolean {
        if (this.icon == null) {
            // If there's no icon, we can't squish the chip to be icon-only
            return false
        }
        return when (this.content) {
            // Icon-only is already maximum squished
            is OngoingActivityChipModel.Content.IconOnly,
            // Countdown shows just a single digit, so already maximum squished
            is OngoingActivityChipModel.Content.Countdown -> false
            // The other chips have icon+text, so we can squish them by hiding text
            is OngoingActivityChipModel.Content.Timer,
            is OngoingActivityChipModel.Content.ShortTimeDelta,
            is OngoingActivityChipModel.Content.Text,
            is OngoingActivityChipModel.Content.TextVariants -> true
        }
    }

    private fun OngoingActivityChipModel.Active.toIconOnly(): OngoingActivityChipModel.Active {
        if (icon == null) {
            // If this chip doesn't have an icon, then it only has text and we should continue
            // showing its text. (This is theoretically impossible because [shouldSquish] returns
            // false for a model with a null icon, but protect against it just in case.)
            return this
        }
        return this.copy(content = OngoingActivityChipModel.Content.IconOnly)
    }

    /**
     * A flow modeling the active and inactive chips as well as which should be shown in the status
     * bar after accounting for possibly multiple ongoing activities and animation requirements.
     */
    private val unrefinedChips =
        combine(
            incomingChipBundle.map { bundle -> rankChips(bundle) },
            displayStateInteractor.isWideScreen,
        ) { rankedChips, isWideScreen -> rankedChips to isWideScreen }

    // Refined before squishing, so refiners see full chip content and only chips still shown
    // in the status bar count towards squishing.
    val chips: StateFlow<MultipleOngoingActivityChipsModel> =
        unrefinedChips
            .map { (rankedChips, isWideScreen) ->
                val refinedChips =
                    chipsRefiners.fold(rankedChips) { currentOutput, refiner ->
                        refiner.transform(currentOutput)
                    }
                if (
                    !StatusBarHeadline.isEnabled &&
                        !isWideScreen &&
                        refinedChips.active.filter { !it.isHidden }.size >= 2
                ) {
                    // If we have at least two showing chips and we don't have a ton of room
                    // (!isWideScreen), then we want to make both of them as small as possible
                    // so that we have the highest chance of showing both chips (as opposed to
                    // showing the first chip with a lot of text and completely hiding the other
                    // chips).
                    // With Headline, there's no need to squish chips.
                    refinedChips.copy(
                        active =
                            refinedChips.active.map {
                                if (!it.isHidden && it.shouldSquish()) it.toIconOnly() else it
                            }
                    )
                } else {
                    refinedChips
                }
            }
            .stateIn(scope, SharingStarted.Lazily, MultipleOngoingActivityChipsModel())

    private val activeChips = chips.map { it.active }

    /** Stores the latest on-screen bounds for each of the chips. */
    // Note: This will also store bounds for chips that have been removed. We may want to clear the
    // value for removed chips.
    private val chipBounds = MutableStateFlow<Map<String, RectF>>(emptyMap())

    /**
     * Invoked each time a chip's on-screen bounds have changed.
     *
     * @param key the raw notification key without any prefixes.
     */
    fun onChipBoundsChanged(key: String, newBounds: RectF) {
        if (!StatusBarChipToHunAnimation.isEnabled) {
            return
        }
        val map = chipBounds.value.toMutableMap()
        val currentValue = map[key]
        if (currentValue != null) {
            currentValue.set(newBounds)
        } else {
            map[key] = newBounds
        }
        chipBounds.value = map
    }

    /** A flow modeling just the keys for the currently visible notification chips. */
    private val visibleNotificationChipKeys: Flow<List<String>> =
        activeChips.map { chips -> chips.filter { !it.isHidden }.mapNotNull { it.notificationKey } }

    /** Placeholder chip bounds to use if {@link StatusBarChipToHunAnimation} is disabled. */
    private val placeholderChipBounds = RectF()

    /**
     * A flow modeling the keys and on-screen bounds for the currently visible chips.
     *
     * This only contains bounds for chips tied to notifications. Other chips, like screen sharing
     * chips, are *NOT* in this list.
     */
    val visibleNotificationChipsWithBounds: Flow<Map<String, RectF>> =
        if (StatusBarChipToHunAnimation.isEnabled) {
            combine(visibleNotificationChipKeys, chipBounds) { keys, chipBounds ->
                    // TODO(b/393369891): Should we provide the placeholder bounds as a backup and
                    // make those bounds public so that [NotificationStackScrollLayout] can do a
                    // good default animation for chips even if we couldn't fetch the bounds for
                    // some reason?
                    keys.associateWith { chipBounds[it] }.filterValuesNotNull()
                }
                .distinctUntilChanged()
        } else {
            // If the custom chip-to-HUN animation isn't enabled, just provide any non-null
            // chip bounds so that [NotificationStackScrollLayout] knows there's a status bar chip.
            visibleNotificationChipKeys
                .map { keys -> keys.associateWith { placeholderChipBounds } }
                .distinctUntilChanged()
        }

    /**
     * Sort the given chip [bundle] in order of priority, and divide the chips between active,
     * overflow, and inactive (see [MultipleOngoingActivityChipsModel] for a description of each).
     */
    // IMPORTANT: PromotedNotificationsInteractor re-implements this same ordering scheme. Any
    // changes here should also be made in PromotedNotificationsInteractor.
    // TODO(b/402471288): Create a single source of truth for the ordering.
    private fun rankChips(bundle: ChipBundle): MultipleOngoingActivityChipsModel {
        val activeChips = mutableListOf<OngoingActivityChipModel.Active>()
        val overflowChips = mutableListOf<OngoingActivityChipModel.Active>()
        val inactiveChips = mutableListOf<OngoingActivityChipModel.Inactive>()

        val sortedChips =
            with(bundle) { listOf(screenRecord, shareToApp, castToOtherDevice, call) + notifs }

        var shownSlotsRemaining = MAX_VISIBLE_CHIPS
        for (chip in sortedChips) {
            when (chip) {
                is OngoingActivityChipModel.Active -> {
                    // Screen recording also activates the media projection APIs, which means that
                    // whenever the screen recording chip is active, the share-to-app chip would
                    // also be active. (Screen recording is a special case of share-to-app, where
                    // the app receiving the share is specifically System UI.)
                    // We want only the screen-recording-specific chip to be shown in this case. If
                    // we did have screen recording as the primary chip, we need to suppress the
                    // share-to-app chip to make sure they don't both show.
                    // See b/296461748.
                    val suppressShareToApp =
                        chip == bundle.shareToApp &&
                            bundle.screenRecord is OngoingActivityChipModel.Active
                    if (shownSlotsRemaining > 0 && !suppressShareToApp) {
                        activeChips.add(chip)
                        if (!chip.isHidden) shownSlotsRemaining--
                    } else {
                        overflowChips.add(chip)
                    }
                }

                is OngoingActivityChipModel.Inactive -> inactiveChips.add(chip)
            }
        }

        return MultipleOngoingActivityChipsModel(activeChips, overflowChips, inactiveChips)
    }

    companion object {
        private val TAG = "ChipsViewModel".pad()
        private const val MAX_VISIBLE_CHIPS = 3
    }
}
