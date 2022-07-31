package com.android.server.ext;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

// Note that instances of IntentReceiver subclasses are:
// - singletons
// - registered lazily
// - never unregistered
// - not automatically re-registered after process restart (doesn't matter in system_server)
public abstract class IntentReceiver extends BroadcastReceiver {
    protected final String TAG = getClass().getSimpleName();

    private static final ArrayMap<Class, IntentReceiver> map = new ArrayMap<>();

    public static <T extends IntentReceiver> IntentReceiver getInstance(Class<T> cls, Context ctx) {
        synchronized (map) {
            IntentReceiver instance = map.get(cls);
            if (instance == null) {
                try {
                    instance = cls.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                instance.context = ctx;
                var filter = new IntentFilter(instance.getIntentAction());
                ctx.registerReceiver(instance, filter, null,
                        instance.getScheduler(), Context.RECEIVER_NOT_EXPORTED);
                map.put(cls, instance);
            }
            return instance;
        }
    }

    private Context context;

    private PendingIntent basePendingIntent;
    private long prevId;

    private String getIntentAction() {
        return getClass().getName();
    }

    private Intent getBaseIntent() {
        var i = new Intent(getIntentAction());
        i.setPackage(context.getPackageName());
        return i;
    }

    public static <T extends IntentReceiver> PendingIntent getPendingIntent(
            Class<T> cls, Context ctx, @Nullable Bundle args) {
        return getInstance(cls, ctx).getPendingIntent(args);
    }

    public PendingIntent getPendingIntent(@Nullable Bundle args) {
        if (args == null) {
            synchronized (this) {
                PendingIntent base = basePendingIntent;
                if (base == null) {
                    base = PendingIntent.getBroadcast(context, 0, getBaseIntent(), PendingIntent.FLAG_IMMUTABLE);
                    basePendingIntent = base;
                }
                return base;
            }
        } else {
            long id;
            synchronized (this) {
                id = prevId++;
            }

            var i = getBaseIntent();
            i.setIdentifier(Long.toString(id));
            i.replaceExtras(args);
            return PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE);
        }
    }

    @Override
    public final void onReceive(Context context, Intent intent) {
        Slog.d(TAG, "onReceive: " + intent);
        String idStr = intent.getIdentifier();
        Bundle args = Bundle.EMPTY;
        if (idStr != null) {
            args = intent.getExtras();
        }
        onReceive(context, args);
    }

    public Handler getScheduler() {
        return BackgroundThread.getHandler();
    }

    public abstract void onReceive(Context ctx, Bundle args);
}
