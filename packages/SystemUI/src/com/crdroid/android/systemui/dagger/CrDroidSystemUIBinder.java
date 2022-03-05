/*
 * Copyright (C) 2021 The Pixel Experience Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.crdroid.android.systemui.dagger;

import android.app.Service;

import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SystemUIBinder;
import com.android.systemui.theme.ThemeOverlayController;

import com.google.android.systemui.columbus.ColumbusTargetRequestService;

import com.crdroid.android.systemui.CrDroidServices;
import com.crdroid.android.systemui.theme.CrDroidThemeOverlayController;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

@Module
public abstract class CrDroidSystemUIBinder extends SystemUIBinder {
    /**
     * Inject into CrDroidServices.
     */
    @Binds
    @IntoMap
    @ClassKey(CrDroidServices.class)
    public abstract SystemUI bindCrDroidServices(CrDroidServices sysui);

    /**
     * Inject into ColumbusTargetRequestService.
     */
    @Binds
    @IntoMap
    @ClassKey(ColumbusTargetRequestService.class)
    public abstract Service bindColumbusTargetRequestService(ColumbusTargetRequestService activity);
}
