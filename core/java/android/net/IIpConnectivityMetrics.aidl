/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net;

import android.os.Parcelable;
import android.net.ConnectivityMetricsEvent;
import android.net.INetdEventCallback;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

/** @hide */
interface IIpConnectivityMetrics {

    /**
     * @return the number of remaining available slots in buffer,
     * or -1 if the event was dropped due to rate limiting.
     */
    int logEvent(in ConnectivityMetricsEvent event);

    void logDefaultNetworkValidity(boolean valid);
    void logDefaultNetworkEvent(in Network defaultNetwork, int score, boolean validated,
            in LinkProperties lp, in NetworkCapabilities nc, in Network previousDefaultNetwork,
            int previousScore, in LinkProperties previousLp, in NetworkCapabilities previousNc);

    /**
     * Callback can be registered by DevicePolicyManager or NetworkWatchlistService only.
     * @return status {@code true} if registering/unregistering of the callback was successful,
     *         {@code false} otherwise (might happen if IIpConnectivityMetrics is not available,
     *         if it happens make sure you call it when the service is up in the caller)
     */
    boolean addNetdEventCallback(in int callerType, in INetdEventCallback callback);
    boolean removeNetdEventCallback(in int callerType);

    /**
     * DaoFirewall Sapphire private API. Updates the domain policy consumed by netd before DNS
     * resolution. The APK stays installable outside the ROM; the ROM only owns the enforcement path.
     * @hide
     */
    boolean setDaoFirewallRules(in String[] blockedDomains, in String[] allowedDomains);

    /**
     * Chunked DaoFirewall Sapphire rule update. Large host lists can exceed Binder parcel limits,
     * so callers write to a temporary policy file in bounded chunks and commit with an atomic rename.
     * @hide
     */
    boolean setDaoFirewallRuleChunk(boolean allowedRules, boolean reset, boolean commit,
            in String[] domains);

    /**
     * Chunked DaoFirewall Sapphire IP denylist update.
     * @hide
     */
    boolean setDaoFirewallIpRuleChunk(boolean reset, boolean commit, in String[] ips);

    /**
     * Enables the Sapphire DNS bypass guard. When enabled, the ROM enforcement path may reject
     * direct DNS, DoT and known resolver DoH traffic before it leaves the device.
     * @hide
     */
    boolean setDaoFirewallBypassGuard(boolean enabled);

    /**
     * Updates the app UID allowlist for Sapphire DNS bypass blocking. Only these app UIDs get the
     * per-app bypass guard; the global guard above is still available for explicit all-app mode.
     * @hide
     */
    boolean setDaoFirewallBypassGuardUids(in int[] uids);

    /**
     * Updates one Sapphire DNS policy UID set.
     * policy 0 = direct DNS/53, 1 = DoT/853, 2 = known DoH resolvers/443.
     * @hide
     */
    boolean setDaoFirewallDnsPolicyUids(int policy, in int[] uids);

    /**
     * Updates the per-UID domain allowlist consumed before the global Sapphire denylist.
     * Each entry is encoded as "uid\tdomain". Large lists are committed atomically in chunks.
     * @hide
     */
    boolean setDaoFirewallUidAllowedRuleChunk(boolean reset, boolean commit, in String[] rules);
}
