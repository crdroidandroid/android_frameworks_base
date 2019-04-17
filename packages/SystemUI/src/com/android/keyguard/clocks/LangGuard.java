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

package com.android.keyguard.clocks;

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
            case "it":
                if (units == 1) {
                    numString = TensString[tens].substring(0, TensString[tens].length() - 1)+
                                UnitsString[units].toLowerCase();
                    return numString;                    
                } else if (units == 3) {
                    numString = TensString[tens] + "tré";
                    return numString; 
                } else {
                    numString = TensString[tens] + UnitsString[units].toLowerCase();
                    return numString;
                }

            case "pt":
                numString = TensString[tens] + "e " + UnitsString[units].toLowerCase();
                return numString;

            case "fr":
                if (units == 1) {
                    numString = TensString[tens] + "et un";
                } else {
                    numString = TensString[tens] + UnitsString[units].toLowerCase();
                }
                return numString;
        }
        return numString;
    }

    public static String evaluateExHr (String lang, int units, String[] TensString, String[] UnitsString, int tens) {
        String numString = "";
        switch (lang) {
            case "it":
                if (units == 1) {
                    numString = TensString[tens].substring(0, TensString[tens].length() - 1)+
                                UnitsString[units].toLowerCase();
                    return numString;
                } else if (units == 3) {
                    numString = TensString[tens] + "tré";
                    return numString;                    
                } else { 
                    numString = TensString[tens] + UnitsString[units].toLowerCase();
                    return numString;
                }
        }
        return numString;
    }
}
