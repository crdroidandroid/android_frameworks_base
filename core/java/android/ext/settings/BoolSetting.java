/*
 * Copyright (C) 2022 GrapheneOS
 * SPDX-License-Identifier: Apache-2.0
 */

package android.ext.settings;

import android.content.Context;

import java.util.function.BooleanSupplier;

/** @hide */
public class BoolSetting extends Setting<BoolSetting> {
    private boolean defaultValue;
    private volatile BooleanSupplier defaultValueSupplier;

    public BoolSetting(Scope scope, String key, boolean defaultValue) {
        super(scope, key);
        this.defaultValue = defaultValue;
    }

    public BoolSetting(Scope scope, String key, BooleanSupplier defaultValue) {
        super(scope, key);
        defaultValueSupplier = defaultValue;
    }

    public final boolean get(Context ctx) {
        return get(ctx, ctx.getUserId());
    }

    // use only if this is a per-user setting and the context is not a per-user one
    public final boolean get(Context ctx, int userId) {
        String valueStr = getRaw(ctx, userId);

        if (valueStr == null) {
            return getDefaultValue();
        }

        if (valueStr.equals("true")) {
            return true;
        }

        if (valueStr.equals("false")) {
            return false;
        }

        try {
            int valueInt = Integer.parseInt(valueStr);
            if (valueInt == 1) {
                return true;
            } else if (valueInt == 0) {
                return false;
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }

        return getDefaultValue();
    }

    public final boolean put(Context ctx, boolean val) {
        return putRaw(ctx, val ? "1" : "0");
    }

    private boolean getDefaultValue() {
        BooleanSupplier supplier = defaultValueSupplier;
        if (supplier != null) {
            defaultValue = supplier.getAsBoolean();
            defaultValueSupplier = null;
        }
        return defaultValue;
    }
}
