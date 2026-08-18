package com.google.android.systemui.keyguard.ui.composable.elements

import android.content.Context
import android.view.LayoutInflater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.ElementContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.graphics.ImageLoader
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.keyguard.ui.composable.elements.BaseLockscreenElement.ElementSource
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementProvider
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.wakelock.DelayedWakeLock
import com.google.android.systemui.ambientmusic.AmbientIndicationContainer
import com.google.android.systemui.keyguard.ui.binder.KeyguardAmbientIndicationAreaViewBinder
import com.google.android.systemui.keyguard.ui.viewmodel.KeyguardAmbientIndicationViewModel
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

@SysUISingleton
class GoogleAmbientIndicationElementProvider
@Inject
constructor(
    @Application val context: Context,
    val viewModel: KeyguardAmbientIndicationViewModel,
    val powerInteractor: PowerInteractor,
    val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    val activityStarter: ActivityStarter,
    val delayedWakeLockFactory: DelayedWakeLock.Factory,
    val keyguardInteractor: KeyguardInteractor,
    val configurationInteractor: ConfigurationInteractor,
    val imageLoader: ImageLoader,
    val keyguardRootViewModel: KeyguardRootViewModel,
    val falsingManager: FalsingManager,
    @Main val mainDelayableExecutor: DelayableExecutor,
    @Background val backgroundExecutor: Executor,
) : LockscreenElementProvider {

    override val elements: List<LockscreenElement> by lazy { listOf(IndicationAreaElement()) }

    @Composable
    fun AmbientIndication(modifier: Modifier = Modifier) {
        var bindingHandle by remember { mutableStateOf<DisposableHandle?>(null) }

        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                val view =
                    LayoutInflater.from(ctx).inflate(R.layout.ambient_indication, null)
                        as AmbientIndicationContainer
                bindingHandle =
                    KeyguardAmbientIndicationAreaViewBinder.bind(
                        viewGroup = view,
                        viewModel = viewModel,
                        powerInteractor = powerInteractor,
                        keyguardUpdateMonitor = keyguardUpdateMonitor,
                        activityStarter = activityStarter,
                        delayedWakeLockFactory = delayedWakeLockFactory,
                        keyguardInteractor = keyguardInteractor,
                        configurationInteractor = configurationInteractor,
                        imageLoader = imageLoader,
                        keyguardRootViewModel = keyguardRootViewModel,
                        falsingManager = falsingManager,
                        mainDelayableExecutor = mainDelayableExecutor,
                        backgroundExecutor = backgroundExecutor,
                    )
                view
            },
            onRelease = {
                bindingHandle?.dispose()
                bindingHandle = null
            },
        )
    }

    inner class IndicationAreaElement : LockscreenElement {
        override val context: Context = this@GoogleAmbientIndicationElementProvider.context
        override val key: ElementKey = LockscreenElementKeys.AmbientIndicationArea
        override val source: ElementSource = ElementSource.STANDARD

        @Composable
        override fun LockscreenScope<ElementContentScope>.LockscreenElement() {
            AmbientIndication()
        }
    }
}
