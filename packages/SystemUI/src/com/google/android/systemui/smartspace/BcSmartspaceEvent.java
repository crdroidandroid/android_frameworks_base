package com.google.android.systemui.smartspace;

public enum BcSmartspaceEvent {
    IGNORE(-1),
    SMARTSPACE_CARD_RECEIVED(759),
    SMARTSPACE_CARD_CLICK(760),
    SMARTSPACE_CARD_DISMISS(761),
    SMARTSPACE_CARD_SEEN(800),
    ENABLED_SMARTSPACE(822),
    DISABLED_SMARTSPACE(823),
    SMARTSPACE_CARD_SWIPE(1960);

    private final int mId;

    BcSmartspaceEvent(int i) {
        mId = i;
    }
    public final int getId() {
        return mId;
    }
}
