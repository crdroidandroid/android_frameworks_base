package com.google.android.systemui.smartspace.logging;

import java.util.Objects;

public final class BcSmartspaceCardLoggingInfo {
    public final int mCardinality;
    public final int mDisplaySurface;
    public int mFeatureType;
    public int mInstanceId;
    public final int mRank;
    public final int mReceivedLatency;
    public BcSmartspaceSubcardLoggingInfo mSubcardInfo;
    public final int mUid;

    public static final class Builder {
        public int mCardinality;
        public int mDisplaySurface = 1;
        public int mFeatureType;
        public int mInstanceId;
        public int mRank;
        public int mReceivedLatency;
        public BcSmartspaceSubcardLoggingInfo mSubcardInfo;
        public int mUid;
    }

    public final boolean equals(Object obj) {
        boolean z = true;
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof BcSmartspaceCardLoggingInfo)) {
            return false;
        }
        BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo = (BcSmartspaceCardLoggingInfo) obj;
        if (this.mInstanceId != bcSmartspaceCardLoggingInfo.mInstanceId || this.mDisplaySurface != bcSmartspaceCardLoggingInfo.mDisplaySurface || this.mRank != bcSmartspaceCardLoggingInfo.mRank || this.mCardinality != bcSmartspaceCardLoggingInfo.mCardinality || this.mFeatureType != bcSmartspaceCardLoggingInfo.mFeatureType || this.mReceivedLatency != bcSmartspaceCardLoggingInfo.mReceivedLatency || this.mUid != bcSmartspaceCardLoggingInfo.mUid || !Objects.equals(this.mSubcardInfo, bcSmartspaceCardLoggingInfo.mSubcardInfo)) {
            z = false;
        }
        return z;
    }

    public final String toString() {
        StringBuilder m = LogBuilder.m("instance_id = ");
        m.append(this.mInstanceId);
        m.append(", feature type = ");
        m.append(this.mFeatureType);
        m.append(", display surface = ");
        m.append(this.mDisplaySurface);
        m.append(", rank = ");
        m.append(this.mRank);
        m.append(", cardinality = ");
        m.append(this.mCardinality);
        m.append(", receivedLatencyMillis = ");
        m.append(this.mReceivedLatency);
        m.append(", uid = ");
        m.append(this.mUid);
        m.append(", subcardInfo = ");
        m.append(this.mSubcardInfo);
        return m.toString();
    }

    public BcSmartspaceCardLoggingInfo(Builder builder) {
        this.mInstanceId = builder.mInstanceId;
        this.mDisplaySurface = builder.mDisplaySurface;
        this.mRank = builder.mRank;
        this.mCardinality = builder.mCardinality;
        this.mFeatureType = builder.mFeatureType;
        this.mReceivedLatency = builder.mReceivedLatency;
        this.mUid = builder.mUid;
        this.mSubcardInfo = builder.mSubcardInfo;
    }

    public final int hashCode() {
        return Objects.hash(Integer.valueOf(this.mInstanceId), Integer.valueOf(this.mDisplaySurface), Integer.valueOf(this.mRank), Integer.valueOf(this.mCardinality), Integer.valueOf(this.mFeatureType), Integer.valueOf(this.mReceivedLatency), Integer.valueOf(this.mUid), this.mSubcardInfo);
    }
}
