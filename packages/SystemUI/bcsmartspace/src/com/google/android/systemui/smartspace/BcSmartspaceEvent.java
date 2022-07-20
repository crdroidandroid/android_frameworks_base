package com.google.android.systemui.smartspace;

public enum BcSmartspaceEvent implements EventEnum {
    IGNORE(-1),
    SMARTSPACE_CARD_RECEIVED(759),
    SMARTSPACE_CARD_CLICK(760),
    SMARTSPACE_CARD_DISMISS(761),
    SMARTSPACE_CARD_SEEN(800),
    ENABLED_SMARTSPACE(822),
    DISABLED_SMARTSPACE(823);
    
    private final int mId;

    BcSmartspaceEvent(int id) {
        this.mId = id;
    }

    @Override // com.google.android.systemui.smartspace.EventEnum
    public int getId() {
        return this.mId;
    }
}
