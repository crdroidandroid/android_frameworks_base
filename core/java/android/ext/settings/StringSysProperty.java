package android.ext.settings;

import android.os.UserHandle;

import java.util.function.Supplier;

/** @hide */
public class StringSysProperty extends StringSetting {

    public StringSysProperty(String key, String defaultValue) {
        super(Scope.SYSTEM_PROPERTY, key, defaultValue);
    }

    public StringSysProperty(String key, Supplier<String> defaultValue) {
        super(Scope.SYSTEM_PROPERTY, key, defaultValue);
    }

    public String get() {
        return super.get(null, UserHandle.USER_SYSTEM);
    }

    public boolean put(String val) {
        return super.put(null, val);
    }
}
