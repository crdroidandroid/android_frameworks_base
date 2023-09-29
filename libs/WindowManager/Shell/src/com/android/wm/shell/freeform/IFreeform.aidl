/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.freeform;

import android.app.PendingIntent;
import android.graphics.Rect;

import com.android.wm.shell.freeform.IFreeformListener;

/**
 * Interface that is exposed to remote callers to manipulate the freeform feature.
 */
oneway interface IFreeform {

    /**
     * Starts a task in freeform.
     */
    void startTask(int taskId, in Rect bounds) = 1;

    /**
     * Starts an intent in freeform.
     */
    void startIntent(in PendingIntent intent, in Rect bounds) = 2;

    /**
     * Changes the provided task to fullscreen.
     */
    void exitFreeform(int taskId) = 3;

    /**
     * Registers a freeform listener.
     */
    void setFreeformListener(in IFreeformListener listener) = 4;

    /**
     * Unregisters a freeform listener.
     */
    void removeFreeformListener() = 5;
}
// Last id = 5