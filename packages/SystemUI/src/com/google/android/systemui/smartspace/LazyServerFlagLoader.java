package com.google.android.systemui.smartspace;

import android.provider.DeviceConfig;

import java.util.Set;
import java.util.concurrent.Executors;

public final class LazyServerFlagLoader {
    public final String mPropertyKey;
    public Boolean mValue = null;

    public LazyServerFlagLoader(String str) {
        mPropertyKey = str;
    }

    public boolean get() {
        if (mValue == null) {
            mValue = Boolean.valueOf(DeviceConfig.getBoolean("launcher", mPropertyKey, true));
            DeviceConfig.addOnPropertiesChangedListener(
                    "launcher",
                    Executors.newSingleThreadExecutor(),
                    new OnPropertiesChangedListener(this));
        }
        return mValue.booleanValue();
    }

    private static class OnPropertiesChangedListener
            implements DeviceConfig.OnPropertiesChangedListener {
        private final LazyServerFlagLoader lazyServerFlagLoader;

        OnPropertiesChangedListener(LazyServerFlagLoader loader) {
            lazyServerFlagLoader = loader;
        }

        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            Set<String> keyset = properties.getKeyset();
            String str = lazyServerFlagLoader.mPropertyKey;
            if (keyset.contains(str)) {
                lazyServerFlagLoader.mValue = Boolean.valueOf(properties.getBoolean(str, true));
            }
        }
    }
}
