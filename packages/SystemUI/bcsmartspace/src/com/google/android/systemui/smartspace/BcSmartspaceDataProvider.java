package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceTargetEvent;
import android.os.Debug;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.android.systemui.bcsmartspace.R;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class BcSmartspaceDataProvider implements BcSmartspaceDataPlugin {
    public static final boolean DEBUG = Log.isLoggable("BcSmartspaceDataPlugin", 3);
    public final HashSet<BcSmartspaceDataPlugin.SmartspaceTargetListener> mSmartspaceTargetListeners = new HashSet<>();
    public final ArrayList<SmartspaceTarget> mSmartspaceTargets = new ArrayList<>();
    public HashSet<View> mViews = new HashSet<>();
    public HashSet<View.OnAttachStateChangeListener> mAttachListeners = new HashSet<>();
    public BcSmartspaceDataPlugin.SmartspaceEventNotifier mEventNotifier = null;
    public View.OnAttachStateChangeListener mStateChangeListener = new View.OnAttachStateChangeListener() { // from class: com.google.android.systemui.smartspace.BcSmartspaceDataProvider.1
        @Override // android.view.View.OnAttachStateChangeListener
        public void onViewAttachedToWindow(View view) {
            BcSmartspaceDataProvider.this.mViews.add(view);
            BcSmartspaceDataProvider.this.mAttachListeners.forEach(listener -> {
                listener.onViewAttachedToWindow(view);
            });
        }

        @Override // android.view.View.OnAttachStateChangeListener
        public void onViewDetachedFromWindow(View view) {
            BcSmartspaceDataProvider.this.mViews.remove(view);
            view.removeOnAttachStateChangeListener(this);
            BcSmartspaceDataProvider.this.mAttachListeners.forEach(listener -> {
                listener.onViewDetachedFromWindow(view);
            });
        }
    };

    public void registerListener(BcSmartspaceDataPlugin.SmartspaceTargetListener listener) {
        this.mSmartspaceTargetListeners.add(listener);
        listener.onSmartspaceTargetsUpdated(this.mSmartspaceTargets);
    }

    public void unregisterListener(BcSmartspaceDataPlugin.SmartspaceTargetListener listener) {
        this.mSmartspaceTargetListeners.remove(listener);
    }

    public void registerSmartspaceEventNotifier(BcSmartspaceDataPlugin.SmartspaceEventNotifier notifier) {
        this.mEventNotifier = notifier;
    }

    public void notifySmartspaceEvent(SmartspaceTargetEvent event) {
        if (this.mEventNotifier != null) {
            this.mEventNotifier.notifySmartspaceEvent(event);
        }
    }

    public BcSmartspaceDataPlugin.SmartspaceView getView(ViewGroup parent) {
        View inflate = LayoutInflater.from(parent.getContext()).inflate(R.layout.smartspace_enhanced, parent, false);
        inflate.addOnAttachStateChangeListener(this.mStateChangeListener);
        return (BcSmartspaceDataPlugin.SmartspaceView) inflate;
    }

    public void addOnAttachStateChangeListener(View.OnAttachStateChangeListener listener) {
        this.mAttachListeners.add(listener);
        HashSet<View> hashSet = this.mViews;
        Objects.requireNonNull(listener);
        hashSet.forEach(v -> mStateChangeListener.onViewAttachedToWindow(v));
    }

    public void onTargetsAvailable(List<SmartspaceTarget> targets) {
        if (DEBUG) {
            Log.d("BcSmartspaceDataPlugin", this + " onTargetsAvailable called. Callers = " + Debug.getCallers(3));
            Log.d("BcSmartspaceDataPlugin", "    targets.size() = " + targets.size());
            Log.d("BcSmartspaceDataPlugin", "    targets = " + targets);
        }
        this.mSmartspaceTargets.clear();
        for (SmartspaceTarget smartspaceTarget : targets) {
            if (smartspaceTarget.getFeatureType() != 15) {
                this.mSmartspaceTargets.add(smartspaceTarget);
            }
        }
        this.mSmartspaceTargetListeners.forEach(listener -> {
            listener.onSmartspaceTargetsUpdated(this.mSmartspaceTargets);
        });
    }
}
