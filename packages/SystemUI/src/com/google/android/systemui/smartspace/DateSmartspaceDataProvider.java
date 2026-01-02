package com.google.android.systemui.smartspace;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.android.systemui.res.R;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public final class DateSmartspaceDataProvider
    implements BcSmartspaceDataPlugin {
  public Set<View.OnAttachStateChangeListener> mAttachListeners =
      new HashSet<>();
  public EventNotifierProxy mEventNotifier = new EventNotifierProxy();
  public final View.OnAttachStateChangeListener mStateChangeListener =
      new StateChangeListener();
  public Set<View> mViews = new HashSet<>();

  public final class StateChangeListener
      implements View.OnAttachStateChangeListener {
    @Override
    public final void onViewAttachedToWindow(View view) {
      mViews.add(view);
      Iterator<View.OnAttachStateChangeListener> iterator =
          mAttachListeners.iterator();
      while (iterator.hasNext()) {
        View.OnAttachStateChangeListener listener = iterator.next();
        listener.onViewAttachedToWindow(view);
      }
    }

    @Override
    public final void onViewDetachedFromWindow(View view) {
      mViews.remove(view);
      Iterator<View.OnAttachStateChangeListener> iterator =
          mAttachListeners.iterator();
      while (iterator.hasNext()) {
        View.OnAttachStateChangeListener listener = iterator.next();
        listener.onViewDetachedFromWindow(view);
      }
    }
  }

  @Override
  public final void
  addOnAttachStateChangeListener(View.OnAttachStateChangeListener listener) {
    mAttachListeners.add(listener);
    Iterator<View> iterator = mViews.iterator();
    while (iterator.hasNext()) {
      View view = iterator.next();
      listener.onViewAttachedToWindow(view);
    }
  }

  @Override
  public final BcSmartspaceDataPlugin.SmartspaceEventNotifier
  getEventNotifier() {
    return mEventNotifier;
  }

  @Override
  public final BcSmartspaceDataPlugin.SmartspaceView
  getLargeClockView(Context context) {
    View view = LayoutInflater.from(context).inflate(
        R.layout.date_plus_extras_large, (ViewGroup)null, false);
    view.setId(R.id.date_smartspace_view_large);
    view.addOnAttachStateChangeListener(this.mStateChangeListener);
    return (BcSmartspaceDataPlugin.SmartspaceView)view;
  }

  @Override
  public final BcSmartspaceDataPlugin.SmartspaceView getView(Context context) {
    View view = LayoutInflater.from(context).inflate(R.layout.date_plus_extras,
                                                     (ViewGroup)null, false);
    view.addOnAttachStateChangeListener(this.mStateChangeListener);
    return (BcSmartspaceDataPlugin.SmartspaceView)view;
  }

  @Override
  public final void setEventDispatcher(
      BcSmartspaceDataPlugin.SmartspaceEventDispatcher eventDispatcher) {
    mEventNotifier.eventDispatcher = eventDispatcher;
  }

  @Override
  public final void
  setIntentStarter(BcSmartspaceDataPlugin.IntentStarter intentStarter) {
    mEventNotifier.intentStarterRef = intentStarter;
  }
}
