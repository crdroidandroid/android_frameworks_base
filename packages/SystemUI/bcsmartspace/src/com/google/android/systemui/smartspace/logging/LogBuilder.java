package com.google.android.systemui.smartspace.logging;

import android.util.Log;
import java.io.PrintWriter;

public class LogBuilder {
    public static StringBuilder m(String str) {
        StringBuilder sb = new StringBuilder();
        sb.append(str);
        return sb;
    }

    public static void m(String str, String str2, String str3) {
        Log.w(str3, str + str2);
    }

    public static String m(StringBuilder sb, int i, char c) {
        sb.append(i);
        sb.append(c);
        return sb.toString();
    }

    public static void m(StringBuilder sb, int i, String str) {
        sb.append(i);
        Log.d(str, sb.toString());
    }

    public static StringBuilder m(StringBuilder sb, boolean z, PrintWriter printWriter, String str) {
        sb.append(z);
        printWriter.println(sb);
        StringBuilder sb2 = new StringBuilder();
        sb2.append(str);
        return sb2;
    }

    public static String mnolog(String str, String str2, String str3) {
        return str + str2 + str3;
    }

    public static StringBuilder m(char c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c);
        return sb;
    }

    public static StringBuilder m(String str, String str2) {
        StringBuilder sb = new StringBuilder();
        sb.append(str);
        sb.append(str2);
        return sb;
    }

    public static String m(StringBuilder sb, String str, String str2) {
        sb.append(str);
        sb.append(str2);
        return sb.toString();
    }

    public static void m(String str, int i, String str2) {
        Log.d(str2, str + i);
    }

    public static StringBuilder m(PrintWriter printWriter, String str, String str2) {
        printWriter.println(str);
        StringBuilder sb = new StringBuilder();
        sb.append(str2);
        return sb;
    }

    public static StringBuilder m(StringBuilder sb, float f, PrintWriter printWriter, String str) {
        sb.append(f);
        printWriter.println(sb);
        StringBuilder sb2 = new StringBuilder();
        sb2.append(str);
        return sb2;
    }

    public static StringBuilder m(String str, int i, String str2, int i2, String str3) {
        StringBuilder sb = new StringBuilder();
        sb.append(str);
        sb.append(i);
        sb.append(str2);
        sb.append(i2);
        sb.append(str3);
        return sb;
    }

    public static void m(StringBuilder sb, boolean z, String str) {
        sb.append(z);
        Log.d(str, sb.toString());
    }

    public static String stringPlus(Object obj, String str) {
        return str + obj;
    }
}
