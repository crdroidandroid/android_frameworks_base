package com.google.android.systemui.keyguard.data.repository

import com.google.android.systemui.keyguard.shared.AmbientIndicationMusic
import com.google.android.systemui.keyguard.shared.AmbientIndicationMusicStatus
import kotlinx.coroutines.flow.MutableStateFlow

class AmbientIndicationRepository {
    val ambientMusic = MutableStateFlow<AmbientIndicationMusic?>(null)
    val ambientMusicStatus = MutableStateFlow(AmbientIndicationMusicStatus(false, false))
}
