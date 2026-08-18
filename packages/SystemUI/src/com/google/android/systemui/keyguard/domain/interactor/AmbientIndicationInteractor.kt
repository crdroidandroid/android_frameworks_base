package com.google.android.systemui.keyguard.domain.interactor

import android.app.PendingIntent
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.google.android.systemui.keyguard.data.repository.AmbientIndicationRepository
import com.google.android.systemui.keyguard.shared.AmbientIndicationMusic
import com.google.android.systemui.keyguard.shared.ExtendedIndication
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class AmbientIndicationInteractor
@Inject
constructor(
    val ambientIndicationRepository: AmbientIndicationRepository,
    val keyguardInteractor: KeyguardInteractor,
) {

    val ambientMusicState: StateFlow<AmbientIndicationMusic?> =
        ambientIndicationRepository.ambientMusic.asStateFlow()

    fun hideAmbientMusic() {
        ambientIndicationRepository.ambientMusic.value = null
        keyguardInteractor.setAmbientIndicationVisible(false)
    }

    fun setAmbientMusic(
        text: CharSequence?,
        openIntent: PendingIntent?,
        favoritingIntent: PendingIntent?,
        iconOverride: Int?,
        skipUnlock: Boolean?,
        iconDescription: String?,
        extendedIndication: ExtendedIndication?,
    ) {
        ambientIndicationRepository.ambientMusic.value =
            AmbientIndicationMusic(
                text = text,
                openIntent = openIntent,
                favoritingIntent = favoritingIntent,
                iconOverride = iconOverride,
                skipUnlock = skipUnlock,
                iconDescription = iconDescription,
                extendedIndication = extendedIndication,
            )
        keyguardInteractor.setAmbientIndicationVisible(true)
    }
}
