package com.google.android.systemui.smartspace.logging;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BcSmartspaceSubcardLoggingInfo {
    public int mClickedSubcardIndex;
    public List<BcSmartspaceCardMetadataLoggingInfo> mSubcards;

    public static final class Builder {
        public int mClickedSubcardIndex;
        public List<BcSmartspaceCardMetadataLoggingInfo> mSubcards;
    }

    public final boolean equals(Object obj) {
        boolean z = true;
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof BcSmartspaceSubcardLoggingInfo)) {
            return false;
        }
        BcSmartspaceSubcardLoggingInfo bcSmartspaceSubcardLoggingInfo = (BcSmartspaceSubcardLoggingInfo) obj;
        if (this.mClickedSubcardIndex != bcSmartspaceSubcardLoggingInfo.mClickedSubcardIndex || !Objects.equals(this.mSubcards, bcSmartspaceSubcardLoggingInfo.mSubcards)) {
            z = false;
        }
        return z;
    }

    public final String toString() {
        StringBuilder m = LogBuilder.m("BcSmartspaceSubcardLoggingInfo{mSubcards=");
        m.append(this.mSubcards);
        m.append(", mClickedSubcardIndex=");
        return LogBuilder.m(m, this.mClickedSubcardIndex, '}');
    }

    public BcSmartspaceSubcardLoggingInfo(Builder builder) {
        List<BcSmartspaceCardMetadataLoggingInfo> list = builder.mSubcards;
        if (list != null) {
            this.mSubcards = list;
        } else {
            this.mSubcards = new ArrayList();
        }
        this.mClickedSubcardIndex = builder.mClickedSubcardIndex;
    }

    public final int hashCode() {
        return Objects.hash(this.mSubcards, Integer.valueOf(this.mClickedSubcardIndex));
    }
}
