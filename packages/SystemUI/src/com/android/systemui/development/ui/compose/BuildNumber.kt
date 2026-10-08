/*
 * Copyright (C) 2025-2026 crDroid Android Project
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

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener
import android.text.format.Formatter
import android.util.Log
import androidx.annotation.WorkerThread
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.settingslib.net.DataUsageController
import com.android.systemui.development.ui.viewmodel.BuildNumberViewModel
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "QSDataUsage"

private const val WINDOW_DAILY = 0
private const val WINDOW_WEEKLY = 1

private const val MIN_PASSIVE_REFRESH_INTERVAL_MS = 10_000L

/** Periodic refresh while the readout is actually shown, so the number does not go stale. */
private const val PERIODIC_REFRESH_MS = 60_000L

@OptIn(ExperimentalCoroutinesApi::class)
private val usageDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/**
 * BuildNumber composable replaced with a data usage readout
 */
@Composable
fun BuildNumber(
    @Suppress("UNUSED_PARAMETER") viewModelFactory: BuildNumberViewModel.Factory? = null,
    @Suppress("UNUSED_PARAMETER") viewModel: BuildNumberViewModel? = null,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var usageText by remember { mutableStateOf<String?>(null) }
    val subMgr = remember { SubscriptionManager.from(context) }
    val usageQuery = remember { DataUsageQuery(context, subMgr) }
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
        scope.launch(Dispatchers.IO) {
            Settings.System.putIntForUser(
                cr, Settings.System.QS_SHOW_DATA_USAGE_WINDOW, newVal, UserHandle.USER_CURRENT
            )
        }
    }

    LaunchedEffect(showDataUsage) {
        if (!showDataUsage) {
            usageText = ""   // keep stable Text node
            return@LaunchedEffect
        }

        launch {
            snapshotFlow { usageWindow }.collect { usageQuery.request(immediate = true) }
        }

        launch {
            while (true) {
                delay(PERIODIC_REFRESH_MS)
                if (view.isShown) usageQuery.request()
            }
        }

        var lastQueryAt = 0L
        while (true) {
            usageQuery.requests.receive()
            if (!usageQuery.consumeImmediate()) {
                val wait = lastQueryAt + MIN_PASSIVE_REFRESH_INTERVAL_MS -
                    SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait)
            }
            val weekly = usageWindow == WINDOW_WEEKLY
            val text = withContext(usageDispatcher) { usageQuery.query(weekly) }
            lastQueryAt = SystemClock.elapsedRealtime()
            if (text != null) usageText = text
        }
    }

    DisposableEffect(showDataUsage) {
        if (!showDataUsage) {
            onDispose { }
        } else {
            val cm = context.getSystemService(ConnectivityManager::class.java)

            val netCb = object : NetworkCallback() {
                private var lastKey: NetKey? = null

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val key = NetKey(
                        network = network,
                        wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                        cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                        validated = caps.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED
                        ),
                    )
                    if (key != lastKey) {
                        lastKey = key
                        usageQuery.request()
                    }
                }

                override fun onLost(network: Network) {
                    lastKey = null
                    usageQuery.request()
                }
            }
            cm.registerDefaultNetworkCallback(netCb)

            val dataSubObserver = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    // Default data SIM changed: follow it again (resolved in background).
                    usageQuery.followDefaultDataSub()
                }
            }
            val dataSubUri =
                Settings.Global.getUriFor(Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION)
            cr.registerContentObserver(dataSubUri, false, dataSubObserver, UserHandle.USER_ALL)

            val subListener = object : OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() {
                    // Validity of the displayed sub is re-checked in the background query.
                    usageQuery.request()
                }
            }
            subMgr.addOnSubscriptionsChangedListener(context.mainExecutor, subListener)

            val windowObserver = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute {
                        // snapshotFlow on usageWindow triggers the refresh if it changed.
                        usageWindow = Settings.System.getIntForUser(
                            cr,
                            Settings.System.QS_SHOW_DATA_USAGE_WINDOW,
                            WINDOW_DAILY,
                            UserHandle.USER_CURRENT
                        )
                    }
                }
            }
            val windowUri = Settings.System.getUriFor(Settings.System.QS_SHOW_DATA_USAGE_WINDOW)
            cr.registerContentObserver(windowUri, false, windowObserver, UserHandle.USER_ALL)

            onDispose {
                cm.unregisterNetworkCallback(netCb)
                cr.unregisterContentObserver(dataSubObserver)
                cr.unregisterContentObserver(windowObserver)
                subMgr.removeOnSubscriptionsChangedListener(subListener)
            }
        }
    }

    val textToShow = if (showDataUsage) usageText.orEmpty() else ""
    val currentText by rememberUpdatedState(textToShow)

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
                    if (currentText.isNotEmpty()) {
                        val next = if (usageWindow == WINDOW_DAILY) WINDOW_WEEKLY else WINDOW_DAILY
                        setUsageWindow(next) 
                    }
                },
                onDoubleTap = {
                    if (currentText.isNotEmpty()) {
                        usageQuery.cycleSub()
                    }
                },
                onLongPress = {
                    if (currentText.isNotEmpty()) {
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

private data class NetKey(
    val network: Network,
    val wifi: Boolean,
    val cellular: Boolean,
    val validated: Boolean,
)

private class DataUsageQuery(
    private val context: Context,
    private val subMgr: SubscriptionManager,
) {
    private val duc = DataUsageController(context)
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wm = context.getSystemService(WifiManager::class.java)

    val requests = Channel<Unit>(Channel.CONFLATED)

    private val immediatePending = AtomicBoolean(false)
    private val followDefaultPending = AtomicBoolean(false)
    private val cycleSubPending = AtomicBoolean(false)

    private var subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID

    fun request(immediate: Boolean = false) {
        if (immediate) immediatePending.set(true)
        requests.trySend(Unit)
    }

    fun followDefaultDataSub() {
        followDefaultPending.set(true)
        request()
    }

    fun cycleSub() {
        cycleSubPending.set(true)
        request(immediate = true)
    }

    fun consumeImmediate(): Boolean = immediatePending.getAndSet(false)

    @WorkerThread
    fun query(weekly: Boolean): String? {
        return try {
            val activeSubs = subMgr.activeSubscriptionInfoList.orEmpty()
            resolveSubId(activeSubs)

            if (isWifiConnected()) {
                val info = if (weekly)
                    duc.getWifiWeeklyDataUsageInfo(true) ?: duc.getWifiWeeklyDataUsageInfo(false)
                else
                    duc.getWifiDailyDataUsageInfo(true) ?: duc.getWifiDailyDataUsageInfo(false)
                info?.let { formatDataUsage(it.usageLevel, ssidWithTruncation(), weekly) }
            } else if (activeSubs.isNotEmpty()) {
                duc.setSubscriptionId(subId)
                val info =
                    if (weekly) duc.getWeeklyDataUsageInfo() else duc.getDailyDataUsageInfo()
                info?.let {
                    val suffix = it.carrier?.takeIf { c -> c.isNotBlank() }
                        ?: fallbackCarrierName(activeSubs, subId)
                    formatDataUsage(it.usageLevel, suffix, weekly)
                }
            } else {
                null
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to query data usage", e)
            null
        }
    }

    @WorkerThread
    private fun resolveSubId(activeSubs: List<SubscriptionInfo>) {
        if (followDefaultPending.getAndSet(false)) {
            subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        if (!SubscriptionManager.isValidSubscriptionId(subId) ||
            activeSubs.none { it.subscriptionId == subId }
        ) {
            subId = currentDataSubId(context, subMgr)
        }
        if (cycleSubPending.getAndSet(false) && activeSubs.size > 1) {
            val ids = activeSubs.sortedBy { it.simSlotIndex }.map { it.subscriptionId }
            val idx = ids.indexOf(subId).let { if (it < 0) 0 else it }
            subId = ids[(idx + 1) % ids.size]
        }
    }

    private fun wifiSsidOrNull(): String? {
        val raw = wm.connectionInfo?.ssid ?: return null
        val ssid = raw.replace("\"", "")
        return when {
            ssid.isEmpty() -> null
            ssid.equals("<unknown ssid>", ignoreCase = true) -> null
            ssid.equals("<unknown>", ignoreCase = true) -> null
            else -> ssid
        }
    }

    private fun ssidWithTruncation(): String {
        val ssid = wifiSsidOrNull() ?: return context.getString(R.string.usage_wifi_default_suffix)
        return if (ssid.length > 10) ssid.substring(0, 7) + "..." else ssid
    }

    private fun isWifiConnected(): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        val validatedWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return validatedWifi && wifiSsidOrNull() != null
    }

    private fun fallbackCarrierName(activeSubs: List<SubscriptionInfo>, subId: Int): String {
        activeSubs.firstOrNull { it.subscriptionId == subId }
            ?.displayName?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        activeSubs.firstOrNull()
            ?.displayName?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return context.getString(R.string.usage_data_default_suffix)
    }

    private fun formatDataUsage(bytes: Long, suffix: String, weekly: Boolean): String {
        val labelId = if (weekly) R.string.usage_data_weekly else R.string.usage_data
        return StringBuilder(Formatter.formatFileSize(context, bytes, Formatter.FLAG_IEC_UNITS))
            .append(" ")
            .append(context.getString(labelId))
            .append(" (")
            .append(suffix)
            .append(")")
            .toString()
    }
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

@WorkerThread
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
