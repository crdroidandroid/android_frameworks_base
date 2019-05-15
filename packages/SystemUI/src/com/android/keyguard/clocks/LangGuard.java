package com.android.keyguard.clocks;

import com.android.internal.util.ArrayUtils;

import java.io.*;
import java.lang.String;

public class LangGuard {

    public static boolean isAvailable (String[] langExceptions, String langVal) {
        return (ArrayUtils.contains(langExceptions, langVal) ? true : false);
    }

    public static String evaluateEx (String lang, int units, String[] TensString, String[] UnitsString, int tens, boolean hours, int num) {
        String numString = "";
        switch (lang) {
            case "it":
                if (num < 10) {
                    numString = UnitsString[num];
                    return numString;
                }
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
                if (num < 10) {
                    numString = UnitsString[num];
                    if (!hours) {
                        numString = "E "+ numString;
                    } else if (hours) {
                        numString = numString + "Heures";
                    }
                    return numString;
                }
                numString = TensString[tens] + "e " + UnitsString[units].toLowerCase();
                if (!hours) {
                    numString = "E "+ numString;
                } else if (hours) {
                    numString = numString + "Heures";
                }
                return numString;

            case "ru":
                if (num < 20) {
                   if (!hours && num < 10 ) {
                       numString = "Ноль " + UnitsString[num];
                   } else
                    numString = UnitsString[num];
                    return numString;
                }
                numString = TensString[tens] + " "+ UnitsString[units];
                return numString;

            case "fr":
                if (num < 10) {
                    numString = UnitsString[num];
                    return numString;
                }
                if (units == 1) {
                    numString = TensString[tens] + "et un";
                } else {
                    numString = TensString[tens] + UnitsString[units].toLowerCase();
                }
                return numString;

            case "ja":
                if (num < 10) {
                    numString = UnitsString[num];
                    return numString;
                }
                numString = TensString[tens] + UnitsString[units];
                return numString;

            case "nl":
                if(hours && num < 10) {
                    units = num;
                    tens = 0;
                }
                if (units == 1 || units == 6) {
                    numString = UnitsString[units].substring(0,3 ) + "en" + TensString[tens].toLowerCase();
                } else if (units == 7 || units == 9) {
                    numString = UnitsString[units].substring(0,5) + "en" + TensString[tens].toLowerCase();
                } else if (units == 2 || units == 3 || units == 4 || units == 5 || units == 8) {
                    numString = UnitsString[units].substring(0,4) + "en" + TensString[tens].toLowerCase();
                } else {
                    numString = UnitsString[units] + "en" + TensString[tens].toLowerCase();
                }
                if (hours && num < 10) {
                    numString = numString.substring(0, (numString.length() - 2));
                }
                return numString;

            case "tr":
                if (num < 10) {
                    numString = UnitsString[num];
                    return numString;
                }
                numString = TensString[tens] + UnitsString[units];
                return numString;
        }
        return numString;
    }
}
