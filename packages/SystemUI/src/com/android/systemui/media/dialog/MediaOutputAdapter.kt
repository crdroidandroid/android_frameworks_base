/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import com.android.settingslib.media.InputMediaDevice
import com.android.settingslib.media.MediaDevice
import com.android.systemui.FontStyles.GSF_TITLE_MEDIUM_EMPHASIZED
import com.android.systemui.FontStyles.GSF_TITLE_SMALL
import com.android.systemui.media.dialog.MediaItem.MediaItemType.TYPE_DEVICE
import com.android.systemui.media.dialog.MediaItem.MediaItemType.TYPE_DEVICE_GROUP
import com.android.systemui.media.dialog.MediaItem.MediaItemType.TYPE_GROUP_DIVIDER
import com.android.systemui.media.dialog.MediaOutputAdapterBase.ConnectionState.CONNECTED
import com.android.systemui.media.dialog.MediaOutputAdapterBase.ConnectionState.CONNECTING
import com.android.systemui.media.dialog.MediaOutputAdapterBase.ConnectionState.DISCONNECTED
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.getOrNull
import com.google.android.material.slider.Slider

/** A RecyclerView adapter for the legacy UI media output dialog device list. */
class MediaOutputAdapter(controller: MediaSwitchingController) :
    MediaOutputAdapterBase(controller) {
    private val mGroupSelectedItems = mController.selectedMediaDevice.size > 1

    /** Refreshes the RecyclerView dataset and forces re-render. */
    override fun updateItems() {
        val newList =
            mController.getMediaItemList(false /* addConnectNewDeviceButton */).toMutableList()

        addSeparatorForTheFirstGroupDivider(newList)
        coalesceSelectedDevices(newList)

        mMediaItemList.clear()
        mMediaItemList.addAll(newList)

        notifyDataSetChanged()
    }

    private fun addSeparatorForTheFirstGroupDivider(newList: MutableList<MediaItem>) {
        for ((i, item) in newList.withIndex()) {
            if (item.mediaItemType == TYPE_GROUP_DIVIDER) {
                newList[i] = MediaItem.createGroupDividerWithSeparatorMediaItem(item.title)
                break
            }
        }
    }

    /**
     * If there are 2+ selected devices, adds an "Connected speakers" expandable group divider and
     * displays a single session control instead of individual device controls.
     */
    private fun coalesceSelectedDevices(newList: MutableList<MediaItem>) {
        val selectedDevices = newList.filter { this.isSelectedDevice(it) }

        if (mGroupSelectedItems && selectedDevices.size > 1) {
            newList.removeAll(selectedDevices.toSet())
            if (mController.isGroupListCollapsed) {
                newList.add(0, MediaItem.createDeviceGroupMediaItem())
            } else {
                newList.addAll(0, selectedDevices)
            }
            newList.add(0, mController.connectedSpeakersExpandableGroupDivider)
        }
    }

    private fun isSelectedDevice(mediaItem: MediaItem): Boolean {
        return mediaItem.mediaDevice.getOrNull()?.let { device ->
            isDeviceIncluded(mController.selectedMediaDevice, device)
        } ?: false
    }

    override fun getItemId(position: Int): Long {
        if (position >= mMediaItemList.size) {
            Log.e(TAG, "Item position exceeds list size: $position")
            return RecyclerView.NO_ID
        }
        val currentMediaItem = mMediaItemList[position]
        return when (currentMediaItem.mediaItemType) {
            TYPE_DEVICE ->
                currentMediaItem.mediaDevice.getOrNull()?.id?.hashCode()?.toLong()
                    ?: RecyclerView.NO_ID
            TYPE_GROUP_DIVIDER -> currentMediaItem.title.hashCode().toLong()
            TYPE_DEVICE_GROUP -> currentMediaItem.hashCode().toLong()
            else -> RecyclerView.NO_ID
        }
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = viewGroup.context
        return when (viewType) {
            TYPE_GROUP_DIVIDER -> {
                val holderView =
                    LayoutInflater.from(context)
                        .inflate(R.layout.media_output_list_item_group_divider, viewGroup, false)
                MediaGroupDividerViewHolder(holderView, context)
            }

            TYPE_DEVICE,
            TYPE_DEVICE_GROUP -> {
                val holderView =
                    LayoutInflater.from(context)
                        .inflate(R.layout.media_output_list_item_device, viewGroup, false)
                MediaDeviceViewHolder(holderView, context)
            }

            else -> throw IllegalArgumentException("Invalid view type: $viewType")
        }
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        require(position < itemCount) { "Invalid position: $position, list size: $itemCount" }
        val currentMediaItem = mMediaItemList[position]
        when (currentMediaItem.mediaItemType) {
            TYPE_GROUP_DIVIDER ->
                (viewHolder as MediaGroupDividerViewHolder).onBind(
                    groupDividerTitle = currentMediaItem.title,
                    isExpandableDivider = currentMediaItem.isExpandableDivider,
                    hasTopSeparator = currentMediaItem.hasTopSeparator(),
                )

            TYPE_DEVICE ->
                (viewHolder as MediaDeviceViewHolder).onBindDevice(
                    mediaItem = currentMediaItem,
                    position = position,
                )

            TYPE_DEVICE_GROUP -> (viewHolder as MediaDeviceViewHolder).onBindDeviceGroup()
            else ->
                throw IllegalArgumentException(
                    "Invalid item type ${currentMediaItem.mediaItemType} for position: $position"
                )
        }
    }

    val controller: MediaSwitchingController
        get() = mController

    /** ViewHolder for binding device view. */
    inner class MediaDeviceViewHolder(view: View, context: Context?) :
        MediaDeviceViewHolderBase(view, context) {
        @VisibleForTesting val mMainContent: LinearLayout = view.requireViewById(R.id.main_content)

        @VisibleForTesting val mItemLayout: LinearLayout = view.requireViewById(R.id.item_layout)

        @VisibleForTesting val mTitleText: TextView = view.requireViewById(R.id.title)

        @VisibleForTesting val mSubTitleText: TextView = view.requireViewById(R.id.subtitle)

        @VisibleForTesting val mTitleIcon: ImageView = view.requireViewById(R.id.title_icon)

        @VisibleForTesting
        val mLoadingIndicator: ProgressBar = view.requireViewById(R.id.loading_indicator)

        @VisibleForTesting val mStatusIcon: ImageView = view.requireViewById(R.id.status_icon)

        @VisibleForTesting val mGroupButton: ImageButton = view.requireViewById(R.id.group_button)

        @VisibleForTesting val mDivider: View = view.requireViewById(R.id.divider)

        @VisibleForTesting
        val mOngoingSessionButton: ImageButton = view.requireViewById(R.id.ongoing_session_button)

        @VisibleForTesting var mSlider: Slider = view.requireViewById(R.id.volume_seekbar)
        private var mLatestUpdateVolume = NO_VOLUME_SET

        private val mInactivePadding =
            mContext.resources.getDimension(R.dimen.media_output_item_content_vertical_margin)

        private val mActivePadding =
            mContext.resources.getDimension(
                R.dimen.media_output_item_content_vertical_margin_active
            )

        private val mSubtitleAlpha =
            mContext.resources.getFloat(R.dimen.media_output_item_subtitle_alpha)

        private val mButtonRippleBackground =
            AppCompatResources.getDrawable(
                mContext,
                R.drawable.media_output_dialog_item_button_ripple,
            )

        private val mFixedVolumeContentBackground =
            AppCompatResources.getDrawable(
                mContext,
                R.drawable.media_output_dialog_item_fixed_volume_background,
            )

        fun onBindDevice(mediaItem: MediaItem, position: Int) {
            resetViewState()
            renderItem(mediaItem, position)
        }

        fun onBindDeviceGroup() {
            resetViewState()
            renderDeviceGroupItem()
        }

        private fun resetViewState() {
            mItemLayout.visibility = VISIBLE
            mGroupButton.visibility = GONE
            mOngoingSessionButton.visibility = GONE
            mStatusIcon.visibility = GONE
            mLoadingIndicator.visibility = GONE
            mDivider.visibility = GONE
            mSubTitleText.visibility = GONE
            mMainContent.setOnClickListener(null)
        }

        override fun renderDeviceItem(
            hideGroupItem: Boolean,
            device: MediaDevice,
            connectionState: ConnectionState,
            restrictVolumeAdjustment: Boolean,
            groupStatus: GroupStatus?,
            ongoingSessionStatus: OngoingSessionStatus?,
            clickListener: View.OnClickListener?,
            deviceDisabled: Boolean,
            subtitle: String?,
            deviceStatusIcon: Drawable?,
        ) {
            val fixedVolumeConnected = connectionState == CONNECTED && restrictVolumeAdjustment
            val colorTheme = ColorTheme(fixedVolumeConnected, deviceDisabled)

            updateTitle(device.name, connectionState, colorTheme)
            updateTitleIcon(device, connectionState, restrictVolumeAdjustment, colorTheme)
            updateSubtitle(subtitle, colorTheme)
            updateSeekBar(device, connectionState, restrictVolumeAdjustment, colorTheme)
            updateEndArea(device, connectionState, groupStatus, ongoingSessionStatus, colorTheme)
            updateLoadingIndicator(connectionState, colorTheme)
            updateDeviceStatusIcon(deviceStatusIcon, colorTheme)
            updateContentBackground(fixedVolumeConnected, colorTheme)
            updateContentClickListener(clickListener)
        }

        override fun renderDeviceGroupItem() {
            mTitleIcon.visibility = GONE
            val colorTheme = ColorTheme()
            updateTitle(
                title = mController.sessionName ?: "",
                connectionState = CONNECTED,
                colorTheme = colorTheme,
            )
            updateGroupSeekBar(colorTheme)
        }

        private fun updateTitle(
            title: CharSequence,
            connectionState: ConnectionState,
            colorTheme: ColorTheme,
        ) {
            mTitleText.text = title
            val fontFamilyName: String =
                if (connectionState == CONNECTED) GSF_TITLE_MEDIUM_EMPHASIZED else GSF_TITLE_SMALL
            mTitleText.typeface = Typeface.create(fontFamilyName, Typeface.NORMAL)
            mTitleText.setTextColor(colorTheme.titleColor)
            mTitleText.alpha = colorTheme.contentAlpha
        }

        private fun updateContentBackground(fixedVolumeConnected: Boolean, colorTheme: ColorTheme) {
            if (fixedVolumeConnected) {
                mMainContent.backgroundTintList =
                    ColorStateList.valueOf(colorTheme.containerRestrictedVolumeBackground)
                mMainContent.background = mFixedVolumeContentBackground
            } else {
                mMainContent.backgroundTintList = null
                mMainContent.background = mButtonRippleBackground
            }
        }

        private fun updateContentPadding(verticalPadding: Float) {
            mMainContent.setPadding(0, verticalPadding.toInt(), 0, verticalPadding.toInt())
        }

        private fun updateLayoutForSlider(showSlider: Boolean) {
            updateContentPadding(if (showSlider) mActivePadding else mInactivePadding)
            mSlider.visibility = if (showSlider) VISIBLE else GONE
            mSlider.alpha = if (showSlider) 1f else 0f
        }

        private fun updateSeekBar(
            device: MediaDevice,
            connectionState: ConnectionState,
            restrictVolumeAdjustment: Boolean,
            colorTheme: ColorTheme,
        ) {
            val showSlider = connectionState == CONNECTED && !restrictVolumeAdjustment
            if (showSlider) {
                updateLayoutForSlider(showSlider = true)
                initSeekbar(
                    volumeChangeCallback = { volume: Int ->
                        mController.adjustVolume(device, volume)
                    },
                    settleCallback = { mController.logInteractionAdjustVolume(device) },
                    deviceDrawable = mController.getDeviceIconDrawable(device),
                    isInputDevice = device is InputMediaDevice,
                    isVolumeControlAllowed = mController.isVolumeControlEnabled(device),
                    currentVolume = device.currentVolume,
                    maxVolume = device.maxVolume,
                    colorTheme = colorTheme,
                )
            } else {
                updateLayoutForSlider(showSlider = false)
            }
        }

        private fun updateGroupSeekBar(colorTheme: ColorTheme) {
            mSlider.visibility = VISIBLE
            updateContentPadding(mActivePadding)
            val groupDrawable =
                AppCompatResources.getDrawable(
                    mContext,
                    com.android.settingslib.R.drawable.ic_media_group_device,
                )
            initSeekbar(
                volumeChangeCallback = { volume: Int -> mController.adjustSessionVolume(volume) },
                deviceDrawable = groupDrawable,
                isVolumeControlAllowed = mController.isVolumeControlEnabledForSession,
                currentVolume = mController.sessionVolume,
                maxVolume = mController.sessionVolumeMax,
                colorTheme = colorTheme,
            )
        }

        private fun updateSubtitle(subtitle: String?, colorTheme: ColorTheme) {
            if (subtitle.isNullOrEmpty()) {
                mSubTitleText.visibility = GONE
            } else {
                mSubTitleText.text = subtitle
                mSubTitleText.setTextColor(colorTheme.subtitleColor)
                mSubTitleText.alpha = mSubtitleAlpha * colorTheme.contentAlpha
                mSubTitleText.visibility = VISIBLE
            }
        }

        private fun updateLoadingIndicator(
            connectionState: ConnectionState,
            colorTheme: ColorTheme,
        ) {
            if (connectionState == CONNECTING) {
                mLoadingIndicator.visibility = VISIBLE
                mLoadingIndicator.indeterminateDrawable.setTintList(
                    ColorStateList.valueOf(colorTheme.statusIconColor)
                )
            } else {
                mLoadingIndicator.visibility = GONE
            }
        }

        private fun initializeSeekbarVolume(currentVolume: Int) {
            tryResolveVolumeUserRequest(currentVolume)
            if (!isDragging && hasNoPendingVolumeRequests()) {
                mSlider.value = currentVolume.toFloat()
            }
        }

        private fun tryResolveVolumeUserRequest(currentVolume: Int) {
            if (currentVolume == mLatestUpdateVolume) {
                mLatestUpdateVolume = NO_VOLUME_SET
            }
        }

        private fun hasNoPendingVolumeRequests(): Boolean {
            return mLatestUpdateVolume == NO_VOLUME_SET
        }

        private fun setLatestVolumeRequest(volume: Int) {
            mLatestUpdateVolume = volume
        }

        private fun initSeekbar(
            volumeChangeCallback: (Int) -> Unit,
            settleCallback: () -> Unit = {},
            deviceDrawable: Drawable?,
            isInputDevice: Boolean = false,
            isVolumeControlAllowed: Boolean,
            currentVolume: Int,
            maxVolume: Int,
            colorTheme: ColorTheme,
        ) {
            if (maxVolume == 0) {
                Log.e(TAG, "Invalid maxVolume value")
                // Slider doesn't allow valueFrom == valueTo, return to prevent crash.
                return
            }

            mSlider.isEnabled = isVolumeControlAllowed
            mSlider.valueFrom = 0f
            mSlider.valueTo = maxVolume.toFloat()
            mSlider.stepSize = 1f
            mSlider.thumbTintList = ColorStateList.valueOf(colorTheme.sliderActiveColor)
            mSlider.trackActiveTintList = ColorStateList.valueOf(colorTheme.sliderActiveColor)
            mSlider.trackInactiveTintList = ColorStateList.valueOf(colorTheme.sliderInactiveColor)
            mSlider.trackIconActiveColor = ColorStateList.valueOf(colorTheme.sliderActiveIconColor)
            mSlider.trackIconInactiveColor =
                ColorStateList.valueOf(colorTheme.sliderInactiveIconColor)
            val muteDrawable = getMuteDrawable(isInputDevice)
            updateSliderIconsVisibility(
                deviceDrawable = deviceDrawable,
                muteDrawable = muteDrawable,
                isMuted = currentVolume == 0,
            )
            initializeSeekbarVolume(currentVolume)

            mSlider.clearOnChangeListeners() // Prevent adding multiple listeners
            mSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
                if (fromUser) {
                    val seekBarVolume = value.toInt()
                    updateSliderIconsVisibility(
                        deviceDrawable = deviceDrawable,
                        muteDrawable = muteDrawable,
                        isMuted = seekBarVolume == 0,
                    )
                    if (seekBarVolume != currentVolume) {
                        setLatestVolumeRequest(seekBarVolume)
                        volumeChangeCallback(seekBarVolume)
                    }
                }
            }

            mSlider.clearOnSliderTouchListeners() // Prevent adding multiple listeners
            mSlider.addOnSliderTouchListener(
                object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) {
                        setIsDragging(true)
                    }

                    override fun onStopTrackingTouch(slider: Slider) {
                        setIsDragging(false)
                        settleCallback()
                    }
                }
            )
        }

        private fun getMuteDrawable(isInputDevice: Boolean): Drawable? {
            return AppCompatResources.getDrawable(
                mContext,
                if (isInputDevice) R.drawable.ic_mic_off
                else R.drawable.media_output_icon_volume_off,
            )
        }

        private fun updateSliderIconsVisibility(
            deviceDrawable: Drawable?,
            muteDrawable: Drawable?,
            isMuted: Boolean,
        ) {
            mSlider.trackIconInactiveStart = if (isMuted) muteDrawable else null
            // A workaround for the slider glitch that sometimes shows the active icon in inactive
            // state.
            mSlider.trackIconActiveStart = if (isMuted) null else deviceDrawable
        }

        private fun updateTitleIcon(
            device: MediaDevice,
            connectionState: ConnectionState,
            restrictVolumeAdjustment: Boolean,
            colorTheme: ColorTheme,
        ) {
            if (connectionState == CONNECTED && !restrictVolumeAdjustment) {
                mTitleIcon.visibility = GONE
            } else {
                mTitleIcon.imageTintList = ColorStateList.valueOf(colorTheme.iconColor)
                val drawable = mController.getDeviceIconDrawable(device)
                mTitleIcon.setImageDrawable(drawable)
                mTitleIcon.visibility = VISIBLE
                mTitleIcon.alpha = colorTheme.contentAlpha
            }
        }

        private fun updateDeviceStatusIcon(deviceStatusIcon: Drawable?, colorTheme: ColorTheme) {
            if (deviceStatusIcon == null) {
                mStatusIcon.visibility = GONE
            } else {
                mStatusIcon.setImageDrawable(deviceStatusIcon)
                mStatusIcon.alpha = colorTheme.contentAlpha
                mStatusIcon.imageTintList = ColorStateList.valueOf(colorTheme.statusIconColor)
                mStatusIcon.visibility = VISIBLE
            }
        }

        private fun updateEndArea(
            device: MediaDevice,
            connectionState: ConnectionState,
            groupStatus: GroupStatus?,
            ongoingSessionStatus: OngoingSessionStatus?,
            colorTheme: ColorTheme,
        ) {
            var showDivider = false

            if (ongoingSessionStatus != null) {
                showDivider = true
                mOngoingSessionButton.visibility = VISIBLE
                updateOngoingSessionButton(device, ongoingSessionStatus.host, colorTheme)
            }

            if (groupStatus != null && shouldShowGroupCheckbox(groupStatus)) {
                showDivider = true
                mGroupButton.visibility = VISIBLE
                updateGroupButton(device, groupStatus, colorTheme)
            }

            mDivider.visibility =
                if (showDivider && connectionState == DISCONNECTED) VISIBLE else GONE
            mDivider.setBackgroundColor(mController.colorScheme.getOutline())
        }

        private fun shouldShowGroupCheckbox(groupStatus: GroupStatus): Boolean {
            val disabled = groupStatus.selected && !groupStatus.deselectable
            return !disabled
        }

        private fun updateOngoingSessionButton(
            device: MediaDevice,
            isHost: Boolean,
            colorTheme: ColorTheme,
        ) {
            val iconDrawableId =
                if (isHost) R.drawable.media_output_status_edit_session
                else R.drawable.ic_sound_bars_anim
            mOngoingSessionButton.setOnClickListener { v: View? ->
                mController.tryToLaunchInAppRoutingIntent(device.id, v)
            }
            val drawable = AppCompatResources.getDrawable(mContext, iconDrawableId)
            mOngoingSessionButton.setImageDrawable(drawable)
            mOngoingSessionButton.imageTintList = ColorStateList.valueOf(colorTheme.iconColor)
            if (drawable is AnimatedVectorDrawable) {
                drawable.start()
            }
        }

        private fun updateGroupButton(
            device: MediaDevice,
            groupStatus: GroupStatus,
            colorTheme: ColorTheme,
        ) {
            mGroupButton.contentDescription =
                mContext.getString(
                    if (groupStatus.selected) R.string.accessibility_remove_device_from_group
                    else R.string.accessibility_add_device_to_group
                )
            mGroupButton.setImageResource(
                if (groupStatus.selected) R.drawable.ic_check_circle_filled
                else R.drawable.ic_add_circle_rounded
            )
            mGroupButton.setOnClickListener {
                onGroupActionTriggered(!groupStatus.selected, device)
            }
            mGroupButton.imageTintList = ColorStateList.valueOf(colorTheme.iconColor)
        }

        private fun updateContentClickListener(listener: View.OnClickListener?) {
            mMainContent.setOnClickListener(listener)
            if (listener == null) {
                mMainContent.isClickable = false // clickable is not removed automatically.
            }
        }

        override fun disableSeekBar() {
            mSlider.isEnabled = false
        }
    }

    inner class MediaGroupDividerViewHolder(itemView: View, val mContext: Context) :
        RecyclerView.ViewHolder(itemView) {
        private val mTopSeparator: View = itemView.requireViewById(R.id.top_separator)
        private val mTitleText: TextView = itemView.requireViewById(R.id.title)
        @VisibleForTesting
        val mExpandButton: ViewGroup = itemView.requireViewById(R.id.expand_button)
        private val mExpandButtonIcon: ImageView = itemView.requireViewById(R.id.expand_button_icon)

        fun onBind(
            groupDividerTitle: String?,
            isExpandableDivider: Boolean,
            hasTopSeparator: Boolean,
        ) {
            mTitleText.text = groupDividerTitle
            mTitleText.setTextColor(mController.colorScheme.getPrimary())
            if (hasTopSeparator) {
                mTopSeparator.visibility = VISIBLE
                mTopSeparator.setBackgroundColor(mController.colorScheme.getOutlineVariant())
            } else {
                mTopSeparator.visibility = GONE
            }
            updateExpandButton(isExpandableDivider)
        }

        private fun updateExpandButton(isExpandableDivider: Boolean) {
            if (!isExpandableDivider) {
                mExpandButton.visibility = GONE
                return
            }
            val isCollapsed = mController.isGroupListCollapsed
            mExpandButtonIcon.setImageDrawable(
                AppCompatResources.getDrawable(
                    mContext,
                    if (isCollapsed) R.drawable.ic_expand_more_rounded
                    else R.drawable.ic_expand_less_rounded,
                )
            )
            mExpandButtonIcon.contentDescription =
                mContext.getString(
                    if (isCollapsed) R.string.accessibility_expand_group
                    else R.string.accessibility_collapse_group
                )
            mExpandButton.visibility = VISIBLE
            mExpandButton.setOnClickListener { toggleGroupList() }
            mExpandButtonIcon.backgroundTintList =
                ColorStateList.valueOf(mController.colorScheme.getOnSurface())
                    .withAlpha((255 * 0.1).toInt())
            mExpandButtonIcon.imageTintList =
                ColorStateList.valueOf(mController.colorScheme.getOnSurface())
        }

        private fun toggleGroupList() {
            mController.isGroupListCollapsed = !mController.isGroupListCollapsed
            updateItems()
        }
    }

    private inner class ColorTheme(
        isConnectedWithFixedVolume: Boolean = false,
        deviceDisabled: Boolean = false,
    ) {
        private val colorScheme: MediaOutputColorScheme = mController.colorScheme

        val titleColor =
            if (isConnectedWithFixedVolume) {
                colorScheme.getOnPrimary()
            } else {
                colorScheme.getOnSurface()
            }
        val subtitleColor =
            if (isConnectedWithFixedVolume) {
                colorScheme.getOnPrimary()
            } else {
                colorScheme.getOnSurfaceVariant()
            }
        val iconColor =
            if (isConnectedWithFixedVolume) {
                colorScheme.getOnPrimary()
            } else {
                colorScheme.getOnSurface()
            }
        val statusIconColor =
            if (isConnectedWithFixedVolume) {
                colorScheme.getOnPrimary()
            } else {
                colorScheme.getOnSurfaceVariant()
            }
        val sliderActiveColor = colorScheme.getPrimary()
        val sliderActiveIconColor = colorScheme.getOnPrimary()
        val sliderInactiveColor = colorScheme.getSecondaryContainer()
        val sliderInactiveIconColor = colorScheme.getOnSurface()
        val containerRestrictedVolumeBackground = colorScheme.getPrimary()
        val contentAlpha = if (deviceDisabled) DEVICE_DISABLED_ALPHA else DEVICE_ACTIVE_ALPHA
    }

    companion object {
        private const val TAG = "MediaOutputAdapter"
        private const val DEVICE_DISABLED_ALPHA = 0.5f
        private const val DEVICE_ACTIVE_ALPHA = 1f
        private const val NO_VOLUME_SET = -1
    }
}
