/*
 * Copyright (C) 2023-2024 LibreMobileOS Foundation
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

package com.android.server.display;

import android.content.Context;
import android.hardware.display.DisplayManagerInternal;
import android.util.Slog;

import com.android.server.SystemService;

import com.libremobileos.freeform.server.LMOFreeformService;
import com.libremobileos.freeform.server.LMOFreeformServiceHolder;
import com.libremobileos.freeform.server.LMOFreeformUIService;

public class FreeformService extends SystemService {

    private static final String TAG = "FreeformService";

    public FreeformService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        // noop
    }

    @Override
    public void onBootPhase(@BootPhase int phase) {
        if (phase != PHASE_ACTIVITY_MANAGER_READY || isSafeMode()) return;

        Slog.d(TAG, "PHASE_ACTIVITY_MANAGER_READY, going to init!");

        DisplayManagerInternal displayManager = getLocalService(DisplayManagerInternal.class);
        if (displayManager == null) {
            Slog.e(TAG, "Cannot init: DisplayManagerInternal is null!");
            return;
        }

        LMOFreeformService service = new LMOFreeformService(displayManager);
        LMOFreeformUIService uiService =
                new LMOFreeformUIService(getContext(), displayManager, service);
        LMOFreeformServiceHolder.init(uiService, service);
    }
}
