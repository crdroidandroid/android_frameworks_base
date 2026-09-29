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

package com.android.server.connectivity;

import android.content.Context;
import android.net.ConnectivityMetricsEvent;
import android.net.IIpConnectivityMetrics;
import android.net.INetdEventCallback;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkStack;
import android.net.metrics.ApfProgramEvent;
import android.net.metrics.IpConnectivityLog;
import android.os.Binder;
import android.os.Process;
import android.os.SELinux;
import android.os.SystemClock;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.Base64;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.RingBuffer;
import com.android.internal.util.TokenBucket;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityEvent;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * Event buffering service for core networking and connectivity metrics.
 *
 * @hide
 */
final public class IpConnectivityMetrics extends SystemService {
    private static final String TAG = IpConnectivityMetrics.class.getSimpleName();
    private static final boolean DBG = false;

    // The logical version numbers of ipconnectivity.proto, corresponding to the
    // "version" field of IpConnectivityLog.
    private static final int NYC      = 0;
    private static final int NYC_MR1  = 1;
    private static final int NYC_MR2  = 2;
    public static final int VERSION   = NYC_MR2;

    private static final String SERVICE_NAME = IpConnectivityLog.SERVICE_NAME;

    // Default size of the event rolling log for bug report dumps.
    private static final int DEFAULT_LOG_SIZE = 500;
    // Default size of the event buffer for metrics reporting.
    // Once the buffer is full, incoming events are dropped.
    private static final int DEFAULT_BUFFER_SIZE = 2000;
    // Maximum size of the event buffer.
    private static final int MAXIMUM_BUFFER_SIZE = DEFAULT_BUFFER_SIZE * 10;

    private static final int MAXIMUM_CONNECT_LATENCY_RECORDS = 20000;
    private static final String DAOFIREWALL_PACKAGE = "org.daofirewall";
    private static final File DAOFIREWALL_RULE_DIR = new File("/data/system/daofirewall");
    private static final File DAOFIREWALL_BLOCK_FILE = new File(DAOFIREWALL_RULE_DIR, "blocked");
    private static final File DAOFIREWALL_ALLOW_FILE = new File(DAOFIREWALL_RULE_DIR, "allowed");
    private static final File DAOFIREWALL_BLOCK_IP_FILE = new File(DAOFIREWALL_RULE_DIR, "blocked_ips");
    private static final File DAOFIREWALL_BYPASS_GUARD_FILE =
            new File(DAOFIREWALL_RULE_DIR, "bypass_guard");
    private static final File DAOFIREWALL_BYPASS_GUARD_UIDS_FILE =
            new File(DAOFIREWALL_RULE_DIR, "bypass_guard_uids");
    private static final File DAOFIREWALL_DIRECT_DNS_UIDS_FILE =
            new File(DAOFIREWALL_RULE_DIR, "direct_dns_uids");
    private static final File DAOFIREWALL_DOT_UIDS_FILE =
            new File(DAOFIREWALL_RULE_DIR, "dot_uids");
    private static final File DAOFIREWALL_DOH_UIDS_FILE =
            new File(DAOFIREWALL_RULE_DIR, "doh_uids");
    private static final File DAOFIREWALL_UID_ALLOWED_DOMAINS_FILE =
            new File(DAOFIREWALL_RULE_DIR, "uid_allowed_domains");
    private static final int DAOFIREWALL_MAX_RULES = 500_000;

    private static final int ERROR_RATE_LIMITED = -1;

    // Lock ensuring that concurrent manipulations of the event buffers are correct.
    // There are three concurrent operations to synchronize:
    //  - appending events to the buffer.
    //  - iterating throught the buffer.
    //  - flushing the buffer content and replacing it by a new buffer.
    private final Object mLock = new Object();

    // Implementation instance of IIpConnectivityMetrics.aidl.
    @VisibleForTesting
    public final Impl impl = new Impl();
    // Subservice listening to Netd events via INetdEventListener.aidl.
    @VisibleForTesting
    NetdEventListenerService mNetdListener;

    // Rolling log of the most recent events. This log is used for dumping
    // connectivity events in bug reports.
    @GuardedBy("mLock")
    private final RingBuffer<ConnectivityMetricsEvent> mEventLog =
            new RingBuffer(ConnectivityMetricsEvent.class, DEFAULT_LOG_SIZE);
    // Buffer of connectivity events used for metrics reporting. This buffer
    // does not rotate automatically and instead saturates when it becomes full.
    // It is flushed at metrics reporting.
    @GuardedBy("mLock")
    private ArrayList<ConnectivityMetricsEvent> mBuffer;
    // Total number of events dropped from mBuffer since last metrics reporting.
    @GuardedBy("mLock")
    private int mDropped;
    // Capacity of mBuffer
    @GuardedBy("mLock")
    private int mCapacity;
    // A list of rate limiting counters keyed by connectivity event types for
    // metrics reporting mBuffer.
    @GuardedBy("mLock")
    private final ArrayMap<Class<?>, TokenBucket> mBuckets = makeRateLimitingBuckets();

    private final ToIntFunction<Context> mCapacityGetter;

    @VisibleForTesting
    final DefaultNetworkMetrics mDefaultNetworkMetrics = new DefaultNetworkMetrics();

    public IpConnectivityMetrics(Context ctx, ToIntFunction<Context> capacityGetter) {
        super(ctx);
        mCapacityGetter = capacityGetter;
        initBuffer();
    }

    public IpConnectivityMetrics(Context ctx) {
        this(ctx, READ_BUFFER_SIZE);
    }

    @Override
    public void onStart() {
        if (DBG) Log.d(TAG, "onStart");
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            if (DBG) Log.d(TAG, "onBootPhase");
            mNetdListener = new NetdEventListenerService(getContext());

            publishBinderService(SERVICE_NAME, impl);
            publishBinderService(mNetdListener.SERVICE_NAME, mNetdListener);

            LocalServices.addService(Logger.class, new LoggerImpl());
        }
    }

    @VisibleForTesting
    public int bufferCapacity() {
        return mCapacityGetter.applyAsInt(getContext());
    }

    private void initBuffer() {
        synchronized (mLock) {
            mDropped = 0;
            mCapacity = bufferCapacity();
            mBuffer = new ArrayList<>(mCapacity);
        }
    }

    private int append(ConnectivityMetricsEvent event) {
        if (DBG) Log.d(TAG, "logEvent: " + event);
        synchronized (mLock) {
            mEventLog.append(event);
            final int left = mCapacity - mBuffer.size();
            if (event == null) {
                return left;
            }
            if (isRateLimited(event)) {
                // Do not count as a dropped event. TODO: consider adding separate counter
                return ERROR_RATE_LIMITED;
            }
            if (left == 0) {
                mDropped++;
                return 0;
            }
            mBuffer.add(event);
            return left - 1;
        }
    }

    private boolean isRateLimited(ConnectivityMetricsEvent event) {
        TokenBucket tb = mBuckets.get(event.data.getClass());
        return (tb != null) && !tb.get();
    }

    private String flushEncodedOutput() {
        final ArrayList<ConnectivityMetricsEvent> events;
        final int dropped;
        synchronized (mLock) {
            events = mBuffer;
            dropped = mDropped;
            initBuffer();
        }

        final List<IpConnectivityEvent> protoEvents = IpConnectivityEventBuilder.toProto(events);

        mDefaultNetworkMetrics.flushEvents(protoEvents);

        if (mNetdListener != null) {
            mNetdListener.flushStatistics(protoEvents);
        }

        final byte[] data;
        try {
            data = IpConnectivityEventBuilder.serialize(dropped, protoEvents);
        } catch (IOException e) {
            Log.e(TAG, "could not serialize events", e);
            return "";
        }

        return Base64.encodeToString(data, Base64.DEFAULT);
    }

    /**
     * Clear the event buffer and prints its content as a protobuf serialized byte array
     * inside a base64 encoded string.
     */
    private void cmdFlush(PrintWriter pw) {
        pw.print(flushEncodedOutput());
    }

    /**
     * Print the content of the rolling event buffer in human readable format.
     * Also print network dns/connect statistics and recent default network events.
     */
    private void cmdList(PrintWriter pw) {
        pw.println("metrics events:");
        final List<ConnectivityMetricsEvent> events = getEvents();
        for (ConnectivityMetricsEvent ev : events) {
            pw.println(ev.toString());
        }
        pw.println("");
        if (mNetdListener != null) {
            mNetdListener.list(pw);
        }
        pw.println("");
        mDefaultNetworkMetrics.listEvents(pw);
    }

    private List<IpConnectivityEvent> listEventsAsProtos() {
        final List<IpConnectivityEvent> events = IpConnectivityEventBuilder.toProto(getEvents());
        if (mNetdListener != null) {
            events.addAll(mNetdListener.listAsProtos());
        }
        events.addAll(mDefaultNetworkMetrics.listEventsAsProto());
        return events;
    }

    /*
     * Print the content of the rolling event buffer in text proto format.
     */
    private void cmdListAsTextProto(PrintWriter pw) {
        listEventsAsProtos().forEach(e -> pw.print(e.toString()));
    }

    /*
     * Write the content of the rolling event buffer in proto wire format to the given OutputStream.
     */
    private void cmdListAsBinaryProto(OutputStream out) {
        final int dropped;
        synchronized (mLock) {
            dropped = mDropped;
        }
        try {
            byte[] data = IpConnectivityEventBuilder.serialize(dropped, listEventsAsProtos());
            out.write(data);
            out.flush();
        } catch (IOException e) {
            Log.e(TAG, "could not serialize events", e);
        }
    }

    /*
     * Return a copy of metrics events stored in buffer for metrics uploading.
     */
    private List<ConnectivityMetricsEvent> getEvents() {
        synchronized (mLock) {
            return Arrays.asList(mEventLog.toArray());
        }
    }

    public final class Impl extends IIpConnectivityMetrics.Stub {
        // Dump and flushes the metrics event buffer in base64 encoded serialized proto output.
        static final String CMD_FLUSH = "flush";
        // Dump the rolling buffer of metrics event in human readable proto text format.
        static final String CMD_PROTO = "proto";
        // Dump the rolling buffer of metrics event in proto wire format. See usage() of
        // frameworks/native/cmds/dumpsys/dumpsys.cpp for details.
        static final String CMD_PROTO_BIN = "--proto";
        // Dump the rolling buffer of metrics event and pretty print events using a human readable
        // format. Also print network dns/connect statistics and default network event time series.
        static final String CMD_LIST = "list";
        // By default any other argument will fall into the default case which is the equivalent
        // of calling both the "list" and "ipclient" commands. This includes most notably bug
        // reports collected by dumpsys.cpp with the "-a" argument.
        static final String CMD_DEFAULT = "";

        @Override
        public int logEvent(ConnectivityMetricsEvent event) {
            NetworkStack.checkNetworkStackPermission(getContext());
            return append(event);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            enforceDumpPermission();
            if (DBG) Log.d(TAG, "dumpsys " + TextUtils.join(" ", args));
            final String cmd = (args.length > 0) ? args[0] : CMD_DEFAULT;
            switch (cmd) {
                case CMD_FLUSH:
                    cmdFlush(pw);
                    return;
                case CMD_PROTO:
                    cmdListAsTextProto(pw);
                    return;
                case CMD_PROTO_BIN:
                    cmdListAsBinaryProto(new FileOutputStream(fd));
                    return;
                case CMD_LIST:
                default:
                    cmdList(pw);
                    return;
            }
        }

        private void enforceDumpPermission() {
            enforcePermission(android.Manifest.permission.DUMP);
        }

        private void enforcePermission(String what) {
            getContext().enforceCallingOrSelfPermission(what, "IpConnectivityMetrics");
        }

        private void enforceNetdEventListeningPermission() {
            final int uid = Binder.getCallingUid();
            if (uid != Process.SYSTEM_UID && !isDaoFirewallCaller(uid)) {
                throw new SecurityException(String.format("Uid %d has no permission to listen for"
                        + " netd events.", uid));
            }
        }

        private boolean isDaoFirewallCaller(int uid) {
            final String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages == null) {
                return false;
            }
            for (String packageName : packages) {
                if (DAOFIREWALL_PACKAGE.equals(packageName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean addNetdEventCallback(int callerType, INetdEventCallback callback) {
            enforceNetdEventListeningPermission();
            if (mNetdListener == null) {
                return false;
            }
            return mNetdListener.addNetdEventCallback(callerType, callback);
        }

        @Override
        public boolean removeNetdEventCallback(int callerType) {
            enforceNetdEventListeningPermission();
            if (mNetdListener == null) {
                // if the service is null, we aren't registered anyway
                return true;
            }
            return mNetdListener.removeNetdEventCallback(callerType);
        }

        @Override
        public boolean setDaoFirewallRules(String[] blockedDomains, String[] allowedDomains) {
            enforceNetdEventListeningPermission();
            try {
                if (!ensureDaoFirewallRuleDir()) {
                    return false;
                }
                writeRuleFile(DAOFIREWALL_BLOCK_FILE, normalizeRules(blockedDomains));
                writeRuleFile(DAOFIREWALL_ALLOW_FILE, normalizeRules(allowedDomains));
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Could not update DaoFirewall rules", e);
                return false;
            }
        }

        @Override
        public boolean setDaoFirewallRuleChunk(boolean allowedRules, boolean reset, boolean commit,
                String[] domains) {
            enforceNetdEventListeningPermission();
            final File target = allowedRules ? DAOFIREWALL_ALLOW_FILE : DAOFIREWALL_BLOCK_FILE;
            try {
                if (!ensureDaoFirewallRuleDir()) {
                    return false;
                }
                writeRuleChunk(target, normalizeRules(domains), reset, commit);
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Could not update DaoFirewall rule chunk", e);
                return false;
            }
        }

        @Override
        public boolean setDaoFirewallIpRuleChunk(boolean reset, boolean commit, String[] ips) {
            enforceNetdEventListeningPermission();
            try {
                if (!ensureDaoFirewallRuleDir()) {
                    return false;
                }
                writeRuleChunk(DAOFIREWALL_BLOCK_IP_FILE, normalizeIps(ips), reset, commit);
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Could not update DaoFirewall IP rule chunk", e);
                return false;
            }
        }

        @Override
        public boolean setDaoFirewallBypassGuard(boolean enabled) {
            enforceNetdEventListeningPermission();
            try {
                if (!ensureDaoFirewallRuleDir()) {
                    return false;
                }
                writeTextFile(DAOFIREWALL_BYPASS_GUARD_FILE, enabled ? "1\n" : "0\n");
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Could not update DaoFirewall bypass guard", e);
                return false;
            }
        }

        @Override
        public boolean setDaoFirewallBypassGuardUids(int[] uids) {
            enforceNetdEventListeningPermission();
            try {
                if (!ensureDaoFirewallRuleDir()) {
                    return false;
                }
                writeRuleFile(DAOFIREWALL_BYPASS_GUARD_UIDS_FILE, normalizeUids(uids));
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Could not update DaoFirewall bypass guard UIDs", e);
                return false;
            }
        }

        @Override
        public boolean setDaoFirewallDnsPolicyUids(int policy, int[] uids) {
            enforceNetdEventListeningPermission();
            final File target;
            switch (policy) {
                case 0:
                    target = DAOFIREWALL_DIRECT_DNS_UIDS_FILE;
                    break;
                case 1:
                    target = DAOFIREWALL_DOT_UIDS_FILE;
                    break;
                case 2:
                    target = DAOFIREWALL_DOH_UIDS_FILE;
                    break;
                default:
                    return false;
            }
            try {
                if (!ensureDaoFirewallRuleDir()) {
                    return false;
                }
                writeRuleFile(target, normalizeUids(uids));
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Could not update DaoFirewall DNS policy UIDs", e);
                return false;
            }
        }

        @Override
        public boolean setDaoFirewallUidAllowedRuleChunk(boolean reset, boolean commit,
                String[] rules) {
            enforceNetdEventListeningPermission();
            try {
                if (!ensureDaoFirewallRuleDir()) {
                    return false;
                }
                writeRuleChunk(DAOFIREWALL_UID_ALLOWED_DOMAINS_FILE,
                        normalizeUidDomainRules(rules), reset, commit);
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Could not update DaoFirewall per-app allowed domains", e);
                return false;
            }
        }

        private boolean ensureDaoFirewallRuleDir() {
            if (!DAOFIREWALL_RULE_DIR.exists() && !DAOFIREWALL_RULE_DIR.mkdirs()) {
                Log.e(TAG, "Could not create DaoFirewall rule directory");
                return false;
            }
            try {
                Os.chmod(DAOFIREWALL_RULE_DIR.getAbsolutePath(), 0770);
            } catch (ErrnoException e) {
                Log.e(TAG, "Could not chmod DaoFirewall rule directory", e);
                return false;
            }
            if (!SELinux.restoreconRecursive(DAOFIREWALL_RULE_DIR)) {
                Log.e(TAG, "Could not restorecon DaoFirewall rule directory");
                return false;
            }
            return true;
        }

        private LinkedHashSet<String> normalizeRules(String[] domains) {
            final LinkedHashSet<String> rules = new LinkedHashSet<>();
            if (domains == null) {
                return rules;
            }
            for (String domain : domains) {
                if (rules.size() >= DAOFIREWALL_MAX_RULES) {
                    break;
                }
                final String normalized = normalizeDomain(domain);
                if (normalized != null) {
                    rules.add(normalized);
                }
            }
            return rules;
        }

        private LinkedHashSet<String> normalizeIps(String[] ips) {
            final LinkedHashSet<String> rules = new LinkedHashSet<>();
            if (ips == null) {
                return rules;
            }
            for (String ip : ips) {
                if (rules.size() >= DAOFIREWALL_MAX_RULES) {
                    break;
                }
                final String normalized = normalizeIp(ip);
                if (normalized != null) {
                    rules.add(normalized);
                }
            }
            return rules;
        }

        private LinkedHashSet<String> normalizeUids(int[] uids) {
            final LinkedHashSet<String> rules = new LinkedHashSet<>();
            if (uids == null) {
                return rules;
            }
            for (int uid : uids) {
                if (rules.size() >= DAOFIREWALL_MAX_RULES) {
                    break;
                }
                if (uid >= 10_000) {
                    rules.add(Integer.toString(uid));
                }
            }
            return rules;
        }

        private LinkedHashSet<String> normalizeUidDomainRules(String[] entries) {
            final LinkedHashSet<String> rules = new LinkedHashSet<>();
            if (entries == null) {
                return rules;
            }
            for (String entry : entries) {
                if (rules.size() >= DAOFIREWALL_MAX_RULES) {
                    break;
                }
                if (entry == null) {
                    continue;
                }
                final String[] parts = entry.trim().split("\\s+", 2);
                if (parts.length != 2) {
                    continue;
                }
                final int uid;
                try {
                    uid = Integer.parseInt(parts[0]);
                } catch (NumberFormatException e) {
                    continue;
                }
                final String domain = normalizeDomain(parts[1]);
                if (uid >= 10_000 && domain != null) {
                    rules.add(uid + "\t" + domain);
                }
            }
            return rules;
        }

        private String normalizeDomain(String domain) {
            if (domain == null) {
                return null;
            }
            String normalized = domain.trim().toLowerCase(Locale.US);
            while (normalized.endsWith(".")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            if (normalized.isEmpty() || normalized.length() > 253) {
                return null;
            }
            for (int i = 0; i < normalized.length(); i++) {
                final char c = normalized.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || c == '-' || c == '.')) {
                    return null;
                }
            }
            return normalized.contains(".") ? normalized : null;
        }

        private String normalizeIp(String ip) {
            if (ip == null) {
                return null;
            }
            String normalized = ip.trim().toLowerCase(Locale.US);
            if (normalized.startsWith("::ffff:") && normalized.indexOf('.') >= 0) {
                normalized = normalized.substring("::ffff:".length());
            }
            try {
                return InetAddress.getByName(normalized).getHostAddress().toLowerCase(Locale.US);
            } catch (IllegalArgumentException | IOException e) {
                return null;
            }
        }

        private void writeRuleFile(File file, LinkedHashSet<String> rules) throws IOException {
            final File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                for (String rule : rules) {
                    out.write(rule.getBytes(StandardCharsets.US_ASCII));
                    out.write('\n');
                }
                out.getFD().sync();
            }
            tmp.setReadable(true, false);
            tmp.setWritable(true, true);
            if (!tmp.renameTo(file)) {
                throw new IOException("Could not replace " + file);
            }
            if (!SELinux.restorecon(file)) {
                throw new IOException("Could not restorecon " + file);
            }
            file.setReadable(true, false);
            file.setWritable(true, true);
        }

        private void writeTextFile(File file, String value) throws IOException {
            final File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write(value.getBytes(StandardCharsets.US_ASCII));
                out.getFD().sync();
            }
            tmp.setReadable(true, false);
            tmp.setWritable(true, true);
            if (!tmp.renameTo(file)) {
                throw new IOException("Could not replace " + file);
            }
            if (!SELinux.restorecon(file)) {
                throw new IOException("Could not restorecon " + file);
            }
            file.setReadable(true, false);
            file.setWritable(true, true);
        }

        private void writeRuleChunk(File file, LinkedHashSet<String> rules, boolean reset,
                boolean commit) throws IOException {
            final File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp, !reset)) {
                for (String rule : rules) {
                    out.write(rule.getBytes(StandardCharsets.US_ASCII));
                    out.write('\n');
                }
                out.getFD().sync();
            }
            tmp.setReadable(true, false);
            tmp.setWritable(true, true);
            if (commit) {
                if (!tmp.renameTo(file)) {
                    throw new IOException("Could not replace " + file);
                }
                if (!SELinux.restorecon(file)) {
                    throw new IOException("Could not restorecon " + file);
                }
                file.setReadable(true, false);
                file.setWritable(true, true);
            }
        }

        @Override
        public void logDefaultNetworkValidity(boolean valid) {
            NetworkStack.checkNetworkStackPermission(getContext());
            mDefaultNetworkMetrics.logDefaultNetworkValidity(SystemClock.elapsedRealtime(), valid);
        }

        @Override
        public void logDefaultNetworkEvent(Network defaultNetwork, int score, boolean validated,
                LinkProperties lp, NetworkCapabilities nc, Network previousDefaultNetwork,
                int previousScore, LinkProperties previousLp, NetworkCapabilities previousNc) {
            NetworkStack.checkNetworkStackPermission(getContext());
            final long timeMs = SystemClock.elapsedRealtime();
            mDefaultNetworkMetrics.logDefaultNetworkEvent(timeMs, defaultNetwork, score, validated,
                    lp, nc,  previousDefaultNetwork, previousScore, previousLp, previousNc);
        }

    };

    private static final ToIntFunction<Context> READ_BUFFER_SIZE = (ctx) -> {
        int size = Settings.Global.getInt(ctx.getContentResolver(),
                Settings.Global.CONNECTIVITY_METRICS_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
        if (size <= 0) {
            return DEFAULT_BUFFER_SIZE;
        }
        return Math.min(size, MAXIMUM_BUFFER_SIZE);
    };

    private static ArrayMap<Class<?>, TokenBucket> makeRateLimitingBuckets() {
        ArrayMap<Class<?>, TokenBucket> map = new ArrayMap<>();
        // one token every minute, 50 tokens max: burst of ~50 events every hour.
        map.put(ApfProgramEvent.class, new TokenBucket((int)DateUtils.MINUTE_IN_MILLIS, 50));
        return map;
    }

    /** Direct non-Binder interface for event producer clients within the system servers. */
    public interface Logger {
        DefaultNetworkMetrics defaultNetworkMetrics();
    }

    private class LoggerImpl implements Logger {
        public DefaultNetworkMetrics defaultNetworkMetrics() {
            return mDefaultNetworkMetrics;
        }
    }
}
