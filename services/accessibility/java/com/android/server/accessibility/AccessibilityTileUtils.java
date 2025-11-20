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

package com.android.server.accessibility;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityShortcutInfo;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Process;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;

import java.util.List;
import java.util.Set;

/**
 * Collection of utilities for Accessibility feature's TileServices.
 */
public class AccessibilityTileUtils {
    private AccessibilityTileUtils() {}
    private static final String TAG = "AccessibilityTileUtils";

    /**
     * Checks if a given {@link ComponentName} corresponds to a valid and enabled TileService
     * for the current user.
     *
     * @param componentName The {@link ComponentName} of the service to validate.
     * @return {@code true} if the component is a valid and enabled TileService, {@code false}
     * otherwise.
     */
    private static boolean isComponentValidTileService(
            @NonNull Context context, @NonNull PackageManagerInternal pm,
            @NonNull ComponentName componentName, @UserIdInt int userId) {
        Intent intent = new Intent(TileService.ACTION_QS_TILE);
        intent.setComponent(componentName);

        ResolveInfo resolveInfo = pm.resolveService(intent,
                intent.resolveTypeIfNeeded(context.getContentResolver()),
                /* flags= */ 0L,
                userId,
                android.os.Process.myUid());

        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            Slog.w(TAG, "TileService could not be resolved: " + componentName);
            return false;
        }

        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        if (!serviceInfo.exported) {
            Slog.w(TAG, "TileService is not exported: " + componentName);
            return false;
        }

        if (!Manifest.permission.BIND_QUICK_SETTINGS_TILE.equals(serviceInfo.permission)) {
            Slog.w(TAG, "TileService is not protected by BIND_QUICK_SETTINGS_TILE permission: "
                    + componentName);
            return false;
        }

        int enabledSetting = pm.getComponentEnabledSetting(componentName, Process.myUid(), userId);
        if (!resolveEnabledComponent(enabledSetting, serviceInfo.enabled)) {
            Slog.w(TAG, "TileService is not enabled: " + componentName.flattenToShortString());
            return false;
        }

        return true;
    }

    /**
     * Resolves the effective enabled state of a component by considering both its dynamic setting
     * and its static manifest declaration.
     *
     * @param pmResult     The component's dynamic enabled state, as returned by
     *                     {@link PackageManager#getComponentEnabledSetting(ComponentName)}.
     * @param defaultValue The component's static enabled state from its manifest (e.g.,
     *                     {@link android.content.pm.ServiceInfo#enabled}). This value is used
     *                     when {@code pmResult} is
     *                     {@link PackageManager#COMPONENT_ENABLED_STATE_DEFAULT}.
     * @return {@code true} if the component is considered enabled, {@code false} otherwise.
     */
    private static boolean resolveEnabledComponent(int pmResult, boolean defaultValue) {
        if (pmResult == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return true;
        }
        if (pmResult == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            return defaultValue;
        }
        return false;
    }

    /**
     * Returns a set of {@link ComponentName}s for valid Accessibility TileServices.
     * A TileService is considered valid if it is properly declared, exported, protected by the
     * {@link Manifest.permission#BIND_QUICK_SETTINGS_TILE} permission, and enabled.
     *
     * @param context The current context.
     * @param pm The {@link PackageManagerInternal} instance.
     * @param accessibilityServiceInfos A list of installed {@link AccessibilityServiceInfo}s.
     * @param accessibilityShortcutInfos A list of installed {@link AccessibilityShortcutInfo}s.
     * @param userId The user ID for which to retrieve the TileServices.
     * @return A {@link Set} of valid {@link ComponentName}s for Accessibility TileServices.
     */
    @NonNull
    public static Set<ComponentName> getValidA11yTileServices(
            @NonNull Context context,
            @Nullable PackageManagerInternal pm,
            @Nullable List<AccessibilityServiceInfo> accessibilityServiceInfos,
            @Nullable List<AccessibilityShortcutInfo> accessibilityShortcutInfos,
            @UserIdInt int userId
    ) {
        Set<ComponentName> validA11yTileServices = new ArraySet<>();
        if (pm == null) {
            return validA11yTileServices;
        }

        if (accessibilityServiceInfos != null) {
            accessibilityServiceInfos.forEach(
                    a11yServiceInfo -> {
                        String tileServiceName = a11yServiceInfo.getTileServiceName();
                        if (!TextUtils.isEmpty(tileServiceName)) {
                            ResolveInfo resolveInfo = a11yServiceInfo.getResolveInfo();
                            ComponentName a11yFeature = new ComponentName(
                                    resolveInfo.serviceInfo.packageName,
                                    resolveInfo.serviceInfo.name
                            );
                            ComponentName tileService = new ComponentName(
                                    a11yFeature.getPackageName(),
                                    tileServiceName
                            );
                            if (isComponentValidTileService(context, pm, tileService, userId)) {
                                validA11yTileServices.add(tileService);
                            }
                        }
                    }
            );
        }

        if (accessibilityShortcutInfos != null) {
            accessibilityShortcutInfos.forEach(
                    a11yShortcutInfo -> {
                        String tileServiceName = a11yShortcutInfo.getTileServiceName();
                        if (!TextUtils.isEmpty(tileServiceName)) {
                            ComponentName a11yFeature = a11yShortcutInfo.getComponentName();
                            ComponentName tileService = new ComponentName(
                                    a11yFeature.getPackageName(),
                                    tileServiceName);
                            if (isComponentValidTileService(context, pm, tileService, userId)) {
                                validA11yTileServices.add(tileService);
                            }
                        }
                    }
            );
        }

        return validA11yTileServices;
    }

}
