package com.google.android.systemui.smartspace

import android.app.smartspace.SmartspaceAction
import android.app.smartspace.SmartspaceTarget
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.os.UserHandle
import android.text.TextUtils
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.res.R
import javax.inject.Inject

class KeyguardMediaViewController
@Inject
constructor(
    val context: Context,
    val mediaManager: NotificationMediaManager,
    val smartspaceController: LockscreenSmartspaceController,
    val userTracker: UserTracker,
    val uiExecutor: DelayableExecutor,
) {
    var title: CharSequence? = null
    var artist: CharSequence? = null
    lateinit var mediaComponent: ComponentName

    val mediaListener =
        object : NotificationMediaManager.MediaListener {
            override fun onPrimaryMetadataOrStateChanged(metadata: MediaMetadata?, state: Int) {
                uiExecutor.execute { updateMediaInfo(metadata, state) }
            }
        }

    private fun updateMediaInfo(metadata: MediaMetadata?, state: Int) {
        if (!NotificationMediaManager.isPlayingState(state)) {
            title = null
            artist = null
            smartspaceController.setMediaTarget(null)
            return
        }

        val newTitle = metadata?.let {
            val displayTitle = it.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            if (TextUtils.isEmpty(displayTitle)) {
                it.getText(MediaMetadata.METADATA_KEY_TITLE)
            } else {
                displayTitle
            } ?: context.resources.getString(R.string.music_controls_no_title)
        }

        val newArtist = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)

        if (TextUtils.equals(title, newTitle) && TextUtils.equals(artist, newArtist)) {
            return
        }

        title = newTitle
        artist = newArtist

        if (newTitle != null) {
            val target =
                SmartspaceTarget.Builder(
                        "deviceMedia",
                        mediaComponent,
                        UserHandle.of(userTracker.userId),
                    )
                    .setFeatureType(41)
                    .setHeaderAction(
                        SmartspaceAction.Builder("deviceMediaTitle", newTitle.toString())
                            .setSubtitle(artist)
                            .setIcon(mediaManager.getMediaIcon())
                            .build()
                    )
                    .build()

            smartspaceController.setMediaTarget(target)
            return
        } else {
            title = null
            artist = null
            smartspaceController.setMediaTarget(null)
        }
    }
}
