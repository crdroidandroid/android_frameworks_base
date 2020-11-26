package com.google.android.systemui.assist;

import android.content.Context;
import android.os.UserManager;
import android.view.View;
import com.android.systemui.statusbar.phone.StatusBar;
import dagger.Lazy;

import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class OpaEnabledDispatcher implements OpaEnabledListener {
    @Inject
    public Lazy<StatusBar> mStatusBarLazy;

    @Inject
    public OpaEnabledDispatcher(Lazy<StatusBar> lazy) {
        mStatusBarLazy = lazy;
    }

    @Override
    public void onOpaEnabledReceived(Context context, boolean z, boolean z2, boolean z3) {
        dispatchUnchecked((z && z2) || UserManager.isDeviceInDemoMode(context));
    }

    private void dispatchUnchecked(boolean z) {
        StatusBar statusBar = mStatusBarLazy.get();
        if (statusBar.getNavigationBarView() != null) {
            ArrayList<View> views = statusBar.getNavigationBarView().getHomeButton().getViews();
            for (int i = 0; i < views.size(); i++) {
                ((OpaLayout) views.get(i)).setOpaEnabled(z);
            }
        }
    }
}
