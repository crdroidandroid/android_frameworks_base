package com.android.systemui.axdynamicbar.model

data class IslandUiState(
    val events: List<IslandEvent> = emptyList(),
    val islandState: IslandState = IslandState.HIDDEN,
    val pinnedEventIndex: Int = 0,
    val manuallyHidden: Boolean = false,
    val forceVisible: Boolean = false,
) {

    val shouldShow: Boolean
        get() = islandState == IslandState.CHIP

    val isChip: Boolean
        get() = islandState == IslandState.CHIP

    val topEvent: IslandEvent?
        get() = events.getOrNull(pinnedEventIndex) ?: events.firstOrNull()

    val activeEvents: List<IslandEvent>
        get() = events.filter { it !is IslandEvent.Notification }
}
