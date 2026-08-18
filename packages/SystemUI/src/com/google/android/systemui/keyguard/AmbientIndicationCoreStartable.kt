package com.google.android.systemui.keyguard

import android.app.AlarmManager
import android.content.Context
import android.content.IntentFilter
import android.os.UserHandle
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.google.android.systemui.ambientmusic.AmbientIndicationService
import com.google.android.systemui.keyguard.domain.interactor.AmbientIndicationInteractor
import javax.inject.Inject

@SysUISingleton
class AmbientIndicationCoreStartable
@Inject
constructor(
    private val alarmManager: AlarmManager,
    @Application private val context: Context,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val ambientIndicationInteractor: AmbientIndicationInteractor,
) : CoreStartable {

    override fun start() {
        val ambientIndicationService =
            AmbientIndicationService(
                alarmManager = alarmManager,
                context = context,
                keyguardUpdateMonitor = keyguardUpdateMonitor,
                selectedUserInteractor = selectedUserInteractor,
                ambientIndicationInteractor = ambientIndicationInteractor,
            )
        if (ambientIndicationService.mStarted) {
            return
        }
        ambientIndicationService.mStarted = true
        val intentFilter =
            IntentFilter().apply {
                addAction("com.google.android.ambientindication.action.AMBIENT_INDICATION_SHOW")
                addAction("com.google.android.ambientindication.action.AMBIENT_INDICATION_EXPAND")
                addAction("com.google.android.ambientindication.action.AMBIENT_INDICATION_HIDE")
                addAction(
                    "com.google.android.ambientindication.action.UPDATE_QUICK_AFFORDANCE_STATE"
                )
            }
        context.registerReceiverAsUser(
            ambientIndicationService,
            UserHandle.ALL,
            intentFilter,
            "com.google.android.ambientindication.permission.AMBIENT_INDICATION",
            null,
            Context.RECEIVER_EXPORTED,
        )
        keyguardUpdateMonitor.registerCallback(ambientIndicationService.mCallback)
    }
}
