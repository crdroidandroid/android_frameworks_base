package com.google.android.systemui.keyguard.ui.binder

import android.graphics.Rect
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.graphics.ImageLoader
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.wakelock.DelayedWakeLock
import com.google.android.systemui.ambientmusic.AmbientIndicationContainer
import com.google.android.systemui.keyguard.ui.viewmodel.KeyguardAmbientIndicationViewModel
import java.util.concurrent.Executor
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.filterNotNull

object KeyguardAmbientIndicationAreaViewBinder {

    @JvmStatic
    fun bind(
        viewGroup: ViewGroup,
        viewModel: KeyguardAmbientIndicationViewModel,
        powerInteractor: PowerInteractor,
        keyguardUpdateMonitor: KeyguardUpdateMonitor,
        activityStarter: ActivityStarter,
        delayedWakeLockFactory: DelayedWakeLock.Factory,
        keyguardInteractor: KeyguardInteractor,
        configurationInteractor: ConfigurationInteractor,
        imageLoader: ImageLoader,
        keyguardRootViewModel: KeyguardRootViewModel?,
        falsingManager: FalsingManager,
        mainDelayableExecutor: DelayableExecutor,
        backgroundExecutor: Executor,
    ): DisposableHandle {
        val ambientIndicationContainer =
            viewGroup.findViewById<AmbientIndicationContainer>(R.id.ambient_indication_container)

        if (ambientIndicationContainer != null) {
            ambientIndicationContainer.mPowerInteractor = powerInteractor
            ambientIndicationContainer.mKeyguardUpdateMonitor = keyguardUpdateMonitor
            ambientIndicationContainer.mActivityStarter = activityStarter
            ambientIndicationContainer.mDelayedWakeLockFactory = delayedWakeLockFactory
            ambientIndicationContainer.mFalsingManager = falsingManager
            ambientIndicationContainer.mMainDelayableExecutor = mainDelayableExecutor
            ambientIndicationContainer.mBackgroundExecutor = backgroundExecutor
            ambientIndicationContainer.mWakeLock = ambientIndicationContainer.createWakeLock()
            ambientIndicationContainer.mImageLoader = imageLoader
            ambientIndicationContainer.mUsingExtendedIndication = false
            ambientIndicationContainer.mCurrentLoadedAlbumArtUri = null
            ambientIndicationContainer.addInflateListener {
                ambientIndicationContainer.initializeView()
            }
            ambientIndicationContainer.getChildAt(0)
            ambientIndicationContainer.initializeView()
        }

        val disposableHandle =
            ambientIndicationContainer?.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    viewModel.indicationAreaTranslationX.collect { x ->
                        ambientIndicationContainer.translationX = x
                    }
                }
                launch {
                    viewModel.indicationAreaTranslationY.collect { y ->
                        ambientIndicationContainer.translationY = y
                    }
                }
                launch {
                    viewModel.ambientIndicationMusicState.collect { music ->
                        if (music != null) {
                            ambientIndicationContainer.setAmbientMusic(
                                music.text,
                                music.openIntent,
                                music.favoritingIntent,
                                music.iconOverride ?: 0,
                                music.skipUnlock == true,
                                music.iconDescription,
                                music.extendedIndication,
                            )
                        } else {
                            ambientIndicationContainer.setAmbientMusic(
                                null,
                                null,
                                null,
                                0,
                                false,
                                null,
                                null,
                            )
                        }
                    }
                }
                launch {
                    keyguardInteractor.dozeTransitionModel.collect { dozeTransition ->
                        if (
                            dozeTransition.from == DozeStateModel.INITIALIZED &&
                                dozeTransition.to == DozeStateModel.DOZE_AOD
                        ) {
                            ambientIndicationContainer.restoreToCollapsedState()
                        }
                    }
                }
                launch {
                    configurationInteractor.configurationValues.collect { configuration ->
                        ambientIndicationContainer.updateContainerWidthOnFoldableDevice(
                            configuration.screenWidthDp,
                            configuration.smallestScreenWidthDp,
                        )
                    }
                }
                launch {
                    keyguardRootViewModel?.lastRootViewTapPosition?.filterNotNull()?.collect { point
                        ->
                        val rect = Rect()
                        ambientIndicationContainer.getHitRect(rect)
                        if (!rect.contains(point.x, point.y)) {
                            ambientIndicationContainer.restoreToCollapsedState()
                        }
                    }
                }
            }
        }

        return DisposableHandle {
            disposableHandle?.dispose()
        }
    }
}
