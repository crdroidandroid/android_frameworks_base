/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Manages the persisted backup preferences per user. */
public class UserBackupPreferences {
    private static final String PREFERENCES_FILE = "backup_preferences";

    private final File mExcludedKeysFile;
    private SharedPreferences mPreferences = null;

    UserBackupPreferences(File storageDir) {
        mExcludedKeysFile = new File(storageDir, PREFERENCES_FILE);
    }

    void addExcludedKeys(Context context, String packageName, List<String> keys) {
        if (mPreferences == null) {
            mPreferences = context.getSharedPreferences(mExcludedKeysFile, Context.MODE_PRIVATE);
        }
        Set<String> existingKeys =
                new HashSet<>(mPreferences.getStringSet(packageName, Collections.emptySet()));
        existingKeys.addAll(keys);
        SharedPreferences.Editor editor = mPreferences.edit();
        editor.putStringSet(packageName, existingKeys);
        editor.commit();
    }

    Set<String> getExcludedRestoreKeysForPackage(Context context, String packageName) {
        if (mPreferences == null) {
            mPreferences = context.getSharedPreferences(mExcludedKeysFile, Context.MODE_PRIVATE);
        }
        return mPreferences.getStringSet(packageName, Collections.emptySet());
    }
}
