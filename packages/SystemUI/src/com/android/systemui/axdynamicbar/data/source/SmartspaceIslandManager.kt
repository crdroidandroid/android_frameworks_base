/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.axdynamicbar.data.source

import android.app.PendingIntent
import android.app.smartspace.SmartspaceAction
import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSession
import android.app.smartspace.SmartspaceTarget
import android.app.smartspace.uitemplatedata.HeadToHeadTemplateData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserTracker
import com.google.android.systemui.keyguard.domain.interactor.AmbientIndicationInteractor
import com.google.android.systemui.keyguard.shared.AmbientIndicationMusic
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SysUISingleton
class SmartspaceIslandManager
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Background private val bgExecutor: Executor,
    private val userTracker: UserTracker,
    private val ambientIndicationInteractor: AmbientIndicationInteractor,
) {
    private val _sportsEvents = MutableStateFlow<List<IslandEvent.Sports>>(emptyList())
    val sportsEvents: StateFlow<List<IslandEvent.Sports>> = _sportsEvents.asStateFlow()

    private val _nowPlayingEvent = MutableStateFlow<IslandEvent.NowPlaying?>(null)
    val nowPlayingEvent: StateFlow<IslandEvent.NowPlaying?> = _nowPlayingEvent.asStateFlow()

    private val lock = Any()
    @Volatile private var listening = false

    private var nowPlayingJob: Job? = null
    private var lastNowPlayingTitle: String? = null
    private var dismissedNowPlayingTitle: String? = null

    private var session: SmartspaceSession? = null
    private var allSports: List<IslandEvent.Sports> = emptyList()
    private val dismissedSportsKeys = mutableSetOf<String>()

    private val sessionListener =
        SmartspaceSession.OnTargetsAvailableListener { targets -> onTargetsAvailable(targets) }

    private val userCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                synchronized(lock) {
                    if (!listening) return
                    closeSession()
                    allSports = emptyList()
                    dismissedSportsKeys.clear()
                    publishSports()
                    openSession()
                }
            }
        }

    fun startListening() {
        synchronized(lock) {
            if (listening) return
            listening = true
            openSession()
        }
        userTracker.addCallback(userCallback, bgExecutor)
        nowPlayingJob =
            applicationScope.launch {
                ambientIndicationInteractor.ambientMusicState.collect { onAmbientMusic(it) }
            }
    }

    fun stopListening() {
        synchronized(lock) {
            if (!listening) return
            listening = false
            closeSession()
            allSports = emptyList()
            dismissedSportsKeys.clear()
            lastNowPlayingTitle = null
            dismissedNowPlayingTitle = null
            _sportsEvents.value = emptyList()
            _nowPlayingEvent.value = null
        }
        userTracker.removeCallback(userCallback)
        nowPlayingJob?.cancel()
        nowPlayingJob = null
    }

    fun clearSportsEvent(key: String, team1: String? = null, team2: String? = null) {
        synchronized(lock) {
            allSports
                .filter { it.key == key || it.isSameGame(team1, team2) }
                .forEach { dismissedSportsKeys.add(it.key) }
            dismissedSportsKeys.add(key)
            publishSports()
        }
    }

    /** Hides the current song until a different one is recognized. */
    fun dismissNowPlaying() {
        synchronized(lock) {
            dismissedNowPlayingTitle = lastNowPlayingTitle
            _nowPlayingEvent.value = null
        }
    }

    private fun onAmbientMusic(music: AmbientIndicationMusic?) {
        synchronized(lock) {
            if (!listening) return
            val event = music?.let { toNowPlaying(it) }
            if (event == null) {
                lastNowPlayingTitle = null
                dismissedNowPlayingTitle = null
                _nowPlayingEvent.value = null
                return
            }
            val title = event.songTitle
            lastNowPlayingTitle = title
            if (title == dismissedNowPlayingTitle) {
                _nowPlayingEvent.value = null
                return
            }
            dismissedNowPlayingTitle = null
            _nowPlayingEvent.value = event
        }
    }

    private fun toNowPlaying(music: AmbientIndicationMusic): IslandEvent.NowPlaying? {
        val extended = music.extendedIndication
        if (extended?.isSongSearching == true) return null
        val songTitle = extended?.songTitle?.toString()?.trim().orEmpty()
        val artistName = extended?.artistName?.toString()?.trim().orEmpty()
        val text = music.text?.toString()?.trim().orEmpty()

        val (title, artist) =
            when {
                songTitle.isNotEmpty() -> songTitle to artistName
                text.isNotEmpty() -> splitSongText(text)
                else -> return null
            }
        return IslandEvent.NowPlaying(
            songTitle = title,
            artist = artist,
            key = NOW_PLAYING_KEY,
            contentIntent = music.openIntent,
        )
    }

    private fun splitSongText(text: String): Pair<String, String> {
        NOW_PLAYING_BY_PATTERN.find(text)?.let {
            return it.groupValues[1].trim() to it.groupValues[2].trim()
        }
        val dashParts = text.split(" - ", " – ", limit = 2)
        if (dashParts.size == 2) return dashParts[0].trim() to dashParts[1].trim()
        return text to ""
    }

    // endregion

    // region Sports (Google Smartspace)

    /** Must be called with [lock] held. */
    private fun openSession() {
        if (session != null) return
        val manager =
            userTracker.userContext.getSystemService(SmartspaceManager::class.java) ?: run {
                Log.w(TAG, "SmartspaceManager not available")
                return
            }
        try {
            val config =
                SmartspaceConfig.Builder(userTracker.userContext, UI_SURFACE)
                    .setSmartspaceTargetCount(TARGET_COUNT)
                    .build()
            session =
                manager.createSmartspaceSession(config).also {
                    it.addOnTargetsAvailableListener(bgExecutor, sessionListener)
                    it.requestSmartspaceUpdate()
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create Smartspace session", e)
            session = null
        }
    }

    /** Must be called with [lock] held. */
    private fun closeSession() {
        val current = session ?: return
        session = null
        try {
            current.removeOnTargetsAvailableListener(sessionListener)
            current.close()
        } catch (_: Exception) {}
    }

    private fun onTargetsAvailable(targets: List<SmartspaceTarget>) {
        val converted =
            targets.mapNotNull { target ->
                try {
                    convertSportsTarget(target)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to convert target ${target.smartspaceTargetId}", e)
                    null
                }
            }
        synchronized(lock) {
            if (!listening) return
            val seen = HashMap<String, Int>()
            allSports =
                converted.map { event ->
                    val count = seen.merge(event.key, 1) { a, b -> a + b } ?: 1
                    if (count == 1) event else event.copy(key = "${event.key}#$count")
                }
            dismissedSportsKeys.retainAll(allSports.mapTo(HashSet()) { it.key })
            publishSports()
        }
    }

    private fun convertSportsTarget(st: SmartspaceTarget): IslandEvent.Sports? {
        val h2h =
            try {
                st.templateData as? HeadToHeadTemplateData
            } catch (_: Exception) {
                null
            }
        return when {
            st.featureType == FEATURE_SPORTS -> convertScoreTarget(st)
            h2h != null -> convertHeadToHeadTarget(st, h2h)
            else -> null
        }
    }

    private fun convertScoreTarget(st: SmartspaceTarget): IslandEvent.Sports? {
        val header = st.headerAction
        val base = st.baseAction
        val headerExtras = safeExtras(header)
        val baseExtras = safeExtras(base)

        val statusDetail = headerExtras?.safeString("matchTimeSummary").orEmpty()
        val score1 =
            headerExtras?.safeString("firstCompetitorScore")
                ?: baseExtras?.safeString("firstCompetitorScore")
        val score2 =
            headerExtras?.safeString("secondCompetitorScore")
                ?: baseExtras?.safeString("secondCompetitorScore")

        val title = header?.title?.toString() ?: base?.title?.toString()
        if (title.isNullOrEmpty() && score1 == null) return null

        val teams = title?.let { VS_PATTERN.find(it) }
        return buildSports(
            st = st,
            team1 = teams?.groupValues?.get(1)?.trim().orEmpty(),
            team2 = teams?.groupValues?.get(2)?.trim().orEmpty(),
            score1 = score1.orEmpty(),
            score2 = score2.orEmpty(),
            team1Icon = headerExtras?.safeBitmap("firstCompetitorLogo")?.toDrawable(),
            team2Icon = headerExtras?.safeBitmap("secondCompetitorLogo")?.toDrawable(),
            statusDetail = statusDetail,
        )
    }

    private fun convertHeadToHeadTarget(
        st: SmartspaceTarget,
        h2h: HeadToHeadTemplateData,
    ): IslandEvent.Sports? {
        val title = h2h.headToHeadTitle?.text?.toString() ?: st.headerAction?.title?.toString()
        val team1Text = h2h.headToHeadFirstCompetitorText?.text?.toString().orEmpty()
        val team2Text = h2h.headToHeadSecondCompetitorText?.text?.toString().orEmpty()
        if (team1Text.isEmpty() && team2Text.isEmpty()) return null

        val m1 = COMPETITOR_SCORE_PATTERN.find(team1Text)
        val m2 = COMPETITOR_SCORE_PATTERN.find(team2Text)
        val scored = m1 != null && m2 != null
        return buildSports(
            st = st,
            team1 = if (scored) m1!!.groupValues[1].trim() else team1Text,
            team2 = if (scored) m2!!.groupValues[1].trim() else team2Text,
            score1 = if (scored) m1!!.groupValues[2] else "",
            score2 = if (scored) m2!!.groupValues[2] else "",
            team1Icon = h2h.headToHeadFirstCompetitorIcon?.icon?.toDrawable(),
            team2Icon = h2h.headToHeadSecondCompetitorIcon?.icon?.toDrawable(),
            statusDetail = title.orEmpty(),
        )
    }

    private fun buildSports(
        st: SmartspaceTarget,
        team1: String,
        team2: String,
        score1: String,
        score2: String,
        team1Icon: Drawable?,
        team2Icon: Drawable?,
        statusDetail: String,
    ) =
        IslandEvent.Sports(
            team1Name = team1,
            team2Name = team2,
            score1 = score1,
            score2 = score2,
            team1Icon = team1Icon,
            team2Icon = team2Icon,
            status = parseGameStatus(statusDetail),
            statusDetail = statusDetail,
            key = "ss_sports_${st.smartspaceTargetId}",
            contentIntent = buildTapIntent(st),
        )

    private fun buildTapIntent(st: SmartspaceTarget): PendingIntent? {
        val action =
            listOfNotNull(st.baseAction, st.headerAction).firstOrNull {
                it.pendingIntent != null || it.intent != null
            } ?: return null
        action.pendingIntent?.let { return it }
        val intent = action.intent ?: return null
        return PendingIntent.getActivityAsUser(
            userTracker.userContext,
            st.smartspaceTargetId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            null,
            userTracker.userHandle,
        )
    }

    /** Must be called with [lock] held. */
    private fun publishSports() {
        _sportsEvents.value = allSports.filterNot { it.key in dismissedSportsKeys }
    }

    private fun IslandEvent.Sports.isSameGame(team1: String?, team2: String?): Boolean =
        team1 != null &&
            team2 != null &&
            team1Name.equals(team1, ignoreCase = true) &&
            team2Name.equals(team2, ignoreCase = true)

    private fun parseGameStatus(detail: String): IslandEvent.GameStatus {
        val text = detail.lowercase()
        return when {
            text.contains("final") -> IslandEvent.GameStatus.FINAL
            text.contains("half") -> IslandEvent.GameStatus.HALFTIME
            text.contains("live") ||
                text.contains("q") ||
                text.contains("inning") ||
                text.contains("set") ||
                text.contains("period") -> IslandEvent.GameStatus.LIVE
            text.contains("pre") ||
                text.contains("upcoming") ||
                text.contains("tip") ||
                text.contains("kick") -> IslandEvent.GameStatus.PRE_GAME
            else -> IslandEvent.GameStatus.LIVE
        }
    }

    private fun safeExtras(action: SmartspaceAction?): Bundle? =
        try {
            action?.extras
        } catch (_: Exception) {
            null
        }

    private fun Bundle.safeString(key: String): String? =
        try {
            getString(key)
        } catch (_: Exception) {
            null
        }

    private fun Bundle.safeBitmap(key: String): Bitmap? =
        try {
            getParcelable(key, Bitmap::class.java)
        } catch (_: Exception) {
            null
        }

    private fun Icon.toDrawable(): Drawable? =
        try {
            loadDrawable(userTracker.userContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load competitor icon", e)
            null
        }

    private fun Bitmap.toDrawable(): Drawable {
        val largest = maxOf(width, height)
        val scaled =
            if (largest > MAX_ICON_PX) {
                val ratio = MAX_ICON_PX.toFloat() / largest
                Bitmap.createScaledBitmap(
                    this,
                    (width * ratio).toInt().coerceAtLeast(1),
                    (height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                this
            }
        return BitmapDrawable(context.resources, scaled)
    }

    // endregion

    private companion object {
        const val TAG = "SmartspaceIslandManager"
        const val NOW_PLAYING_KEY = "ambient_now_playing"
        const val UI_SURFACE = "lockscreen"
        const val TARGET_COUNT = 10
        const val FEATURE_SPORTS = 9
        const val MAX_ICON_PX = 192
        val NOW_PLAYING_BY_PATTERN = Regex("""(.+?)\s+by\s+(.+)""", RegexOption.IGNORE_CASE)
        val VS_PATTERN = Regex("""(.+?)\s+(?:vs\.?|v)\s+(.+)""", RegexOption.IGNORE_CASE)
        val COMPETITOR_SCORE_PATTERN = Regex("""(\D*?)\s*(\d+)\s*$""")
    }
}
