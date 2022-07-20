package com.google.android.systemui.smartspace.logging;

import java.util.Objects;

public final class BcSmartspaceCardMetadataLoggingInfo {
    public final int mCardTypeId;
    public final int mInstanceId;

    public static final class Builder {
        public int mCardTypeId;
        public int mInstanceId;
    }

    public final boolean equals(Object obj) {
        boolean z = true;
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof BcSmartspaceCardMetadataLoggingInfo)) {
            return false;
        }
        BcSmartspaceCardMetadataLoggingInfo bcSmartspaceCardMetadataLoggingInfo = (BcSmartspaceCardMetadataLoggingInfo) obj;
        if (this.mInstanceId != bcSmartspaceCardMetadataLoggingInfo.mInstanceId || this.mCardTypeId != bcSmartspaceCardMetadataLoggingInfo.mCardTypeId) {
            z = false;
        }
        return z;
    }

    public final String toString() {
        StringBuilder m = LogBuilder.m("BcSmartspaceCardMetadataLoggingInfo{mInstanceId=");
        m.append(this.mInstanceId);
        m.append(", mCardTypeId=");
        return LogBuilder.m(m, this.mCardTypeId, '}');
    }

    public BcSmartspaceCardMetadataLoggingInfo(Builder builder) {
        this.mInstanceId = builder.mInstanceId;
        this.mCardTypeId = builder.mCardTypeId;
    }

    public final int hashCode() {
        return Objects.hash(Integer.valueOf(this.mInstanceId), Integer.valueOf(this.mCardTypeId));
    }
}
