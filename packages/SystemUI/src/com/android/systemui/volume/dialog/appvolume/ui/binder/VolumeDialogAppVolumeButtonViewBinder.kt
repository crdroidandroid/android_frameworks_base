package com.android.systemui.volume.dialog.appvolume.ui.binder

import android.view.View
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.res.R
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.appvolume.ui.viewmodel.VolumeDialogAppVolumeButtonViewModel
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.ui.binder.ViewBinder
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.onEach

/** Binds the app volume button view. */
@VolumeDialogScope
class VolumeDialogAppVolumeButtonViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogAppVolumeButtonViewModel,
    private val dialogViewModel: VolumeDialogViewModel,
) : ViewBinder {
    override fun CoroutineScope.bind(view: View) {
        val appVolumeButton = view.requireViewById<android.widget.ImageButton>(R.id.app_volume_icon)

        launchTraced("VDAVBVB#addTouchableBounds") {
            dialogViewModel.addTouchableBounds(appVolumeButton)
        }

        viewModel.isVisible
            .onEach { isVisible ->
                appVolumeButton.visibility =
                    if (isVisible) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
            }
            .launchInTraced("VDAVBVB#isVisible", this)

        // Set color filter to match captions button disabled state (theme-aware)
        appVolumeButton.setColorFilter(
            appVolumeButton.context.getColor(
                com.android.internal.R.color.materialColorOnSurface
            )
        )

        appVolumeButton.setOnClickListener {
            viewModel.onButtonClicked()
            Events.writeEvent(Events.EVENT_SETTINGS_CLICK)
        }
    }
}
