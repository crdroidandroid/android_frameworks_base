package com.google.android.systemui.ambientmusic

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.google.android.systemui.keyguard.domain.interactor.AmbientIndicationInteractor
import com.google.android.systemui.keyguard.shared.AmbientIndicationMusicStatus
import com.google.android.systemui.keyguard.shared.ExpandedIndicationData
import com.google.android.systemui.keyguard.shared.ExtendedIndication

class AmbientIndicationService(
    private val alarmManager: AlarmManager,
    private val context: Context,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val ambientIndicationInteractor: AmbientIndicationInteractor,
) : BroadcastReceiver() {

    private val TAG = "AmbientIndication"

    var mStarted: Boolean = false

    internal val mCallback: KeyguardUpdateMonitorCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onUserSwitchComplete(userId: Int) {
                onUserSwitched()
            }
        }

    private val mHideIndicationListener: AlarmManager.OnAlarmListener =
        AlarmManager.OnAlarmListener {
            ambientIndicationInteractor.hideAmbientMusic()
        }

    fun getCurrentUser(): Int = selectedUserInteractor.getSelectedUserId()

    fun isForCurrentUser(): Boolean {
        return sendingUserId == getCurrentUser() || sendingUserId == -1
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!isForCurrentUser()) {
            Log.i(TAG, "Suppressing ambient, not for this user.")
            return
        }

        val version = intent.getIntExtra("com.google.android.ambientindication.extra.VERSION", 0)
        if (version != 1) {
            Log.e(
                TAG,
                "AmbientIndicationApi.EXTRA_VERSION is 1, but received an intent with version $version, dropping intent.",
            )
            return
        }

        val text = intent.getCharSequenceExtra("com.google.android.ambientindication.extra.TEXT")
        val openIntent =
            intent.getParcelableExtra<PendingIntent>(
                "com.google.android.ambientindication.extra.OPEN_INTENT"
            )
        val favoritingIntent =
            intent.getParcelableExtra<PendingIntent>(
                "com.google.android.ambientindication.extra.FAVORITING_INTENT"
            )
        val songTitle =
            intent.getCharSequenceExtra("com.google.android.ambientindication.extra.SONG_TITLE")
        val artistName =
            intent.getCharSequenceExtra("com.google.android.ambientindication.extra.ARTIST_NAME")
        val ttlMillis =
            Math.min(
                Math.max(
                    intent.getLongExtra(
                        "com.google.android.ambientindication.extra.TTL_MILLIS",
                        180000L,
                    ),
                    0L,
                ),
                180000L,
            )

        val action = intent.action!!

        when (action) {
            "com.google.android.ambientindication.action.AMBIENT_INDICATION_HIDE" -> {
                alarmManager.cancel(mHideIndicationListener)
                ambientIndicationInteractor.hideAmbientMusic()
                Log.i(TAG, "Hiding ambient indication.")
            }
            "com.google.android.ambientindication.action.AMBIENT_INDICATION_SHOW" -> {
                val skipUnlock =
                    intent.getBooleanExtra(
                        "com.google.android.ambientindication.extra.SKIP_UNLOCK",
                        false,
                    )
                val iconOverride =
                    intent.getIntExtra(
                        "com.google.android.ambientindication.extra.ICON_OVERRIDE",
                        0,
                    )
                val iconDescription =
                    intent.getStringExtra(
                        "com.google.android.ambientindication.extra.ICON_DESCRIPTION"
                    )
                val useExtendedInteraction =
                    intent.getBooleanExtra(
                        "com.google.android.ambientindication.extra.USE_EXTENDED_INTERACTION",
                        false,
                    )
                Log.i(TAG, "Using extended interaction: $useExtendedInteraction")

                if (useExtendedInteraction) {
                    ambientIndicationInteractor.setAmbientMusic(
                        text,
                        openIntent,
                        favoritingIntent,
                        iconOverride,
                        skipUnlock,
                        iconDescription,
                        ExtendedIndication(
                            songTitle,
                            artistName,
                            intent.getParcelableExtra<PendingIntent>(
                                "com.google.android.ambientindication.extra.EXPAND_INTENT"
                            ),
                            intent.getBooleanExtra(
                                "com.google.android.ambientindication.extra.IS_RECOGNITION_RESULT",
                                false,
                            ),
                            intent.getBooleanExtra(
                                "com.google.android.ambientindication.extra.IS_SONG_SEARCHING",
                                false,
                            ),
                            null,
                        ),
                    )
                } else {
                    ambientIndicationInteractor.setAmbientMusic(
                        text,
                        openIntent,
                        favoritingIntent,
                        iconOverride,
                        skipUnlock,
                        iconDescription,
                        null,
                    )
                }
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + ttlMillis,
                    "AmbientIndication",
                    mHideIndicationListener,
                    null,
                )
                Log.i(TAG, "Showing ambient indication.")
            }
            "com.google.android.ambientindication.action.UPDATE_QUICK_AFFORDANCE_STATE" -> {
                val isEnabled =
                    intent.getBooleanExtra(
                        "com.google.android.ambientindication.extra.IS_ENABLED",
                        false,
                    )
                val isActive =
                    intent.getBooleanExtra(
                        "com.google.android.ambientindication.extra.IS_ACTIVE",
                        false,
                    )
                Log.d(
                    TAG,
                    "Received ACTION_UPDATE_QUICK_AFFORDANCE_STATE: isEnabled=$isEnabled, isActive=$isActive",
                )
                val status = AmbientIndicationMusicStatus(isEnabled, isActive)
                ambientIndicationInteractor.ambientIndicationRepository.ambientMusicStatus.value =
                    status
            }
            "com.google.android.ambientindication.action.AMBIENT_INDICATION_EXPAND" -> {
                val albumArtUriString =
                    intent.getStringExtra(
                        "com.google.android.ambientindication.extra.ALBUM_ART_URI"
                    )
                val albumArtUri =
                    if (TextUtils.isEmpty(albumArtUriString)) null else Uri.parse(albumArtUriString)
                val isFavorite =
                    intent.getBooleanExtra(
                        "com.google.android.ambientindication.extra.IS_FAVORITE",
                        false,
                    )
                val dmpIntent =
                    intent.getParcelableExtra<PendingIntent>(
                        "com.google.android.ambientindication.extra.DMP_INTENT"
                    )
                val dmpPackageName =
                    intent.getStringExtra(
                        "com.google.android.ambientindication.extra.DMP_PACKAGE_NAME"
                    )

                val expandedIndicationData =
                    ExpandedIndicationData(
                        dmpIntent = dmpIntent,
                        dmpPackageName = dmpPackageName,
                        albumArtUri = albumArtUri,
                        isFavorite = isFavorite,
                    )

                ambientIndicationInteractor.setAmbientMusic(
                    text,
                    openIntent,
                    favoritingIntent,
                    0,
                    false,
                    "",
                    ExtendedIndication(
                        songTitle,
                        artistName,
                        null,
                        true,
                        false,
                        expandedIndicationData,
                    ),
                )
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + ttlMillis,
                    "AmbientIndication",
                    mHideIndicationListener,
                    null,
                )
                Log.i(TAG, "Showing expanded ambient indication.")
            }
        }
    }

    fun onUserSwitched() {
        ambientIndicationInteractor.hideAmbientMusic()
    }
}
