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

package org.pixelexperience.systemui.columbus;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.android.internal.logging.UiEventLogger;

import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.settings.UserTracker;
import com.google.android.systemui.columbus.ColumbusTargetRequestService;
import com.google.android.systemui.columbus.ColumbusSettings;
import com.google.android.systemui.columbus.ColumbusStructuredDataManager;

import javax.inject.Inject;

public class ColumbusTargetRequestServiceWrapper extends ColumbusTargetRequestService {

    private final Context mContext;
    private final UserTracker mUserTracker;
    private final ColumbusSettings mColumbusSettings;
    private final ColumbusStructuredDataManager mColumbusStructuredDataManager;
    private final UiEventLogger mUiEventLogger;
    private final Handler mHandler;
    private final Looper mLooper;

    @Inject
    public ColumbusTargetRequestServiceWrapper(Context context, UserTracker userTracker, ColumbusSettings columbusSettings, ColumbusStructuredDataManager columbusStructuredDataManager, UiEventLogger uiEventLogger, @Main Handler handler, @Background Looper looper) {
        super(context, userTracker, columbusSettings, columbusStructuredDataManager, uiEventLogger, handler, looper);
        mContext = context;
        mUserTracker = userTracker;
        mColumbusSettings = columbusSettings;
        mColumbusStructuredDataManager = columbusStructuredDataManager;
        mUiEventLogger = uiEventLogger;
        mHandler = handler;
        mLooper = looper;
    }
}
