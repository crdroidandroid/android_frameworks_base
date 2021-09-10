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

package com.android.systemui.development.ui.compose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.UserHandle
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener
import android.text.format.Formatter
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import com.android.settingslib.net.DataUsageController
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R

private const val WINDOW_DAILY = 0
private const val WINDOW_WEEKLY = 1

/**
 * BuildNumber composable replaced with a data usage readout
 */
@Composable
fun BuildNumber(
    @Suppress("UNUSED_PARAMETER") viewModelFactory: Any? = null,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    var usageText by remember { mutableStateOf<String?>(null) }
    val subMgr = remember { SubscriptionManager.from(context) }
    val duc = remember { DataUsageController(context) }
    val cr = context.contentResolver

    var showDataUsage by remember {
        mutableStateOf(
            try {
                Settings.System.getIntForUser(
                    cr,
                    Settings.System.QS_SHOW_DATA_USAGE,
                    0,
                    UserHandle.USER_CURRENT
                ) != 0
            } catch (_: Throwable) {
                false
            }
        )
    }

    DisposableEffect(Unit) {
        val usageToggleObserver = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    showDataUsage = try {
                        Settings.System.getIntForUser(
                            cr,
                            Settings.System.QS_SHOW_DATA_USAGE,
                            0,
                            UserHandle.USER_CURRENT
                        ) != 0
                    } catch (_: Throwable) {
                        false
                    }
                }
            }
        }

        val toggleUri = Settings.System.getUriFor(Settings.System.QS_SHOW_DATA_USAGE)
        cr.registerContentObserver(toggleUri, false, usageToggleObserver, UserHandle.USER_ALL)

        onDispose {
            cr.unregisterContentObserver(usageToggleObserver)
        }
    }

    var usageWindow by rememberSaveable {
        mutableIntStateOf(
            Settings.System.getIntForUser(
                cr, Settings.System.QS_SHOW_DATA_USAGE_WINDOW, WINDOW_DAILY, UserHandle.USER_CURRENT
            )
        )
    }

    fun setUsageWindow(newVal: Int) {
        usageWindow = newVal
        Settings.System.putIntForUser(
            cr, Settings.System.QS_SHOW_DATA_USAGE_WINDOW, newVal, UserHandle.USER_CURRENT
        )
    }

    var displaySubId by remember { mutableIntStateOf(currentDataSubId(context, subMgr)) }

    fun wifiSsidOrNull(wm: WifiManager): String? {
        val raw = wm.connectionInfo?.ssid ?: return null
        val ssid = raw.replace("\"", "")
        return when {
            ssid.isEmpty() -> null
            ssid.equals("<unknown ssid>", ignoreCase = true) -> null
            ssid.equals("<unknown>", ignoreCase = true) -> null
            else -> ssid
        }
    }

    fun ssidWithTruncation(wm: WifiManager): String {
        val ssid = wifiSsidOrNull(wm) ?: return context.getString(R.string.usage_wifi_default_suffix)
        return if (ssid.length > 10) ssid.substring(0, 7) + "..." else ssid
    }

    fun isWifiConnected(cm: ConnectivityManager, wm: WifiManager): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        val validatedWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return validatedWifi && wifiSsidOrNull(wm) != null
    }

    fun fallbackCarrierName(subMgr: SubscriptionManager, subId: Int): String {
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            val subInfo: SubscriptionInfo? = subMgr.getActiveSubscriptionInfo(subId)
            if (subInfo != null) {
                val name = subInfo.displayName?.toString()
                if (!name.isNullOrBlank()) return name
            }
        }
        val list = subMgr.activeSubscriptionInfoList
        if (!list.isNullOrEmpty()) {
            val name = list[0].displayName?.toString()
            if (!name.isNullOrBlank()) return name
        }
        return context.getString(R.string.usage_data_default_suffix)
    }

    fun formatDataUsage(bytes: Long, suffix: String, weekly: Boolean): String {
        // Example: "1.23 GB used today (airtel)"
        val labelId = if (weekly) R.string.usage_data_weekly else R.string.usage_data
        return StringBuilder(Formatter.formatFileSize(context, bytes, Formatter.FLAG_IEC_UNITS))
            .append(" ")
            .append(context.getString(labelId))
            .append(" (")
            .append(suffix)
            .append(")")
            .toString()
    }

    fun updateUsage() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifi = isWifiConnected(cm, wm)
        val hasSims = subMgr.activeSubscriptionInfoCount > 0
        val weekly = (usageWindow == WINDOW_WEEKLY)

        if (wifi) {
            val info = if (weekly)
                duc.getWifiWeeklyDataUsageInfo(true) ?: duc.getWifiWeeklyDataUsageInfo(false)
            else
                duc.getWifiDailyDataUsageInfo(true) ?: duc.getWifiDailyDataUsageInfo(false)

            val suffix = ssidWithTruncation(wm)
            if (info != null) {
                usageText = formatDataUsage(info.usageLevel, suffix, weekly)
            }
        } else if (hasSims) {
            val subId = displaySubId.takeIf { SubscriptionManager.isValidSubscriptionId(it) }
                ?: currentDataSubId(context, subMgr)
            displaySubId = subId
            duc.setSubscriptionId(subId)
            val info = if (weekly) duc.getWeeklyDataUsageInfo() else duc.getDailyDataUsageInfo()
            val suffix = (info?.carrier?.takeIf { !it.isNullOrBlank() })
                ?: fallbackCarrierName(subMgr, subId)
            if (info != null) {
                usageText = formatDataUsage(info.usageLevel, suffix, weekly)
            }
        } else {
            // Radios off / no SIM: keep last known text
        }
    }

    LaunchedEffect(showDataUsage, usageWindow) {
        if (showDataUsage) updateUsage() else usageText = ""   // keep stable Text node
    }

    val latestUpdate by rememberUpdatedState(newValue = { updateUsage() })

    DisposableEffect(showDataUsage) {
        if (!showDataUsage) {
            onDispose { }
        } else {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

            val wifiFilter = IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.RSSI_CHANGED_ACTION)
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) { updateUsage() }
            }
            context.registerReceiver(receiver, wifiFilter, Context.RECEIVER_NOT_EXPORTED)

            val netCb = object : NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    latestUpdate()
                }
                override fun onLost(network: Network) {
                    latestUpdate()
                }
            }
            cm.registerDefaultNetworkCallback(netCb)

            val settingsObserver = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute {
                        displaySubId = currentDataSubId(context, subMgr)
                        latestUpdate()
                    }
                }
            }

            val uri = Settings.Global.getUriFor(Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION)
            context.contentResolver.registerContentObserver(uri, false, settingsObserver, UserHandle.USER_ALL)

            val subListener = object : OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() {
                    if (!SubscriptionManager.isValidSubscriptionId(displaySubId) ||
                        subMgr.getActiveSubscriptionInfo(displaySubId) == null
                    ) {
                        displaySubId = currentDataSubId(context, subMgr)
                    }
                    updateUsage()
                }
            }
            subMgr.addOnSubscriptionsChangedListener(context.mainExecutor, subListener)

            val windowObserver = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute {
                        usageWindow = Settings.System.getIntForUser(
                            cr,
                            Settings.System.QS_SHOW_DATA_USAGE_WINDOW,
                            WINDOW_DAILY,
                            UserHandle.USER_CURRENT
                        )
                        latestUpdate()
                    }
                }
            }

            val windowUri = Settings.System.getUriFor(Settings.System.QS_SHOW_DATA_USAGE_WINDOW)
            context.contentResolver.registerContentObserver(windowUri, false, windowObserver, UserHandle.USER_ALL)

            onDispose {
                context.unregisterReceiver(receiver)
                cm.unregisterNetworkCallback(netCb)
                context.contentResolver.unregisterContentObserver(settingsObserver)
                context.contentResolver.unregisterContentObserver(windowObserver)
                subMgr.removeOnSubscriptionsChangedListener(subListener)
            }
        }
    }

    val textToShow = if (showDataUsage) usageText.orEmpty() else ""

    val base = modifier
        .borderOnFocus(
            color = MaterialTheme.colorScheme.secondary,
            cornerSize = CornerSize(1.dp),
        )
        .focusable()
        .wrapContentWidth()
        .minimumInteractiveComponentSize()
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = {
                    if (!textToShow.isNullOrEmpty()) {
                        val next = if (usageWindow == WINDOW_DAILY) WINDOW_WEEKLY else WINDOW_DAILY
                        setUsageWindow(next)
                        updateUsage()
                    }
                },
                onDoubleTap = {
                    if (!textToShow.isNullOrEmpty()) {
                        val list = subMgr.activeSubscriptionInfoList
                        if (!list.isNullOrEmpty() && list.size > 1) {
                            val ids = list.sortedBy { it.simSlotIndex }.map { it.subscriptionId }
                            val idx = ids.indexOf(displaySubId).let { if (it < 0) 0 else it }
                            displaySubId = ids[(idx + 1) % ids.size]
                            updateUsage()
                        }
                    }
                },
                onLongPress = {
                    if (textToShow.isNotEmpty()) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        openDataUsageSettings(context)
                    }
                }
            )
        }
        .semantics {
            onLongClick("Open data usage settings") {
                if (textToShow.isNotEmpty()) {
                    openDataUsageSettings(context)
                    true
                } else false
            }
        }

    val marquee = if (textToShow.isNotEmpty()) {
        base.basicMarquee(iterations = 1, initialDelayMillis = 2000)
    } else {
        base
    }

    Text(
        text = textToShow,
        style = MaterialTheme.typography.bodySmall,
        modifier = marquee.alpha(if (textToShow.isNotEmpty()) 1f else 0f),
        color = textColor,
        maxLines = 1,
    )
}

fun openDataUsageSettings(context: Context) {
    val intent = Intent(Settings.ACTION_DATA_USAGE_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivityAsUser(intent, UserHandle.CURRENT)
    } catch (_: Throwable) {
        val fallback = Intent(Intent.ACTION_MAIN).apply {
            setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$DataUsageSummaryActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivityAsUser(fallback, UserHandle.CURRENT)
    }
}

private fun currentDataSubId(context: Context, subMgr: SubscriptionManager): Int {
    val fromSettings = Settings.Global.getInt(
        context.contentResolver,
        Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION,
        SubscriptionManager.INVALID_SUBSCRIPTION_ID
    )
    if (SubscriptionManager.isValidSubscriptionId(fromSettings)) {
        return fromSettings
    }
    val fallback = SubscriptionManager.getDefaultDataSubscriptionId()
    if (SubscriptionManager.isValidSubscriptionId(fallback)) {
        return fallback
    }
    val active = subMgr.activeSubscriptionInfoList
    return if (!active.isNullOrEmpty()) active[0].subscriptionId
    else SubscriptionManager.INVALID_SUBSCRIPTION_ID
}
