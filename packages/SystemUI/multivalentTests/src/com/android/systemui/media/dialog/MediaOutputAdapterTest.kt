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
import android.graphics.drawable.Icon
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper.RunWithLooper
import android.text.BidiFormatter
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.drawable.IconCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.RecyclerView
import com.android.media.flags.Flags
import com.android.settingslib.media.InputMediaDevice
import com.android.settingslib.media.LocalMediaManager.MediaDeviceState.STATE_CONNECTED
import com.android.settingslib.media.LocalMediaManager.MediaDeviceState.STATE_CONNECTING
import com.android.settingslib.media.LocalMediaManager.MediaDeviceState.STATE_CONNECTING_FAILED
import com.android.settingslib.media.LocalMediaManager.MediaDeviceState.STATE_DISCONNECTED
import com.android.settingslib.media.LocalMediaManager.MediaDeviceState.STATE_GROUPING
import com.android.settingslib.media.MediaDevice
import com.android.settingslib.media.MediaDevice.SelectionBehavior.SELECTION_BEHAVIOR_GO_TO_APP
import com.android.settingslib.media.MediaDevice.SelectionBehavior.SELECTION_BEHAVIOR_NONE
import com.android.settingslib.media.MediaDevice.SelectionBehavior.SELECTION_BEHAVIOR_TRANSFER
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.dialog.MediaItem.MediaItemType
import com.android.systemui.media.dialog.MediaItem.createDeviceMediaItem
import com.android.systemui.media.dialog.MediaOutputAdapter.MediaDeviceViewHolder
import com.android.systemui.media.dialog.MediaOutputAdapter.MediaGroupDividerViewHolder
import com.android.systemui.res.R
import com.google.android.material.slider.Slider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@EnableFlags(Flags.FLAG_ENABLE_OUTPUT_SWITCHER_REDESIGN)
@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
class MediaOutputAdapterTest : SysuiTestCase() {
    private val mMediaSwitchingController = mock<MediaSwitchingController>()
    private val mMediaDevice1: MediaDevice = mock<MediaDevice>()
    private val mMediaDevice2: MediaDevice = mock<MediaDevice>()
    private val mIcon: Icon = mock<Icon>()
    private val mIconCompat: IconCompat = mock<IconCompat>()
    private lateinit var mMediaOutputAdapter: MediaOutputAdapter
    private val mMediaItems: MutableList<MediaItem> = ArrayList()

    @Before
    fun setUp() {
        mMediaSwitchingController.stub {
            on { getMediaItemList(false) } doReturn mMediaItems
            on { hasAdjustVolumeUserRestriction() } doReturn false
            on { isAnyDeviceTransferring } doReturn false
            on { currentConnectedMediaDevice } doReturn mMediaDevice1
            on { connectedSpeakersExpandableGroupDivider }
                .doReturn(
                    MediaItem.createExpandableGroupDividerMediaItem(
                        mContext.getString(R.string.media_output_group_title_connected_speakers)
                    )
                )
            on { sessionVolumeMax } doReturn TEST_MAX_VOLUME
            on { sessionVolume } doReturn TEST_CURRENT_VOLUME
            on { sessionName } doReturn TEST_SESSION_NAME
            on { colorSchemeLegacy } doReturn mock<MediaOutputColorSchemeLegacy>()
            on { colorScheme } doReturn mock<MediaOutputColorScheme>()
        }

        mIconCompat.stub { on { toIcon(mContext) } doReturn mIcon }

        mMediaDevice1
            .stub {
                on { id } doReturn TEST_DEVICE_ID_1
                on { name } doReturn TEST_DEVICE_NAME_1
            }
            .also {
                whenever(mMediaSwitchingController.getDeviceIconCompat(it)) doReturn mIconCompat
            }

        mMediaDevice2
            .stub {
                on { id } doReturn TEST_DEVICE_ID_2
                on { name } doReturn TEST_DEVICE_NAME_2
            }
            .also {
                whenever(mMediaSwitchingController.getDeviceIconCompat(it)) doReturn mIconCompat
            }

        mMediaOutputAdapter = MediaOutputAdapter(mMediaSwitchingController)
    }

    @Test
    fun getItemCount_returnsMediaItemSize() {
        updateAdapterWithDevices(listOf(mMediaDevice1, mMediaDevice2))

        assertThat(mMediaOutputAdapter.itemCount).isEqualTo(mMediaItems.size)
    }

    @Test
    fun getItemId_forDevice_returnsIdHashCode() {
        updateAdapterWithDevices(listOf(mMediaDevice1, mMediaDevice2))

        assertThat(mMediaOutputAdapter.getItemId(0))
            .isEqualTo(mMediaItems[0].mediaDevice.get().id.hashCode())
    }

    @Test
    fun getItemId_forGroupSeparator_returnsTitleHashCode() {
        mMediaItems.add(MediaItem.createGroupDividerMediaItem("Suggested Devices"))
        mMediaOutputAdapter.updateItems()

        assertThat(mMediaOutputAdapter.getItemId(0)).isEqualTo("Suggested Devices".hashCode())
    }

    @Test
    fun getItemId_forDeviceGroup_returnsItemType() {
        initializeGroupSessionCollapsed()

        assertThat(mMediaOutputAdapter.getItemId(1))
            .isEqualTo(MediaItemType.TYPE_DEVICE_GROUP.toLong())
    }

    @Test
    fun getItemId_invalidPosition_returnsNoId() {
        updateAdapterWithDevices(listOf(mMediaDevice1, mMediaDevice2))
        val invalidPosition = mMediaItems.size + 1

        assertThat(mMediaOutputAdapter.getItemId(invalidPosition)).isEqualTo(RecyclerView.NO_ID)
    }

    @Test
    fun onBindViewHolder_bindDisconnectedDevice_verifyView() {
        mMediaDevice2.stub { on { state } doReturn STATE_DISCONNECTED }
        updateAdapterWithDevices(listOf(mMediaDevice2))

        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mTitleIcon.visibility).isEqualTo(VISIBLE)
            assertThat(mTitleText.visibility).isEqualTo(VISIBLE)
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_2)
            assertThat(mSlider.visibility).isEqualTo(GONE)
            assertThat(mMainContent.stateDescription).isNull()
        }
    }

    @Test
    fun onBindViewHolder_bindConnectedDevice_verifyView() {
        mMediaDevice1.stub { on { state } doReturn STATE_CONNECTED }
        updateAdapterWithDevices(listOf(mMediaDevice1))

        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mTitleIcon.visibility).isEqualTo(GONE)
            assertThat(mTitleText.visibility).isEqualTo(VISIBLE)
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_1)
            assertThat(mSlider.visibility).isEqualTo(VISIBLE)
            assertThat(mMainContent.stateDescription.toString())
                .isEqualTo(mContext.getString(R.string.media_output_item_connected_state))
        }
    }

    @Test
    fun onBindViewHolder_isMutingExpectedDevice_verifyView() {
        mMediaDevice1.stub {
            on { isMutingExpectedDevice } doReturn true
            on { state } doReturn STATE_DISCONNECTED
        }
        mMediaSwitchingController.stub { on { isCurrentConnectedDeviceRemote } doReturn false }
        updateAdapterWithDevices(listOf(mMediaDevice1))

        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_1)
            assertThat(mLoadingIndicator.visibility).isEqualTo(GONE)
            assertThat(mSlider.visibility).isEqualTo(GONE)
            assertThat(mGroupButton.visibility).isEqualTo(GONE)
            assertThat(mTitleText.visibility).isEqualTo(VISIBLE)
        }
    }

    @Test
    fun onBindViewHolder_bindConnectedDeviceWithMutingExpectedDeviceExist_verifyView() {
        mMediaDevice1.stub {
            on { isMutingExpectedDevice } doReturn true
            on { state } doReturn STATE_CONNECTED
        }
        mMediaSwitchingController.stub {
            on { hasMutingExpectedDevice() } doReturn true
            on { isCurrentConnectedDeviceRemote } doReturn false
        }
        updateAdapterWithDevices(listOf(mMediaDevice1))

        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mLoadingIndicator.visibility).isEqualTo(GONE)
            assertThat(mSlider.visibility).isEqualTo(GONE)
            assertThat(mGroupButton.visibility).isEqualTo(GONE)
            assertThat(mTitleText.visibility).isEqualTo(VISIBLE)
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_1)
        }
    }

    @Test
    fun onBindViewHolder_initSeekbar_setsVolume() {
        mMediaDevice1.stub {
            on { state } doReturn STATE_CONNECTED
            on { maxVolume } doReturn TEST_MAX_VOLUME
            on { currentVolume } doReturn TEST_CURRENT_VOLUME
        }
        updateAdapterWithDevices(listOf(mMediaDevice1))

        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mSlider.visibility).isEqualTo(VISIBLE)
            assertThat(mSlider.value).isEqualTo(TEST_CURRENT_VOLUME)
            assertThat(mSlider.valueFrom).isEqualTo(0)
            assertThat(mSlider.valueTo).isEqualTo(TEST_MAX_VOLUME)
            assertThat(mSlider.stateDescription).isEqualTo("50%")
        }
    }

    @Test
    fun onBindViewHolder_initSeekbarWithIncorrectVolumeValues_noop() {
        mMediaDevice1.stub {
            on { state } doReturn STATE_CONNECTED
            on { maxVolume } doReturn TEST_MAX_VOLUME
        }

        // volume < 0
        mMediaDevice1.stub { on { currentVolume } doReturn -2 }
        updateAdapterWithDevices(listOf(mMediaDevice1))
        createAndBindDeviceViewHolder(position = 0).apply { assertThat(mSlider.value).isEqualTo(0) }

        // volume > max volume
        mMediaDevice1.stub { on { currentVolume } doReturn TEST_MAX_VOLUME + 2 }
        updateAdapterWithDevices(listOf(mMediaDevice1))
        createAndBindDeviceViewHolder(position = 0).apply { assertThat(mSlider.value).isEqualTo(0) }

        // valid volume
        mMediaDevice1.stub { on { currentVolume } doReturn TEST_CURRENT_VOLUME }
        updateAdapterWithDevices(listOf(mMediaDevice1))
        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mSlider.value).isEqualTo(TEST_CURRENT_VOLUME)
        }
    }

    @Test
    fun onBindViewHolder_dragSeekbar_adjustsVolume() {
        mMediaDevice1.stub {
            on { maxVolume } doReturn TEST_MAX_VOLUME
            on { currentVolume } doReturn TEST_CURRENT_VOLUME
        }
        updateAdapterWithDevices(listOf(mMediaDevice1))

        val viewHolder =
            mMediaOutputAdapter.onCreateViewHolder(
                LinearLayout(mContext),
                MediaItemType.TYPE_DEVICE,
            ) as MediaDeviceViewHolder

        var sliderChangeListener: Slider.OnChangeListener? = null
        viewHolder.mSlider =
            object : Slider(contextWithTheme(mContext)) {
                override fun addOnChangeListener(listener: OnChangeListener) {
                    sliderChangeListener = listener
                }
            }
        mMediaOutputAdapter.onBindViewHolder(viewHolder, 0)
        sliderChangeListener?.onValueChange(viewHolder.mSlider, 5f, true)

        verify(mMediaSwitchingController).adjustVolume(mMediaDevice1, 5)
    }

    @Test
    fun onBindViewHolder_dragSeekbar_logsInteraction() {
        mMediaDevice1
            .stub {
                on { maxVolume } doReturn TEST_MAX_VOLUME
                on { currentVolume } doReturn TEST_CURRENT_VOLUME
            }
            .also { mMediaItems.add(createDeviceMediaItem(it)) }
        updateAdapterWithDevices(listOf(mMediaDevice1))

        val viewHolder =
            mMediaOutputAdapter.onCreateViewHolder(
                LinearLayout(mContext),
                MediaItemType.TYPE_DEVICE,
            ) as MediaDeviceViewHolder

        var sliderTouchListener: Slider.OnSliderTouchListener? = null
        viewHolder.mSlider =
            object : Slider(contextWithTheme(mContext)) {
                override fun addOnSliderTouchListener(listener: OnSliderTouchListener) {
                    sliderTouchListener = listener
                }
            }
        mMediaOutputAdapter.onBindViewHolder(viewHolder, 0)
        sliderTouchListener?.onStopTrackingTouch(viewHolder.mSlider)

        verify(mMediaSwitchingController).logInteractionAdjustVolume(mMediaDevice1)
    }

    @Test
    fun onBindViewHolder_bindSelectableDevice_verifyView() {
        mMediaDevice2.stub { on { isSelectable() } doReturn true }
        updateAdapterWithDevices(listOf(mMediaDevice1, mMediaDevice2))

        createAndBindDeviceViewHolder(position = 1).apply {
            assertThat(mLoadingIndicator.visibility).isEqualTo(GONE)
            assertThat(mDivider.visibility).isEqualTo(VISIBLE)
            assertThat(mGroupButton.visibility).isEqualTo(VISIBLE)
            assertThat(mGroupButton.contentDescription)
                .isEqualTo(
                    mContext.getString(
                        R.string.accessibility_add_device_to_group_with_name,
                        BidiFormatter.getInstance().unicodeWrap(TEST_DEVICE_NAME_2),
                    )
                )
            assertThat(mTitleText.visibility).isEqualTo(VISIBLE)
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_2)

            mGroupButton.performClick()
        }
        verify(mMediaSwitchingController).addDeviceToPlayMedia(mMediaDevice2)
    }

    @Test
    fun onBindViewHolder_inputDeviceWithOtherGroupDevices_hasNoGroupCheckbox() {
        mMediaSwitchingController.stub {
            on { isGroupListCollapsed } doReturn false
            on { isVolumeControlEnabledForSession } doReturn true
            on { hasGroupPlayback() } doReturn true
        }
        mMediaDevice1.stub {
            on { isSelected() } doReturn true
            on { isDeselectable() } doReturn true
        }
        mMediaDevice2.stub {
            on { isSelectable() } doReturn true
            on { isDeselectable() } doReturn true
        }
        val inputDevice: InputMediaDevice =
            mock<InputMediaDevice>() {
                    on { id } doReturn INPUT_TEST_DEVICE_ID
                    on { name } doReturn INPUT_TEST_DEVICE_NAME
                    on { isInputDevice() } doReturn true
                    on { isSelected() } doReturn true
                    on { isDeselectable() } doReturn false
                }
                .also {
                    whenever(mMediaSwitchingController.getDeviceIconCompat(it)) doReturn mIconCompat
                }

        updateAdapterWithDevices(listOf(mMediaDevice1, mMediaDevice2, inputDevice))

        // Selected output device has a group checkbox.
        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mGroupButton.visibility).isEqualTo(VISIBLE)
        }

        // Selected input device doesn't have a group checkbox.
        createAndBindDeviceViewHolder(position = 2).apply {
            assertThat(mGroupButton.visibility).isEqualTo(GONE)
        }
    }

    @Test
    fun onBindViewHolder_bindDeselectableDevice_verifyView() {
        mMediaSwitchingController.stub {
            on { isGroupListCollapsed } doReturn false
            on { isVolumeControlEnabledForSession } doReturn true
            on { hasGroupPlayback() } doReturn true
        }
        mMediaDevice1.stub {
            on { isSelected() } doReturn true
            on { isDeselectable() } doReturn true
        }
        mMediaDevice2.stub {
            on { isSelected() } doReturn true
            on { isDeselectable() } doReturn true
        }
        updateAdapterWithDevices(listOf(mMediaDevice1, mMediaDevice2))

        // positions: 0 - device1, 1 - device2.
        createAndBindDeviceViewHolder(position = 1).apply {
            assertThat(mGroupButton.visibility).isEqualTo(VISIBLE)
            assertThat(mGroupButton.contentDescription)
                .isEqualTo(
                    mContext.getString(
                        R.string.accessibility_remove_device_from_group_with_name,
                        BidiFormatter.getInstance().unicodeWrap(TEST_DEVICE_NAME_2),
                    )
                )
            mGroupButton.performClick()
        }

        verify(mMediaSwitchingController).removeDeviceFromPlayMedia(mMediaDevice2)
    }

    @Test
    fun onBindViewHolder_bindNonDeselectableDevice_verifyView() {
        mMediaDevice1.stub { on { isSelected() } doReturn true }
        updateAdapterWithDevices(listOf(mMediaDevice1, mMediaDevice2))

        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_1)
            assertThat(mGroupButton.visibility).isEqualTo(GONE)
        }
    }

    @Test
    fun onBindViewHolder_bindFailedStateDevice_verifyView() {
        mMediaDevice2.stub { on { state } doReturn STATE_CONNECTING_FAILED }
        updateAdapterWithDevices(listOf(mMediaDevice2))

        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mStatusIcon.visibility).isEqualTo(VISIBLE)
            assertThat(mSubTitleText.visibility).isEqualTo(VISIBLE)
            assertThat(mSubTitleText.text.toString())
                .isEqualTo(mContext.getText(R.string.media_output_dialog_connect_failed).toString())
        }
    }

    @Test
    fun onBindViewHolder_deviceHasSubtext_displaySubtitle() {
        mMediaDevice2.stub {
            on { state } doReturn STATE_DISCONNECTED
            on { hasSubtext() } doReturn true
            on { subtextString } doReturn TEST_CUSTOM_SUBTEXT
        }
        updateAdapterWithDevices(listOf(mMediaDevice2))

        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_2)
            assertThat(mSubTitleText.visibility).isEqualTo(VISIBLE)
            assertThat(mSubTitleText.text.toString()).isEqualTo(TEST_CUSTOM_SUBTEXT)
        }
    }

    @Test
    fun onBindViewHolder_deviceWithOngoingSession_displaysGoToAppButton() {
        mMediaDevice2.stub {
            on { state } doReturn STATE_DISCONNECTED
            on { hasOngoingSession() } doReturn true
        }
        updateAdapterWithDevices(listOf(mMediaDevice2))

        val viewHolder =
            createAndBindDeviceViewHolder(position = 0).apply {
                assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_2)
                assertThat(mOngoingSessionButton.visibility).isEqualTo(VISIBLE)
                assertThat(mOngoingSessionButton.contentDescription)
                    .isEqualTo(mContext.getString(R.string.accessibility_open_application))
                mOngoingSessionButton.performClick()
            }

        verify(mMediaSwitchingController)
            .tryToLaunchInAppRoutingIntent(TEST_DEVICE_ID_2, viewHolder.mOngoingSessionButton)
    }

    @Test
    fun onItemClick_selectionBehaviorTransfer_connectsDevice() {
        mMediaDevice2.stub {
            on { state } doReturn STATE_DISCONNECTED
            on { selectionBehavior } doReturn SELECTION_BEHAVIOR_TRANSFER
        }
        updateAdapterWithDevices(listOf(mMediaDevice2))

        createAndBindDeviceViewHolder(position = 0).apply { mMainContent.performClick() }

        verify(mMediaSwitchingController).connectDevice(mMediaDevice2)
    }

    @Test
    fun onItemClick_selectionBehaviorTransferAndSessionHost_showsEndSessionDialog() {
        mMediaSwitchingController.stub {
            on { isCurrentOutputDeviceHasSessionOngoing() } doReturn true
        }
        mMediaDevice2.stub {
            on { state } doReturn STATE_DISCONNECTED
            on { selectionBehavior } doReturn SELECTION_BEHAVIOR_TRANSFER
        }
        updateAdapterWithDevices(listOf(mMediaDevice2))

        val viewHolder =
            mMediaOutputAdapter.onCreateViewHolder(
                LinearLayout(mContext),
                MediaItemType.TYPE_DEVICE,
            ) as MediaDeviceViewHolder
        val spyMediaDeviceViewHolder = spy(viewHolder)
        doNothing().whenever(spyMediaDeviceViewHolder).showCustomEndSessionDialog(mMediaDevice2)

        mMediaOutputAdapter.onBindViewHolder(spyMediaDeviceViewHolder, 0)
        spyMediaDeviceViewHolder.mMainContent.performClick()

        verify(mMediaSwitchingController, never()).connectDevice(ArgumentMatchers.any())
        verify(spyMediaDeviceViewHolder).showCustomEndSessionDialog(mMediaDevice2)
    }

    @Test
    fun onItemClick_selectionBehaviorGoToApp_sendsLaunchIntent() {
        mMediaDevice2.stub {
            on { state } doReturn STATE_DISCONNECTED
            on { selectionBehavior } doReturn SELECTION_BEHAVIOR_GO_TO_APP
        }
        updateAdapterWithDevices(listOf(mMediaDevice2))

        val viewHolder =
            createAndBindDeviceViewHolder(position = 0).apply { mMainContent.performClick() }
        verify(mMediaSwitchingController)
            .tryToLaunchInAppRoutingIntent(TEST_DEVICE_ID_2, viewHolder.mMainContent)
    }

    @Test
    fun onItemClick_selectionBehaviorNone_doesNothing() {
        mMediaDevice2.stub {
            on { state } doReturn STATE_DISCONNECTED
            on { selectionBehavior } doReturn SELECTION_BEHAVIOR_NONE
        }
        updateAdapterWithDevices(listOf(mMediaDevice2))
        createAndBindDeviceViewHolder(position = 0).apply { mMainContent.performClick() }

        verify(mMediaSwitchingController, never()).tryToLaunchInAppRoutingIntent(any(), any())
        verify(mMediaSwitchingController, never()).connectDevice(any())
    }

    @Test
    fun clickFullItemOfSelectableDevice_hasListingPreference_verifyConnectDevice() {
        mMediaDevice2.stub {
            on { hasRouteListingPreferenceItem() } doReturn true
            on { isSelectable() } doReturn true
        }
        updateAdapterWithDevices(listOf(mMediaDevice2))

        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_2)
            mMainContent.performClick()
        }
        verify(mMediaSwitchingController).connectDevice(mMediaDevice2)
    }

    @Test
    fun clickFullItemOfSelectableDevice_isTransferable_verifyConnectDevice() {
        mMediaDevice2.stub {
            on { isSelectable() } doReturn true
            on { isTransferable() } doReturn true
        }
        updateAdapterWithDevices(listOf(mMediaDevice2))

        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_2)
            mMainContent.performClick()
        }
        verify(mMediaSwitchingController).connectDevice(mMediaDevice2)
    }

    @Test
    fun clickFullItemOfSelectableDevice_notTransferable_verifyNotConnectDevice() {
        mMediaDevice2.stub {
            on { hasRouteListingPreferenceItem() } doReturn false
            on { isSelectable() } doReturn true
        }
        updateAdapterWithDevices(listOf(mMediaDevice2))

        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_2)
            mMainContent.performClick()
        }
        verify(mMediaSwitchingController, never()).connectDevice(any())
    }

    @Test
    fun onBindViewHolder_inTransferring_bindTransferringDevice_verifyView() {
        mMediaSwitchingController.stub { on { isAnyDeviceTransferring() } doReturn true }
        mMediaDevice2.stub { on { state } doReturn STATE_CONNECTING }
        updateAdapterWithDevices(listOf(mMediaDevice1, mMediaDevice2))

        // Connected device, looks like disconnected during transfer
        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mTitleText.visibility).isEqualTo(VISIBLE)
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_1)
            assertThat(mSlider.visibility).isEqualTo(GONE)
            assertThat(mLoadingIndicator.visibility).isEqualTo(GONE)
        }

        // Connecting device
        createAndBindDeviceViewHolder(position = 1).apply {
            assertThat(mTitleText.visibility).isEqualTo(VISIBLE)
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_2)
            assertThat(mSlider.visibility).isEqualTo(GONE)
            assertThat(mLoadingIndicator.visibility).isEqualTo(VISIBLE)
        }
    }

    @Test
    fun onBindViewHolder_bindGroupingDevice_verifyView() {
        mMediaDevice1.stub { on { state } doReturn STATE_GROUPING }
        updateAdapterWithDevices(listOf(mMediaDevice1))

        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mTitleText.visibility).isEqualTo(VISIBLE)
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_1)
            assertThat(mSlider.visibility).isEqualTo(GONE)
            assertThat(mSubTitleText.visibility).isEqualTo(GONE)
            assertThat(mGroupButton.visibility).isEqualTo(GONE)
            assertThat(mLoadingIndicator.visibility).isEqualTo(VISIBLE)
        }
    }

    @Test
    fun onItemClick_clicksWithMutingExpectedDeviceExist_cancelsMuteAwaitConnection() {
        mMediaSwitchingController.stub {
            on { hasMutingExpectedDevice() } doReturn true
            on { isCurrentConnectedDeviceRemote() } doReturn false
        }
        mMediaDevice1.stub { on { isMutingExpectedDevice } doReturn false }
        updateAdapterWithDevices(listOf(mMediaDevice1))

        createAndBindDeviceViewHolder(position = 0).apply { mMainContent.performClick() }
        verify(mMediaSwitchingController).cancelMuteAwaitConnection()
    }

    @Test
    fun onGroupActionTriggered_clicksSelectableDevice_triggerGrouping() {
        mMediaDevice2.stub { on { isSelectable() } doReturn true }
        updateAdapterWithDevices(listOf(mMediaDevice2))

        createAndBindDeviceViewHolder(position = 0).apply { mGroupButton.performClick() }
        verify(mMediaSwitchingController).addDeviceToPlayMedia(mMediaDevice2)
    }

    @Test
    fun onGroupActionTriggered_clickSelectedRemoteDevice_triggerUngrouping() {
        mMediaDevice1.stub {
            on { isSelected() } doReturn true
            on { isDeselectable() } doReturn true
        }
        mMediaDevice2.stub { on { isSelectable() } doReturn true }
        mMediaSwitchingController.stub { on { isCurrentConnectedDeviceRemote } doReturn true }
        updateAdapterWithDevices(listOf(mMediaDevice1, mMediaDevice2))

        createAndBindDeviceViewHolder(position = 0).apply { mGroupButton.performClick() }
        verify(mMediaSwitchingController).removeDeviceFromPlayMedia(mMediaDevice1)
    }

    @Test
    fun onBindViewHolder_hasVolumeAdjustmentRestriction_verifySeekbarDisabled() {
        mMediaSwitchingController.stub {
            on { isCurrentConnectedDeviceRemote } doReturn true
            on { hasAdjustVolumeUserRestriction() } doReturn true
        }
        mMediaDevice1.stub { on { state } doReturn STATE_CONNECTED }
        updateAdapterWithDevices(listOf(mMediaDevice1))

        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mSlider.visibility).isEqualTo(GONE)
        }
    }

    @Test
    fun onBindViewHolder_volumeControlChangeToEnabled_enableSeekbarAgain() {
        mMediaSwitchingController.stub {
            on { isVolumeControlEnabled(mMediaDevice1) } doReturn false
        }
        mMediaDevice1.stub {
            on { state } doReturn STATE_CONNECTED
            on { currentVolume } doReturn TEST_CURRENT_VOLUME
            on { maxVolume } doReturn TEST_MAX_VOLUME
        }
        updateAdapterWithDevices(listOf(mMediaDevice1))

        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mSlider.visibility).isEqualTo(VISIBLE)
            assertThat(mSlider.isEnabled).isFalse()
        }

        mMediaSwitchingController.stub {
            on { isVolumeControlEnabled(mMediaDevice1) } doReturn true
        }
        createAndBindDeviceViewHolder(position = 0).apply {
            assertThat(mSlider.visibility).isEqualTo(VISIBLE)
            assertThat(mSlider.isEnabled).isTrue()
        }
    }

    @Test
    fun updateItems_controllerItemsUpdated_notUpdatesInAdapterUntilUpdateItems() {
        mMediaOutputAdapter.updateItems()
        val updatedList: MutableList<MediaItem> = ArrayList()
        updatedList.add(MediaItem.createDeviceGroupMediaItem())
        whenever(mMediaSwitchingController.getMediaItemList(false)).doReturn(updatedList)
        assertThat(mMediaOutputAdapter.itemCount).isEqualTo(mMediaItems.size)

        mMediaOutputAdapter.updateItems()
        assertThat(mMediaOutputAdapter.itemCount).isEqualTo(updatedList.size)
    }

    @Test
    fun multipleSelectedDevices_listCollapsed_verifyItemTypes() {
        mMediaSwitchingController.stub {
            on { isGroupListCollapsed } doReturn true
            on { isVolumeControlEnabledForSession } doReturn true
        }

        mMediaItems.add(MediaItem.createGroupDividerMediaItem("Connected Speakers"))
        mMediaItems.add(MediaItem.createDeviceGroupMediaItem())

        mMediaOutputAdapter = MediaOutputAdapter(mMediaSwitchingController)
        mMediaOutputAdapter.updateItems()

        with(mMediaOutputAdapter) {
            assertThat(itemCount).isEqualTo(2)
            assertThat(getItemViewType(0)).isEqualTo(MediaItemType.TYPE_GROUP_DIVIDER)
            assertThat(getItemViewType(1)).isEqualTo(MediaItemType.TYPE_DEVICE_GROUP)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_OUTPUT_SWITCHER_PERSONAL_AUDIO_SHARING)
    fun multipleSelectedDevices_listCollapsed_verifySessionControl() {
        mMediaSwitchingController.stub { on { isVolumeControlEnabledForSession } doReturn true }
        initializeGroupSessionCollapsed()

        createAndBindDeviceViewHolder(position = 1).apply {
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_SESSION_NAME)
            assertThat(mSlider.visibility).isEqualTo(VISIBLE)
            assertThat(mTitleText.visibility).isEqualTo(VISIBLE)
            assertThat(mSlider.value).isEqualTo(TEST_CURRENT_VOLUME)
            assertThat(mSlider.isEnabled).isTrue()
        }

        val viewHolder =
            mMediaOutputAdapter.onCreateViewHolder(
                LinearLayout(mContext),
                MediaItemType.TYPE_DEVICE_GROUP,
            ) as MediaDeviceViewHolder

        var sliderChangeListener: Slider.OnChangeListener? = null
        viewHolder.mSlider =
            object : Slider(contextWithTheme(mContext)) {
                override fun addOnChangeListener(listener: OnChangeListener) {
                    sliderChangeListener = listener
                }
            }
        mMediaOutputAdapter.onBindViewHolder(viewHolder, 1)
        sliderChangeListener?.onValueChange(viewHolder.mSlider, 7f, true)

        verify(mMediaSwitchingController).adjustSessionVolume(7)
    }

    @Test
    fun multipleSelectedDevices_listExpanded_verifyIndividualDevices() {
        mMediaSwitchingController.stub { on { isVolumeControlEnabledForSession } doReturn true }
        initializeGroupSessionExpanded()

        createAndBindDeviceViewHolder(position = 1).apply {
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_1)
            assertThat(mSlider.visibility).isEqualTo(VISIBLE)
            assertThat(mTitleText.visibility).isEqualTo(VISIBLE)
            assertThat(mGroupButton.visibility).isEqualTo(VISIBLE)
        }

        createAndBindDeviceViewHolder(position = 2).apply {
            assertThat(mTitleText.text.toString()).isEqualTo(TEST_DEVICE_NAME_2)
            assertThat(mSlider.visibility).isEqualTo(VISIBLE)
            assertThat(mTitleText.visibility).isEqualTo(VISIBLE)
            assertThat(mGroupButton.visibility).isEqualTo(VISIBLE)
        }
    }

    @Test
    fun multipleSelectedDevices_expandIconClicked_setGroupListCollapsed() {
        mMediaSwitchingController.stub {
            on { isGroupListCollapsed } doReturn true
            on { isVolumeControlEnabledForSession } doReturn true
        }
        initializeGroupSessionCollapsed()

        val groupDividerViewHolder =
            mMediaOutputAdapter.onCreateViewHolder(
                LinearLayout(mContext),
                MediaItemType.TYPE_GROUP_DIVIDER,
            ) as MediaGroupDividerViewHolder
        mMediaOutputAdapter.onBindViewHolder(groupDividerViewHolder, 0)

        groupDividerViewHolder.mExpandButton.performClick()

        verify(mMediaSwitchingController).setGroupListCollapsed(false)
    }

    private fun contextWithTheme(context: Context) =
        ContextThemeWrapper(
            context,
            com.google.android.material.R.style.Theme_Material3_DynamicColors_DayNight,
        )

    private fun updateAdapterWithDevices(deviceList: List<MediaDevice>) {
        for (device in deviceList) {
            mMediaItems.add(createDeviceMediaItem(device))
        }
        mMediaOutputAdapter.updateItems()
    }

    private fun createAndBindDeviceViewHolder(position: Int): MediaDeviceViewHolder {
        val viewHolder =
            mMediaOutputAdapter.onCreateViewHolder(
                LinearLayout(mContext),
                mMediaOutputAdapter.getItemViewType(position),
            )
        if (viewHolder is MediaDeviceViewHolder) {
            mMediaOutputAdapter.onBindViewHolder(viewHolder, position)
            return viewHolder
        } else {
            throw RuntimeException("ViewHolder for position $position is not MediaDeviceViewHolder")
        }
    }

    private fun initializeGroupSessionCollapsed() {
        mMediaSwitchingController.stub {
            on { isGroupListCollapsed } doReturn true
            on { hasGroupPlayback() } doReturn true
        }

        mMediaItems.add(
            MediaItem.createExpandableGroupDividerMediaItem(
                mContext.getString(R.string.media_output_group_title_connected_speakers)
            )
        )
        mMediaItems.add(MediaItem.createDeviceGroupMediaItem())

        mMediaOutputAdapter = MediaOutputAdapter(mMediaSwitchingController)
        mMediaOutputAdapter.updateItems()
    }

    private fun initializeGroupSessionExpanded() {
        mMediaSwitchingController.stub {
            on { isGroupListCollapsed } doReturn false
            on { hasGroupPlayback() } doReturn true
        }

        mMediaItems.add(
            MediaItem.createExpandableGroupDividerMediaItem(
                mContext.getString(R.string.media_output_group_title_connected_speakers)
            )
        )
        mMediaDevice1.stub {
            on { isSelected() } doReturn true
            on { isSelectable() } doReturn true
            on { isDeselectable() } doReturn true
        }

        mMediaDevice2.stub {
            on { isSelected() } doReturn true
            on { isSelectable() } doReturn true
            on { isDeselectable() } doReturn true
        }

        mMediaOutputAdapter = MediaOutputAdapter(mMediaSwitchingController)
        updateAdapterWithDevices(listOf(mMediaDevice1, mMediaDevice2))
    }

    companion object {
        private const val TEST_DEVICE_NAME_1 = "test_device_name_1"
        private const val TEST_DEVICE_NAME_2 = "test_device_name_2"
        private const val INPUT_TEST_DEVICE_NAME = "input_test_device_name"
        private const val TEST_DEVICE_ID_1 = "test_device_id_1"
        private const val TEST_DEVICE_ID_2 = "test_device_id_2"
        private const val INPUT_TEST_DEVICE_ID = "input_test_device_id"
        private const val TEST_SESSION_NAME = "test_session_name"
        private const val TEST_CUSTOM_SUBTEXT = "custom subtext"

        private const val TEST_MAX_VOLUME = 20
        private const val TEST_CURRENT_VOLUME = 10
    }
}
