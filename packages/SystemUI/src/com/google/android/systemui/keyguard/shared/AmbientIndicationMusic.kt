package com.google.android.systemui.keyguard.shared

import android.app.PendingIntent

data class AmbientIndicationMusic(
    @JvmField var text: CharSequence? = null,
    @JvmField var openIntent: PendingIntent? = null,
    @JvmField var favoritingIntent: PendingIntent? = null,
    @JvmField var iconOverride: Int? = null,
    @JvmField var skipUnlock: Boolean? = null,
    @JvmField var iconDescription: String? = null,
    @JvmField var extendedIndication: ExtendedIndication? = null,
)
