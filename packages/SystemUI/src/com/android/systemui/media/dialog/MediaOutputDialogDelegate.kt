/*
 * Copyright (C) 2020 The Android Open Source Project
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
 */
package com.android.systemui.media.dialog

import android.app.Dialog
import android.app.WallpaperColors
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.google.android.material.button.MaterialButton
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** The Output Switcher dialog */
class MediaOutputDialogDelegate
@AssistedInject
constructor(
    @Assisted(ABOVE_STATUS_BAR) private val aboveStatusBar: Boolean,
    @Assisted private val mMediaSwitchingController: MediaSwitchingController,
    /**
     * Signals whether the dialog should NOT show app-related metadata. A metadata-less dialog hides
     * the title, subtitle, and app icon in the header.
     */
    @Assisted(INCLUDE_PLAYBACK_AND_APP_METADATA)
    private val mIncludePlaybackAndAppMetadata: Boolean,
    @Assisted private val mOnDialogEventListener: OnDialogEventListener?,
    @Assisted(USE_SYSTEM_COLORS) private val mUseSystemColors: Boolean = false,
    private val context: Context,
    private val mBroadcastSender: BroadcastSender,
    private val mUiEventLogger: UiEventLogger,
    private val systemUIDialogFactory: SystemUIDialog.Factory,
) : SystemUIDialog.Delegate, MediaSwitchingController.Callback {

    private lateinit var mContext: Context

    private val mMainThreadHandler: Handler = Handler(Looper.getMainLooper())
    private lateinit var mLayoutManager: LinearLayoutManager

    @VisibleForTesting lateinit var mDialogView: View
    private lateinit var mHeaderTitle: TextView
    private lateinit var mHeaderSubtitle: TextView
    private lateinit var mHeaderIcon: ImageView
    private lateinit var mAppResourceIconNormal: ImageView
    private lateinit var mAppResourceIconSmall: ImageView
    private lateinit var mDevicesRecyclerView: RecyclerView
    private lateinit var mDeviceListLayout: ViewGroup
    private lateinit var mQuickAccessShelf: ViewGroup
    private lateinit var mConnectDeviceButton: MaterialButton
    private lateinit var mAudioSharingButton: MaterialButton
    private lateinit var mMediaMetadataSectionLayout: LinearLayout
    private lateinit var mDoneButton: Button
    private lateinit var mDialogFooter: ViewGroup
    private lateinit var mStopButton: Button
    private lateinit var mWarningSection: ViewGroup
    private lateinit var mWarningTextView: TextView
    private lateinit var mWarningFixButton: Button
    private var mDismissing = false
    private var currentDialog: SystemUIDialog? = null

    @JvmField
    @VisibleForTesting
    var mAdapter: MediaOutputAdapter = MediaOutputAdapter(mMediaSwitchingController)

    private inner class LayoutManagerWrapper(context: Context?) : LinearLayoutManager(context) {
        override fun onLayoutCompleted(state: RecyclerView.State?) {
            super.onLayoutCompleted(state)
            mMediaSwitchingController.setRefreshing(false)
            mMediaSwitchingController.refreshDataSetIfNeeded()
        }
    }

    override fun createDialog(): SystemUIDialog {
        val dialog =
            systemUIDialogFactory.create(this, context, R.style.Theme_SystemUI_Dialog_Media)
        if (!aboveStatusBar) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
        currentDialog = dialog
        return dialog
    }

    override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        mContext = dialog.context
        mDialogView = LayoutInflater.from(mContext).inflate(R.layout.media_output_dialog, null)
        val lp = dialog.window?.attributes
        lp?.gravity = Gravity.CENTER
        // Config insets to make sure the layout is above the navigation bar
        lp?.setFitInsetsTypes(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        lp?.setFitInsetsSides(WindowInsets.Side.all())
        lp?.setFitInsetsIgnoringVisibility(true)
        dialog.window?.setAttributes(lp)
        dialog.window?.setContentView(mDialogView)
        dialog.window?.setTitle(
            mContext.getString(R.string.media_output_dialog_accessibility_title)
        )
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)

        mLayoutManager = LayoutManagerWrapper(mContext)
        mHeaderTitle = mDialogView.requireViewById(R.id.header_title)
        mHeaderSubtitle = mDialogView.requireViewById(R.id.header_subtitle)
        mHeaderIcon = mDialogView.requireViewById(R.id.header_icon)
        mAppResourceIconNormal = mDialogView.requireViewById(R.id.app_source_icon)
        mAppResourceIconSmall =
            mDialogView.requireViewById(R.id.app_source_icon_small_screen_height)
        mQuickAccessShelf = mDialogView.requireViewById(R.id.quick_access_shelf)
        mConnectDeviceButton = mDialogView.requireViewById(R.id.connect_device)
        mAudioSharingButton = mDialogView.requireViewById(R.id.audio_sharing)
        mDevicesRecyclerView = mDialogView.requireViewById(R.id.list_result)
        mDialogFooter = mDialogView.requireViewById(R.id.dialog_footer)
        mMediaMetadataSectionLayout = mDialogView.requireViewById(R.id.media_metadata_section)
        mDeviceListLayout = mDialogView.requireViewById(R.id.device_list)
        mDoneButton = mDialogView.requireViewById(R.id.done)
        mStopButton = mDialogView.requireViewById(R.id.stop)
        mWarningSection = mDialogView.requireViewById(R.id.warning_section)
        mWarningTextView = mDialogView.requireViewById(R.id.warning_text)
        mWarningFixButton = mDialogView.requireViewById(R.id.warning_fix_button)

        updateAppResourceIcon()
        mMediaMetadataSectionLayout.visibility =
            if (isSmallScreenHeight()) View.GONE else View.VISIBLE

        // Init device list
        mLayoutManager.isAutoMeasureEnabled = true
        mDevicesRecyclerView.setLayoutManager(mLayoutManager)
        mDevicesRecyclerView.setAdapter(mAdapter)
        mDevicesRecyclerView.setHasFixedSize(false)
        // Init bottom buttons
        mDoneButton.setOnClickListener { dialog.dismiss() }
        mStopButton.setOnClickListener { onStopButtonClick() }
        if (mMediaSwitchingController.getAppLaunchIntent() != null) {
            // For a11y purposes only add listener if a section is clickable.
            mMediaMetadataSectionLayout.setOnClickListener { view: View ->
                mMediaSwitchingController.tryToLaunchMediaApplication(view)
            }
        }

        mDismissing = false

        // Change footer background color on scroll.
        mDevicesRecyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    changeFooterColorForScroll()
                }
            }
        )
        // Changes footer background when the list dimensions changed without scroll.
        mDevicesRecyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            changeFooterColorForScroll()
        }

        mUiEventLogger.log(MediaOutputEvent.MEDIA_OUTPUT_DIALOG_SHOW)

        mOnDialogEventListener?.onCreate(dialog)
    }

    override fun onConfigurationChanged(dialog: SystemUIDialog, configuration: Configuration) {
        mMainThreadHandler.post {
            updateAppResourceIcon()
            mMediaMetadataSectionLayout.visibility =
                if (isSmallScreenHeight()) View.GONE else View.VISIBLE
        }
    }

    override fun beforeDismiss(dialog: SystemUIDialog) {
        // TODO(287191450): remove this once expensive binder calls are removed from refresh().
        // Due to these binder calls on the UI thread, calling refresh() during dismissal causes
        // significant frame drops for the dismissal animation. Since the dialog is going away
        // anyway, we use this state to turn refresh() into a no-op.
        mDismissing = true
    }

    fun dismiss() {
        currentDialog?.dismiss()
    }

    override fun onStart(dialog: SystemUIDialog) {
        mMediaSwitchingController.start(this)
    }

    override fun onStop(dialog: SystemUIDialog) {
        mMediaSwitchingController.stop()
        mOnDialogEventListener?.onStop(dialog)
        currentDialog = null
    }

    @VisibleForTesting
    fun refresh() {
        refresh(false)
    }

    fun refresh(deviceSetChanged: Boolean) {
        // TODO(287191450): remove binder calls in this method from the UI thread.
        // If the dialog is going away or is already refreshing, do nothing.
        if (mDismissing || mMediaSwitchingController.isRefreshing()) {
            return
        }
        mMediaSwitchingController.setRefreshing(true)
        // Update header icon
        val headerIcon = mMediaSwitchingController.getHeaderIcon()
        if (headerIcon != null) {
            val icon = headerIcon.toIcon(mContext)
            val iconSupportsColorExtraction =
                icon.type == Icon.TYPE_BITMAP || icon.type == Icon.TYPE_ADAPTIVE_BITMAP
            val useSystemColors = mUseSystemColors
            if (!iconSupportsColorExtraction || useSystemColors) {
                // icon doesn't support getBitmap, use default value for color scheme
                updateButtonBackgroundColorFilter()
                updateDialogBackgroundColor()
            } else {
                val config = mContext.resources.configuration
                val currentNightMode = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
                val isDarkThemeOn = currentNightMode == Configuration.UI_MODE_NIGHT_YES
                val wallpaperColors = WallpaperColors.fromBitmap(icon.getBitmap())
                mMediaSwitchingController.updateCurrentColorScheme(wallpaperColors, isDarkThemeOn)
                updateButtonBackgroundColorFilter()
                updateDialogBackgroundColor()
            }
            mHeaderIcon.setVisibility(View.VISIBLE)
            mHeaderIcon.setImageIcon(icon)
        } else {
            updateButtonBackgroundColorFilter()
            updateDialogBackgroundColor()
            mHeaderIcon.setVisibility(View.GONE)
        }

        updateAppResourceIcon()

        if (!mIncludePlaybackAndAppMetadata) {
            mHeaderTitle.visibility = View.GONE
            mHeaderSubtitle.visibility = View.GONE
        } else {
            // Update title and subtitle
            mHeaderTitle.text = mMediaSwitchingController.getHeaderTitle()
            mHeaderTitle.setTextColor(mMediaSwitchingController.getColorScheme().getOnSurface())
            val subTitle = mMediaSwitchingController.getHeaderSubTitle()
            if (subTitle.isNullOrEmpty()) {
                mHeaderSubtitle.visibility = View.GONE
                mHeaderTitle.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)
            } else {
                mHeaderSubtitle.visibility = View.VISIBLE
                mHeaderSubtitle.text = subTitle
                mHeaderSubtitle.setTextColor(
                    mMediaSwitchingController.getColorScheme().getOnSurface()
                )
                mHeaderTitle.setGravity(Gravity.NO_GRAVITY)
            }
        }

        refreshQuickAccessShelf()

        // Show when remote media session is available or
        //      when the device supports BT LE audio + media is playing
        val stopResId = mMediaSwitchingController.getStopButtonStringRes()
        if (stopResId != null) {
            mStopButton.text = mContext.getString(stopResId)
            mStopButton.visibility = View.VISIBLE
        } else {
            mStopButton.visibility = View.GONE
        }
        mStopButton.setEnabled(true)
        mStopButton.setOnClickListener { onStopButtonClick() }

        refreshWarningSection()

        if (!mAdapter.isDragging()) {
            val currentActivePosition = mAdapter.getCurrentActivePosition()
            if (!deviceSetChanged && currentActivePosition in 0..<mAdapter.itemCount) {
                mAdapter.notifyItemChanged(currentActivePosition)
            } else {
                mAdapter.updateItems()
            }
        } else {
            mMediaSwitchingController.setRefreshing(false)
            mMediaSwitchingController.refreshDataSetIfNeeded()
        }
    }

    private fun updateButtonBackgroundColorFilter() {
        val colorScheme = mMediaSwitchingController.getColorScheme()
        mDoneButton.background?.setTint(colorScheme.getPrimary())
        mDoneButton.setTextColor(colorScheme.getOnPrimary())
        mStopButton.background?.setTint(colorScheme.getOutlineVariant())
        mStopButton.setTextColor(colorScheme.getPrimary())
        mConnectDeviceButton.setTextColor(colorScheme.getOnSurfaceVariant())
        mConnectDeviceButton.setStrokeColor(ColorStateList.valueOf(colorScheme.getOutlineVariant()))
        mConnectDeviceButton.setIconTint(ColorStateList.valueOf(colorScheme.getPrimary()))

        mAudioSharingButton.setTextColor(
            getButtonColorStateList(
                defaultColor = colorScheme.getOnSurfaceVariant(),
                activatedColor = colorScheme.getOnPrimary(),
            )
        )
        mAudioSharingButton.setStrokeColor(
            getButtonColorStateList(
                defaultColor = colorScheme.getOutlineVariant(),
                activatedColor = colorScheme.getPrimary(),
            )
        )
        mAudioSharingButton.backgroundTintList =
            getButtonColorStateList(
                defaultColor = colorScheme.getSurfaceContainer(),
                activatedColor = colorScheme.getPrimary(),
            )
        mAudioSharingButton.setIconTint(
            getButtonColorStateList(
                defaultColor = colorScheme.getPrimary(),
                activatedColor = colorScheme.getOnPrimary(),
            )
        )
    }

    private fun getButtonColorStateList(defaultColor: Int, activatedColor: Int): ColorStateList {
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf()),
            intArrayOf(activatedColor, defaultColor),
        )
    }

    private fun updateDialogBackgroundColor() {
        val backgroundColor = mMediaSwitchingController.getColorScheme().getSurfaceContainer()
        mDialogView.background?.setTint(backgroundColor)
        mDeviceListLayout.setBackgroundColor(backgroundColor)
    }

    private fun changeFooterColorForScroll() {
        val totalItemCount = mLayoutManager.getItemCount()
        val lastVisibleItemPosition = mLayoutManager.findLastCompletelyVisibleItemPosition()
        val hasBottomScroll = totalItemCount > 0 && lastVisibleItemPosition != totalItemCount - 1
        val colorScheme = mMediaSwitchingController.getColorScheme()
        mDialogFooter.background?.setTint(
            if (hasBottomScroll) colorScheme.getSurfaceContainerHigh()
            else colorScheme.getSurfaceContainer()
        )
    }

    private fun refreshQuickAccessShelf() {
        var showQuickAccessShelf = false
        val buttonState = mMediaSwitchingController.getAudioSharingButtonState()
        if (buttonState == null) {
            mAudioSharingButton.visibility = View.GONE
        } else {
            showQuickAccessShelf = true
            mAudioSharingButton.apply {
                visibility = View.VISIBLE
                setText(buttonState.resId)
                isActivated = buttonState.isActive
                setOnClickListener { view: View ->
                    mMediaSwitchingController.launchAudioSharing(view)
                }
            }
        }

        if (mMediaSwitchingController.hasConnectDeviceButton()) {
            showQuickAccessShelf = true
            mConnectDeviceButton.apply {
                visibility = View.VISIBLE
                setOnClickListener { view: View ->
                    mMediaSwitchingController.launchBluetoothPairing(view)
                }
            }
        } else {
            mConnectDeviceButton.visibility = View.GONE
        }

        mQuickAccessShelf.visibility = if (showQuickAccessShelf) View.VISIBLE else View.GONE
    }

    private fun updateAppResourceIcon() {
        mAppResourceIconNormal.visibility = View.GONE
        mAppResourceIconSmall.visibility = View.GONE

        val appIcon = mMediaSwitchingController.getAppIcon()
        val mAppResourceIcon =
            if (isSmallScreenHeight()) mAppResourceIconSmall else mAppResourceIconNormal
        if (mIncludePlaybackAndAppMetadata && appIcon != null) {
            mAppResourceIcon.apply {
                setColorFilter(mMediaSwitchingController.getColorScheme().getSecondary())
                setImageDrawable(appIcon)
                visibility = View.VISIBLE
            }
        }
    }

    private fun isSmallScreenHeight(): Boolean =
        mContext.resources.configuration.screenHeightDp <= SMALL_SCREEN_HEIGHT_DP

    private fun refreshWarningSection() {
        val warningInfo = mMediaSwitchingController.getMissingPermissionsWarning()
        if (warningInfo == null) {
            mWarningSection.visibility = View.GONE
            return
        }
        mWarningSection.visibility = View.VISIBLE
        val appName = warningInfo.appName
        mWarningTextView.text =
            mContext.getString(R.string.media_output_dialog_missing_permissions_warning, appName)
        mWarningFixButton.text =
            mContext.getString(R.string.media_output_dialog_warning_button_fix, appName)
        mWarningFixButton.setOnClickListener {
            mMediaSwitchingController.tryToLaunchMissingPermissionsResolveIntent()
        }
    }

    @VisibleForTesting
    fun onStopButtonClick() {
        mMediaSwitchingController.releaseSession()
        currentDialog?.dismissWithoutAnimation()
    }

    override fun onMediaChanged() {
        mMainThreadHandler.post { refresh() }
    }

    override fun onMediaStoppedOrPaused() {
        if (currentDialog?.isShowing == true) {
            currentDialog?.dismiss()
        }
    }

    override fun onRouteChanged() {
        mMainThreadHandler.post { refresh() }
    }

    override fun onDeviceListChanged() {
        mMainThreadHandler.post { refresh(true) }
    }

    override fun dismissDialog() {
        // Explicitly use dismiss() to dismiss the dialog, as relying on closeSystemDialogs() for
        // dismissal is unstable on desktop.
        // TODO(b/457526674): Remove the dismiss() call once the issue with closeSystemDialogs() is
        // fixed on desktop.
        currentDialog?.dismiss()
        mBroadcastSender.closeSystemDialogs()
    }

    override fun onQuickAccessButtonsChanged() {
        mMainThreadHandler.post { refreshQuickAccessShelf() }
    }

    @VisibleForTesting
    enum class MediaOutputEvent(private val mId: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The MediaOutput dialog became visible on the screen.")
        MEDIA_OUTPUT_DIALOG_SHOW(655);

        override fun getId(): Int = mId
    }

    /** Callback for dialog events. */
    interface OnDialogEventListener {
        /** Will be called when the dialog is created. */
        fun onCreate(dialog: Dialog)

        /** Will be called when the dialog is stopping. */
        fun onStop(dialog: Dialog)
    }

    @AssistedFactory
    interface Factory {

        fun create(
            @Assisted(ABOVE_STATUS_BAR) aboveStatusBar: Boolean,
            mMediaSwitchingController: MediaSwitchingController,
            @Assisted(INCLUDE_PLAYBACK_AND_APP_METADATA) includePlaybackAndAppMetadata: Boolean,
            onDialogEventListener: OnDialogEventListener?,
            @Assisted(USE_SYSTEM_COLORS) useSystemColors: Boolean,
        ): MediaOutputDialogDelegate
    }

    companion object {
        private const val TAG = "MediaOutputDialog"
        private const val SMALL_SCREEN_HEIGHT_DP: Int = 400
        private const val ABOVE_STATUS_BAR = "above_status_bar"
        private const val INCLUDE_PLAYBACK_AND_APP_METADATA = "include_playback_and_app_metadata"
        private const val USE_SYSTEM_COLORS = "use_system_colors"
    }
}
