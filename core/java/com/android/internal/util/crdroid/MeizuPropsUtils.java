/*
 * Copyright (C) 2020 The Pixel Experience Project
 *               2020 The exTHmUI Open Source Project
 *               2022 Project Kaleidoscope
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
package com.android.internal.util.crdroid;

import android.os.Build;
import android.util.Log;

import java.util.Arrays;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public class MeizuPropsUtils {

    private static final String TAG = MeizuPropsUtils.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final Map<String, Object> propsToChange;

    private static final String[] packagesToChange = {
        "com.hihonor.cloudmusic",
        "com.netease.cloudmusic",
        "com.tencent.qqmusic",
        "com.kugou.android",
        "com.kugou.android.lite",
        "cmccwm.mobilemusic",
        "cn.kuwo.player",
        "com.meizu.media.music"
    };

    static {
        propsToChange = new HashMap<>();
        propsToChange.put("BRAND", "meizu");
        propsToChange.put("MANUFACTURER", "Meizu");
        propsToChange.put("DEVICE", "m1892");
        propsToChange.put("DISPLAY","Flyme");
        propsToChange.put("PRODUCT","meizu_16thPlus_CN");
        propsToChange.put("MODEL", "meizu 16th Plus");
    }

    public static void setProps(String packageName) {
        if (packageName == null){
            return;
        }
        if (Arrays.asList(packagesToChange).contains(packageName)){
            if (DEBUG){
                Log.d(TAG, "Defining props for: " + packageName);
            }
            for (Map.Entry<String, Object> prop : propsToChange.entrySet()) {
                String key = prop.getKey();
                Object value = prop.getValue();
                setPropValue(key, value);
            }
        }
    }

    private static void setPropValue(String key, Object value){
        try {
            if (DEBUG){
                Log.d(TAG, "Defining prop " + key + " to " + value.toString());
            }
            Field field = Build.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }
}
