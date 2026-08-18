package com.google.android.systemui.keyguard.data.quickaffordance

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.android.systemui.animation.Expandable
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.res.R
import com.google.android.systemui.keyguard.data.repository.AmbientIndicationRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@SysUISingleton
class NowPlayingQuickAffordanceConfig
@Inject
constructor(
    @Application private val context: Context,
    private val broadcastSender: BroadcastSender,
    val ambientIndicationRepository: AmbientIndicationRepository,
) : KeyguardQuickAffordanceConfig {

    override val key: String = "now_playing"

    private sealed interface NowPlayingState {
        data object On : NowPlayingState
        data object Off : NowPlayingState
    }

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> =
        flow {
            ambientIndicationRepository.ambientMusicStatus
                .onStart {
                    val intent =
                        Intent("com.google.intelligence.sense.ambientmusic.ondemand.AQA_GET_STATUS")
                            .apply {
                                component =
                                    ComponentName(
                                        "com.google.android.as",
                                        "com.google.intelligence.sense.ondemand.SystemUiBroadcastReceiver",
                                    )
                            }
                    broadcastSender.sendBroadcast(
                        intent,
                        "com.google.android.ambientindication.permission.AMBIENT_INDICATION",
                    )
                }
                .map { status ->
                    if (status.isActive) NowPlayingState.On else NowPlayingState.Off
                }
                .map { state ->
                    val activationState =
                        if (state is NowPlayingState.On) {
                            ActivationState.Active
                        } else {
                            ActivationState.Inactive
                        }
                    KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                        icon =
                            Icon.Resource(
                                R.drawable.ic_now_playing_lockscreen,
                                ContentDescription.Resource(R.string.now_playing_label),
                            ),
                        activationState = activationState,
                    )
                }
                .collect { emit(it) }
        }

    override fun pickerName(): String = context.getString(R.string.now_playing_label)

    override val pickerIconResourceId: Int = R.drawable.ic_now_playing_lockscreen

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState =
        KeyguardQuickAffordanceConfig.PickerScreenState.Default()

    override fun onTriggered(
        expandable: Expandable?
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        val intent =
            Intent("com.google.intelligence.sense.ambientmusic.ondemand.AQA_CLICK").apply {
                component =
                    ComponentName(
                        "com.google.android.as",
                        "com.google.intelligence.sense.ondemand.SystemUiBroadcastReceiver",
                    )
                putExtra("EXTRA_ON_DEMAND_TIMESTAMP", System.currentTimeMillis())
                putExtra("EXTRA_ON_DEMAND_SESSION", UUID.randomUUID().toString())
            }
        broadcastSender.sendBroadcast(
            intent,
            "com.google.android.ambientindication.permission.AMBIENT_INDICATION",
        )
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled(true)
    }
}
