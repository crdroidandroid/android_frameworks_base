package com.google.android.systemui.smartspace.logging;

import java.util.List;
import java.util.Objects;

public final class BcSmartspaceSubcardLoggingInfo {
    public int mClickedSubcardIndex;
    public List<BcSmartspaceCardMetadataLoggingInfo> mSubcards;

    public final boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof BcSmartspaceSubcardLoggingInfo)) {
            return false;
        }
        BcSmartspaceSubcardLoggingInfo other = (BcSmartspaceSubcardLoggingInfo) obj;
        return mClickedSubcardIndex == other.mClickedSubcardIndex && Objects.equals(mSubcards, other.mSubcards);
    }

    public final int hashCode() {
        return Objects.hash(mSubcards, mClickedSubcardIndex);
    }

    public final String toString() {
        return "BcSmartspaceSubcardLoggingInfo{mSubcards="
                + mSubcards
                + ", mClickedSubcardIndex="
                + mClickedSubcardIndex
                + "}";
    }
}
