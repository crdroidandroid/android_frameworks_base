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

package com.android.server.accessibility.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Process;

import java.util.function.Consumer;

/**
 * A test utility for setting up a mock {@link PackageManagerInternal} for TileService queries.
 */
public class TileServiceUtil {
    private TileServiceUtil() {
        // Utility class
    }

    /**
     * Sets up a mock {@link PackageManagerInternal} to respond to queries for a tile service.
     *
     * <p>This is a convenience method that configures a valid, enabled TileService.
     *
     * @param pm The mock {@link PackageManagerInternal} instance.
     * @param userId The user ID for the query.
     * @param tileComponent The {@link ComponentName} of the TileService to mock.
     */
    public static void setupPackageManagerForValidTileService(@NonNull PackageManagerInternal pm,
            @UserIdInt int userId,
            @NonNull ComponentName tileComponent) {
        // A valid service is one with no special mutations.
        setupPackageManagerForTileService(pm, userId, tileComponent, serviceInfo -> {});
    }

    /**
     * Sets up a mock {@link PackageManagerInternal} to respond to queries for a tile service,
     * allowing for custom modifications to simulate different states.
     *
     * <p>By default, this prepares a valid TileService. The {@code serviceInfoMutator} can be
     * used to configure invalid states (e.g., disabled, missing permission) for testing.
     *
     * @param pm The mock {@link PackageManagerInternal} instance.
     * @param userId The user ID for the query.
     * @param tileComponent The {@link ComponentName} of the TileService to mock.
     * @param serviceInfoMutator A {@link Consumer} that can modify the default {@link ServiceInfo}.
     */
    public static void setupPackageManagerForTileService(@NonNull PackageManagerInternal pm,
            @UserIdInt int userId,
            @NonNull ComponentName tileComponent,
            @NonNull Consumer<ServiceInfo> serviceInfoMutator) {
        // Create the base ServiceInfo for a valid TileService.
        final ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.exported = true;
        serviceInfo.permission = Manifest.permission.BIND_QUICK_SETTINGS_TILE;
        serviceInfo.enabled = true;

        final ResolveInfo tileResolveInfo = new ResolveInfo();
        tileResolveInfo.serviceInfo = serviceInfo;

        // Allow the caller to mutate the ServiceInfo to create invalid or custom states.
        serviceInfoMutator.accept(serviceInfo);

        when(pm.resolveService(
                argThat(intent -> intent != null && tileComponent.equals(intent.getComponent())),
                any(), eq(0L), eq(userId), eq(Process.myUid()))).thenReturn(tileResolveInfo);

        when(pm.getComponentEnabledSetting(
                eq(tileComponent), eq(Process.myUid()), eq(userId)))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
    }
}
