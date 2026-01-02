package com.google.android.systemui.smartspace

import android.app.smartspace.SmartspaceAction
import android.app.smartspace.SmartspaceTarget
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.os.UserHandle
import android.text.TextUtils
import android.view.View
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.res.R;
import javax.inject.Inject

class KeyguardMediaViewController
@Inject
constructor(
    val context: Context,
    val mediaManager: NotificationMediaManager,
    val plugin: BcSmartspaceDataPlugin,
    val userTracker: UserTracker,
    val uiExecutor: DelayableExecutor,
) {
    var title: CharSequence? = null
    var artist: CharSequence? = null
    var smartspaceView: BcSmartspaceDataPlugin.SmartspaceView? = null
    lateinit var mediaComponent: ComponentName

    val mediaListener =
        object : NotificationMediaManager.MediaListener {
            override fun onPrimaryMetadataOrStateChanged(metadata: MediaMetadata?, state: Int) {
                uiExecutor.execute { updateMediaInfo(metadata, state) }
            }
        }

    val attachStateChangeListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                smartspaceView = view as? BcSmartspaceDataPlugin.SmartspaceView
                mediaManager.addCallback(mediaListener)
            }

            override fun onViewDetachedFromWindow(view: View) {
                smartspaceView = null
                mediaManager.removeCallback(mediaListener)
            }
        }

    private fun updateMediaInfo(metadata: MediaMetadata?, state: Int) {
        if (!NotificationMediaManager.isPlayingState(state)) {
            clearMedia()
            return
        }

        val newTitle =
            metadata?.let {
                it.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    ?: it.getText(MediaMetadata.METADATA_KEY_TITLE)
                    ?: context.resources.getString(R.string.music_controls_no_title)
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
                    .setFeatureType(SmartspaceTarget.FEATURE_MEDIA)
                    .setHeaderAction(
                        SmartspaceAction.Builder("deviceMediaTitle", newTitle.toString())
                            .setSubtitle(artist)
                            .setIcon(mediaManager.getMediaIcon())
                            .build()
                    )
                    .build()

            smartspaceView?.setMediaTarget(target) ?: clearMedia()
        } else {
            clearMedia()
        }
    }

    private fun clearMedia() {
        title = null
        artist = null
        smartspaceView?.setMediaTarget(null)
    }
}
