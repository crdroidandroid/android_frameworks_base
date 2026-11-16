package com.android.systemui.axdynamicbar.ui

import com.android.systemui.kosmos.Kosmos
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

var Kosmos.axDynamicBarChipViewModel: AxDynamicBarChipViewModel by
    Kosmos.Fixture {
        mock<AxDynamicBarChipViewModel> {
            on { isEnabled } doReturn MutableStateFlow(false)
            on { chipState } doReturn MutableStateFlow(null)
            on { isOnKeyguard } doReturn MutableStateFlow(false)
            on { isShowingOnStatusBar } doReturn MutableStateFlow(false)
        }
    }
