/*
**
** Copyright 2019, Descendant
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.keyguard;

import com.android.internal.util.ArrayUtils;

import java.io.*;
import java.lang.String;

public class LangGuard {

    public static boolean isAvailable (String[] langExceptions, String langVal) {
        return (ArrayUtils.contains(langExceptions, langVal) ? true : false);
    }

    public static String evaluateExMin (String lang, int units, String[] TensString, String[] UnitsString, int tens) {
        String numString = "";
        switch (lang) {

            case "pl":
                numString = TensString[tens] + " " + UnitsString[units];
                return numString;

            case "nl":
                numString = UnitsString[units].substring(0, UnitsString[units].length() - 5) + "en" + TensString[tens].toLowerCase();
                return numString;

            case "pt":
                numString = TensString[tens] + " e " + UnitsString[units].toLowerCase();
                return numString;

            case "fr":
                if (units == 1) {
                    numString = TensString[tens] + " et un";
                    return numString;
                } else {
                    numString = TensString[tens] + "-" + UnitsString[units].toLowerCase();
                    return numString;
                }

            case "it":
                if (units == 1) {
                    numString = TensString[tens].substring(0, TensString[tens].length() - 1)+
                                UnitsString[units].toLowerCase();
                    return numString;                    
                } else if (units == 3) {
                    numString = TensString[tens] + "tré";
                    return numString; 
                } else if (units == 8) {
                    numString = TensString[tens].substring(0, TensString[tens].length() - 1)+
                                UnitsString[units].toLowerCase();
                    return numString;
                } else {
                    numString = TensString[tens] + UnitsString[units].toLowerCase();
                    return numString;
                }

            case "ja":
                numString = TensString[tens] + " " + UnitsString[units];
                return numString;

            case "tr":
                numString = TensString[tens] + " " + UnitsString[units];
                return numString;
        }
        return numString;
    }

    public static String evaluateExHr (String lang, int units, String[] TensString, String[] UnitsString, int tens, int hours, String[] UnitsStringH, String[] TensStringH, boolean h24) {
        String numString = "";
        switch (lang) {
                    
            case "pl":
                if ( hours == 0 && h24) {
                    numString = "Dwudziesta czwarta";
                    return numString;
                }
                if ( hours == 0 && !h24) {
                    numString = "Dwunasta";
                    return numString;
                }
                if ( hours > 20 ) {
                    numString = "Dwudziesta" + " " + UnitsStringH[units];
                    return numString;
                }
                if (units != 0) {
                    numString = TensStringH[tens] + " " + UnitsStringH[units];
                    return numString;
                } else {
                    numString = TensStringH[tens];
                    return numString;
                }

            case "nl":
                if (units != 0) {
                    numString = UnitsString[units].substring(0, UnitsString[units].length() - 5) + "en" + TensString[tens].toLowerCase();
                    return numString;
                } else {
                    numString = UnitsString[units].substring(0, UnitsString[units].length() - 5);
                    return numString;
                }

            case "pt":
                if (units != 0) {
                    numString = TensString[tens] + " e " + UnitsString[units].toLowerCase();
                    return numString;
                } else {
                   numString = TensString[tens];
                   return numString;
                }

            case "fr":
                if (units == 1) {
                    numString = TensString[tens] + " et un" + "heures";
                    return numString;
                }
                if (units == 0) {
                    numString = TensStringH[tens] + " heures";
                    return numString;
                }
                if (hours > 1) {
                    numString = TensString[tens] + "-" + UnitsString[units].toLowerCase() + "heures";
                    return numString;
                }

            case "it":
                if ( hours == 0 && h24) {
                    numString = "Mezzanotte e";
                    return numString;
                }
                if (units == 1) {
                    numString = TensStringH[tens].substring(0, TensStringH[tens].length() - 1)+
                                UnitsString[units].toLowerCase() + " e";
                    return numString;
                }
                if (units == 3) {
                    numString = TensStringH[tens] + "tré" + " e";
                    return numString;                    
                }
                if (units == 0) {
                    numString = TensStringH[tens] + " e";
                    return numString;
                }
                    numString = TensStringH[tens] + UnitsString[units].toLowerCase() + " e";
                    return numString;

            case "ja":
                if (units != 0) {
                    numString = TensStringH[tens] + " " + UnitsString[units];
                    return numString;
                } else {
                    numString = TensStringH[tens];
                }

            case "tr":
                if (units != 0) {
                    numString = TensStringH[tens] + " " + UnitsString[units];
                    return numString;
                } else {
                    numString = TensStringH[tens];
                }
        }
        return numString;
    }
}
