/*
 * Copyright (C) 2022 GrapheneOS
 * SPDX-License-Identifier: Apache-2.0
 */

package android.ext.settings;

import android.content.Context;

import java.util.function.Supplier;

/** @hide */
public class StringSetting extends Setting<StringSetting> {
    private String defaultValue;
    private volatile Supplier<String> defaultValueSupplier;

    public StringSetting(Scope scope, String key, String defaultValue) {
        super(scope, key);
        setDefaultValue(defaultValue);
    }

    public StringSetting(Scope scope, String key, Supplier<String> defaultValue) {
        super(scope, key);
        this.defaultValueSupplier = defaultValue;
    }

    public boolean validateValue(String val) {
        return true;
    }

    public final String get(Context ctx) {
        return get(ctx, ctx.getUserId());
    }

    // use only if this is a per-user setting and the context is not a per-user one
    public final String get(Context ctx, int userId) {
        String s = getRaw(ctx, userId);
        if (s == null || !validateValue(s)) {
            return getDefaultValue();
        }
        return s;
    }

    public final boolean put(Context ctx, String val) {
        if (!validateValue(val)) {
            throw new IllegalStateException("invalid value " + val);
        }
        return putRaw(ctx, val);
    }

    private void setDefaultValue(String val) {
        if (!validateValue(val)) {
            throw new IllegalStateException("invalid default value " + val);
        }
        defaultValue = val;
    }

    private String getDefaultValue() {
        Supplier<String> supplier = defaultValueSupplier;
        if (supplier != null) {
            setDefaultValue(supplier.get());
            defaultValueSupplier = null;
        }
        return defaultValue;
    }
}
