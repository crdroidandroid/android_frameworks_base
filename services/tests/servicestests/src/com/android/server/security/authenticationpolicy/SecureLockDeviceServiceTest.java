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

package com.android.server.security.authenticationpolicy;

import static android.app.StatusBarManager.DISABLE2_NOTIFICATION_SHADE;
import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;
import static android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS;
import static android.app.StatusBarManager.DISABLE_BACK;
import static android.app.StatusBarManager.DISABLE_EXPAND;
import static android.app.StatusBarManager.DISABLE_HOME;
import static android.app.StatusBarManager.DISABLE_NOTIFICATION_ALERTS;
import static android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS;
import static android.app.StatusBarManager.DISABLE_ONGOING_CALL_CHIP;
import static android.app.StatusBarManager.DISABLE_SEARCH;
import static android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG;
import static android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK;
import static android.hardware.biometrics.SensorProperties.STRENGTH_STRONG;
import static android.hardware.biometrics.SensorProperties.STRENGTH_WEAK;
import static android.os.UserManager.DISALLOW_CHANGE_WIFI_STATE;
import static android.os.UserManager.DISALLOW_CONFIG_WIFI;
import static android.os.UserManager.DISALLOW_SMS;
import static android.os.UserManager.DISALLOW_USER_SWITCH;
import static android.security.Flags.FLAG_SECURE_LOCKDOWN;
import static android.security.Flags.FLAG_SECURE_LOCK_DEVICE;
import static android.security.authenticationpolicy.AuthenticationPolicyManager.ERROR_ALREADY_ENABLED;
import static android.security.authenticationpolicy.AuthenticationPolicyManager.ERROR_INSUFFICIENT_BIOMETRICS;
import static android.security.authenticationpolicy.AuthenticationPolicyManager.ERROR_NOT_AUTHORIZED;
import static android.security.authenticationpolicy.AuthenticationPolicyManager.ERROR_NO_BIOMETRICS_ENROLLED;
import static android.security.authenticationpolicy.AuthenticationPolicyManager.ERROR_UNKNOWN;
import static android.security.authenticationpolicy.AuthenticationPolicyManager.ERROR_UNSUPPORTED;
import static android.security.authenticationpolicy.AuthenticationPolicyManager.SUCCESS;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.PRIMARY_AUTH_REQUIRED_FOR_SECURE_LOCK_DEVICE;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_BIOMETRIC_AUTH_REQUIRED_FOR_SECURE_LOCK_DEVICE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.IStrongAuthTracker;
import android.content.Context;
import android.hardware.biometrics.BiometricEnrollmentStatus;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.SensorProperties;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.usb.IUsbManagerInternal;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.security.authenticationpolicy.DisableSecureLockDeviceParams;
import android.security.authenticationpolicy.EnableSecureLockDeviceParams;
import android.security.authenticationpolicy.ISecureLockDeviceStatusListener;
import android.testing.TestableContext;
import android.util.ArrayMap;
import android.util.Slog;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.test.LocalServiceKeeperRule;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.locksettings.LockSettingsInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.security.authenticationpolicy.settings.DevicePolicyRestrictionsController;
import com.android.server.security.authenticationpolicy.settings.SecureLockDeviceSettingsManager;
import com.android.server.security.authenticationpolicy.settings.SecureLockDeviceSettingsManagerImpl;
import com.android.server.security.authenticationpolicy.settings.SecureLockDeviceStore;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** atest FrameworksServicesTests:SecureLockDeviceServiceTest */
@Presubmit
@SmallTest
@EnableFlags({FLAG_SECURE_LOCKDOWN, FLAG_SECURE_LOCK_DEVICE})
@RunWith(AndroidJUnit4.class)
public class SecureLockDeviceServiceTest {
    private static final int TEST_USER_ID = 0;
    private static final int OTHER_USER_ID = 1;
    private static final int[] USER_IDS = {TEST_USER_ID, OTHER_USER_ID};
    private static final String TAG = "SecureLockDeviceService";
    private static final String FILE_NAME = "secure_lock_device_state";
    private static final String XML_TAG_ROOT = "secure-lock-device-state";
    private static final String XML_TAG_ENABLED = "enabled";
    private static final String XML_TAG_CLIENT_ID = "client-id";
    private static final String XML_TAG_ORIGINAL_SETTINGS = "original-settings";
    private static final Set<String> DEVICE_POLICY_RESTRICTIONS = Set.of(
            DISALLOW_CHANGE_WIFI_STATE,
            DISALLOW_CONFIG_WIFI,
            DISALLOW_SMS,
            DISALLOW_USER_SWITCH
    );
    private static final String DEVICE_POLICY_RESTRICTIONS_KEY = "device_policy_restrictions";
    private static final String DISABLE_FLAGS_KEY = "disable_flags";
    private static final String USB_ENABLED_KEY = "usb_enabled";
    private static final String NFC_ENABLED_KEY = "nfc_enabled";
    private static final int DISABLE_FLAGS =
            // Flag to make the status bar not expandable
            DISABLE_EXPAND
                    // Flag to hide notification icons and scrolling ticker text.
                    | DISABLE_NOTIFICATION_ICONS
                    // Flag to disable incoming notification alerts.  This will not block
                    // icons, but it will block sound, vibrating and other visual or aural
                    // notifications.
                    | DISABLE_NOTIFICATION_ALERTS
                    // Flag to hide only the home button.
                    | DISABLE_HOME
                    // Flag to hide only the back button.
                    | DISABLE_BACK
                    // Flag to disable the global search gesture.
                    | DISABLE_SEARCH
                    // Flag to disable the ongoing call chip.
                    | DISABLE_ONGOING_CALL_CHIP;

    private static final int DISABLE2_FLAGS =
            // Setting this flag disables quick settings completely
            DISABLE2_QUICK_SETTINGS
                    // Flag to hide system icons.
                    | DISABLE2_SYSTEM_ICONS
                    // Flag to disable notification shade
                    | DISABLE2_NOTIFICATION_SHADE;

    // Map of secure settings keys to their values when secure lock device is enabled
    private static final Map<String, Integer> SECURE_SETTINGS_SECURE_LOCK_DEVICE_VALUES =
            Map.of(
                    Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 0,
                    Settings.Secure.DOUBLE_TAP_POWER_BUTTON_GESTURE_ENABLED, 0,
                    Settings.Secure.CAMERA_GESTURE_DISABLED, 1,
                    Settings.Secure.CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED, 1,
                    Settings.Secure.CAMERA_LIFT_TRIGGER_ENABLED, 0,
                    Settings.Secure.LOCKSCREEN_SMARTSPACE_ENABLED, 0,
                    Settings.Secure.LOCKSCREEN_SHOW_CONTROLS, 0,
                    Settings.Secure.LOCKSCREEN_SHOW_WALLET, 0,
                    Settings.Secure.LOCK_SCREEN_SHOW_QR_CODE_SCANNER, 0,
                    Settings.Secure.GLANCEABLE_HUB_ENABLED, 0);

    // Map of system settings keys to their values when secure lock device is enabled
    private static final Map<String, Integer> SYSTEM_SETTINGS_SECURE_LOCK_DEVICE_VALUES =
            Map.of(
                    Settings.System.BLUETOOTH_DISCOVERABILITY, 0,
                    Settings.System.LOCK_TO_APP_ENABLED, 0);

    private static final Set<String> GLOBAL_SETTINGS =
            Set.of(
                    Settings.Global.ADB_ENABLED,
                    Settings.Global.ADB_WIFI_ENABLED,
                    Settings.Global.ADD_USERS_WHEN_LOCKED,
                    Settings.Global.USER_SWITCHER_ENABLED,
                    Settings.Global.ALLOW_USER_SWITCHING_WHEN_SYSTEM_USER_LOCKED);

    @Captor private ArgumentCaptor<BiometricStateListener> mBiometricStateListenerCaptor;
    @Captor private ArgumentCaptor<Integer> mSecureLockDeviceAvailableStatusArgumentCaptor;
    @Captor private ArgumentCaptor<Boolean> mSecureLockDeviceEnabledStatusArgumentCaptor;

    @Mock private ActivityManager mActivityManager;
    @Mock private ActivityTaskManager mActivityTaskManager;
    @Mock private BiometricManager mBiometricManager;
    @Mock private BiometricManager mUserBiometricManager;
    @Mock private BiometricManager mOtherUserBiometricManager;
    @Mock private DevicePolicyManager mDevicePolicyManager;
    @Mock private FaceManager mFaceManager;
    @Mock private FingerprintManager mFingerprintManager;
    @Mock private IBinder mSecureLockDeviceStatusListenerBinder;
    @Mock private IBinder mSecureLockDeviceStatusOtherListenerBinder;
    @Mock private IPowerManager mIPowerManager;
    @Mock private ISecureLockDeviceStatusListener mSecureLockDeviceStatusListener;
    // For OTHER_USER_ID
    @Mock private ISecureLockDeviceStatusListener mSecureLockDeviceStatusOtherListener;
    @Mock private IStatusBarService mStatusBarService;
    @Mock private IThermalService mThermalService;
    @Mock private IVoiceInteractionManagerService mVoiceInteractionManagerService;
    @Mock private LockPatternUtils mLockPatternUtils;
    @Mock private LockSettingsInternal mLockSettingsInternal;
    @Mock private SecureLockDeviceService.StrongAuthTracker mStrongAuthTracker;
    @Mock private SecureLockDeviceServiceInternal mSecureLockDeviceServiceInternal;
    @Mock private UserManagerInternal mUserManagerInternal;
    @Mock private UsbManager mUsbManager;
    @Mock private IUsbManagerInternal mUsbManagerInternal;
    @Mock private WindowManagerInternal mWindowManagerInternal;

    private final EnableSecureLockDeviceParams mEnableParams =
            new EnableSecureLockDeviceParams("test");
    private final DisableSecureLockDeviceParams mDisableParams =
            new DisableSecureLockDeviceParams("test");
    private final UserHandle mUser = new UserHandle(TEST_USER_ID);
    private final UserHandle mOtherUser = new UserHandle(OTHER_USER_ID);
    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final SecureLockDeviceContext mTestContext =
            new SecureLockDeviceContext(mContext, mUser, mOtherUser);
    @Rule public LocalServiceKeeperRule mLocalServiceKeeperRule = new LocalServiceKeeperRule();
    @Rule public MockitoRule mockito = MockitoJUnit.rule();

    private SecureLockDeviceService mSecureLockDeviceService;
    private int mDisableFlags;
    private int mDisable2Flags;
    private SecureLockDeviceStore mSecureLockDeviceStore;
    private SecureLockDeviceSettingsManager mSecureLockDeviceSettingsManager;
    private Set<String> mDevicePolicyRestrictions = new HashSet<>();
    private IBiometricEnabledOnKeyguardCallback mBiometricEnabledOnKeyguardCallback;

    @SuppressLint("VisibleForTests")
    @Before
    public void setUp() throws Exception {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        // Mock user-aware BiometricManager retrieval
        mTestContext.mockBiometricManagerForUser(mUser, mUserBiometricManager);
        mTestContext.mockBiometricManagerForUser(mOtherUser, mOtherUserBiometricManager);

        // Mock system services
        mTestContext.addMockSystemService(ActivityManager.class, mActivityManager);
        mTestContext.addMockSystemService(PowerManager.class,
                new PowerManager(mTestContext, mIPowerManager, mThermalService, null));
        mTestContext.addMockSystemService(BiometricManager.class, mBiometricManager);
        mTestContext.addMockSystemService(DevicePolicyManager.class, mDevicePolicyManager);
        mTestContext.addMockSystemService((FaceManager.class), mFaceManager);
        mTestContext.addMockSystemService((FingerprintManager.class), mFingerprintManager);
        disableSceneContainer();

        when(mActivityManager.isProfileForeground(eq(mUser))).thenReturn(true);
        doAnswer(
                invocation -> {
                    String restriction = invocation.getArgument(1);
                    mDevicePolicyRestrictions.add(restriction);
                    return null;
                })
                .when(mDevicePolicyManager).addUserRestrictionGlobally(anyString(), anyString());

        doAnswer(
                invocation -> {
                    String restriction = invocation.getArgument(1);
                    mDevicePolicyRestrictions.remove(restriction);
                    return null;
                })
                .when(mDevicePolicyManager).clearUserRestrictionGlobally(anyString(), anyString());
        when(mDevicePolicyManager.getUserRestrictionsGlobally())
                .thenReturn(setToBundle(mDevicePolicyRestrictions));

        when(mSecureLockDeviceStatusListener.asBinder())
                .thenReturn(mSecureLockDeviceStatusListenerBinder);
        when(mSecureLockDeviceStatusOtherListener.asBinder())
                .thenReturn(mSecureLockDeviceStatusOtherListenerBinder);

        mLocalServiceKeeperRule.overrideLocalService(LockSettingsInternal.class,
                mLockSettingsInternal);
        mLocalServiceKeeperRule.overrideLocalService(SecureLockDeviceServiceInternal.class,
                mSecureLockDeviceServiceInternal);
        mLocalServiceKeeperRule.overrideLocalService(UserManagerInternal.class,
                mUserManagerInternal);
        mLocalServiceKeeperRule.overrideLocalService(WindowManagerInternal.class,
                mWindowManagerInternal);
        when(mUserManagerInternal.getUserIds()).thenReturn(USER_IDS);

        doAnswer(
                invocation -> {
                    mDisableFlags = invocation.getArgument(0);
                    return null;
                })
                .when(mStatusBarService)
                .disableForUser(anyInt(), any(), anyString(), anyInt());
        doAnswer(
                invocation -> {
                    mDisable2Flags = invocation.getArgument(0);
                    return null;
                })
                .when(mStatusBarService)
                .disable2ForUser(anyInt(), any(), anyString(), anyInt());
        when(mStatusBarService.getDisableFlags(any(), anyInt())).thenReturn(
                new int[] {mDisableFlags, mDisable2Flags});
        when(mStrongAuthTracker.getStub()).thenReturn(mock(IStrongAuthTracker.Stub.class));

        mSecureLockDeviceSettingsManager = new SecureLockDeviceSettingsManagerImpl(
                mTestContext,
                mActivityTaskManager,
                mStatusBarService,
                mVoiceInteractionManagerService
        );
        mSecureLockDeviceSettingsManager.initSettingsControllerDependencies(
                mDevicePolicyManager, mUsbManager, mUsbManagerInternal);

        mSecureLockDeviceService =
                new SecureLockDeviceService(
                        mTestContext,
                        mSecureLockDeviceSettingsManager,
                        mBiometricManager,
                        mFaceManager,
                        mFingerprintManager,
                        new PowerManager(mTestContext, mIPowerManager, mThermalService, null),
                        mUserManagerInternal
                );

        ArgumentCaptor<IBiometricEnabledOnKeyguardCallback>
                biometricEnabledOnKeyguardCallbackCaptor = ArgumentCaptor.forClass(
                        IBiometricEnabledOnKeyguardCallback.class);
        verify(mBiometricManager).registerEnabledOnKeyguardCallback(
                biometricEnabledOnKeyguardCallbackCaptor.capture());

        mBiometricEnabledOnKeyguardCallback = biometricEnabledOnKeyguardCallbackCaptor.getValue();

        File secureLockDeviceStateFile =
                File.createTempFile(FILE_NAME, ".xml", mTestContext.getCacheDir());
        mSecureLockDeviceStore = mSecureLockDeviceService.getStore();
        mSecureLockDeviceStore.overrideStateFile(secureLockDeviceStateFile);

        mSecureLockDeviceService.setSecureLockDeviceTestStatus(true);

        try {
            Field strongAuthTracker =
                    SecureLockDeviceService.class.getDeclaredField("mStrongAuthTracker");
            strongAuthTracker.setAccessible(true);
            strongAuthTracker.set(mSecureLockDeviceService, mStrongAuthTracker);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to inject mock StrongAuthTracker via reflection", e);
        }

        try {
            Field lockPatternUtils =
                    SecureLockDeviceService.class.getDeclaredField("mLockPatternUtils");
            lockPatternUtils.setAccessible(true);
            lockPatternUtils.set(mSecureLockDeviceService, mLockPatternUtils);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to inject mock LockPatternUtils via reflection", e);
        }

        mSecureLockDeviceService.onLockSettingsReady();
        mSecureLockDeviceService.onBootCompleted();
    }

    @SuppressLint("VisibleForTests")
    @After
    public void tearDown() throws Exception {
        disableSecureLockDevice(mUser);
        disableSecureLockDevice(mOtherUser);
        mSecureLockDeviceService.setSecureLockDeviceTestStatus(false);
    }

    @Test
    public void enableSecureLockDevice_goesToSleep_locksDevice() throws RemoteException {
        setupBiometricState(
                true /* deviceHasStrongBiometricSensor */,
                true /* primaryUserHasStrongBiometricEnrollment */,
                false /* otherUserHasStrongBiometricEnrollment */,
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );

        enableSecureLockDevice(mUser);

        verify(mIPowerManager).goToSleep(anyLong(), anyInt(), anyInt());
        verify(mWindowManagerInternal).lockNow();
    }

    @Test
    public void disableSecureLockDevice_asUnauthorizedUser_returnsNotAuthorized()
            throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        assertThat(enableSecureLockDevice(mUser)).isEqualTo(SUCCESS);

        assertThat(mSecureLockDeviceService.isSecureLockDeviceEnabled()).isTrue();
        assertThat(mSecureLockDeviceStore.retrieveSecureLockDeviceClientId()).isEqualTo(
                TEST_USER_ID);

        assertThat(disableSecureLockDevice(mOtherUser)).isEqualTo(ERROR_NOT_AUTHORIZED);
        assertThat(mSecureLockDeviceService.isSecureLockDeviceEnabled()).isTrue();
        assertThat(mSecureLockDeviceStore.retrieveSecureLockDeviceClientId()).isEqualTo(
                TEST_USER_ID);
    }

    @Test
    public void disableSecureLockDevice_asAuthorizedUser_returnsSuccess() throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        assertThat(enableSecureLockDevice(mUser)).isEqualTo(SUCCESS);

        assertThat(mSecureLockDeviceService.isSecureLockDeviceEnabled()).isTrue();
        assertThat(mSecureLockDeviceStore.retrieveSecureLockDeviceClientId()).isEqualTo(
                TEST_USER_ID);

        int disableResult = disableSecureLockDevice(mUser);
        assertThat(disableResult).isEqualTo(SUCCESS);

        assertThat(mSecureLockDeviceService.isSecureLockDeviceEnabled()).isFalse();
        assertThat(mSecureLockDeviceStore.retrieveSecureLockDeviceClientId())
                .isEqualTo(UserHandle.USER_NULL);
    }

    @Test
    public void enableSecureLockDeviceReturnsError_whenAlreadyEnabled() throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        enableSecureLockDevice(mUser);

        boolean isSecureLockDeviceEnabled = mSecureLockDeviceService.isSecureLockDeviceEnabled();
        int enableSecureLockDeviceRequestStatus = enableSecureLockDevice(mUser);

        assertThat(isSecureLockDeviceEnabled).isTrue();
        assertThat(enableSecureLockDeviceRequestStatus).isEqualTo(ERROR_ALREADY_ENABLED);
    }

    @Test
    public void enableSecureLockDevice_userSwitchFails_returnsErrorUnknown()
            throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        when(mActivityManager.isProfileForeground(eq(mUser))).thenReturn(false);
        when(mActivityManager.switchUser(eq(TEST_USER_ID))).thenReturn(false);
        assertThat(enableSecureLockDevice(mUser)).isEqualTo(ERROR_UNKNOWN);
    }

    @Test
    public void testEnableAndDisableSecureLockDevice_setsAuthFlags() throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        enableSecureLockDevice(mUser);

        for (int userId : USER_IDS) {
            verify(mLockPatternUtils).requireStrongAuth(
                    eq(PRIMARY_AUTH_REQUIRED_FOR_SECURE_LOCK_DEVICE), eq(userId));
            verify(mLockPatternUtils).requireStrongAuth(
                    eq(STRONG_BIOMETRIC_AUTH_REQUIRED_FOR_SECURE_LOCK_DEVICE), eq(userId));
        }

        disableSecureLockDevice(mUser);

        verify(mLockSettingsInternal).disableSecureLockDevice(eq(TEST_USER_ID), anyBoolean());
    }

    @Test
    public void enableSecureLockDeviceForOtherUser_secureLockDeviceUnavailable()
            throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                false, /* primaryUserHasStrongBiometricEnrollment */
                true, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );

        // Mock mUser as current user, successful switch to mOtherUser
        when(mActivityManager.isProfileForeground(eq(mOtherUser))).thenReturn(false)
                .thenReturn(true);
        when(mActivityManager.switchUser(eq(mOtherUser))).thenReturn(true);

        assertThat(enableSecureLockDevice(mOtherUser)).isEqualTo(ERROR_UNSUPPORTED);

        assertThat(mSecureLockDeviceService.isSecureLockDeviceEnabled()).isFalse();
        assertThat(mSecureLockDeviceStore.retrieveSecureLockDeviceClientId()).isEqualTo(
                UserHandle.USER_NULL);
    }

    @Test
    public void secureLockDevice_checksBiometricEnrollmentsOfCallingUser() throws RemoteException {
        // Calling user TEST_USER_ID has no biometrics enrolled, but some other user on the
        // device has strong biometrics enrolled
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                false, /* primaryUserHasStrongBiometricEnrollment */
                true, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );

        int secureLockDeviceAvailability =
                mSecureLockDeviceService.getSecureLockDeviceAvailability(mUser);
        int enableSecureLockDeviceRequestStatus = enableSecureLockDevice(mUser);

        assertThat(secureLockDeviceAvailability).isEqualTo(ERROR_NO_BIOMETRICS_ENROLLED);
        assertThat(enableSecureLockDeviceRequestStatus).isEqualTo(ERROR_NO_BIOMETRICS_ENROLLED);
    }

    @Test
    public void secureLockDeviceUnavailable_whenNoStrongBiometricSensors() throws RemoteException {
        setupBiometricState(
                false, /* deviceHasStrongBiometricSensor */
                false, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );

        int secureLockDeviceAvailability =
                mSecureLockDeviceService.getSecureLockDeviceAvailability(mUser);
        int enableSecureLockDeviceRequestStatus = enableSecureLockDevice(mUser);

        assertThat(secureLockDeviceAvailability).isEqualTo(ERROR_INSUFFICIENT_BIOMETRICS);
        assertThat(enableSecureLockDeviceRequestStatus).isEqualTo(ERROR_INSUFFICIENT_BIOMETRICS);
    }

    @Test
    public void getSecureLockDeviceAvailability_whenMissingStrongBiometricEnrollments()
            throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                false, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        int secureLockDeviceAvailability =
                mSecureLockDeviceService.getSecureLockDeviceAvailability(mUser);
        int enableSecureLockDeviceRequestStatus = enableSecureLockDevice(mUser);

        assertThat(secureLockDeviceAvailability).isEqualTo(ERROR_NO_BIOMETRICS_ENROLLED);
        assertThat(enableSecureLockDeviceRequestStatus).isEqualTo(ERROR_NO_BIOMETRICS_ENROLLED);
    }

    @Test
    public void getSecureLockDeviceAvailability_whenStrongBiometricsNotEnabledOnKeyguard()
            throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                false /* primaryUserHasEnabledBiometricsOnKeyguard */,
                false /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        int secureLockDeviceAvailability =
                mSecureLockDeviceService.getSecureLockDeviceAvailability(mUser);
        int enableSecureLockDeviceRequestStatus = enableSecureLockDevice(mUser);

        assertThat(secureLockDeviceAvailability).isEqualTo(ERROR_NO_BIOMETRICS_ENROLLED);
        assertThat(enableSecureLockDeviceRequestStatus).isEqualTo(ERROR_NO_BIOMETRICS_ENROLLED);
    }

    @Test
    public void getSecureLockDeviceAvailability_whenSceneContainerEnabled() throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                false /* primaryUserHasEnabledBiometricsOnKeyguard */,
                false /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        enableSceneContainer();
        int secureLockDeviceAvailability =
                mSecureLockDeviceService.getSecureLockDeviceAvailability(mUser);
        int enableSecureLockDeviceRequestStatus = enableSecureLockDevice(mUser);

        assertThat(secureLockDeviceAvailability).isEqualTo(ERROR_UNSUPPORTED);
        assertThat(enableSecureLockDeviceRequestStatus).isEqualTo(ERROR_UNSUPPORTED);
    }

    @Test
    public void notifySecureLockDeviceAvailabilityStatusChanged_whenStrongBiometricsNotEnabledOnKeyguard()
            throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                false /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        mSecureLockDeviceService.registerSecureLockDeviceStatusListener(
                mUser, mSecureLockDeviceStatusListener);

        mBiometricEnabledOnKeyguardCallback.onChanged(false /* enabled */, TEST_USER_ID,
                BiometricManager.TYPE_FINGERPRINT);

        verify(mSecureLockDeviceStatusListener).onSecureLockDeviceAvailableStatusChanged(
                ERROR_NO_BIOMETRICS_ENROLLED);
    }

    @Test
    public void getSecureLockDeviceAvailability_success() throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                false /* otherUserHasEnabledBiometricsOnKeyguard */
        );

        int secureLockDeviceAvailability =
                mSecureLockDeviceService.getSecureLockDeviceAvailability(mUser);
        int enableSecureLockDeviceRequestStatus = enableSecureLockDevice(mUser);

        assertThat(secureLockDeviceAvailability).isEqualTo(SUCCESS);
        assertThat(enableSecureLockDeviceRequestStatus).isEqualTo(SUCCESS);
    }

    @Test
    public void isSecureLockDeviceEnabled_updatesState() throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );

        boolean isSecureLockDeviceEnabled = mSecureLockDeviceService.isSecureLockDeviceEnabled();
        assertThat(isSecureLockDeviceEnabled).isFalse();

        enableSecureLockDevice(mUser);
        isSecureLockDeviceEnabled = mSecureLockDeviceService.isSecureLockDeviceEnabled();

        assertThat(isSecureLockDeviceEnabled).isTrue();

        disableSecureLockDevice(mUser);
        isSecureLockDeviceEnabled = mSecureLockDeviceService.isSecureLockDeviceEnabled();

        assertThat(isSecureLockDeviceEnabled).isFalse();
    }

    @Test
    public void hasUserCompletedTwoFactorAuthentication_whenStrongAuthRequiredChangedForOtherUser()
            throws RemoteException {
        when(mUserManagerInternal.getProfileParentId(TEST_USER_ID)).thenReturn(TEST_USER_ID);
        when(mUserManagerInternal.getProfileParentId(OTHER_USER_ID)).thenReturn(OTHER_USER_ID);

        final SecureLockDeviceService.StrongAuthTracker strongAuthTracker = spy(
                mSecureLockDeviceService.new StrongAuthTracker(mContext, Looper.getMainLooper()));

        when(strongAuthTracker.getStrongAuthForUser(anyInt()))
                .thenReturn(PRIMARY_AUTH_REQUIRED_FOR_SECURE_LOCK_DEVICE);

        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                true, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        enableSecureLockDevice(mUser);
        mSecureLockDeviceService.onStrongBiometricAuthenticationSuccess(mUser);

        assertThat(mSecureLockDeviceService.hasUserCompletedTwoFactorAuthentication(mUser))
                .isTrue();

        strongAuthTracker.onStrongAuthRequiredChanged(OTHER_USER_ID);

        assertThat(mSecureLockDeviceService.hasUserCompletedTwoFactorAuthentication(mUser))
                .isTrue();
    }

    @Test
    public void hasUserCompletedTwoFactorAuthentication_whenStrongAuthRequiredChangedForTestUser()
            throws RemoteException {
        when(mUserManagerInternal.getProfileParentId(TEST_USER_ID)).thenReturn(TEST_USER_ID);
        when(mUserManagerInternal.getProfileParentId(OTHER_USER_ID)).thenReturn(OTHER_USER_ID);

        final SecureLockDeviceService.StrongAuthTracker strongAuthTracker = spy(
                mSecureLockDeviceService.new StrongAuthTracker(mContext, Looper.getMainLooper()));

        when(strongAuthTracker.getStrongAuthForUser(anyInt()))
                .thenReturn(PRIMARY_AUTH_REQUIRED_FOR_SECURE_LOCK_DEVICE);

        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                true, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        enableSecureLockDevice(mUser);
        mSecureLockDeviceService.onStrongBiometricAuthenticationSuccess(mUser);

        assertThat(mSecureLockDeviceService.hasUserCompletedTwoFactorAuthentication(mUser))
                .isTrue();

        strongAuthTracker.onStrongAuthRequiredChanged(TEST_USER_ID);

        assertThat(mSecureLockDeviceService.hasUserCompletedTwoFactorAuthentication(mUser))
                .isFalse();
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    public void enableSecureLockDevice_appliesSecurityFeatures_writesSettingsToFile()
            throws RemoteException, IOException {
        final String tag = "SecureLockDeviceService";

        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        enableSecureLockDevice(mUser);

        SYSTEM_SETTINGS_SECURE_LOCK_DEVICE_VALUES.forEach((settingKey, secureLockDeviceValue) -> {
            try {
                int currentValue = Settings.System.getIntForUser(mTestContext.getContentResolver(),
                        settingKey, TEST_USER_ID);
                assertThat(currentValue).isEqualTo(secureLockDeviceValue);
            } catch (Settings.SettingNotFoundException e) {
                Slog.w(TAG, "System setting not found: ", e);
            }
        });

        SECURE_SETTINGS_SECURE_LOCK_DEVICE_VALUES.forEach((settingKey, secureLockDeviceValue) -> {
            try {
                int currentValue = Settings.Secure.getIntForUser(mTestContext.getContentResolver(),
                        settingKey, TEST_USER_ID);
                assertThat(currentValue).isEqualTo(secureLockDeviceValue);
            } catch (Settings.SettingNotFoundException e) {
                Slog.w(TAG, "Secure setting not found: ", e);
            }
        });

        verify(mActivityTaskManager).stopSystemLockTaskMode();
        verify(mStatusBarService).disableForUser(eq(DISABLE_FLAGS), any(), anyString(), anyInt());
        verify(mStatusBarService).disable2ForUser(eq(DISABLE2_FLAGS), any(), anyString(), anyInt());
        verify(mVoiceInteractionManagerService).setDisabled(eq(true));

        DEVICE_POLICY_RESTRICTIONS.forEach(setting -> {
            verify(mDevicePolicyManager).addUserRestrictionGlobally(
                    eq(DevicePolicyRestrictionsController.TAG), eq(setting));
        });

        // Read the contents of the file
        String fileContents = readFileContents(mSecureLockDeviceStore.getStateFile().getBaseFile());

        // Assert that the file contains the correct settings in XML format
        assertThat(fileContents).contains(XML_TAG_ROOT);
        assertThat(fileContents).contains(XML_TAG_ENABLED);
        assertThat(fileContents).contains(XML_TAG_CLIENT_ID);
        assertThat(fileContents).contains(XML_TAG_ORIGINAL_SETTINGS);
        assertThat(fileContents).contains(DEVICE_POLICY_RESTRICTIONS_KEY);
        assertThat(fileContents).contains(DISABLE_FLAGS_KEY);
        assertThat(fileContents).contains(NFC_ENABLED_KEY);
        assertThat(fileContents).contains(USB_ENABLED_KEY);

        // Assert that each setting is in the XML
        SECURE_SETTINGS_SECURE_LOCK_DEVICE_VALUES.forEach((settingsKey, secureLockDeviceValue) -> {
            assertThat(fileContents).contains(settingsKey);
        });
        SYSTEM_SETTINGS_SECURE_LOCK_DEVICE_VALUES.forEach((settingsKey, secureLockDeviceValue) -> {
            assertThat(fileContents).contains(settingsKey);
        });
        GLOBAL_SETTINGS.forEach(settingsKey -> {
            assertThat(fileContents).contains(settingsKey);
        });
    }

    @Test
    public void disableSecureLockDevice_restoresSettingsToOriginalValues() throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        when(mUserManagerInternal.isUserUnlocked(TEST_USER_ID)).thenReturn(true);
        final Map<String, Integer> originalSystemSettings = new HashMap<>();
        SYSTEM_SETTINGS_SECURE_LOCK_DEVICE_VALUES.forEach((settingKey, secureLockDeviceValue) -> {
            try {
                int currentValue = Settings.System.getIntForUser(mTestContext.getContentResolver(),
                        settingKey, TEST_USER_ID);
                originalSystemSettings.put(settingKey, currentValue);
            } catch (Settings.SettingNotFoundException e) {
                Slog.w(TAG, "System setting not found: ", e);
            }
        });

        final Map<String, Integer> originalSecureSettings = new HashMap<>();
        SECURE_SETTINGS_SECURE_LOCK_DEVICE_VALUES.forEach((settingKey, secureLockDeviceValue) -> {
            try {
                int currentValue = Settings.Secure.getIntForUser(mTestContext.getContentResolver(),
                        settingKey, TEST_USER_ID);
                originalSecureSettings.put(settingKey, currentValue);
            } catch (Settings.SettingNotFoundException e) {
                Slog.w(TAG, "Secure setting not found: ", e);
            }
        });

        int[] originalStatusBarState =
                mStatusBarService.getDisableFlags(new Binder(), TEST_USER_ID);
        Bundle originalDevicePolicyRestrictions =
                mDevicePolicyManager.getUserRestrictionsGlobally();

        enableSecureLockDevice(mUser);
        clearInvocations(mStatusBarService, mActivityTaskManager, mDevicePolicyManager);
        disableSecureLockDevice(mUser);

        originalSystemSettings.forEach((settingKey, originalValue) -> {
            try {
                int currentValue = Settings.System.getIntForUser(mTestContext.getContentResolver(),
                        settingKey, TEST_USER_ID);
                assertThat(currentValue).isEqualTo(originalValue);
            } catch (Exception e) {
                Slog.w(TAG, "System setting not found: ", e);
            }
        });

        originalSecureSettings.forEach((settingKey, originalValue) -> {
            try {
                int currentValue = Settings.Secure.getIntForUser(mTestContext.getContentResolver(),
                        settingKey, TEST_USER_ID);
                assertThat(currentValue).isEqualTo(originalValue);
            } catch (Exception e) {
                Slog.w(TAG, "Secure setting not found: ", e);
            }
        });
        assertThat(mStatusBarService.getDisableFlags(new Binder(), TEST_USER_ID))
                .isEqualTo(originalStatusBarState);
        assertThat(mDevicePolicyManager.getUserRestrictionsGlobally()).isEqualTo(
                originalDevicePolicyRestrictions);
    }

    @Test
    public void testAllListenersNotified_onEnableSecureLockDevice() throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        mSecureLockDeviceService.registerSecureLockDeviceStatusListener(
                mUser, mSecureLockDeviceStatusListener);
        mSecureLockDeviceService.registerSecureLockDeviceStatusListener(
                mOtherUser, mSecureLockDeviceStatusOtherListener);
        clearInvocations(mSecureLockDeviceStatusListener);
        clearInvocations(mSecureLockDeviceStatusOtherListener);

        enableSecureLockDevice(mUser);

        // Verify listener registered from TEST_USER_ID is notified
        verify(mSecureLockDeviceStatusListener).onSecureLockDeviceAvailableStatusChanged(
                mSecureLockDeviceAvailableStatusArgumentCaptor.capture());
        verify(mSecureLockDeviceStatusListener).onSecureLockDeviceEnabledStatusChanged(
                mSecureLockDeviceEnabledStatusArgumentCaptor.capture());
        int available = mSecureLockDeviceAvailableStatusArgumentCaptor.getValue();
        boolean enabled = mSecureLockDeviceEnabledStatusArgumentCaptor.getValue();
        assertThat(available).isEqualTo(SUCCESS);
        assertThat(enabled).isTrue();

        // Verify listener registered from OTHER_USER_ID is notified
        verify(mSecureLockDeviceStatusOtherListener).onSecureLockDeviceAvailableStatusChanged(
                mSecureLockDeviceAvailableStatusArgumentCaptor.capture());
        verify(mSecureLockDeviceStatusOtherListener).onSecureLockDeviceEnabledStatusChanged(
                mSecureLockDeviceEnabledStatusArgumentCaptor.capture());
        available = mSecureLockDeviceAvailableStatusArgumentCaptor.getValue();
        enabled = mSecureLockDeviceEnabledStatusArgumentCaptor.getValue();
        assertThat(available).isEqualTo(ERROR_UNSUPPORTED);
        assertThat(enabled).isTrue();
    }

    @Test
    public void testAllListenersNotified_onDisableSecureLockDevice() throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        mSecureLockDeviceService.registerSecureLockDeviceStatusListener(
                mUser, mSecureLockDeviceStatusListener);
        mSecureLockDeviceService.registerSecureLockDeviceStatusListener(
                mOtherUser, mSecureLockDeviceStatusOtherListener);
        enableSecureLockDevice(mUser);
        clearInvocations(mSecureLockDeviceStatusListener);
        clearInvocations(mSecureLockDeviceStatusOtherListener);

        disableSecureLockDevice(mUser);

        // Verify listener registered from TEST_USER_ID is notified
        verify(mSecureLockDeviceStatusListener).onSecureLockDeviceAvailableStatusChanged(
                mSecureLockDeviceAvailableStatusArgumentCaptor.capture());
        verify(mSecureLockDeviceStatusListener).onSecureLockDeviceEnabledStatusChanged(
                mSecureLockDeviceEnabledStatusArgumentCaptor.capture());
        int available = mSecureLockDeviceAvailableStatusArgumentCaptor.getValue();
        boolean enabled = mSecureLockDeviceEnabledStatusArgumentCaptor.getValue();
        assertThat(available).isEqualTo(SUCCESS);
        assertThat(enabled).isFalse();

        // Verify listener registered from OTHER_USER_ID is notified
        verify(mSecureLockDeviceStatusOtherListener).onSecureLockDeviceAvailableStatusChanged(
                mSecureLockDeviceAvailableStatusArgumentCaptor.capture());
        verify(mSecureLockDeviceStatusOtherListener).onSecureLockDeviceEnabledStatusChanged(
                mSecureLockDeviceEnabledStatusArgumentCaptor.capture());
        available = mSecureLockDeviceAvailableStatusArgumentCaptor.getValue();
        enabled = mSecureLockDeviceEnabledStatusArgumentCaptor.getValue();

        assertThat(available).isEqualTo(ERROR_UNSUPPORTED);
        assertThat(enabled).isFalse();
    }

    @Test
    public void testRelevantListenerNotified_onBiometricEnrollmentAdded() throws RemoteException {
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                false, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        mSecureLockDeviceService.registerSecureLockDeviceStatusListener(
                mUser, mSecureLockDeviceStatusListener);
        mSecureLockDeviceService.registerSecureLockDeviceStatusListener(
                mOtherUser, mSecureLockDeviceStatusOtherListener);
        verify(mFingerprintManager).registerBiometricStateListener(
                mBiometricStateListenerCaptor.capture());
        BiometricStateListener biometricStateListener = mBiometricStateListenerCaptor.getValue();
        clearInvocations(mSecureLockDeviceStatusListener);
        clearInvocations(mSecureLockDeviceStatusOtherListener);

        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        clearInvocations(mSecureLockDeviceStatusListener);
        biometricStateListener.onEnrollmentsChanged(
                TEST_USER_ID, 1 /* sensorId */, true /* hasEnrollments */
        );

        // Verify user that enrolled is notified
        verify(mSecureLockDeviceStatusListener).onSecureLockDeviceAvailableStatusChanged(
                mSecureLockDeviceAvailableStatusArgumentCaptor.capture());
        int available = mSecureLockDeviceAvailableStatusArgumentCaptor.getValue();
        assertThat(available).isEqualTo(SUCCESS);

        // Verify other user is not notified
        verify(mSecureLockDeviceStatusOtherListener, never())
                .onSecureLockDeviceAvailableStatusChanged(anyInt());
    }

    @Test
    public void testRelevantListenerNotified_onBiometricEnrollmentRemoved() throws RemoteException {
        // Add enrollments
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                true, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        mSecureLockDeviceService.registerSecureLockDeviceStatusListener(
                mUser, mSecureLockDeviceStatusListener);
        mSecureLockDeviceService.registerSecureLockDeviceStatusListener(
                mOtherUser, mSecureLockDeviceStatusOtherListener);
        verify(mFingerprintManager).registerBiometricStateListener(
                mBiometricStateListenerCaptor.capture());
        BiometricStateListener biometricStateListener = mBiometricStateListenerCaptor.getValue();

        // Remove enrollments
        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                false, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        clearInvocations(mSecureLockDeviceStatusListener);
        clearInvocations(mSecureLockDeviceStatusOtherListener);

        setupBiometricState(
                true, /* deviceHasStrongBiometricSensor */
                false, /* primaryUserHasStrongBiometricEnrollment */
                false, /* otherUserHasStrongBiometricEnrollment */
                true /* primaryUserHasEnabledBiometricsOnKeyguard */,
                true /* otherUserHasEnabledBiometricsOnKeyguard */
        );
        biometricStateListener.onEnrollmentsChanged(
                TEST_USER_ID, 1 /* sensorId */, false /* hasEnrollments */
        );

        // Verify user that enrolled is notified
        verify(mSecureLockDeviceStatusListener).onSecureLockDeviceAvailableStatusChanged(
                mSecureLockDeviceAvailableStatusArgumentCaptor.capture());
        int available = mSecureLockDeviceAvailableStatusArgumentCaptor.getValue();
        assertThat(available).isEqualTo(ERROR_NO_BIOMETRICS_ENROLLED);

        // Verify other user is not notified
        verify(mSecureLockDeviceStatusOtherListener, never())
                .onSecureLockDeviceAvailableStatusChanged(anyInt());
    }

    private String readFileContents(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        fis.read(data);
        fis.close();
        return new String(data, StandardCharsets.UTF_8);
    }

    private void setupBiometricState(
            boolean deviceHasStrongBiometricSensor,
            boolean primaryUserHasStrongBiometricEnrollment,
            boolean otherUserHasStrongBiometricEnrollment,
            boolean primaryUserHasEnabledBiometricsOnKeyguard,
            boolean otherUserHasEnabledBiometricsOnKeyguard
    ) throws RemoteException {
        if (deviceHasStrongBiometricSensor) {
            when(mBiometricManager.getSensorProperties()).thenReturn(
                    getSensorPropertiesList(STRENGTH_STRONG));
        } else {
            when(mBiometricManager.getSensorProperties()).thenReturn(
                    getSensorPropertiesList(STRENGTH_WEAK));
        }

        if (primaryUserHasStrongBiometricEnrollment) {
            when(mUserBiometricManager.getEnrollmentStatus()).thenReturn(
                    getEnrollmentStatusMap(BIOMETRIC_STRONG));
            if (primaryUserHasEnabledBiometricsOnKeyguard) {
                enableBiometricsOnKeyguardForTestUser();
            }
        } else {
            when(mUserBiometricManager.getEnrollmentStatus()).thenReturn(
                    getEnrollmentStatusMap(BIOMETRIC_WEAK));
        }

        if (otherUserHasStrongBiometricEnrollment) {
            when(mOtherUserBiometricManager.getEnrollmentStatus()).thenReturn(
                    getEnrollmentStatusMap(BIOMETRIC_STRONG));
            if (otherUserHasEnabledBiometricsOnKeyguard) {
                enableBiometricsOnKeyguardForOtherUser();
            }
        } else {
            when(mOtherUserBiometricManager.getEnrollmentStatus()).thenReturn(
                    getEnrollmentStatusMap(BIOMETRIC_WEAK));
        }
    }

    private void enableBiometricsOnKeyguardForTestUser() throws RemoteException {
        mBiometricEnabledOnKeyguardCallback.onChanged(true /* enabled */, TEST_USER_ID,
                BiometricManager.TYPE_FINGERPRINT);
    }

    private void enableBiometricsOnKeyguardForOtherUser() throws RemoteException {
        mBiometricEnabledOnKeyguardCallback.onChanged(true /* enabled */, OTHER_USER_ID,
                BiometricManager.TYPE_FINGERPRINT);
    }

    private static void waitForIdle() {
        androidx.test.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private List<SensorProperties> getSensorPropertiesList(
            @SensorProperties.Strength int strength
    ) {
        return List.of(new SensorProperties(0, strength, List.of()));
    }

    private Map<Integer, BiometricEnrollmentStatus> getEnrollmentStatusMap(int sensorStrength) {
        Map<Integer, BiometricEnrollmentStatus> enrollmentStatusMap = new HashMap<>();
        enrollmentStatusMap.put(BiometricManager.TYPE_FINGERPRINT, new BiometricEnrollmentStatus(
                sensorStrength, 1
        ));

        return enrollmentStatusMap;
    }

    private int enableSecureLockDevice(UserHandle user) {
        return mSecureLockDeviceService.enableSecureLockDevice(user, mEnableParams);
    }

    private int disableSecureLockDevice(UserHandle user) {
        return mSecureLockDeviceService.disableSecureLockDevice(user, mDisableParams);
    }

    private Bundle setToBundle(Set<String> restrictions) {
        Bundle bundle = new Bundle();
        for (String restriction : restrictions) {
            bundle.putBoolean(restriction, true);
        }
        return bundle;
    }

    private void enableSceneContainer() {
        Settings.Global.putInt(mTestContext.getContentResolver(),
                Settings.Global.SCENE_CONTAINER_ENABLED, 1);
    }

    private void disableSceneContainer() {
        Settings.Global.putInt(mTestContext.getContentResolver(),
                Settings.Global.SCENE_CONTAINER_ENABLED, 0);
    }

    private class SecureLockDeviceContext extends TestableContext {
        private final Context mContext =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        @Rule public final TestableContext mUserContext = new TestableContext(mContext, null);
        @Rule public final TestableContext mOtherUserContext = new TestableContext(mContext, null);
        private final ArrayMap<UserHandle, TestableContext> mMockUserContexts = new ArrayMap<>();

        SecureLockDeviceContext(Context baseContext, UserHandle primaryUser, UserHandle otherUser) {
            super(baseContext);
            mMockUserContexts.put(primaryUser, mUserContext);
            mMockUserContexts.put(otherUser, mOtherUserContext);
        }

        @NonNull
        @Override
        public Context createContextAsUser(UserHandle user, int flags) {
            return mMockUserContexts.get(user);
        }

        public void mockBiometricManagerForUser(UserHandle user,
                BiometricManager biometricManager) {
            mMockUserContexts.get(user).addMockSystemService(BiometricManager.class,
                    biometricManager);
        }
    }
}