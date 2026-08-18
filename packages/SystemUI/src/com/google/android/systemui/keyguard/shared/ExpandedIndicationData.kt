package com.google.android.systemui.keyguard.shared

import android.app.PendingIntent
import android.net.Uri

data class ExpandedIndicationData(
    @JvmField var dmpIntent: PendingIntent? = null,
    @JvmField var dmpPackageName: String? = null,
    @JvmField var albumArtUri: Uri? = null,
    @JvmField var isFavorite: Boolean? = null,
)
