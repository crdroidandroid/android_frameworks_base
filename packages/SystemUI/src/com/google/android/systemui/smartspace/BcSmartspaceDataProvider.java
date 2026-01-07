package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceTarget;
import android.os.Debug;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.plugins.BcSmartspaceConfigPlugin;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

public final class BcSmartspaceDataProvider
        implements BcSmartspaceDataPlugin {

    private static final boolean DEBUG =
            Log.isLoggable("BcSmartspaceDataPlugin", Log.DEBUG);

    private final Set<SmartspaceTargetListener> mListeners =
            new CopyOnWriteArraySet<>();
    private final Set<View> mViews = new HashSet<>();
    private final Set<View.OnAttachStateChangeListener> mAttachListeners =
            new HashSet<>();

    private List<SmartspaceTarget> mTargets = new ArrayList<>();
    private final EventNotifierProxy mEventNotifier =
            new EventNotifierProxy();

    private BcSmartspaceConfigPlugin mConfigProvider =
            new DefaultBcSmartspaceConfigProvider();

    private final View.OnAttachStateChangeListener mStateListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    mViews.add(v);
                    for (View.OnAttachStateChangeListener l : mAttachListeners) {
                        l.onViewAttachedToWindow(v);
                    }
                    if (v instanceof SmartspaceView) {
                        ((SmartspaceView) v).registerDataProvider(
                                BcSmartspaceDataProvider.this);
                    }
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    mViews.remove(v);
                    for (View.OnAttachStateChangeListener l : mAttachListeners) {
                        l.onViewDetachedFromWindow(v);
                    }
                }
            };

    @Override
    public void addOnAttachStateChangeListener(
            View.OnAttachStateChangeListener listener) {
        mAttachListeners.add(listener);
        for (View v : mViews) {
            listener.onViewAttachedToWindow(v);
        }
    }

    @Override
    public SmartspaceEventNotifier getEventNotifier() {
        return mEventNotifier;
    }

    @Override
    public SmartspaceView getView(ViewGroup parent) {
        int layout = mConfigProvider.isViewPager2Enabled()
                ? R.layout.smartspace_enhanced2
                : R.layout.smartspace_enhanced;

        View view = LayoutInflater.from(parent.getContext())
                .inflate(layout, parent, false);
        view.addOnAttachStateChangeListener(mStateListener);
        return (SmartspaceView) view;
    }

    @Override
    public void onTargetsAvailable(List<SmartspaceTarget> targets) {
        if (DEBUG) {
            Log.d("BcSmartspaceDataPlugin",
                    this + " onTargetsAvailable. Callers="
                            + Debug.getCallers(3));
        }

        mTargets = targets.stream()
                .filter(t -> t.getFeatureType() != 15)
                .collect(Collectors.toList());

        mListeners.forEach(
                l -> l.onSmartspaceTargetsUpdated(mTargets));
    }

    @Override
    public void registerListener(SmartspaceTargetListener listener) {
        mListeners.add(listener);
        listener.onSmartspaceTargetsUpdated(mTargets);
    }

    @Override
    public void unregisterListener(SmartspaceTargetListener listener) {
        mListeners.remove(listener);
    }

    @Override
    public void registerConfigProvider(
            BcSmartspaceConfigPlugin configProvider) {
        mConfigProvider = configProvider;
    }

    @Override
    public void setEventDispatcher(SmartspaceEventDispatcher dispatcher) {
        mEventNotifier.eventDispatcher = dispatcher;
    }

    @Override
    public void setIntentStarter(IntentStarter intentStarter) {
        mEventNotifier.intentStarterRef = intentStarter;
    }
}
