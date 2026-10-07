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
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.android.axion.quicklook.SportsData
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.quicklook.QuickLookClient
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class SmartspaceIslandManager
@Inject
constructor(
    private val quickLookClient: QuickLookClient,
) {
    private val _sportsEvents = MutableStateFlow<List<IslandEvent.Sports>>(emptyList())
    val sportsEvents: StateFlow<List<IslandEvent.Sports>> = _sportsEvents.asStateFlow()

    private val _nowPlayingEvent = MutableStateFlow<IslandEvent.NowPlaying?>(null)
    val nowPlayingEvent: StateFlow<IslandEvent.NowPlaying?> = _nowPlayingEvent.asStateFlow()

    private var listening = false

    private var allSports: List<IslandEvent.Sports> = emptyList()
    private val dismissedSportsKeys = mutableSetOf<String>()

    private var lastNowPlayingText: String? = null
    private var dismissedNowPlayingText: String? = null

    private val callback = object : QuickLookClient.Callback {
        override fun onSportsUpdate(sports: List<SportsData>) {
            val seen = HashMap<String, Int>()
            allSports = sports.map { data ->
                val base = "ql_sports_${data.league}|${data.team1Name}|${data.team2Name}"
                val count = seen.merge(base, 1) { a, b -> a + b } ?: 1
                val key = if (count == 1) base else "$base#$count"
                val status = parseGameStatus(data.status, data.statusDetail)
                IslandEvent.Sports(
                    team1Name = data.team1Name,
                    team2Name = data.team2Name,
                    score1 = data.score1,
                    score2 = data.score2,
                    team1Icon = bytesToDrawable(data.team1IconBytes),
                    team2Icon = bytesToDrawable(data.team2IconBytes),
                    status = status,
                    statusDetail = data.statusDetail,
                    league = data.league,
                    key = key,
                )
            }
            val liveKeys = allSports.mapTo(HashSet()) { it.key }
            dismissedSportsKeys.retainAll(liveKeys)
            publishSports()
        }

        override fun onNowPlayingUpdate(text: String, tapAction: PendingIntent?) {
            if (text.isBlank()) {
                lastNowPlayingText = null
                dismissedNowPlayingText = null
                _nowPlayingEvent.value = null
                return
            }
            lastNowPlayingText = text
            if (text == dismissedNowPlayingText) {
                _nowPlayingEvent.value = null
                return
            }
            dismissedNowPlayingText = null
            val byMatch = Regex("""(.+?)\s+by\s+(.+)""", RegexOption.IGNORE_CASE).find(text)
            val dashParts = if (byMatch == null) text.split(" - ", " – ", limit = 2) else null
            val songTitle: String
            val artist: String
            when {
                byMatch != null -> {
                    songTitle = byMatch.groupValues[1].trim()
                    artist = byMatch.groupValues[2].trim()
                }
                dashParts != null && dashParts.size == 2 -> {
                    songTitle = dashParts[0].trim()
                    artist = dashParts[1].trim()
                }
                else -> {
                    songTitle = text
                    artist = ""
                }
            }
            _nowPlayingEvent.value = IslandEvent.NowPlaying(
                songTitle = songTitle,
                artist = artist,
                key = "ql_now_playing",
            )
        }
    }

    fun startListening() {
        if (listening) return
        listening = true
        quickLookClient.addCallback(callback)
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        quickLookClient.removeCallback(callback)
        allSports = emptyList()
        dismissedSportsKeys.clear()
        lastNowPlayingText = null
        dismissedNowPlayingText = null
        _sportsEvents.value = emptyList()
        _nowPlayingEvent.value = null
    }

    fun clearSportsEvent(key: String, team1: String? = null, team2: String? = null) {
        allSports
            .filter { it.key == key || it.isSameGame(team1, team2) }
            .forEach { dismissedSportsKeys.add(it.key) }
        dismissedSportsKeys.add(key)
        publishSports()
    }

    fun dismissNowPlaying() {
        dismissedNowPlayingText = lastNowPlayingText
        _nowPlayingEvent.value = null
    }

    private fun publishSports() {
        _sportsEvents.value = allSports.filterNot { it.key in dismissedSportsKeys }
    }

    private fun IslandEvent.Sports.isSameGame(team1: String?, team2: String?): Boolean =
        team1 != null && team2 != null &&
            team1Name.equals(team1, ignoreCase = true) &&
            team2Name.equals(team2, ignoreCase = true)

    private fun parseGameStatus(status: String, detail: String): IslandEvent.GameStatus {
        val combined = "$status $detail".lowercase()
        return when {
            combined.contains("final") -> IslandEvent.GameStatus.FINAL
            combined.contains("half") -> IslandEvent.GameStatus.HALFTIME
            combined.contains("live") || combined.contains("q") ||
                combined.contains("inning") || combined.contains("set") ||
                combined.contains("period") -> IslandEvent.GameStatus.LIVE
            combined.contains("pre") || combined.contains("upcoming") ||
                combined.contains("tip") || combined.contains("kick") -> IslandEvent.GameStatus.PRE_GAME
            else -> IslandEvent.GameStatus.LIVE
        }
    }

    private fun bytesToDrawable(bytes: ByteArray?): Drawable? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            BitmapDrawable(null, bitmap)
        } catch (_: Exception) {
            null
        }
    }
}
