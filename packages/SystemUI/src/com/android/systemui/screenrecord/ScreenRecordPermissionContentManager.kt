/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenrecord

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.util.DisplayMetrics
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.StopReason
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.UserHandle
import android.view.Display
import android.view.MotionEvent.ACTION_MOVE
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.Spinner
import androidx.annotation.LayoutRes
import com.android.systemui.Prefs
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorActivity
import com.android.systemui.mediaprojection.permission.BaseMediaProjectionPermissionContentManager
import com.android.systemui.mediaprojection.permission.ENTIRE_SCREEN
import com.android.systemui.mediaprojection.permission.MediaProjectionPermissionUtils.getConnectedDisplays
import com.android.systemui.mediaprojection.permission.SINGLE_APP
import com.android.systemui.mediaprojection.permission.ScreenShareMode
import com.android.systemui.mediaprojection.permission.ScreenShareOption
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.screenrecord.domain.ScreenRecordingParameters
import com.android.systemui.screenrecord.domain.interactor.ScreenRecordingStartStopInteractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class ScreenRecordPermissionContentManager(
    private val hostUserHandle: UserHandle,
    private val hostUid: Int,
    mediaProjectionMetricsLogger: MediaProjectionMetricsLogger,
    @ScreenShareMode defaultSelectedMode: Int,
    displayManager: DisplayManager,
    private val controller: ScreenRecordUxController,
    private val activityStarter: ActivityStarter,
    private val onStartRecordingClicked: Runnable?,
    private val screenRecordingStartStopInteractor: ScreenRecordingStartStopInteractor,
) :
    BaseMediaProjectionPermissionContentManager(
        createOptionList(displayManager),
        appName = null,
        hostUid = hostUid,
        mediaProjectionMetricsLogger,
        defaultSelectedMode,
    ) {
    @AssistedInject
    constructor(
        @Assisted hostUserHandle: UserHandle,
        @Assisted hostUid: Int,
        mediaProjectionMetricsLogger: MediaProjectionMetricsLogger,
        displayManager: DisplayManager,
        @Assisted controller: ScreenRecordUxController,
        activityStarter: ActivityStarter,
        @Assisted onStartRecordingClicked: Runnable?,
        screenRecordingStartStopInteractor: ScreenRecordingStartStopInteractor,
    ) : this(
        hostUserHandle,
        hostUid,
        mediaProjectionMetricsLogger,
        defaultSelectedMode = SINGLE_APP,
        displayManager,
        controller,
        activityStarter,
        onStartRecordingClicked,
        screenRecordingStartStopInteractor,
    )

    @AssistedFactory
    interface Factory {
        fun create(
            hostUserHandle: UserHandle,
            hostUid: Int,
            controller: ScreenRecordUxController,
            onStartRecordingClicked: Runnable?,
        ): ScreenRecordPermissionContentManager
    }

    private lateinit var tapsSwitch: CompoundButton
    private lateinit var audioSwitch: CompoundButton
    private lateinit var lowQualitySwitch: CompoundButton
    private lateinit var longerDurationSwitch: CompoundButton
    private lateinit var skipTimeSwitch: CompoundButton
    private lateinit var hevcSwitch: CompoundButton
    private lateinit var tapsView: View
    private lateinit var options: Spinner
    private lateinit var bitrateSpinner: Spinner
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var timeLimitSpinner: Spinner
    private lateinit var fileSizeSpinner: Spinner
    private val resolutionModeValues = mutableListOf<Int>()

    override fun bind(view: View) {
        super.bind(view)
        initRecordOptionsView()
        setStartButtonOnClickListener { startButtonOnClicked() }
    }

    fun startButtonOnClicked() {
        onStartRecordingClicked?.run()
        if (selectedScreenShareOption.mode == ENTIRE_SCREEN) {
            requestScreenCapture(
                captureTarget = null,
                displayId = selectedScreenShareOption.displayId,
            )
        }
        if (selectedScreenShareOption.mode == SINGLE_APP) {
            val intent =
                Intent(containerView.context, MediaProjectionAppSelectorActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // We can't start activity for result here so we use result receiver to get
            // the selected target to capture
            intent.putExtra(
                MediaProjectionAppSelectorActivity.EXTRA_CAPTURE_REGION_RESULT_RECEIVER,
                CaptureTargetResultReceiver(),
            )

            intent.putExtra(
                MediaProjectionAppSelectorActivity.EXTRA_HOST_APP_USER_HANDLE,
                hostUserHandle,
            )
            intent.putExtra(MediaProjectionAppSelectorActivity.EXTRA_HOST_APP_UID, hostUid)
            intent.putExtra(
                MediaProjectionAppSelectorActivity.EXTRA_SCREEN_SHARE_TYPE,
                MediaProjectionAppSelectorActivity.ScreenShareType.ScreenRecord.name,
            )
            activityStarter.startActivity(intent, /* dismissShade= */ true)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initRecordOptionsView() {
        audioSwitch = containerView.requireViewById(R.id.screenrecord_audio_switch)
        tapsSwitch = containerView.requireViewById(R.id.screenrecord_taps_switch)
        lowQualitySwitch = containerView.requireViewById(R.id.screenrecord_lowquality_switch)
        longerDurationSwitch = containerView.requireViewById(R.id.screenrecord_longer_timeout_switch)
        skipTimeSwitch = containerView.requireViewById(R.id.screenrecord_skip_time_switch)
        hevcSwitch = containerView.requireViewById(R.id.screenrecord_hevc_switch)

        tapsView = containerView.requireViewById(R.id.show_taps)
        updateTapsViewVisibility()

        // Add these listeners so that the switch only responds to movement
        // within its target region, to meet accessibility requirements
        audioSwitch.setOnTouchListener { _, event -> event.action == ACTION_MOVE }
        tapsSwitch.setOnTouchListener { _, event -> event.action == ACTION_MOVE }
        lowQualitySwitch.setOnTouchListener { _, event -> event.action == ACTION_MOVE }
        longerDurationSwitch.setOnTouchListener { _, event -> event.action == ACTION_MOVE }
        skipTimeSwitch.setOnTouchListener { _, event -> event.action == ACTION_MOVE }
        hevcSwitch.setOnTouchListener { _, event -> event.action == ACTION_MOVE }

        options = containerView.requireViewById(R.id.screen_recording_options)
        val a: ArrayAdapter<*> =
            ScreenRecordingAdapter(
                containerView.context,
                android.R.layout.simple_spinner_dropdown_item,
                MODES,
            )
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        options.adapter = a
        options.setOnItemClickListenerInt { _: AdapterView<*>?, _: View?, _: Int, _: Long ->
            audioSwitch.isChecked = true
        }

        resolutionSpinner = containerView.requireViewById(R.id.screenrecord_resolution_spinner)
        fpsSpinner = containerView.requireViewById(R.id.screenrecord_fps_spinner)
        timeLimitSpinner = containerView.requireViewById(R.id.screenrecord_time_limit_spinner)
        fileSizeSpinner = containerView.requireViewById(R.id.screenrecord_file_size_spinner)
        bitrateSpinner = containerView.requireViewById(R.id.screenrecord_bitrate_spinner)
        setupSpinner(bitrateSpinner, R.array.screenrecord_bitrate_entries)
        setupResolutionSpinner()
        setupSpinner(fpsSpinner, R.array.screenrecord_fps_entries)
        setupSpinner(timeLimitSpinner, R.array.screenrecord_time_limit_entries)
        setupSpinner(fileSizeSpinner, R.array.screenrecord_file_size_entries)

        // Disable HEVC when hardware accelerated codec is not available
        if (!hasHevcHwEncoder()) {
            Prefs.putInt(containerView.context, PREF_HEVC, 0)
            containerView.requireViewById<View>(R.id.show_hevc).visibility = View.GONE
        }

        // disable redundant Touch & Hold accessibility action for Switch Access
        options.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
                    super.onInitializeAccessibilityNodeInfo(host, info)
                }
            }
        options.isLongClickable = false

        loadPrefs();
    }

    override fun onItemSelected(adapterView: AdapterView<*>?, view: View, pos: Int, id: Long) {
        super.onItemSelected(adapterView, view, pos, id)
        updateTapsViewVisibility()
    }

    private fun updateTapsViewVisibility() {
        tapsView.visibility = if (selectedScreenShareOption.mode == SINGLE_APP) GONE else VISIBLE
    }

    @LayoutRes override fun getOptionsViewLayoutId(): Int = R.layout.screen_record_options

    /**
     * Starts screen capture after some countdown
     *
     * @param captureTarget target to capture (could be e.g. a task) or null to record the whole
     *   screen
     */
    private fun requestScreenCapture(
        captureTarget: MediaProjectionCaptureTarget?,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ) {
        val showTaps = selectedScreenShareOption.mode != SINGLE_APP && tapsSwitch.isChecked
        val audioMode =
            if (audioSwitch.isChecked) options.selectedItem as ScreenRecordingAudioSource
            else ScreenRecordingAudioSource.NONE
        val lowQuality = lowQualitySwitch.isChecked
        val longerDuration = longerDurationSwitch.isChecked
        val skipTime = skipTimeSwitch.isChecked
        val hevc = hevcSwitch.isChecked
        val bitrateMultiplier = BITRATE_MULTIPLIER_VALUES[bitrateSpinner.selectedItemPosition]
        val resolutionMode = resolutionModeValues[resolutionSpinner.selectedItemPosition]
        val fpsMode = FPS_MODE_VALUES[fpsSpinner.selectedItemPosition]
        val timeLimitMs = TIME_LIMIT_VALUES_MS[timeLimitSpinner.selectedItemPosition]
        val fileSizeBytes = FILE_SIZE_VALUES_BYTES[fileSizeSpinner.selectedItemPosition]

        savePrefs();

        controller.startCountdown(
            if (skipTime) NO_DELAY else DELAY_MS,
            if (skipTime) NO_DELAY else INTERVAL_MS,
            {
                screenRecordingStartStopInteractor.startRecording(
                    ScreenRecordingParameters(
                        captureTarget = captureTarget,
                        audioSource = audioMode,
                        displayId = displayId,
                        shouldShowTaps = showTaps,
                        lowQuality = lowQuality,
                        longerDuration = longerDuration,
                        hevc = hevc,
                        bitrateMultiplier = bitrateMultiplier,
                        resolutionMode = resolutionMode,
                        fpsMode = fpsMode,
                        timeLimitMs = timeLimitMs,
                        fileSizeBytes = fileSizeBytes,
                    )
                )
            },
            { screenRecordingStartStopInteractor.stopRecording(StopReason.STOP_UNKNOWN) },
        )
    }

    private fun setupResolutionSpinner() {
        val ctx = containerView.context
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val metrics = DisplayMetrics()
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val longEdge = maxOf(screenW, screenH)

        val entries = mutableListOf<CharSequence>(ctx.getString(R.string.screenrecord_resolution_auto))
        resolutionModeValues.clear()
        resolutionModeValues.add(0)

        if (longEdge > 2560) {
            entries.add(resolutionLabel(screenW, screenH, 2560))
            resolutionModeValues.add(2560)
        }
        if (longEdge > 1920) {
            entries.add(resolutionLabel(screenW, screenH, 1920))
            resolutionModeValues.add(1920)
        }
        if (longEdge > 1280) {
            entries.add(resolutionLabel(screenW, screenH, 1280))
            resolutionModeValues.add(1280)
        }

        val adapter = ArrayAdapter(ctx, R.layout.screenrecord_spinner_item, entries)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        resolutionSpinner.adapter = adapter
    }

    private fun resolutionLabel(screenW: Int, screenH: Int, longEdgeCap: Int): String {
        val scale = longEdgeCap.toDouble() / maxOf(screenW, screenH)
        val w = (screenW * scale).toInt()
        val h = (screenH * scale).toInt()
        val className = when (longEdgeCap) {
            2560 -> "1440p"
            1920 -> "1080p"
            else -> "720p"
        }
        return "$className  ($w × $h)"
    }

    private fun setupSpinner(spinner: Spinner, entriesRes: Int) {
        val ctx = containerView.context
        val entries = ctx.resources.getTextArray(entriesRes)
        val adapter = ArrayAdapter(ctx, R.layout.screenrecord_spinner_item, entries)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun hasHevcHwEncoder(): Boolean {
        val mediaCodecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

        for (codecInfo in mediaCodecList.getCodecInfos()) {
            if (!codecInfo.isEncoder() || !codecInfo.isHardwareAccelerated()) {
                continue
            }

            for (type in codecInfo.getSupportedTypes()) {
                if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                    return true
                }
            }
        }

        return false
    }

    private fun savePrefs() {
        val userContext = containerView.context
        Prefs.putInt(userContext, PREF_TAPS, if (tapsSwitch.isChecked) 1 else 0)
        Prefs.putInt(userContext, PREF_LOW, if (lowQualitySwitch.isChecked) 1 else 0)
        Prefs.putInt(userContext, PREF_LONGER, if (longerDurationSwitch.isChecked) 1 else 0)
        Prefs.putInt(userContext, PREF_AUDIO, if (audioSwitch.isChecked) 1 else 0)
        Prefs.putInt(userContext, PREF_AUDIO_SOURCE, options.selectedItemPosition)
        Prefs.putInt(userContext, PREF_SKIP, if (skipTimeSwitch.isChecked) 1 else 0)
        Prefs.putInt(userContext, PREF_HEVC, if (hevcSwitch.isChecked) 1 else 0)
        Prefs.putInt(userContext, PREF_BITRATE, bitrateSpinner.selectedItemPosition)
        Prefs.putInt(userContext, PREF_RESOLUTION, resolutionModeValues[resolutionSpinner.selectedItemPosition])
        Prefs.putInt(userContext, PREF_FPS, fpsSpinner.selectedItemPosition)
        Prefs.putInt(userContext, PREF_TIME_LIMIT, timeLimitSpinner.selectedItemPosition)
        Prefs.putInt(userContext, PREF_FILE_SIZE, fileSizeSpinner.selectedItemPosition)
    }

    private fun loadPrefs() {
        val userContext = containerView.context
        tapsSwitch.isChecked = Prefs.getInt(userContext, PREF_TAPS, 0) == 1
        lowQualitySwitch.isChecked = Prefs.getInt(userContext, PREF_LOW, 0) == 1
        longerDurationSwitch.isChecked = Prefs.getInt(userContext, PREF_LONGER, 0) == 1
        audioSwitch.isChecked = Prefs.getInt(userContext, PREF_AUDIO, 0) == 1
        options.setSelection(Prefs.getInt(userContext, PREF_AUDIO_SOURCE, 0))
        skipTimeSwitch.isChecked = Prefs.getInt(userContext, PREF_SKIP, 0) == 1
        hevcSwitch.isChecked = Prefs.getInt(userContext, PREF_HEVC, 1) == 1
        bitrateSpinner.setSelection(Prefs.getInt(userContext, PREF_BITRATE, 0))
        val savedResMode = Prefs.getInt(userContext, PREF_RESOLUTION, 0)
        resolutionSpinner.setSelection(resolutionModeValues.indexOf(savedResMode).coerceAtLeast(0))
        fpsSpinner.setSelection(Prefs.getInt(userContext, PREF_FPS, 0))
        timeLimitSpinner.setSelection(Prefs.getInt(userContext, PREF_TIME_LIMIT, 0))
        fileSizeSpinner.setSelection(Prefs.getInt(userContext, PREF_FILE_SIZE, 0))
    }

    private inner class CaptureTargetResultReceiver :
        ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
            if (resultCode == Activity.RESULT_OK) {
                val captureTarget =
                    resultData.getParcelable(
                        MediaProjectionAppSelectorActivity.KEY_CAPTURE_TARGET,
                        MediaProjectionCaptureTarget::class.java,
                    )

                // Start recording of the selected target
                requestScreenCapture(captureTarget)
            }
        }
    }

    companion object {
        private val MODES =
            listOf(
                ScreenRecordingAudioSource.INTERNAL,
                ScreenRecordingAudioSource.MIC,
                ScreenRecordingAudioSource.MIC_AND_INTERNAL,
            )

        private const val DELAY_MS: Long = 3000
        private const val NO_DELAY: Long = 100
        private const val INTERVAL_MS: Long = 1000

        private const val PREF_TAPS = "screenrecord_show_taps"
        private const val PREF_LOW = "screenrecord_use_low_quality"
        private const val PREF_LONGER = "screenrecord_use_longer_timeout"
        private const val PREF_AUDIO = "screenrecord_use_audio"
        private const val PREF_AUDIO_SOURCE = "screenrecord_audio_source"
        private const val PREF_SKIP = "screenrecord_skip_timer"
        private const val PREF_HEVC = "screenrecord_use_hevc"
        private const val PREF_BITRATE = "screenrecord_bitrate"
        private const val PREF_RESOLUTION = "screenrecord_resolution_mode"
        private const val PREF_FPS = "screenrecord_fps_mode"
        private const val PREF_TIME_LIMIT = "screenrecord_time_limit"
        private const val PREF_FILE_SIZE = "screenrecord_file_size"

        private val BITRATE_MULTIPLIER_VALUES = floatArrayOf(1.0f, 0.5f, 0.25f)
        private val FPS_MODE_VALUES = intArrayOf(0, 30, 60)
        private val TIME_LIMIT_VALUES_MS = intArrayOf(0, 5 * 60 * 1000, 10 * 60 * 1000, 30 * 60 * 1000, 60 * 60 * 1000)
        private val FILE_SIZE_VALUES_BYTES = longArrayOf(0L, 10L shl 20, 100L shl 20, 500L shl 20, 1L shl 30, 15L shl 30)

        fun createOptionList(displayManager: DisplayManager): List<ScreenShareOption> {
            val connectedDisplays = getConnectedDisplays(displayManager)

            val options =
                mutableListOf(
                    ScreenShareOption(
                        SINGLE_APP,
                        R.string.screenrecord_permission_dialog_option_text_single_app,
                        R.string.screenrecord_permission_dialog_warning_single_app,
                        startButtonText =
                            R.string
                                .media_projection_entry_generic_permission_dialog_continue_single_app,
                    ),
                    ScreenShareOption(
                        ENTIRE_SCREEN,
                        R.string.screenrecord_permission_dialog_option_text_entire_screen,
                        R.string.screenrecord_permission_dialog_warning_entire_screen,
                        startButtonText =
                            R.string.screenrecord_permission_dialog_continue_entire_screen,
                        displayId = Display.DEFAULT_DISPLAY,
                        displayName = Build.MODEL,
                    ),
                )

            if (connectedDisplays.isNotEmpty()) {
                options +=
                    connectedDisplays.map {
                        ScreenShareOption(
                            ENTIRE_SCREEN,
                            R.string
                                .screenrecord_permission_dialog_option_text_entire_screen_for_display,
                            warningText =
                                R.string
                                    .media_projection_entry_app_permission_dialog_warning_entire_screen,
                            startButtonText =
                                R.string
                                    .media_projection_entry_app_permission_dialog_continue_entire_screen,
                            displayId = it.displayId,
                            displayName = it.name,
                        )
                    }
            }
            return options.toList()
        }
    }
}
