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

package org.pixelexperience.systemui.gamedashboard;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;

import com.android.systemui.plugins.ActivityStarter;
import com.google.android.systemui.gamedashboard.EntryPointController;
import com.google.android.systemui.gamedashboard.GameDashboardUiEventLogger;
import com.google.android.systemui.gamedashboard.GameMenuActivity;
import com.google.android.systemui.gamedashboard.GameModeDndController;
import com.google.android.systemui.gamedashboard.ShortcutBarController;

import javax.inject.Inject;

public class GameMenuActivityWrapper extends GameMenuActivity {

    private final Context mContext;
    private final EntryPointController mController;
    private final ShortcutBarController mShortcutBarController;
    private final ActivityStarter mActivityStarter;
    private final GameModeDndController mDndController;
    private final LayoutInflater mLayoutInflater;
    private final Handler mMainHandler;
    private final GameDashboardUiEventLogger mUiEventLogger;

    @Inject
    public GameMenuActivityWrapper(Context context, EntryPointController entryPointController, ActivityStarter activityStarter, ShortcutBarController shortcutBarController, GameModeDndController gameModeDndController, LayoutInflater layoutInflater, Handler handler, GameDashboardUiEventLogger gameDashboardUiEventLogger) {
        super(context, entryPointController, activityStarter, shortcutBarController, gameModeDndController, layoutInflater, handler, gameDashboardUiEventLogger);
        mContext = context;
        mController = entryPointController;
        mShortcutBarController = shortcutBarController;
        mActivityStarter = activityStarter;
        mDndController = gameModeDndController;
        mLayoutInflater = layoutInflater;
        mMainHandler = handler;
        mUiEventLogger = gameDashboardUiEventLogger;
    }

    public static Intent createIntentForStart(Context context) {
        return new Intent(context, GameMenuActivityWrapper.class);
    }
}
