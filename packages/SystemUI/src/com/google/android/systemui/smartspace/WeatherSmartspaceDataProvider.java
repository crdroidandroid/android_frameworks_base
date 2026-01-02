package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.os.Debug;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class WeatherSmartspaceDataProvider implements BcSmartspaceDataPlugin {
    public static final boolean DEBUG = Log.isLoggable("WeatherSSDataProvider", Log.DEBUG);
    public final Set<SmartspaceTargetListener> mSmartspaceTargetListeners = new HashSet<>();
    public final List<SmartspaceTarget> mSmartspaceTargets = new ArrayList<>();
    public final EventNotifierProxy mEventNotifier = new EventNotifierProxy();

    @Override
    public final BcSmartspaceDataPlugin.SmartspaceEventNotifier getEventNotifier() {
        return mEventNotifier;
    }

    @Override
    public final BcSmartspaceDataPlugin.SmartspaceView getLargeClockView(Context context) {
        View view = LayoutInflater.from(context).inflate(R.layout.weather_large, (ViewGroup) null, false);
        view.setId(R.id.weather_smartspace_view_large);
        return (BcSmartspaceDataPlugin.SmartspaceView) view;
    }

    @Override
    public final BcSmartspaceDataPlugin.SmartspaceView getView(Context context) {
        return (BcSmartspaceDataPlugin.SmartspaceView) LayoutInflater.from(context).inflate(R.layout.weather, (ViewGroup) null, false);
    }

    @Override
    public void onTargetsAvailable(List<SmartspaceTarget> targets) {
        if (DEBUG) {
            Log.d(
                    "WeatherSSDataProvider",
                    this
                            + " onTargetsAvailable called. Callers = "
                            + android.os.Debug.getCallers(3));
            Log.d("WeatherSSDataProvider", " targets.size() = " + targets.size());
            Log.d("WeatherSSDataProvider", " targets = " + targets.toString());
        }

        mSmartspaceTargets.clear();
        for (SmartspaceTarget target : targets) {
            if (target.getFeatureType() == 1) {
                mSmartspaceTargets.add(target);
            }
        }

        mSmartspaceTargetListeners.forEach(
                listener -> listener.onSmartspaceTargetsUpdated(mSmartspaceTargets));
    }

    @Override
    public final void registerListener(BcSmartspaceDataPlugin.SmartspaceTargetListener listener) {
        mSmartspaceTargetListeners.add(listener);
        listener.onSmartspaceTargetsUpdated(mSmartspaceTargets);
    }

    @Override
    public final void setEventDispatcher(BcSmartspaceDataPlugin.SmartspaceEventDispatcher eventDispatcher) {
        mEventNotifier.eventDispatcher = eventDispatcher;
    }

    @Override
    public final void setIntentStarter(BcSmartspaceDataPlugin.IntentStarter intentStarter) {
        mEventNotifier.intentStarterRef = intentStarter;
    }

    @Override
    public final void unregisterListener(BcSmartspaceDataPlugin.SmartspaceTargetListener listener) {
        mSmartspaceTargetListeners.remove(listener);
    }
}
