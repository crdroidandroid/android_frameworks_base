/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.icon.domain.interactor

import android.content.Context
import android.graphics.drawable.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryBypassInteractor
import com.android.systemui.kairos.util.Either
import com.android.systemui.kairos.util.mergeSecond
import com.android.systemui.statusbar.data.repository.NotificationListenerSettingsRepository
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.NotificationsKeyguardViewStateRepository
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationIconInteractor
import com.android.systemui.statusbar.notification.promoted.domain.interactor.AODPromotedNotificationInteractor
import com.android.systemui.statusbar.notification.shared.ActiveBundleModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationEntryModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationGroupModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.wm.shell.bubbles.Bubbles
import java.util.Optional
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Domain logic related to notification icons. */
class NotificationIconsInteractor
@Inject
constructor(
    private val activeNotificationsRepository: ActiveNotificationListRepository,
    private val bubbles: Optional<Bubbles>,
    private val headsUpNotificationIconInteractor: HeadsUpNotificationIconInteractor,
    aodPromotedNotificationInteractor: AODPromotedNotificationInteractor,
    private val keyguardViewStateRepository: NotificationsKeyguardViewStateRepository,
    @Application private val appContext: Context,
) {
    private val aodPromotedKeyToHide: Flow<String?> =
        combine(
            aodPromotedNotificationInteractor.content,
            aodPromotedNotificationInteractor.isPresent,
        ) { content, isPresent ->
            when {
                !isPresent -> null
                content == null -> null
                else -> content.identity.key
            }
        }

    /** Returns a subset of all active notifications based on the supplied filtration parameters. */
    fun filteredNotifSet(
        forceShowHeadsUp: Boolean = false,
        showAmbient: Boolean = true,
        showLowPriority: Boolean = true,
        showDismissed: Boolean = true,
        showRepliedMessages: Boolean = true,
        showPulsing: Boolean = true,
        showAodPromoted: Boolean = true,
    ): Flow<Set<ActiveNotificationIconModel>> {
        return combine(
            activeNotificationsRepository.activeNotifications,
            headsUpNotificationIconInteractor.isolatedNotification,
            if (showAodPromoted) flowOf(null) else aodPromotedKeyToHide,
            keyguardViewStateRepository.areNotificationsFullyHidden,
        ) { store, isolatedNotifKey, aodPromotedKeyToHide, notifsFullyHidden ->
            store.renderList
                .asSequence()
                .mapNotNull { key: ActiveNotificationsStore.Key ->
                    store[key]
                        ?.let {
                            when (it) {
                                // bundles are located in the silent section, so only include them
                                // if we're showing low priority icons
                                is ActiveBundleModel ->
                                    if (shouldShowBundleIcon(it, showAmbient, showLowPriority)) {
                                        Either.first(it.toIconModel())
                                    } else {
                                        null
                                    }
                                is ActiveNotificationGroupModel -> Either.second(it.summary)
                                is ActiveNotificationModel -> Either.second(it)
                            }
                        }
                        // for non-bundles, perform additional filtering based on the provided
                        // arguments
                        ?.mergeSecond { notifModel: ActiveNotificationModel ->
                            notifModel
                                .takeIf {
                                    shouldShowNotificationIcon(
                                        model = notifModel,
                                        forceShowHeadsUp = forceShowHeadsUp,
                                        showAmbient = showAmbient,
                                        showLowPriority = showLowPriority,
                                        showDismissed = showDismissed,
                                        showRepliedMessages = showRepliedMessages,
                                        showPulsing = showPulsing,
                                        isolatedNotifKey = isolatedNotifKey,
                                        aodPromotedKeyToHide = aodPromotedKeyToHide,
                                        notifsFullyHidden = notifsFullyHidden,
                                    )
                                }
                                ?.toIconModel()
                        }
                }
                .toSet()
        }
    }

    private fun shouldShowBundleIcon(
        model: ActiveBundleModel,
        showAmbient: Boolean,
        showLowPriority: Boolean,
    ): Boolean {
        return when {
            !showLowPriority -> false
            !showAmbient && areAllChildrenSuppressed(model.children) -> false
            else -> true
        }
    }

    private fun areAllChildrenSuppressed(children: List<ActiveNotificationEntryModel>): Boolean {
        return children.none {
            (it is ActiveNotificationModel && !it.isSuppressedFromStatusBar) ||
                (it is ActiveNotificationGroupModel && !areAllChildrenSuppressed(it.children))
        }
    }

    private fun shouldShowNotificationIcon(
        model: ActiveNotificationModel,
        forceShowHeadsUp: Boolean,
        showAmbient: Boolean,
        showLowPriority: Boolean,
        showDismissed: Boolean,
        showRepliedMessages: Boolean,
        showPulsing: Boolean,
        isolatedNotifKey: String?,
        aodPromotedKeyToHide: String?,
        notifsFullyHidden: Boolean,
    ): Boolean {
        return when {
            forceShowHeadsUp && model.key == isolatedNotifKey -> true
            !showAmbient && model.isAmbient -> false
            !showLowPriority && model.isSilent -> false
            !showDismissed && model.isRowDismissed -> false
            !showRepliedMessages && model.isLastMessageFromReply -> false
            !showAmbient && model.isSuppressedFromStatusBar -> false
            !showPulsing && model.isPulsing && !notifsFullyHidden -> false
            model.key == aodPromotedKeyToHide -> false
            bubbles.getOrNull()?.isBubbleExpanded(model.key) == true -> false
            else -> true
        }
    }

    private fun ActiveBundleModel.toIconModel(): ActiveNotificationIconModel =
        ActiveNotificationIconModel(
            notifKey = key,
            groupKey = key,
            shelfIcon = icon,
            statusBarIcon = icon,
            aodIcon = icon,
            isAmbient = false,
        )
}

private fun ActiveNotificationModel.toIconModel(): ActiveNotificationIconModel? =
    groupKey?.let {
        ActiveNotificationIconModel(
            notifKey = key,
            groupKey = groupKey,
            shelfIcon = shelfIcon,
            statusBarIcon = statusBarIcon,
            aodIcon = aodIcon,
            isAmbient = isAmbient,
        )
    }

/**
 * Model for an individual notification icon. This can be associated with an individual top-level
 * notification, a group of notifications, or a bundle.
 */
data class ActiveNotificationIconModel(
    /** Key of notification pipeline entry associated with this icon. */
    val notifKey: String,
    /** Group key associated with this icon. */
    val groupKey: String,
    /** Icon to display in the notification shelf. */
    val shelfIcon: Icon?,
    /** Icon to display in the status bar. */
    val statusBarIcon: Icon?,
    /** Icon to display on AOD. */
    val aodIcon: Icon?,
    /** Is the associated pipeline entry in the ambient / minimized section (lowest priority)? */
    val isAmbient: Boolean,
)

/** Domain logic related to notification icons shown on the always-on display. */
class AlwaysOnDisplayNotificationIconsInteractor
@Inject
constructor(
    @Background bgContext: CoroutineContext,
    deviceEntryBypassInteractor: DeviceEntryBypassInteractor,
    iconsInteractor: NotificationIconsInteractor,
) {
    val aodNotifs: Flow<Set<ActiveNotificationIconModel>> =
        deviceEntryBypassInteractor.isBypassEnabled
            .flatMapLatest { isBypassEnabled ->
                iconsInteractor.filteredNotifSet(
                    showAmbient = false,
                    showDismissed = false,
                    showRepliedMessages = false,
                    showPulsing = !isBypassEnabled,
                    showAodPromoted = false,
                )
            }
            .flowOn(bgContext)
}

/** Domain logic related to notification icons shown in the status bar. */
class StatusBarNotificationIconsInteractor
@Inject
constructor(
    @Background bgContext: CoroutineContext,
    iconsInteractor: NotificationIconsInteractor,
    settingsRepository: NotificationListenerSettingsRepository,
) {
    val statusBarNotifs: Flow<Set<ActiveNotificationIconModel>> =
        settingsRepository.showSilentStatusIcons
            .flatMapLatest { showSilentIcons ->
                iconsInteractor.filteredNotifSet(
                    forceShowHeadsUp = true,
                    showAmbient = false,
                    showLowPriority = showSilentIcons,
                    showDismissed = false,
                    showRepliedMessages = false,
                )
            }
            .map { notifs ->
                notifs
                    .filter { it.statusBarIcon != null }
                    .distinctBy { it.statusBarIcon!!.toString() }
                    .toSet()
            }
            .flowOn(bgContext)
}
