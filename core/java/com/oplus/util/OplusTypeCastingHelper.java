package com.oplus.util;

public final class OplusTypeCastingHelper {
    public static <T> T typeCasting(Class<T> type, Object object) {
        if (object != null && type.isInstance(object)) {
            return type.cast(object);
        }
        return null;
    }
}
