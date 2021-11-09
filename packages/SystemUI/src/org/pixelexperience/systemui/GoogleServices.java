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

package org.pixelexperience.systemui;

import android.app.AlarmManager;
import android.content.Context;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.VendorServices;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.phone.StatusBar;
import com.google.android.systemui.DisplayCutoutEmulationAdapter;
import com.google.android.systemui.ambientmusic.AmbientIndicationContainer;
import com.google.android.systemui.ambientmusic.AmbientIndicationService;
import com.google.android.systemui.autorotate.AutorotateDataService;
import com.google.android.systemui.columbus.ColumbusContext;
import com.google.android.systemui.columbus.ColumbusServiceWrapper;
import com.google.android.systemui.coversheet.CoversheetService;
import com.google.android.systemui.elmyra.ElmyraContext;
import com.google.android.systemui.elmyra.ElmyraService;
import com.google.android.systemui.elmyra.ServiceConfigurationGoogle;
import com.google.android.systemui.face.FaceNotificationService;
import com.google.android.systemui.input.TouchContextService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

import javax.inject.Inject;

import dagger.Lazy;

@SysUISingleton
public class GoogleServices extends VendorServices {
    private final AlarmManager mAlarmManager;
    private final AutorotateDataService mAutorotateDataService;
    private final Lazy<ColumbusServiceWrapper> mColumbusServiceLazy;
    private final Lazy<ServiceConfigurationGoogle> mServiceConfigurationGoogle;
    private final ArrayList<Object> mServices = new ArrayList<>();
    private final StatusBar mStatusBar;
    private final UiEventLogger mUiEventLogger;

    @Inject
    public GoogleServices(Context context, Lazy<ServiceConfigurationGoogle> serviceConfigurationGoogle, StatusBar statusBar, UiEventLogger uiEventLogger, Lazy<ColumbusServiceWrapper> columbusService, AlarmManager alarmManager, AutorotateDataService autorotateDataService) {
        super(context);
        mServiceConfigurationGoogle = serviceConfigurationGoogle;
        mStatusBar = statusBar;
        mUiEventLogger = uiEventLogger;
        mColumbusServiceLazy = columbusService;
        mAlarmManager = alarmManager;
        mAutorotateDataService = autorotateDataService;
    }

    @Override
    public void start() {
        AmbientIndicationContainer ambientIndicationContainer = mStatusBar.getNotificationShadeWindowView().findViewById(R.id.ambient_indication_container);
        ambientIndicationContainer.initializeView(mStatusBar);
        addService(new AmbientIndicationService(mContext, ambientIndicationContainer, mAlarmManager));
        addService(new DisplayCutoutEmulationAdapter(mContext));
        addService(new CoversheetService(mContext));
        mAutorotateDataService.init();
        addService(mAutorotateDataService);
        if (mContext.getPackageManager().hasSystemFeature("android.hardware.context_hub") && new ElmyraContext(mContext).isAvailable()) {
            addService(new ElmyraService(mContext, mServiceConfigurationGoogle.get(), mUiEventLogger));
        }
        if (new ColumbusContext(mContext).isAvailable()) {
            addService(mColumbusServiceLazy.get());
        }
        if (mContext.getPackageManager().hasSystemFeature("android.hardware.biometrics.face")) {
            addService(new FaceNotificationService(mContext));
        }
        if (mContext.getResources().getBoolean(R.bool.config_touch_context_enabled)) {
            addService(new TouchContextService(mContext));
        }
    }

    @Override
    public void dump(FileDescriptor fileDescriptor, PrintWriter printWriter, String[] strArr) {
        for (int i = 0; i < mServices.size(); i++) {
            if (mServices.get(i) instanceof Dumpable) {
                ((Dumpable) mServices.get(i)).dump(fileDescriptor, printWriter, strArr);
            }
        }
    }

    private void addService(Object obj) {
        if (obj != null) {
            mServices.add(obj);
        }
    }
}

