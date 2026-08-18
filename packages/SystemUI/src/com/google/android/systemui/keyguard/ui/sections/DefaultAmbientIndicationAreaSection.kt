package com.google.android.systemui.keyguard.ui.sections

import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.graphics.ImageLoader
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.wakelock.DelayedWakeLock
import com.google.android.systemui.keyguard.ui.binder.KeyguardAmbientIndicationAreaViewBinder
import com.google.android.systemui.keyguard.ui.viewmodel.KeyguardAmbientIndicationViewModel
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

@SysUISingleton
class DefaultAmbientIndicationAreaSection
@Inject
constructor(
    private val keyguardAmbientIndicationViewModel: KeyguardAmbientIndicationViewModel,
    private val powerInteractor: PowerInteractor,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val activityStarter: ActivityStarter,
    private val delayedWakeLockFactory: DelayedWakeLock.Factory,
    private val keyguardInteractor: KeyguardInteractor,
    private val configurationInteractor: ConfigurationInteractor,
    private val imageLoader: ImageLoader,
    private val keyguardRootViewModel: KeyguardRootViewModel,
    private val falsingManager: FalsingManager,
    @Main private val mainDelayableExecutor: DelayableExecutor,
    @Background private val backgroundExecutor: Executor,
) : KeyguardSection() {

    private var ambientIndicationAreaHandle: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        val view =
            LayoutInflater.from(constraintLayout.context)
                .inflate(R.layout.ambient_indication, constraintLayout, false)
        constraintLayout.addView(view)
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.constrainWidth(
            R.id.ambient_indication_container,
            ConstraintLayout.LayoutParams.MATCH_PARENT,
        )
        if (keyguardUpdateMonitor.isUdfpsSupported()) {
            constraintSet.constrainHeight(R.id.ambient_indication_container, 0)
            constraintSet.connect(
                R.id.ambient_indication_container,
                ConstraintSet.TOP,
                R.id.device_entry_icon_view,
                ConstraintSet.BOTTOM,
            )
            constraintSet.connect(
                R.id.ambient_indication_container,
                ConstraintSet.BOTTOM,
                R.id.keyguard_indication_area,
                ConstraintSet.TOP,
            )
            constraintSet.connect(
                R.id.ambient_indication_container,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
            )
            constraintSet.connect(
                R.id.ambient_indication_container,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
            )
        } else {
            constraintSet.constrainHeight(
                R.id.ambient_indication_container,
                ConstraintSet.WRAP_CONTENT,
            )
            constraintSet.connect(
                R.id.ambient_indication_container,
                ConstraintSet.BOTTOM,
                R.id.device_entry_icon_view,
                ConstraintSet.TOP,
            )
            constraintSet.connect(
                R.id.ambient_indication_container,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
            )
            constraintSet.connect(
                R.id.ambient_indication_container,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
            )
        }
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        ambientIndicationAreaHandle =
            KeyguardAmbientIndicationAreaViewBinder.bind(
                viewGroup = constraintLayout,
                viewModel = keyguardAmbientIndicationViewModel,
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
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        ambientIndicationAreaHandle?.dispose()
        ambientIndicationAreaHandle = null
        val view = constraintLayout.findViewById<View>(R.id.ambient_indication_container)
        if (view != null) {
            constraintLayout.removeView(view)
        }
    }
}
