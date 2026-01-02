package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.os.Debug;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.android.systemui.res.R;

import com.android.systemui.plugins.BcSmartspaceConfigPlugin;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

public final class BcSmartspaceDataProvider implements BcSmartspaceDataPlugin {
    public static final boolean DEBUG = Log.isLoggable("BcSmartspaceDataPlugin", 3);

    public final View.OnAttachStateChangeListener mStateChangeListener;
    public final Set<BcSmartspaceDataPlugin.SmartspaceTargetListener> mSmartspaceTargetListeners = new CopyOnWriteArraySet<>();
    public List<SmartspaceTarget> mSmartspaceTargets = new ArrayList<>();
    public final Set<View> mViews = new HashSet<>();
    public final Set<View.OnAttachStateChangeListener> mAttachListeners = new HashSet<>();
    public final EventNotifierProxy mEventNotifier = new EventNotifierProxy();
    public BcSmartspaceConfigPlugin mConfigProvider = new DefaultBcSmartspaceConfigProvider();

    public final class StateChangeListener implements View.OnAttachStateChangeListener {
        @Override
        public void onViewAttachedToWindow(View view) {
            mViews.add(view);
            for (View.OnAttachStateChangeListener listener : mAttachListeners) {
                listener.onViewAttachedToWindow(view);
            }
            if (view instanceof BcSmartspaceView) {
                ((BcSmartspaceView) view).registerDataProvider(BcSmartspaceDataProvider.this);
            }
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            mViews.remove(view);
            for (View.OnAttachStateChangeListener listener : mAttachListeners) {
                listener.onViewDetachedFromWindow(view);
            }
        }
    }

    public BcSmartspaceDataProvider() {
        mStateChangeListener = new StateChangeListener();
    }

    @Override
    public void addOnAttachStateChangeListener(View.OnAttachStateChangeListener listener) {
        mAttachListeners.add(listener);
        for (View view : mViews) {
            listener.onViewAttachedToWindow(view);
        }
    }

    @Override
    public BcSmartspaceDataPlugin.SmartspaceEventNotifier getEventNotifier() {
        return mEventNotifier;
    }

    @Override
    public BcSmartspaceDataPlugin.SmartspaceView getView(Context context) {
        int layoutId = mConfigProvider.isViewPager2Enabled()
                ? R.layout.smartspace_enhanced2
                : R.layout.smartspace_enhanced;

        View view = LayoutInflater.from(context).inflate(layoutId, (ViewGroup) null, false);
        view.addOnAttachStateChangeListener(mStateChangeListener);

        // Explicitly register data provider.
        // Note: The StateChangeListener also attempts this on attach, but doing it here ensures immediate availability.
        if (view instanceof BcSmartspaceView) {
            ((BcSmartspaceView) view).registerDataProvider(this);
        }

        return (BcSmartspaceDataPlugin.SmartspaceView) view;
    }

    @Override
    public void onTargetsAvailable(List<SmartspaceTarget> list) {
        if (DEBUG) {
            Log.d("BcSmartspaceDataPlugin", this + " onTargetsAvailable called. Callers = " + Debug.getCallers(3));
            Log.d("BcSmartspaceDataPlugin", "    targets.size() = " + list.size());
        }

        // Filter out feature type 15 (MEDIA?) as seen in reference implementation
        mSmartspaceTargets = list.stream()
                .filter(target -> target.getFeatureType() != 15)
                .collect(Collectors.toList());

        mSmartspaceTargetListeners.forEach(listener -> listener.onSmartspaceTargetsUpdated(mSmartspaceTargets));
    }

    @Override
    public void registerConfigProvider(BcSmartspaceConfigPlugin configPlugin) {
        mConfigProvider = configPlugin;
    }

    @Override
    public void registerListener(BcSmartspaceDataPlugin.SmartspaceTargetListener listener) {
        mSmartspaceTargetListeners.add(listener);
        listener.onSmartspaceTargetsUpdated(mSmartspaceTargets);
    }

    @Override
    public void unregisterListener(BcSmartspaceDataPlugin.SmartspaceTargetListener listener) {
        mSmartspaceTargetListeners.remove(listener);
    }

    @Override
    public void setEventDispatcher(BcSmartspaceDataPlugin.SmartspaceEventDispatcher dispatcher) {
        mEventNotifier.eventDispatcher = dispatcher;
    }

    @Override
    public void setIntentStarter(BcSmartspaceDataPlugin.IntentStarter intentStarter) {
        mEventNotifier.intentStarterRef = intentStarter;
    }
}
