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

package com.android.server.wm;

import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_FOLDED;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_HALF_FOLDED;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_UNFOLDED;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_UNKNOWN;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_LOCKED;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_UNLOCKED;
import static android.provider.Settings.System.ACCELEROMETER_ROTATION;
import static android.provider.Settings.System.getUriFor;

import static com.android.internal.view.RotationPolicy.getNaturalRotation;
import static com.android.server.wm.DisplayRotation.NO_UPDATE_USER_ROTATION;
import static com.android.server.wm.DisplayRotation.USE_CURRENT_ROTATION;
import static com.android.server.wm.utils.DeviceStateTestUtils.FOLDED;
import static com.android.server.wm.utils.DeviceStateTestUtils.HALF_FOLDED;
import static com.android.server.wm.utils.DeviceStateTestUtils.OPEN;
import static com.android.server.wm.utils.DeviceStateTestUtils.REAR;
import static com.android.server.wm.utils.DeviceStateTestUtils.UNKNOWN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.devicestate.DeviceState;
import android.os.Handler;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.util.SparseIntArray;
import android.view.Surface;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.utils.TestDeviceStateAutoRotateManager;
import com.android.server.wm.utils.TestDeviceStateAutoRotateManager.TestDeviceStateAutoRotateSetting;
import com.android.settingslib.devicestate.DeviceStateAutoRotateSettingManager;
import com.android.settingslib.devicestate.PostureDeviceStateConverter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test class for {@link DeviceStateAutoRotateSettingController}.
 *
 * <p>Build/Install/Run: atest WmTests:DeviceStateAutoRotateSettingControllerTests
 */
@SmallTest
@Presubmit
public class DeviceStateAutoRotateSettingControllerTests {
    private static final int ACCELEROMETER_ROTATION_OFF = 0;
    private static final int ACCELEROMETER_ROTATION_ON = 1;
    private static final SparseIntArray FOLDED_UNLOCKED_OPEN_UNLOCKED_SETTING;
    private static final SparseIntArray FOLDED_LOCKED_OPEN_UNLOCKED_SETTING;
    private static final SparseIntArray FOLDED_LOCKED_OPEN_LOCKED_SETTING;
    private static final SparseIntArray FOLDED_UNLOCKED_OPEN_LOCKED_SETTING;

    static {
        FOLDED_UNLOCKED_OPEN_UNLOCKED_SETTING = new SparseIntArray();
        FOLDED_UNLOCKED_OPEN_UNLOCKED_SETTING.put(DEVICE_STATE_ROTATION_KEY_FOLDED,
                DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
        FOLDED_UNLOCKED_OPEN_UNLOCKED_SETTING.put(DEVICE_STATE_ROTATION_KEY_UNFOLDED,
                DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
        FOLDED_LOCKED_OPEN_UNLOCKED_SETTING = new SparseIntArray();
        FOLDED_LOCKED_OPEN_UNLOCKED_SETTING.put(DEVICE_STATE_ROTATION_KEY_FOLDED,
                DEVICE_STATE_ROTATION_LOCK_LOCKED);
        FOLDED_LOCKED_OPEN_UNLOCKED_SETTING.put(DEVICE_STATE_ROTATION_KEY_UNFOLDED,
                DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
        FOLDED_LOCKED_OPEN_LOCKED_SETTING = new SparseIntArray();
        FOLDED_LOCKED_OPEN_LOCKED_SETTING.put(DEVICE_STATE_ROTATION_KEY_FOLDED,
                DEVICE_STATE_ROTATION_LOCK_LOCKED);
        FOLDED_LOCKED_OPEN_LOCKED_SETTING.put(DEVICE_STATE_ROTATION_KEY_UNFOLDED,
                DEVICE_STATE_ROTATION_LOCK_LOCKED);
        FOLDED_UNLOCKED_OPEN_LOCKED_SETTING = new SparseIntArray();
        FOLDED_UNLOCKED_OPEN_LOCKED_SETTING.put(DEVICE_STATE_ROTATION_KEY_FOLDED,
                DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
        FOLDED_UNLOCKED_OPEN_LOCKED_SETTING.put(DEVICE_STATE_ROTATION_KEY_UNFOLDED,
                DEVICE_STATE_ROTATION_LOCK_LOCKED);
    }

    @Rule
    public final FakeSettingsProviderRule rule = FakeSettingsProvider.rule();

    @Mock
    private DeviceStateController mMockDeviceStateController;
    @Mock
    private ContentResolver mMockResolver;
    @Mock
    private Resources mMockResources;
    @Mock
    private WindowManagerService mMockWm;
    @Mock
    private DisplayRotation mMockDisplayRotation;
    @Mock
    private PostureDeviceStateConverter mMockPostureDeviceStateConverter;

    @Captor
    private ArgumentCaptor<DeviceStateController.DeviceStateListener> mDeviceStateListenerCaptor;
    @Captor
    private ArgumentCaptor<ContentObserver> mAccelerometerRotationSettingObserver;

    private final TestLooper mTestLooper = new TestLooper();
    private DeviceStateAutoRotateSettingController mDeviceStateAutoRotateSettingController;
    private TestDeviceStateAutoRotateManager mTestAutoRotateSettingManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        final Handler handler = new Handler(mTestLooper.getLooper());
        final Context mockContext = mock(Context.class);
        final FakeSettingsProvider fakeSettingsProvider = new FakeSettingsProvider();
        final RootWindowContainer mockRoot = mock(RootWindowContainer.class);
        final DisplayContent mockDisplayContent = mock(DisplayContent.class);
        final ActivityTaskManagerService atmService = mock(ActivityTaskManagerService.class);

        // Setup context
        when(mockContext.getContentResolver()).thenReturn(mMockResolver);
        when(mockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getStringArray(
                R.array.config_perDeviceStateRotationLockDefaults)).thenReturn(new String[]{});
        when(mMockResolver.acquireProvider(Settings.AUTHORITY)).thenReturn(
                fakeSettingsProvider.getIContentProvider());

        // Setup WindowManagerServices
        WindowTestsBase.setFieldValue(mMockWm, "mContext", mockContext);
        mockRoot.mService = atmService;
        WindowTestsBase.setFieldValue(atmService, "mGlobalLock", new WindowManagerGlobalLock());
        WindowTestsBase.setFieldValue(mMockWm, "mRoot", mockRoot);
        when(mockRoot.getDefaultDisplay()).thenReturn(mockDisplayContent);
        when(mockDisplayContent.getDisplayRotation()).thenReturn(mMockDisplayRotation);

        setUpMockPostureHelper();
        mTestAutoRotateSettingManager = new TestDeviceStateAutoRotateManager(
                mMockPostureDeviceStateConverter);
        final TestDeviceStateAutoRotateSetting defaultDeviceStateAutoRotateSetting =
                mTestAutoRotateSettingManager.new TestDeviceStateAutoRotateSetting(
                        createDeviceStateAutoRotateSettingMap(DEVICE_STATE_ROTATION_LOCK_UNLOCKED,
                                DEVICE_STATE_ROTATION_LOCK_UNLOCKED));
        mTestAutoRotateSettingManager.setDefaultDeviceStateAutoRotateSetting(
                defaultDeviceStateAutoRotateSetting);
        setAccelerometerRotationSetting(ACCELEROMETER_ROTATION_OFF);
        setDeviceStateAutoRotateSetting(FOLDED_LOCKED_OPEN_LOCKED_SETTING);

        mDeviceStateAutoRotateSettingController = new DeviceStateAutoRotateSettingController(
                mMockDeviceStateController, mTestAutoRotateSettingManager, mMockWm,
                mMockPostureDeviceStateConverter) {
            @Override
            Handler getHandler() {
                return handler;
            }
        };
        mTestLooper.dispatchAll();

        verify(mMockResolver).registerContentObserver(
                eq(getUriFor(ACCELEROMETER_ROTATION)), anyBoolean(),
                mAccelerometerRotationSettingObserver.capture(), eq(UserHandle.USER_CURRENT));
        verify(mMockDeviceStateController).registerDeviceStateCallback(
                mDeviceStateListenerCaptor.capture(), any());
    }

    @Test
    public void requestDSAutoRotateSettingChange_updatesDeviceStateAutoRotateSetting() {
        setDeviceState(FOLDED);

        mDeviceStateAutoRotateSettingController.requestDeviceStateAutoRotateSettingChange(
                FOLDED.getIdentifier(), true);
        mTestLooper.dispatchAll();

        verifyDeviceStateAutoRotateSettingSet(
                FOLDED_UNLOCKED_OPEN_LOCKED_SETTING);

    }

    @Test
    public void requestAccelerometerRotationChange_updatesAccelerometerRotation() {
        setDeviceState(FOLDED);

        mDeviceStateAutoRotateSettingController.requestAccelerometerRotationSettingChange(true,
                USE_CURRENT_ROTATION, "");
        mTestLooper.dispatchAll();

        verifyAccelerometerRotationSettingSet(ACCELEROMETER_ROTATION_ON);
    }

    @Test
    public void requestDSAutoRotateSettingChange_curDeviceState_updatesAccelerometerRotation() {
        setDeviceState(FOLDED);

        mDeviceStateAutoRotateSettingController.requestDeviceStateAutoRotateSettingChange(
                FOLDED.getIdentifier(), true);
        mTestLooper.dispatchAll();

        verifyAccelerometerRotationSettingSet(ACCELEROMETER_ROTATION_ON);
    }

    @Test
    public void requestAccelerometerRotationChange_updatesDSAutoRotateSetting() {
        setDeviceState(FOLDED);

        mDeviceStateAutoRotateSettingController.requestAccelerometerRotationSettingChange(true,
                USE_CURRENT_ROTATION, "");
        mTestLooper.dispatchAll();

        verifyDeviceStateAutoRotateSettingSet(
                FOLDED_UNLOCKED_OPEN_LOCKED_SETTING);
    }

    @Test
    public void accelerometerRotationSettingChanged_updatesDSAutoRotateSetting() {
        setDeviceState(FOLDED);
        setAccelerometerRotationSetting(ACCELEROMETER_ROTATION_ON);

        mAccelerometerRotationSettingObserver.getValue().onChange(false);
        mTestLooper.dispatchAll();

        verifyDeviceStateAutoRotateSettingSet(
                FOLDED_UNLOCKED_OPEN_LOCKED_SETTING);
    }

    @Test
    public void dSAutoRotateSettingChanged_updatesAccelerometerRotation() {
        setDeviceState(FOLDED);
        setDeviceStateAutoRotateSetting(FOLDED_UNLOCKED_OPEN_LOCKED_SETTING);

        mTestAutoRotateSettingManager.notifyListeners();
        mTestLooper.dispatchAll();

        verifyAccelerometerRotationSettingSet(ACCELEROMETER_ROTATION_ON);
    }

    @Test
    public void onDeviceStateChange_updatesAccelerometerRotation() {
        setDeviceState(FOLDED);
        setDeviceStateAutoRotateSetting(FOLDED_LOCKED_OPEN_UNLOCKED_SETTING);
        mTestAutoRotateSettingManager.notifyListeners();
        mTestLooper.dispatchAll();

        setDeviceState(OPEN);

        verifyAccelerometerRotationSettingSet(ACCELEROMETER_ROTATION_ON);
    }

    @Test
    public void requestDSAutoRotateSettingChange_nonCurDeviceState_noUpdateAccelerometerRotation() {
        setDeviceState(FOLDED);
        mDeviceStateAutoRotateSettingController.requestDeviceStateAutoRotateSettingChange(
                OPEN.getIdentifier(), true);
        mTestLooper.dispatchAll();

        verifyAccelerometerRotationSettingSet(ACCELEROMETER_ROTATION_OFF);
    }

    @Test
    public void dSAutoRotateCorrupted_writesDefaultSettingWhileRespectingAccelerometerRotation() {
        setDeviceState(FOLDED);
        setDeviceStateAutoRotateSetting(null);

        mTestAutoRotateSettingManager.notifyListeners();
        mTestLooper.dispatchAll();

        verifyDeviceStateAutoRotateSettingSet(
                FOLDED_LOCKED_OPEN_UNLOCKED_SETTING);
    }

    @Test
    public void multipleSettingChanges_accelerometerRotationSettingTakesPrecedenceWhenConflict() {
        setDeviceState(FOLDED);
        setDeviceStateAutoRotateSetting(FOLDED_UNLOCKED_OPEN_LOCKED_SETTING);
        setAccelerometerRotationSetting(ACCELEROMETER_ROTATION_ON);
        mAccelerometerRotationSettingObserver.getValue().onChange(false);
        mTestLooper.dispatchAll();

        // Change accelerometer rotation setting to locked
        setAccelerometerRotationSetting(ACCELEROMETER_ROTATION_OFF);
        mAccelerometerRotationSettingObserver.getValue().onChange(false);
        mTestLooper.dispatchAll();

        verifyDeviceStateAutoRotateSettingSet(
                FOLDED_LOCKED_OPEN_LOCKED_SETTING);

        // Change device state auto rotate setting to unlocked for both states
        setDeviceStateAutoRotateSetting(FOLDED_LOCKED_OPEN_UNLOCKED_SETTING);
        setAccelerometerRotationSetting(ACCELEROMETER_ROTATION_ON);
        mTestAutoRotateSettingManager.notifyListeners();
        mTestLooper.dispatchAll();

        verifyDeviceStateAutoRotateSettingSet(
                FOLDED_UNLOCKED_OPEN_UNLOCKED_SETTING);
    }

    @Test
    public void multipleDeviceStateChanges_updatesAccelerometerRotationForRespectiveDeviceState() {
        setDeviceState(FOLDED);
        setDeviceStateAutoRotateSetting(FOLDED_UNLOCKED_OPEN_LOCKED_SETTING);
        setAccelerometerRotationSetting(ACCELEROMETER_ROTATION_ON);
        mTestAutoRotateSettingManager.notifyListeners();
        mTestLooper.dispatchAll();

        setDeviceState(OPEN);

        verifyAccelerometerRotationSettingSet(ACCELEROMETER_ROTATION_OFF);

        setDeviceState(FOLDED);

        verifyAccelerometerRotationSettingSet(ACCELEROMETER_ROTATION_ON);
    }

    @Test
    public void requestAccelerometerRotationChange_dSUnavailable_noSettingUpdate() {
        mDeviceStateAutoRotateSettingController.requestAccelerometerRotationSettingChange(true,
                USE_CURRENT_ROTATION, "");
        mTestLooper.dispatchAll();

        verifyAccelerometerRotationSettingSet(ACCELEROMETER_ROTATION_OFF);
    }

    @Test
    public void requestDSAutoRotateSettingChange_dSUnavailable_noSettingUpdate() {
        mDeviceStateAutoRotateSettingController.requestDeviceStateAutoRotateSettingChange(
                FOLDED.getIdentifier(), true);
        mTestLooper.dispatchAll();

        verifyAccelerometerRotationSettingSet(ACCELEROMETER_ROTATION_OFF);
    }

    @Test
    public void requestAccelerometerRotationChange_dSUnavailable_writeAfterReceivingDSUpdate() {
        mDeviceStateAutoRotateSettingController.requestAccelerometerRotationSettingChange(true,
                USE_CURRENT_ROTATION, "");
        mTestLooper.dispatchAll();

        setDeviceState(FOLDED);

        verifyAccelerometerRotationSettingSet(ACCELEROMETER_ROTATION_ON);
        verifyDeviceStateAutoRotateSettingSet(
                FOLDED_UNLOCKED_OPEN_LOCKED_SETTING);
    }

    @Test
    public void requestDSAutoRotateSettingChange_dSUnavailable_writeAfterReceivingDSUpdate() {
        mDeviceStateAutoRotateSettingController.requestDeviceStateAutoRotateSettingChange(
                FOLDED.getIdentifier(), true);
        mTestLooper.dispatchAll();

        setDeviceState(FOLDED);

        verifyAccelerometerRotationSettingSet(ACCELEROMETER_ROTATION_ON);
        verifyDeviceStateAutoRotateSettingSet(
                FOLDED_UNLOCKED_OPEN_LOCKED_SETTING);
    }

    @Test
    public void dSUnavailable_sendMultipleRequests_accelerometerPrecedesAfterReceivingDSUpdate() {
        mDeviceStateAutoRotateSettingController.requestDeviceStateAutoRotateSettingChange(
                FOLDED.getIdentifier(), true);
        mDeviceStateAutoRotateSettingController.requestAccelerometerRotationSettingChange(false,
                USE_CURRENT_ROTATION, "");
        mDeviceStateAutoRotateSettingController.requestDeviceStateAutoRotateSettingChange(
                OPEN.getIdentifier(), true);
        mTestLooper.dispatchAll();

        setDeviceState(FOLDED);

        verifyAccelerometerRotationSettingSet(ACCELEROMETER_ROTATION_OFF);
        verifyDeviceStateAutoRotateSettingSet(FOLDED_LOCKED_OPEN_UNLOCKED_SETTING);
    }

    @Test
    public void dSUnavailable_sendMultipleRequests_dSAutoRotatePrecedesAfterReceivingDSUpdate() {
        mDeviceStateAutoRotateSettingController.requestAccelerometerRotationSettingChange(false,
                USE_CURRENT_ROTATION, "");
        mDeviceStateAutoRotateSettingController.requestDeviceStateAutoRotateSettingChange(
                OPEN.getIdentifier(), true);
        mDeviceStateAutoRotateSettingController.requestDeviceStateAutoRotateSettingChange(
                FOLDED.getIdentifier(), true);
        mTestLooper.dispatchAll();

        setDeviceState(FOLDED);

        verifyAccelerometerRotationSettingSet(ACCELEROMETER_ROTATION_ON);
        verifyDeviceStateAutoRotateSettingSet(FOLDED_UNLOCKED_OPEN_UNLOCKED_SETTING);
    }

    @Test
    public void dSUnavailable_cleanDevice_defaultDSAutoRotateSettingSet() {
        setDeviceStateAutoRotateSetting(null);
        mTestLooper.dispatchAll();

        setDeviceState(FOLDED);

        verifyDeviceStateAutoRotateSettingSet(FOLDED_UNLOCKED_OPEN_UNLOCKED_SETTING);
        verifyAccelerometerRotationSettingSet(ACCELEROMETER_ROTATION_ON);
    }

    @Test
    public void requestAccelerometerRotationChange_turnOnAccelerometer_setUserRotation() {
        setDeviceState(FOLDED);

        mDeviceStateAutoRotateSettingController.requestAccelerometerRotationSettingChange(true,
                Surface.ROTATION_90, "");
        mTestLooper.dispatchAll();

        verify(mMockDisplayRotation).setUserRotationSetting(
                eq(WindowManagerPolicy.USER_ROTATION_FREE), eq(Surface.ROTATION_90), any());
    }

    @Test
    public void requestAccelerometerRotationChange_turnOffAccelerometer_setUserRotation() {
        setDeviceState(FOLDED);
        setAccelerometerRotationSetting(ACCELEROMETER_ROTATION_ON);
        mAccelerometerRotationSettingObserver.getValue().onChange(false);
        mTestLooper.dispatchAll();

        mDeviceStateAutoRotateSettingController.requestAccelerometerRotationSettingChange(
                false, Surface.ROTATION_180, "");
        mTestLooper.dispatchAll();

        verify(mMockDisplayRotation).setUserRotationSetting(
                eq(WindowManagerPolicy.USER_ROTATION_LOCKED), eq(Surface.ROTATION_180), any());
    }

    @Test
    public void requestAccelerometerRotationChange_withCurrentRotation_setUserRotation() {
        setDeviceState(FOLDED);
        mTestLooper.dispatchAll();

        mDeviceStateAutoRotateSettingController.requestAccelerometerRotationSettingChange(true,
                USE_CURRENT_ROTATION, "");
        mTestLooper.dispatchAll();

        verify(mMockDisplayRotation).setUserRotationSetting(
                eq(WindowManagerPolicy.USER_ROTATION_FREE), eq(USE_CURRENT_ROTATION), any());
    }

    @Test
    public void requestAccelerometerRotationChange_withNaturalRotation_setUserRotation() {
        setDeviceState(FOLDED);
        setAccelerometerRotationSetting(ACCELEROMETER_ROTATION_ON);
        mAccelerometerRotationSettingObserver.getValue().onChange(false);
        mTestLooper.dispatchAll();

        mDeviceStateAutoRotateSettingController.requestAccelerometerRotationSettingChange(
                false, getNaturalRotation(), "");
        mTestLooper.dispatchAll();

        verify(mMockDisplayRotation).setUserRotationSetting(
                eq(WindowManagerPolicy.USER_ROTATION_LOCKED), eq(getNaturalRotation()), any());
    }

    @Test
    public void requestDSAutoRotateSettingChange_turnsOnAccelerometer_setUserRotation() {
        setDeviceState(FOLDED);

        mDeviceStateAutoRotateSettingController.requestDeviceStateAutoRotateSettingChange(
                FOLDED.getIdentifier(), true);
        mTestLooper.dispatchAll();

        verify(mMockDisplayRotation).setUserRotationSetting(
                eq(WindowManagerPolicy.USER_ROTATION_FREE), eq(NO_UPDATE_USER_ROTATION), any());
    }

    @Test
    public void requestAccelerometerRotationChange_noChangeInAccelerometer_setUserRotation() {
        setDeviceState(FOLDED);

        mDeviceStateAutoRotateSettingController.requestAccelerometerRotationSettingChange(
                false, Surface.ROTATION_90, "");
        mTestLooper.dispatchAll();

        verify(mMockDisplayRotation).setUserRotationSetting(
                eq(WindowManagerPolicy.USER_ROTATION_LOCKED), eq(Surface.ROTATION_90), any());
    }

    @Test
    public void foldableConfigTrue_locksRotation_usesCurrentRotation() {
        when(mMockResources.getBoolean(com.android.internal.R.bool.config_allowAllRotations))
                .thenReturn(true);
        when(mMockResources.getBoolean(
                com.android.internal.R.bool.config_useCurrentRotationOnRotationLockChange))
                .thenReturn(true);
        setDeviceState(FOLDED);
        setAccelerometerRotationSetting(ACCELEROMETER_ROTATION_ON);
        mAccelerometerRotationSettingObserver.getValue().onChange(false);
        mTestLooper.dispatchAll();

        setDeviceStateAutoRotateSetting(FOLDED_LOCKED_OPEN_LOCKED_SETTING);
        mTestAutoRotateSettingManager.notifyListeners();
        mTestLooper.dispatchAll();

        verify(mMockDisplayRotation).setUserRotationSetting(
                eq(WindowManagerPolicy.USER_ROTATION_LOCKED), eq(USE_CURRENT_ROTATION),
                any());
    }

    @Test
    public void allRotationsAllowedConfigFalse_locksRotation_usesCurrentRotation() {
        when(mMockResources.getBoolean(com.android.internal.R.bool.config_allowAllRotations))
                .thenReturn(false);
        when(mMockResources.getBoolean(
                com.android.internal.R.bool.config_useCurrentRotationOnRotationLockChange))
                .thenReturn(true);
        setDeviceState(FOLDED);
        setAccelerometerRotationSetting(ACCELEROMETER_ROTATION_ON);
        mAccelerometerRotationSettingObserver.getValue().onChange(false);
        mTestLooper.dispatchAll();

        setDeviceStateAutoRotateSetting(FOLDED_LOCKED_OPEN_LOCKED_SETTING);
        mTestAutoRotateSettingManager.notifyListeners();
        mTestLooper.dispatchAll();

        verify(mMockDisplayRotation).setUserRotationSetting(
                eq(WindowManagerPolicy.USER_ROTATION_LOCKED), eq(USE_CURRENT_ROTATION),
                any());
    }

    @Test
    public void useCurrentRotationConfigFalse_locksRotation_usesCurrentRotation() {
        when(mMockResources.getBoolean(com.android.internal.R.bool.config_allowAllRotations))
                .thenReturn(true);
        when(mMockResources.getBoolean(
                com.android.internal.R.bool.config_useCurrentRotationOnRotationLockChange))
                .thenReturn(false);
        setDeviceState(FOLDED);
        setAccelerometerRotationSetting(ACCELEROMETER_ROTATION_ON);
        mAccelerometerRotationSettingObserver.getValue().onChange(false);
        mTestLooper.dispatchAll();

        setDeviceStateAutoRotateSetting(FOLDED_LOCKED_OPEN_LOCKED_SETTING);
        mTestAutoRotateSettingManager.notifyListeners();
        mTestLooper.dispatchAll();

        verify(mMockDisplayRotation).setUserRotationSetting(
                eq(WindowManagerPolicy.USER_ROTATION_LOCKED), eq(USE_CURRENT_ROTATION), any());
    }

    @Test
    public void foldableConfigFalse_locksRotation_usesNaturalRotation() {
        when(mMockResources.getBoolean(com.android.internal.R.bool.config_allowAllRotations))
                .thenReturn(false);
        when(mMockResources.getBoolean(
                com.android.internal.R.bool.config_useCurrentRotationOnRotationLockChange))
                .thenReturn(false);
        // Ensure the current rotation is not eligible so the natural-rotation branch is tested.
        Settings.System.putIntForUser(mMockResolver,
                Settings.System.ACCELEROMETER_ROTATION_ANGLES, 0, UserHandle.USER_CURRENT);
        setDeviceState(FOLDED);
        setAccelerometerRotationSetting(ACCELEROMETER_ROTATION_ON);
        mAccelerometerRotationSettingObserver.getValue().onChange(false);
        mTestLooper.dispatchAll();

        setDeviceStateAutoRotateSetting(FOLDED_LOCKED_OPEN_LOCKED_SETTING);
        mTestAutoRotateSettingManager.notifyListeners();
        mTestLooper.dispatchAll();

        verify(mMockDisplayRotation).setUserRotationSetting(
                eq(WindowManagerPolicy.USER_ROTATION_LOCKED), eq(getNaturalRotation()), any());
    }

    private void setDeviceStateAutoRotateSetting(SparseIntArray deviceStateAutoRotateSetting) {
        TestDeviceStateAutoRotateSetting testDeviceStateAutoRotateSetting =
                mTestAutoRotateSettingManager.new TestDeviceStateAutoRotateSetting(
                        deviceStateAutoRotateSetting);
        if (deviceStateAutoRotateSetting == null) {
            testDeviceStateAutoRotateSetting = null;
        }

        mTestAutoRotateSettingManager.setDeviceStateAutoRotateSetting(
                testDeviceStateAutoRotateSetting);
    }

    private void setAccelerometerRotationSetting(int accelerometerRotationSetting) {
        Settings.System.putIntForUser(mMockResolver, ACCELEROMETER_ROTATION,
                accelerometerRotationSetting, UserHandle.USER_CURRENT);
    }

    private void setDeviceState(DeviceState deviceState) {
        mDeviceStateListenerCaptor.getValue().onDeviceStateChanged(
                DeviceStateController.DeviceStateEnum.UNKNOWN, deviceState);
        mTestLooper.dispatchAll();
    }

    private SparseIntArray createDeviceStateAutoRotateSettingMap(int foldedAutoRotateValue,
            int unfoldedAutoRotateValue) {
        final SparseIntArray deviceStateAutoRotateSetting = new SparseIntArray();
        deviceStateAutoRotateSetting.put(DEVICE_STATE_ROTATION_KEY_FOLDED,
                foldedAutoRotateValue);
        deviceStateAutoRotateSetting.put(DEVICE_STATE_ROTATION_KEY_UNFOLDED,
                unfoldedAutoRotateValue);
        return deviceStateAutoRotateSetting;
    }

    private void verifyAccelerometerRotationSettingSet(int expectedAccelerometerRotationSetting) {
        int accelerometerRotationSetting = Settings.System.getIntForUser(mMockResolver,
                ACCELEROMETER_ROTATION,  /* def= */ -1, UserHandle.USER_CURRENT);
        assertThat(accelerometerRotationSetting).isEqualTo(expectedAccelerometerRotationSetting);
    }

    private void verifyDeviceStateAutoRotateSettingSet(
            SparseIntArray expectedSettingMap) {
        final DeviceStateAutoRotateSettingManager.DeviceStateAutoRotateSetting expectedValue =
                mTestAutoRotateSettingManager.new TestDeviceStateAutoRotateSetting(
                        expectedSettingMap);
        final DeviceStateAutoRotateSettingManager.DeviceStateAutoRotateSetting actualValue =
                mTestAutoRotateSettingManager.getRotationLockSetting();
        assertThat(expectedValue.equals(actualValue)).isTrue();
    }

    private void setUpMockPostureHelper() {
        when(mMockPostureDeviceStateConverter.deviceStateToPosture(
                eq(OPEN.getIdentifier()))).thenReturn(DEVICE_STATE_ROTATION_KEY_UNFOLDED);
        when(mMockPostureDeviceStateConverter.deviceStateToPosture(
                eq(FOLDED.getIdentifier()))).thenReturn(DEVICE_STATE_ROTATION_KEY_FOLDED);
        when(mMockPostureDeviceStateConverter.deviceStateToPosture(
                eq(HALF_FOLDED.getIdentifier()))).thenReturn(DEVICE_STATE_ROTATION_KEY_HALF_FOLDED);
        when(mMockPostureDeviceStateConverter.deviceStateToPosture(
                eq(UNKNOWN.getIdentifier()))).thenReturn(DEVICE_STATE_ROTATION_KEY_UNKNOWN);
        when(mMockPostureDeviceStateConverter.deviceStateToPosture(
                eq(REAR.getIdentifier()))).thenReturn(DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY);

        when(mMockPostureDeviceStateConverter.postureToDeviceState(
                eq(DEVICE_STATE_ROTATION_KEY_UNFOLDED))).thenReturn(OPEN.getIdentifier());
        when(mMockPostureDeviceStateConverter.postureToDeviceState(
                eq(DEVICE_STATE_ROTATION_KEY_FOLDED))).thenReturn(FOLDED.getIdentifier());
        when(mMockPostureDeviceStateConverter.postureToDeviceState(
                eq(DEVICE_STATE_ROTATION_KEY_HALF_FOLDED))).thenReturn(HALF_FOLDED.getIdentifier());
        when(mMockPostureDeviceStateConverter.postureToDeviceState(
                eq(DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY))).thenReturn(REAR.getIdentifier());
    }
}
