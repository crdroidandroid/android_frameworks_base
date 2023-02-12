package android.ext.settings;

import android.os.UserHandle;

import java.util.function.BooleanSupplier;

/** @hide */
public class BoolSysProperty extends BoolSetting {

    public BoolSysProperty(String key, boolean defaultValue) {
        super(Scope.SYSTEM_PROPERTY, key, defaultValue);
    }

    public BoolSysProperty(String key, BooleanSupplier defaultValue) {
        super(Scope.SYSTEM_PROPERTY, key, defaultValue);
    }

    public boolean get() {
        return super.get(null, UserHandle.USER_SYSTEM);
    }

    public boolean put(boolean val) {
        return super.put(null, val);
    }
}
