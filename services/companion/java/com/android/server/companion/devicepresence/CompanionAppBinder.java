/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.devicepresence;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;
import android.companion.DevicePresenceEvent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.companion.CompanionDeviceManagerService;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages communication with companion applications via
 * {@link android.companion.ICompanionDeviceService} interface, including "connecting" (binding) to
 * the services, maintaining the connection (the binding), and invoking callback methods such as
 * {@link CompanionDeviceService#onDeviceAppeared(AssociationInfo)},
 * {@link CompanionDeviceService#onDeviceDisappeared(AssociationInfo)} and
 * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)} in the
 * application process.
 *
 * <p>
 * The following is the list of the APIs provided by {@link CompanionAppBinder} (to be
 * utilized by {@link CompanionDeviceManagerService}):
 * <ul>
 * <li> {@link #bindCompanionApp(int, String, boolean, CompanionServiceConnector.Listener)}
 * <li> {@link #unbindCompanionApp(int, String)}
 * <li> {@link #isCompanionApplicationBound(int, String)}
 * <li> {@link #isRebindingCompanionApplicationScheduled(int, String)}
 * </ul>
 *
 * @see CompanionDeviceService
 * @see android.companion.ICompanionDeviceService
 * @see CompanionServiceConnector
 */
@SuppressLint("LongLogTag")
public class CompanionAppBinder {
    private static final String TAG = "CDM_CompanionAppBinder";

    private static final Intent COMPANION_SERVICE_INTENT =
            new Intent(CompanionDeviceService.SERVICE_INTERFACE);

    private static final String PROPERTY_PRIMARY_TAG =
            "android.companion.PROPERTY_PRIMARY_COMPANION_DEVICE_SERVICE";

    private static final long REBIND_TIMEOUT = 10 * 1000; // 10 sec

    @NonNull
    private final Context mContext;

    @NonNull
    @GuardedBy("mBoundCompanionApplications")
    private final Map<Pair<Integer, String>, List<CompanionServiceConnector>>
            mBoundCompanionApplications;
    @NonNull
    @GuardedBy("mScheduledForRebindingCompanionApplications")
    private final Set<Pair<Integer, String>> mScheduledForRebindingCompanionApplications;

    public CompanionAppBinder(@NonNull Context context) {
        mContext = context;
        mBoundCompanionApplications = new HashMap<>();
        mScheduledForRebindingCompanionApplications = new HashSet<>();
    }

    /**
     * CDM binds to the companion app.
     */
    public void bindCompanionApp(@UserIdInt int userId, @NonNull String packageName,
            boolean isSelfManaged, CompanionServiceConnector.Listener listener) {
        Slog.i(TAG, "Binding user=[" + userId + "], package=[" + packageName + "], isSelfManaged=["
                + isSelfManaged + "]...");

        final List<ComponentName> companionServices = getCompanionServiceComponentsForPackage(
                mContext, packageName, userId);

        if (companionServices.isEmpty()) {
            Slog.e(TAG, "Can not bind companion applications u" + userId + "/" + packageName + ": "
                    + "eligible CompanionDeviceService not found.\n"
                    + "A CompanionDeviceService should declare an intent-filter for "
                    + "\"android.companion.CompanionDeviceService\" action and require "
                    + "\"android.permission.BIND_COMPANION_DEVICE_SERVICE\" permission.");
            return;
        }

        final List<CompanionServiceConnector> serviceConnectors = new ArrayList<>();
        synchronized (mBoundCompanionApplications) {
            if (mBoundCompanionApplications.containsKey(new Pair<>(userId, packageName))) {
                Slog.w(TAG, "The package is ALREADY bound.");
                return;
            }

            for (int i = 0; i < companionServices.size(); i++) {
                boolean isPrimary = i == 0;
                serviceConnectors.add(CompanionServiceConnector.newInstance(mContext, userId,
                        companionServices.get(i), isSelfManaged, isPrimary));
            }

            mBoundCompanionApplications.put(new Pair<>(userId, packageName), serviceConnectors);
        }

        // Set listeners for both Primary and Secondary connectors.
        for (CompanionServiceConnector serviceConnector : serviceConnectors) {
            serviceConnector.setListener(listener);
        }

        // Now "bind" all the connectors: the primary one and the rest of them.
        for (CompanionServiceConnector serviceConnector : serviceConnectors) {
            serviceConnector.connect();
        }
    }

    /**
     * CDM unbinds the companion app.
     */
    public void unbindCompanionApp(@UserIdInt int userId, @NonNull String packageName) {
        Slog.i(TAG, "Unbinding user=[" + userId + "], package=[" + packageName + "]...");

        final List<CompanionServiceConnector> serviceConnectors;

        synchronized (mBoundCompanionApplications) {
            serviceConnectors = mBoundCompanionApplications.remove(new Pair<>(userId, packageName));
        }

        synchronized (mScheduledForRebindingCompanionApplications) {
            mScheduledForRebindingCompanionApplications.remove(new Pair<>(userId, packageName));
        }

        if (serviceConnectors == null) {
            Slog.e(TAG, "The package is not bound.");
            return;
        }

        for (CompanionServiceConnector serviceConnector : serviceConnectors) {
            serviceConnector.postUnbind();
        }
    }

    /**
     * @return whether the companion application is bound now.
     */
    public boolean isCompanionApplicationBound(@UserIdInt int userId, @NonNull String packageName) {
        synchronized (mBoundCompanionApplications) {
            return mBoundCompanionApplications.containsKey(new Pair<>(userId, packageName));
        }
    }

    /**
     * Remove bound apps for package.
     */
    public void removePackage(int userId, String packageName) {
        synchronized (mBoundCompanionApplications) {
            mBoundCompanionApplications.remove(new Pair<>(userId, packageName));
        }

        synchronized (mScheduledForRebindingCompanionApplications) {
            mScheduledForRebindingCompanionApplications.remove(new Pair<>(userId, packageName));
        }
    }

    /**
     * Schedule rebinding for the package.
     */
    public void scheduleRebinding(@UserIdInt int userId, @NonNull String packageName,
            CompanionServiceConnector serviceConnector) {
        Slog.i(TAG, "scheduleRebinding() " + userId + "/" + packageName);

        if (isRebindingCompanionApplicationScheduled(userId, packageName)) {
            Slog.i(TAG, "CompanionApplication rebinding has been scheduled, skipping "
                    + serviceConnector.getComponentName());
            return;
        }

        if (serviceConnector.isPrimary()) {
            synchronized (mScheduledForRebindingCompanionApplications) {
                mScheduledForRebindingCompanionApplications.add(new Pair<>(userId, packageName));
            }
        }

        // Rebinding in 10 seconds.
        Handler.getMain().postDelayed(() ->
                        onRebindingCompanionApplicationTimeout(userId, packageName,
                                serviceConnector),
                REBIND_TIMEOUT);
    }

    private boolean isRebindingCompanionApplicationScheduled(
            @UserIdInt int userId, @NonNull String packageName) {
        synchronized (mScheduledForRebindingCompanionApplications) {
            return mScheduledForRebindingCompanionApplications.contains(
                    new Pair<>(userId, packageName));
        }
    }

    private void onRebindingCompanionApplicationTimeout(
            @UserIdInt int userId, @NonNull String packageName,
            @NonNull CompanionServiceConnector serviceConnector) {
        // Re-mark the application is bound.
        if (serviceConnector.isPrimary()) {
            synchronized (mBoundCompanionApplications) {
                if (!mBoundCompanionApplications.containsKey(new Pair<>(userId, packageName))) {
                    List<CompanionServiceConnector> serviceConnectors =
                            Collections.singletonList(serviceConnector);
                    mBoundCompanionApplications.put(new Pair<>(userId, packageName),
                            serviceConnectors);
                }
            }

            synchronized (mScheduledForRebindingCompanionApplications) {
                mScheduledForRebindingCompanionApplications.remove(new Pair<>(userId, packageName));
            }
        }

        serviceConnector.connect();
    }

    /**
     * Dump bound apps.
     */
    public void dump(@NonNull PrintWriter out) {
        out.append("Companion Device Application Controller: \n");

        synchronized (mBoundCompanionApplications) {
            out.append("  Bound Companion Applications: ");
            if (mBoundCompanionApplications.isEmpty()) {
                out.append("<empty>\n");
            } else {
                out.append("\n");
                for (Map.Entry<Pair<Integer, String>, List<CompanionServiceConnector>> entry :
                        mBoundCompanionApplications.entrySet()) {
                    out.append("<u").append(String.valueOf(entry.getKey().first)).append(", ")
                            .append(entry.getKey().second).append(">");
                    for (CompanionServiceConnector serviceConnector : entry.getValue()) {
                        out.append(", isPrimary=").append(
                                String.valueOf(serviceConnector.isPrimary()));
                    }
                }
            }
        }

        out.append("  Companion Applications Scheduled For Rebinding: ");
        synchronized (mScheduledForRebindingCompanionApplications) {
            if (mScheduledForRebindingCompanionApplications.isEmpty()) {
                out.append("<empty>\n");
            } else {
                out.append("\n");
                for (Pair<Integer, String> app : mScheduledForRebindingCompanionApplications) {
                    out.append("<u").append(String.valueOf(app.first)).append(", ")
                            .append(app.second).append(">");
                }
            }
        }
    }

    @Nullable
    CompanionServiceConnector getPrimaryServiceConnector(
            @UserIdInt int userId, @NonNull String packageName) {
        final List<CompanionServiceConnector> connectors;
        synchronized (mBoundCompanionApplications) {
            connectors = mBoundCompanionApplications.get(new Pair<>(userId, packageName));
        }
        return connectors != null ? connectors.get(0) : null;
    }

    /**
     * @return list of {@link CompanionDeviceService}-s per package for a given user.
     *         Services marked as "primary" would always appear at the head of the lists, *before*
     *         all non-primary services.
     */
    private @NonNull List<ComponentName> getCompanionServiceComponentsForPackage(
            @NonNull Context context, @NonNull String packageName, @UserIdInt int userId) {
        final PackageManager pm = context.getPackageManager();
        final List<ResolveInfo> companionServices = pm.queryIntentServicesAsUser(
                COMPANION_SERVICE_INTENT, PackageManager.ResolveInfoFlags.of(0), userId);
        final List<ComponentName> componentNames = new ArrayList<>();

        for (ResolveInfo resolveInfo : companionServices) {
            final ServiceInfo service = resolveInfo.serviceInfo;
            final ComponentName componentName = service.getComponentName();

            if (!componentName.getPackageName().equals(packageName)) continue;

            final boolean requiresPermission = Manifest.permission.BIND_COMPANION_DEVICE_SERVICE
                    .equals(resolveInfo.serviceInfo.permission);
            if (!requiresPermission) {
                Slog.w(TAG, "CompanionDeviceService "
                        + service.getComponentName().flattenToShortString() + " must require "
                        + "android.permission.BIND_COMPANION_DEVICE_SERVICE");
                break;
            }

            if (isPrimaryCompanionDeviceService(pm, componentName, userId)) {
                // "Primary" service should be at the head of the list.
                componentNames.add(0, componentName);
            } else {
                componentNames.add(componentName);
            }
        }

        return componentNames;
    }

    private boolean isPrimaryCompanionDeviceService(@NonNull PackageManager pm,
            @NonNull ComponentName componentName, @UserIdInt int userId) {
        try {
            return pm.getPropertyAsUser(PROPERTY_PRIMARY_TAG, componentName.getPackageName(),
                    componentName.getClassName(), userId).getBoolean();
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
