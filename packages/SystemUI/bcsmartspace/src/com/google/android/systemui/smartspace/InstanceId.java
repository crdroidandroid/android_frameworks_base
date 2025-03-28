package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceTarget;

import java.util.UUID;

public class InstanceId {
    public static int create(SmartspaceTarget smartspaceTarget) {
        if (smartspaceTarget == null) {
            return SmallHash.hash(UUID.randomUUID().toString());
        }
        String smartspaceTargetId = smartspaceTarget.getSmartspaceTargetId();
        if (smartspaceTargetId != null && !smartspaceTargetId.isEmpty()) {
            return SmallHash.hash(smartspaceTargetId);
        }
        return SmallHash.hash(String.valueOf(smartspaceTarget.getCreationTimeMillis()));
    }

    public static int create(String str) {
        if (str != null && !str.isEmpty()) {
            return SmallHash.hash(str);
        }
        return SmallHash.hash(UUID.randomUUID().toString());
    }
}
