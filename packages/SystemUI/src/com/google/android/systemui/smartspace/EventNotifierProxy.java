package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceTargetEvent;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

public final class EventNotifierProxy implements BcSmartspaceDataPlugin.SmartspaceEventNotifier {
    public BcSmartspaceDataPlugin.SmartspaceEventDispatcher eventDispatcher;
    public BcSmartspaceDataPlugin.IntentStarter intentStarterRef;

    @Override
    public final BcSmartspaceDataPlugin.IntentStarter getIntentStarter() {
        return intentStarterRef;
    }

    @Override
    public final void notifySmartspaceEvent(SmartspaceTargetEvent smartspaceTargetEvent) {
        if (eventDispatcher != null) {
            eventDispatcher.notifySmartspaceEvent(smartspaceTargetEvent);
        }
    }
}
