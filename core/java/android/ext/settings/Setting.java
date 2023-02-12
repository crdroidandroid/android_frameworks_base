/*
 * Copyright (C) 2022 GrapheneOS
 * SPDX-License-Identifier: Apache-2.0
 */

package android.ext.settings;

import android.annotation.Nullable;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemProperties;
import android.provider.Settings;

import java.util.function.Consumer;

/** @hide */
public abstract class Setting<SelfType extends Setting> {

    public enum Scope {
        SYSTEM_PROPERTY, // android.os.SystemProperties, doesn't support state observers
        GLOBAL, // android.provider.Settings.Global
        PER_USER, // android.provider.Settings.Secure
    }

    private final Scope scope;
    private final String key;

    protected Setting(Scope scope, String key) {
        this.scope = scope;
        this.key = key;
    }

    public final String getKey() {
        return key;
    }

    public final Scope getScope() {
        return scope;
    }

    @Nullable
    protected final String getRaw(Context ctx, int userId) {
        try {
            switch (scope) {
                case SYSTEM_PROPERTY: {
                    String s = SystemProperties.get(key);
                    if (s.isEmpty()) {
                        return null;
                    }
                    return s;
                }
                case GLOBAL:
                    return Settings.Global.getString(ctx.getContentResolver(), key);
                case PER_USER:
                    return Settings.Secure.getStringForUser(ctx.getContentResolver(), key, userId);
            }
        } catch (Throwable e) {
            e.printStackTrace();
            if (Settings.isInSystemServer()) {
                // should never happen under normal circumstances, but if it does,
                // don't crash the system_server
                return null;
            }

            throw e;
        }

        // "switch (scope)" above should be exhaustive
        throw new IllegalStateException();
    }

    protected final boolean putRaw(Context ctx, String val) {
        switch (scope) {
            case SYSTEM_PROPERTY: {
                try {
                    SystemProperties.set(key, val);
                    return true;
                } catch (RuntimeException e) {
                    e.printStackTrace();
                    if (e instanceof IllegalArgumentException) {
                        // see doc
                        throw e;
                    }
                    return false;
                }
            }
            case GLOBAL:
                return Settings.Global.putString(ctx.getContentResolver(), key, val);
            case PER_USER:
                return Settings.Secure.putString(ctx.getContentResolver(), key, val);
            default:
                throw new IllegalStateException();
        }
    }

    public final boolean canObserveState() {
        return scope != Scope.SYSTEM_PROPERTY;
    }

    // pass the return value to unregisterObserver() to remove the observer
    public final Object registerObserver(Context ctx, Consumer<SelfType> callback, Handler handler) {
        return registerObserver(ctx, ctx.getUserId(), callback, handler);
    }

    public final Object registerObserver(Context ctx, int userId, Consumer<SelfType> callback, Handler handler) {
        if (scope == Scope.SYSTEM_PROPERTY) {
            // SystemProperties.addChangeCallback() doesn't work unless the change is actually
            // reported elsewhere in the same process with SystemProperties.callChangeCallbacks()
            // or with its native equivalent (report_sysprop_change()).
            // Leave the code in place in case this changes in the future.
            if (false) {
                Runnable observer = new Runnable() {
                    private volatile String prev = SystemProperties.get(getKey());

                    @Override
                    public void run() {
                        String value = SystemProperties.get(getKey());
                        // change callback is dispatched whenever any change to system props occurs
                        if (!prev.equals(value)) {
                            prev = value;
                            handler.post(() -> callback.accept((SelfType) Setting.this));
                        }
                    }
                };
                SystemProperties.addChangeCallback(observer);
                return observer;
            }
            throw new UnsupportedOperationException("observing sysprop state is not supported");
        }

        Uri uri;
        switch (scope) {
            case GLOBAL:
                uri = Settings.Global.getUriFor(key);
                break;
            case PER_USER:
                uri = Settings.Secure.getUriFor(key);
                break;
            default:
                throw new IllegalStateException();
        }

        ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                callback.accept((SelfType) Setting.this);
            }
        };
        ctx.getContentResolver().registerContentObserver(uri, false, observer, userId);

        return observer;
    }

    public final void unregisterObserver(Context ctx, Object observer) {
        if (scope == Scope.SYSTEM_PROPERTY) {
            if (false) { // see comment in registerObserverInner
                SystemProperties.removeChangeCallback((Runnable) observer);
            }
            throw new UnsupportedOperationException("observing sysprop state is not supported");
        } else {
            ctx.getContentResolver().unregisterContentObserver((ContentObserver) observer);
        }
    }
}
