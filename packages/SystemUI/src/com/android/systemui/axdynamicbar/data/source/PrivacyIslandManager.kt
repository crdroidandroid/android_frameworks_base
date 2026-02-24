package com.android.systemui.axdynamicbar.data.source

import android.app.AppOpsManager
import android.content.Context
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.MicCamApp
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.privacy.PrivacyType
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class PrivacyIslandManager
@Inject
constructor(
    @Application private val context: Context,
    private val privacyItemController: PrivacyItemController,
) {
    private val _micCamEvent = MutableStateFlow<IslandEvent.MicCamActive?>(null)
    val micCamEvent: StateFlow<IslandEvent.MicCamActive?> = _micCamEvent.asStateFlow()

    var onCamStarted: ((IslandEvent.MicCamActive) -> Unit)? = null

    private var listening = false
    @Volatile private var wasCamActive = false

    @Volatile private var privacyControllerReportedCam = false

    private val appOpsManager: AppOpsManager by lazy {
        context.getSystemService(AppOpsManager::class.java)
    }

    private val callback =
        object : PrivacyItemController.Callback {
            override fun onPrivacyItemsChanged(privacyItems: List<PrivacyItem>) {
                val isMic = privacyItems.any { it.privacyType == PrivacyType.TYPE_MICROPHONE }
                val isCam = privacyItems.any { it.privacyType == PrivacyType.TYPE_CAMERA }
                privacyControllerReportedCam = isCam

                if (isMic || isCam) {
                    val apps: List<MicCamApp> =
                        privacyItems
                            .filter {
                                it.privacyType == PrivacyType.TYPE_MICROPHONE ||
                                    it.privacyType == PrivacyType.TYPE_CAMERA
                            }
                            .mapNotNull { item ->
                                val pkg = item.application.packageName ?: return@mapNotNull null
                                val name =
                                    try {
                                        context.packageManager
                                            .getApplicationLabel(
                                                context.packageManager.getApplicationInfo(pkg, 0)
                                            )
                                            .toString()
                                    } catch (_: Exception) {
                                        pkg
                                    }
                                val icon =
                                    try {
                                        context.packageManager.getApplicationIcon(pkg)
                                    } catch (_: Exception) {
                                        null
                                    }
                                MicCamApp(packageName = pkg, appName = name, appIcon = icon)
                            }
                            .distinctBy { it.packageName }

                    emitEvent(isMic = isMic, isCam = isCam, apps = apps)
                } else if (!directCamActive) {
                    wasCamActive = false
                    _micCamEvent.value = null
                }
            }

            override fun onFlagAllChanged(flag: Boolean) {}
        }

    @Volatile private var directCamActive = false

    private val cameraOpCallback =
        AppOpsManager.OnOpActiveChangedListener { op, _, packageName, active ->
            if (op != AppOpsManager.OPSTR_CAMERA) return@OnOpActiveChangedListener
            directCamActive = active

            if (privacyControllerReportedCam) return@OnOpActiveChangedListener

            if (active) {
                val appName =
                    try {
                        context.packageManager
                            .getApplicationLabel(
                                context.packageManager.getApplicationInfo(packageName, 0)
                            )
                            .toString()
                    } catch (_: Exception) {
                        packageName
                    }
                val icon =
                    try {
                        context.packageManager.getApplicationIcon(packageName)
                    } catch (_: Exception) {
                        null
                    }
                val current = _micCamEvent.value
                emitEvent(
                    isMic = current?.isMic ?: false,
                    isCam = true,
                    apps =
                        listOfNotNull(MicCamApp(packageName, appName, icon)) +
                            (current?.apps?.filter { it.packageName != packageName } ?: emptyList()),
                )
            } else {
                val current = _micCamEvent.value ?: return@OnOpActiveChangedListener
                if (current.isMic) {
                    _micCamEvent.value = current.copy(isCam = false)
                } else {
                    wasCamActive = false
                    _micCamEvent.value = null
                }
            }
        }

    private fun emitEvent(isMic: Boolean, isCam: Boolean, apps: List<MicCamApp>) {
        val appName = apps.firstOrNull()?.appName ?: ""
        val event =
            IslandEvent.MicCamActive(isMic = isMic, isCam = isCam, appName = appName, apps = apps)
        _micCamEvent.value = event

        if (isCam && !wasCamActive) onCamStarted?.invoke(event)
        wasCamActive = isCam
    }

    fun startListening() {
        if (listening) return
        listening = true
        privacyItemController.addCallback(callback)
        try {
            appOpsManager.startWatchingActive(
                arrayOf(AppOpsManager.OPSTR_CAMERA),
                context.mainExecutor,
                cameraOpCallback,
            )
        } catch (_: Exception) {}
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        privacyItemController.removeCallback(callback)
        try {
            appOpsManager.stopWatchingActive(cameraOpCallback)
        } catch (_: Exception) {}
        _micCamEvent.value = null
        directCamActive = false
        privacyControllerReportedCam = false
    }
}

