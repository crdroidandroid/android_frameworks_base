package android.ext.settings;

import android.os.UserHandle;

import java.util.function.IntSupplier;

/** @hide */
public class IntSysProperty extends IntSetting {

    public IntSysProperty(String key, int defaultValue) {
        super(Scope.SYSTEM_PROPERTY, key, defaultValue);
    }

    public IntSysProperty(String key, int defaultValue, int... validValues) {
        super(Scope.SYSTEM_PROPERTY, key, defaultValue, validValues);
    }

    public IntSysProperty(String key, IntSupplier defaultValue) {
        super(Scope.SYSTEM_PROPERTY, key, defaultValue);
    }

    public IntSysProperty(String key, IntSupplier defaultValue, int... validValues) {
        super(Scope.SYSTEM_PROPERTY, key, defaultValue, validValues);
    }

    public int get() {
        return super.get(null, UserHandle.USER_SYSTEM);
    }

    public boolean put(int val) {
        return super.put(null, val);
    }
}
