package com.google.android.systemui.smartspace;

import android.provider.DeviceConfig;

public final class LazyServerFlagLoader {
    public final String mPropertyKey;
    public Boolean mValue = null;

    public LazyServerFlagLoader(String key) {
        this.mPropertyKey = key;
    }

    public boolean get() {
        if (this.mValue == null) {
            this.mValue = Boolean.valueOf(DeviceConfig.getBoolean("launcher", this.mPropertyKey, true));
            DeviceConfig.addOnPropertiesChangedListener("launcher", (v0) -> {
                v0.run();
            }, properties -> {
                if (properties.getKeyset().contains(this.mPropertyKey)) {
                    this.mValue = Boolean.valueOf(properties.getBoolean(this.mPropertyKey, true));
                }
            });
        }
        return this.mValue.booleanValue();
    }
}
