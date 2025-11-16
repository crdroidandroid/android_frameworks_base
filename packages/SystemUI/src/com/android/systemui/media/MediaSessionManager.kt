/*
 * Copyright (C) 2025 The AxionAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.media

import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.android.systemui.util.WeakListenerManager

class MediaSessionManager private constructor() {

    interface MediaDataListener {
        fun onPlaybackStateChanged(state: Int) {}
        fun onAlbumArtChanged(drawable: Drawable) {}
        fun onMediaColorsChanged(color: Int) {}
        fun onMetadataChanged(track: String, artist: String) {}
    }

    private val listenerManager = WeakListenerManager<MediaDataListener>()

    @Volatile
    private var currentPlaybackState: Int = PlaybackState.STATE_NONE

    @Volatile
    private var currentAlbumArt: Drawable? = null

    @Volatile
    private var currentMediaColor: Int? = null

    @Volatile
    var trackTitle: String = "Unknown"

    @Volatile
    var artist: String = "Unknown"

    val isMediaPlaying: Boolean
        get() = currentPlaybackState == PlaybackState.STATE_PLAYING

    fun addListener(listener: MediaDataListener) {
        listenerManager.addListener(listener)

        listenerManager.notifyOnBackground {
            if (it === listener) {
                it.onPlaybackStateChanged(currentPlaybackState)
                it.onMetadataChanged(trackTitle, artist)
                currentAlbumArt?.let { art -> it.onAlbumArtChanged(art) }
                currentMediaColor?.let { color -> it.onMediaColorsChanged(color) }
            }
        }
    }

    fun removeListener(listener: MediaDataListener) = listenerManager.removeListener(listener)

    fun onPlaybackStateChanged(state: Int) {
        if (currentPlaybackState != state) {
            currentPlaybackState = state
            listenerManager.notifyOnBackground { it.onPlaybackStateChanged(state) }
        }
    }

    fun onAlbumArtChanged(drawable: Drawable) {
        currentAlbumArt = drawable
        listenerManager.notifyOnBackground { it.onAlbumArtChanged(drawable) }
    }

    fun onMediaColorsChanged(color: Int) {
        currentMediaColor = color
        listenerManager.notifyOnBackground { it.onMediaColorsChanged(color) }
    }

    fun onMetadataChanged(metadata: MediaMetadata) {
        val newTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
        val newArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"

        if (trackTitle != newTitle || artist != newArtist) {
            trackTitle = newTitle
            artist = newArtist
            listenerManager.notifyOnBackground { it.onMetadataChanged(trackTitle, artist) }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: MediaSessionManager? = null

        fun get(): MediaSessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MediaSessionManager().also { INSTANCE = it }
            }
        }
    }
}
