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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener
import android.text.format.Formatter
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import com.android.settingslib.net.DataUsageController
import com.android.systemui.Dependency
import com.android.systemui.res.R
import com.android.systemui.tuner.TunerService

/**
 * BuildNumber composable replaced with a data usage readout
 */
@Composable
fun BuildNumber(
    @Suppress("UNUSED_PARAMETER") viewModelFactory: Any? = null,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val QS_SHOW_DATA_USAGE =
        "system:" + Settings.System.QS_SHOW_DATA_USAGE

    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val copyA11y = stringResource(id = R.string.copy_to_clipboard_a11y_action)

    var usageText by remember { mutableStateOf<String?>(null) }
    val subMgr = remember { SubscriptionManager.from(context) }
    val duc = remember { DataUsageController(context) }

    val tunerService = Dependency.get(TunerService::class.java)

    val showDataUsage = remember {
        mutableStateOf(
            TunerService.parseIntegerSwitch(
                tunerService.getValue(QS_SHOW_DATA_USAGE),
                false
            )
        )
    }

    DisposableEffect(Unit) {
        val tunable = TunerService.Tunable { k, newValue ->
            if (k == QS_SHOW_DATA_USAGE) {
                showDataUsage.value = TunerService.parseIntegerSwitch(newValue, false)
            }
        }
        tunerService.addTunable(tunable, QS_SHOW_DATA_USAGE)
        onDispose { tunerService.removeTunable(tunable) }
    }

    var displaySubId by remember { mutableIntStateOf(currentDataSubId(context, subMgr)) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

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

    fun formatDataUsage(bytes: Long, suffix: String): String {
        // Example: "1.23 GB used today (airtel)"
        return StringBuilder(
            Formatter.formatFileSize(context, bytes, Formatter.FLAG_IEC_UNITS)
        )
            .append(" ")
            .append(context.getString(R.string.usage_data))
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

        if (wifi) {
            val info = duc.getWifiDailyDataUsageInfo(true) ?: duc.getWifiDailyDataUsageInfo(false)
            val suffix = ssidWithTruncation(wm)
            if (info != null) {
                usageText = formatDataUsage(info.usageLevel, suffix)
            }
        } else if (hasSims) {
            val subId = displaySubId.takeIf { SubscriptionManager.isValidSubscriptionId(it) }
                ?: currentDataSubId(context, subMgr)
            displaySubId = subId
            duc.setSubscriptionId(subId)
            val info = duc.getDailyDataUsageInfo()
            val suffix = (info?.carrier?.takeIf { !it.isNullOrBlank() })
                ?: fallbackCarrierName(subMgr, subId)
            if (info != null) {
                usageText = formatDataUsage(info.usageLevel, suffix)
            }
        } else {
            // Radios off / no SIM: keep last known text
        }
    }

    val updateRunnable = remember { Runnable { updateUsage() } }

    fun scheduleUpdateUsage() {
        mainHandler.removeCallbacks(updateRunnable)
        mainHandler.postDelayed(updateRunnable, 300)
    }

    LaunchedEffect(showDataUsage.value) { if (showDataUsage.value) updateUsage() else { usageText = null } }

    val latestUpdate by rememberUpdatedState(newValue = { updateUsage() })

    DisposableEffect(showDataUsage.value) {
        if (!showDataUsage.value) {
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
                override fun onReceive(context: Context?, intent: Intent?) { scheduleUpdateUsage() }
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

            val settingsObserver = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    displaySubId = currentDataSubId(context, subMgr)
                    latestUpdate()
                }
            }

            val uri = Settings.Global.getUriFor(Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION)
            context.contentResolver.registerContentObserver(uri, false, settingsObserver)

            val subListener = object : OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() {
                    if (!SubscriptionManager.isValidSubscriptionId(displaySubId) ||
                        subMgr.getActiveSubscriptionInfo(displaySubId) == null
                    ) {
                        displaySubId = currentDataSubId(context, subMgr)
                    }
                    scheduleUpdateUsage()
                }
            }
            subMgr.addOnSubscriptionsChangedListener(context.mainExecutor, subListener)

            onDispose {
                context.unregisterReceiver(receiver)
                cm.unregisterNetworkCallback(netCb)
                context.contentResolver.unregisterContentObserver(settingsObserver)
                subMgr.removeOnSubscriptionsChangedListener(subListener)
                mainHandler.removeCallbacksAndMessages(null)
            }
        }
    }

    val text = usageText
    if (showDataUsage.value && text != null) {
        Text(
            text = text,
            modifier =
                modifier
                    .focusable()
                    .wrapContentWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                val list = subMgr.activeSubscriptionInfoList
                                if (!list.isNullOrEmpty() && list.size > 1) {
                                    val ids = list.sortedBy { it.simSlotIndex }.map { it.subscriptionId }
                                    val idx = ids.indexOf(displaySubId).let { if (it < 0) 0 else it }
                                    displaySubId = ids[(idx + 1) % ids.size]
                                    updateUsage()
                                }
                            },
                            onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                clipboard.setText(AnnotatedString(text))
                            }
                        )
                    }
                    .semantics {
                        onLongClick(copyA11y) {
                            clipboard.setText(AnnotatedString(text))
                            true
                        }
                    }
                    .basicMarquee(iterations = 1, initialDelayMillis = 2000)
                    .minimumInteractiveComponentSize(),
            color = textColor,
            maxLines = 1,
        )
    } else {
        Spacer(modifier)
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
