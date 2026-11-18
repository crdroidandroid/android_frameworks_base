/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.CoreStartable
import com.android.systemui.biometrics.domain.interactor.FingerprintPropertyInteractor
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardBlueprintRepository
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.SplitShadeKeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Config
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Type
import com.android.systemui.keyguard.ui.view.layout.sections.KeyguardSliceViewSection
import com.android.systemui.keyguard.ui.view.layout.sections.KeyguardWeatherViewSection
import com.android.systemui.keyguard.ui.view.layout.sections.SmartspaceSection
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@SysUISingleton
class KeyguardBlueprintInteractor
@Inject
constructor(
    private val keyguardBlueprintRepository: KeyguardBlueprintRepository,
    @Application private val applicationScope: CoroutineScope,
    shadeModeInteractor: ShadeModeInteractor,
    @ShadeDisplayAware private val configurationInteractor: ConfigurationInteractor,
    private val fingerprintPropertyInteractor: FingerprintPropertyInteractor,
    private val smartspaceSection: SmartspaceSection,
    private val keyguardSmartspaceInteractor: KeyguardSmartspaceInteractor,
) : CoreStartable {
    /** The current blueprint for the lockscreen. */
    val blueprint: StateFlow<KeyguardBlueprint> = keyguardBlueprintRepository.blueprint

    /**
     * Triggered when the blueprint isn't changed, but the ConstraintSet should be rebuilt and
     * optionally a transition should be fired to move to the rebuilt ConstraintSet.
     */
    val refreshTransition = keyguardBlueprintRepository.refreshTransition

    /** Current BlueprintId */
    val blueprintId =
        shadeModeInteractor.shadeMode.map { shadeMode ->
            when {
                shadeMode is ShadeMode.Split && !SceneContainerFlag.isEnabled ->
                    SplitShadeKeyguardBlueprint.ID
                else -> DefaultKeyguardBlueprint.DEFAULT
            }
        }

    override fun start() {
        applicationScope.launch { blueprintId.collect { transitionToBlueprint(it) } }
        applicationScope.launch {
            fingerprintPropertyInteractor.propertiesInitialized
                .filter { it }
                .collect { refreshBlueprint() }
        }
        applicationScope.launch {
            val refreshConfig =
                Config(Type.NoTransition, rebuildSections = listOf(smartspaceSection))
            configurationInteractor.onAnyConfigurationChange.collect {
                refreshBlueprint(refreshConfig)
            }
        }
        applicationScope.launch {
            combine(
                    keyguardSmartspaceInteractor.isWeatherEnabled,
                    keyguardSmartspaceInteractor.isOmniWeatherEnabled,
                ) { smartspace, omniWeather ->
                    smartspace to (omniWeather && !smartspace)
                }
                .distinctUntilChanged()
                .drop(1) // Initial state is already built.
                .collect { rebuildSmartspaceSections() }
        }
    }

    /**
     * Transitions to a blueprint, or refreshes it if already applied.
     *
     * @param blueprintId
     * @return whether the transition has succeeded.
     */
    fun transitionOrRefreshBlueprint(blueprintId: String): Boolean {
        if (blueprintId == blueprint.value.id) {
            refreshBlueprint()
            return true
        }

        return keyguardBlueprintRepository.applyBlueprint(blueprintId)
    }

    /**
     * Transitions to a blueprint.
     *
     * @param blueprintId
     * @return whether the transition has succeeded.
     */
    fun transitionToBlueprint(blueprintId: String): Boolean {
        return keyguardBlueprintRepository.applyBlueprint(blueprintId)
    }

    /** Emits a value to refresh the blueprint with the appropriate transition. */
    fun refreshBlueprint(type: Type = Type.NoTransition) = refreshBlueprint(Config(type))

    /** Emits a value to refresh the blueprint with the appropriate transition. */
    fun refreshBlueprint(config: Config) = keyguardBlueprintRepository.refreshBlueprint(config)

    fun getCurrentBlueprint(): KeyguardBlueprint {
        return keyguardBlueprintRepository.blueprint.value
    }

    fun rebuildSmartspaceSections() {
        val sections =
            getCurrentBlueprint().sections.filter {
                it is SmartspaceSection ||
                    it is KeyguardSliceViewSection ||
                    it is KeyguardWeatherViewSection
            }
        refreshBlueprint(Config(Type.NoTransition, rebuildSections = sections))
    }

    companion object {
        private val TAG = "KeyguardBlueprintInteractor"
    }
}
