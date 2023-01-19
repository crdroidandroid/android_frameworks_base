/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.quickaffordance

import android.content.Context
import android.provider.Settings

import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordancePosition
import javax.inject.Inject
import kotlin.reflect.KClass

/** Central registry of all known quick affordance configs. */
interface KeyguardQuickAffordanceRegistry<T : KeyguardQuickAffordanceConfig> {
    fun getAll(position: KeyguardQuickAffordancePosition): List<T>
    fun get(configClass: KClass<out T>): T
    fun updateSettings()
}

class KeyguardQuickAffordanceRegistryImpl
@Inject
constructor(
    private val context: Context,
    private val homeControls: HomeControlsKeyguardQuickAffordanceConfig,
    private val quickAccessWallet: QuickAccessWalletKeyguardQuickAffordanceConfig,
    private val qrCodeScanner: QrCodeScannerKeyguardQuickAffordanceConfig,
    private val camera: CameraKeyguardQuickAffordanceConfig,
    private val flashlight: FlashlightKeyguardQuickAffordanceConfig,
    private val remoteControls: RemoteControlsKeyguardQuickAffordanceConfig,
) : KeyguardQuickAffordanceRegistry<KeyguardQuickAffordanceConfig> {

    private val configsBySetting: Map<String, KeyguardQuickAffordanceConfig> =
        mapOf(
            "home" to homeControls,
            "wallet" to quickAccessWallet,
            "qr" to qrCodeScanner,
            "camera" to camera,
            "flashlight" to flashlight,
            "remote" to remoteControls,
        )

    private var configsByPosition: Map<KeyguardQuickAffordancePosition, MutableList<KeyguardQuickAffordanceConfig>>
    private var configByClass: Map<KClass<out KeyguardQuickAffordanceConfig>, KeyguardQuickAffordanceConfig>

    init {
        configsByPosition = mapOf()
        configByClass = mapOf()
        updateSettings()
    }

    override fun getAll(
        position: KeyguardQuickAffordancePosition,
    ): List<KeyguardQuickAffordanceConfig> {
        return configsByPosition.getValue(position)
    }

    override fun get(
        configClass: KClass<out KeyguardQuickAffordanceConfig>
    ): KeyguardQuickAffordanceConfig {
        return configByClass.getValue(configClass)
    }

    override fun updateSettings() {
        var setting = Settings.System.getString(context.getContentResolver(),
                Settings.System.KEYGUARD_QUICK_TOGGLES)
        if (setting == null || setting.isEmpty())
            setting = "home,flashlight;wallet,qr,camera"
        val split: List<String> = setting.split(";")
        val start: List<String> = split.get(0).split(",")
        val end: List<String> = split.get(1).split(",")
        var startList: MutableList<KeyguardQuickAffordanceConfig> = mutableListOf()
        var endList: MutableList<KeyguardQuickAffordanceConfig> = mutableListOf()
        if (!start.get(0).equals("none")) {
            for (str in start)
                startList.add(configsBySetting.getOrDefault(str, homeControls))
        }
        if (!end.get(0).equals("none")) {
            for (str in end)
                endList.add(configsBySetting.getOrDefault(str, quickAccessWallet))
        }

        configsByPosition =
            mapOf(
                KeyguardQuickAffordancePosition.BOTTOM_START to
                    startList,
                KeyguardQuickAffordancePosition.BOTTOM_END to
                    endList,
            )

        configByClass =
            configsByPosition.values.flatten().associateBy { config -> config::class }
    }
}
