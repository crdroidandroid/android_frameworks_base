/*
 * Copyright (C) 2018-2019 crDroid Android Project
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

package com.android.internal.statusbar;

import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.os.RemoteException;

public class ThemeAccentUtils {

    public static final String TAG = "ThemeAccentUtils";

    public static String[] AllPackages = {
        "default_accent", // 0
        "com.accents.red", // 1
        "com.accents.pink", // 2
        "com.accents.purple", // 3
        "com.accents.deeppurple", // 4
        "com.accents.indigo", // 5
        "com.accents.blue", // 6
        "com.accents.lightblue", // 7
        "com.accents.cyan", // 8
        "com.accents.teal", // 9
        "com.accents.green", // 10
        "com.accents.lightgreen", // 11
        "com.accents.lime", // 12
        "com.accents.yellow", // 13
        "com.accents.amber", // 14
        "com.accents.orange", // 15
        "com.accents.deeporange", // 16
        "com.accents.brown", // 17
        "com.accents.grey", // 18
        "com.accents.bluegrey", // 19
        "com.accents.black", // 20
        "com.accents.white", // 21
        "com.accents.userone", // 22
        "com.accents.usertwo", // 23
        "com.accents.userthree", // 24
        "com.accents.userfour", // 25
        "com.accents.userfive", // 26
        "com.accents.usersix", // 27
        "com.accents.userseven", // 28
        "com.accents.aospagreen", // 29
        "com.accents.androidonegreen", // 30
        "com.accents.cocacolared", // 31
        "com.accents.discordpurple", // 32
        "com.accents.facebookblue", // 33
        "com.accents.instagramcerise", // 34
        "com.accents.jollibeecrimson", // 35
        "com.accents.monsterenergygreen", // 36
        "com.accents.nextbitmint", // 37
        "com.accents.oneplusred", // 38
        "com.accents.pepsiblue", // 39
        "com.accents.pocophoneyellow", // 40
        "com.accents.razergreen", // 41
        "com.accents.samsungblue", // 42
        "com.accents.spotifygreen", // 43
        "com.accents.starbucksgreen", // 44
        "com.accents.twitchpurple", // 45
        "com.accents.twitterblue", // 46
        "com.accents.xboxgreen", // 47
        "com.accents.xiaomiorange", // 48
    };
}
