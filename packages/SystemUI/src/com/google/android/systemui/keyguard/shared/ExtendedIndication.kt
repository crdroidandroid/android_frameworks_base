package com.google.android.systemui.keyguard.shared

import android.app.PendingIntent

data class ExtendedIndication(
    @JvmField val songTitle: CharSequence? = null,
    @JvmField val artistName: CharSequence? = null,
    @JvmField val expandIntent: PendingIntent? = null,
    @JvmField val isRecognitionResult: Boolean? = null,
    @JvmField val isSongSearching: Boolean? = null,
    @JvmField val expandedIndicationData: ExpandedIndicationData? = null,
)
