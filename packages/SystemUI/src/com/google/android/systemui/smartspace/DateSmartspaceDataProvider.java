package com.google.android.systemui.smartspace;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.res.R;

import java.util.HashSet;
import java.util.Set;

public final class DateSmartspaceDataProvider
        implements BcSmartspaceDataPlugin {

    private final Set<View.OnAttachStateChangeListener> mAttachListeners =
            new HashSet<>();
    private final Set<View> mViews = new HashSet<>();
    private final EventNotifierProxy mEventNotifier =
            new EventNotifierProxy();

    private final View.OnAttachStateChangeListener mStateChangeListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    mViews.add(view);
                    for (View.OnAttachStateChangeListener l : mAttachListeners) {
                        l.onViewAttachedToWindow(view);
                    }
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    mViews.remove(view);
                    for (View.OnAttachStateChangeListener l : mAttachListeners) {
                        l.onViewDetachedFromWindow(view);
                    }
                }
            };

    @Override
    public void addOnAttachStateChangeListener(
            View.OnAttachStateChangeListener listener) {
        mAttachListeners.add(listener);
        for (View view : mViews) {
            listener.onViewAttachedToWindow(view);
        }
    }

    @Override
    public SmartspaceEventNotifier getEventNotifier() {
        return mEventNotifier;
    }

    @Override
    public SmartspaceView getView(ViewGroup parent) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.date_plus_extras, parent, false);
        view.addOnAttachStateChangeListener(mStateChangeListener);
        return (SmartspaceView) view;
    }

    @Override
    public SmartspaceView getLargeClockView(ViewGroup parent) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.date_plus_extras_large, parent, false);
        view.setId(R.id.date_smartspace_view_large);
        view.addOnAttachStateChangeListener(mStateChangeListener);
        return (SmartspaceView) view;
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
