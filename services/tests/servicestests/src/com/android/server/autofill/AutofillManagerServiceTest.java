/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.autofill;

import static com.android.server.autofill.AutofillManagerService.getAllowedCompatModePackages;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Build;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RunWith(JUnit4.class)
public class AutofillManagerServiceTest {

    private static final int USER_ID = 42;
    private static final String RESET_INVALID_SERVICE_ON_DEVICE_PROVISIONED_FLAG =
            "autofill_reset_invalid_service_on_device_provisioned";

    @Mock private Context mContext;
    private MockContentResolver mContentResolver;
    private @Mock PackageManager mPackageManager;
    private @Mock Resources mResources;
    @Mock private UserManagerInternal mMockUserManagerInternal;
    private String mOriginalResetInvalidServiceOnDeviceProvisioned;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        LocalServices.addService(UserManagerInternal.class, mMockUserManagerInternal);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
        when(mContext.getApplicationInfo()).thenReturn(applicationInfo);
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        mContentResolver = new MockContentResolver(mContext);
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mResources.getString(anyInt())).thenReturn("autofill");
        List<UserInfo> users = new ArrayList<>();
        users.add(new UserInfo(10, "user10", UserInfo.FLAG_FULL));
        users.add(new UserInfo(12, "user12", UserInfo.FLAG_FULL));
        when(mMockUserManagerInternal.getUserInfos()).thenReturn(users.toArray(new UserInfo[0]));
        mOriginalResetInvalidServiceOnDeviceProvisioned = DeviceConfig.getProperty(
                DeviceConfig.NAMESPACE_AUTOFILL,
                RESET_INVALID_SERVICE_ON_DEVICE_PROVISIONED_FLAG);
    }

    @After
    public void teardown() {
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .adoptShellPermissionIdentity();
        try {
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_AUTOFILL,
                    RESET_INVALID_SERVICE_ON_DEVICE_PROVISIONED_FLAG,
                    mOriginalResetInvalidServiceOnDeviceProvisioned, false);
        } finally {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    public void testGetAllowedCompatModePackages_null() {
        assertThat(getAllowedCompatModePackages(null)).isNull();
    }

    @Test
    public void testGetAllowedCompatModePackages_empty() {
        assertThat(getAllowedCompatModePackages("")).isNull();
    }

    @Test
    public void testGetAllowedCompatModePackages_onePackageNoUrls() {
        assertThat(getAllowedCompatModePackages("one_is_the_loniest_package"))
                .containsExactly("one_is_the_loniest_package", null);
    }

    @Test
    public void testGetAllowedCompatModePackages_onePackageMissingEndDelimiter() {
        assertThat(getAllowedCompatModePackages("one_is_the_loniest_package[")).isEmpty();
    }

    @Test
    public void testGetAllowedCompatModePackages_onePackageOneUrl() {
        final Map<String, String[]> result =
                getAllowedCompatModePackages("one_is_the_loniest_package[url]");
        assertThat(result).hasSize(1);
        assertThat(result.get("one_is_the_loniest_package")).asList().containsExactly("url");
    }

    @Test
    public void testGetAllowedCompatModePackages_onePackageMultipleUrls() {
        final Map<String, String[]> result =
                getAllowedCompatModePackages("one_is_the_loniest_package[4,5,8,15,16,23,42]");
        assertThat(result).hasSize(1);
        assertThat(result.get("one_is_the_loniest_package")).asList()
            .containsExactly("4", "5", "8", "15", "16", "23", "42");
    }

    @Test
    public void testGetAllowedCompatModePackages_multiplePackagesOneInvalid() {
        final Map<String, String[]> result = getAllowedCompatModePackages("one:two[");
        assertThat(result).hasSize(1);
        assertThat(result.get("one")).isNull();
    }

    @Test
    public void testGetAllowedCompatModePackages_multiplePackagesMultipleUrls() {
        final Map<String, String[]> result =
                getAllowedCompatModePackages("p1[p1u1]:p2:p3[p3u1,p3u2]");
        assertThat(result).hasSize(3);
        assertThat(result.get("p1")).asList().containsExactly("p1u1");
        assertThat(result.get("p2")).isNull();
        assertThat(result.get("p3")).asList().containsExactly("p3u1", "p3u2");
    }

    @Test
    public void testGetAllowedCompatModePackages_threePackagesOneInvalid() {
        final Map<String, String[]> result =
                getAllowedCompatModePackages("p1[p1u1]:p2[:p3[p3u1,p3u2]");
        assertThat(result).hasSize(2);
        assertThat(result.get("p1")).asList().containsExactly("p1u1");
        assertThat(result.get("p3")).asList().containsExactly("p3u1", "p3u2");
    }

    @Test
    public void testHandleDeviceProvisioned() throws Exception {
        // Arrange
        setResetInvalidServiceOnDeviceProvisioned("true");
        Settings.Global.putInt(mContentResolver, Settings.Global.DEVICE_PROVISIONED, 0);
        final String invalidService = "com.invalid.service/.Service";
        Settings.Secure.putStringForUser(
                mContentResolver, Settings.Secure.AUTOFILL_SERVICE, invalidService, 10);
        final String defaultService = "default/service";
        when(mResources.getString(com.android.internal.R.string.config_defaultAutofillService))
                .thenReturn(defaultService);
        ContentResolver spiedResolver = spy(mContentResolver);
        when(mContext.getContentResolver()).thenReturn(spiedResolver);
        AutofillManagerService service = new AutofillManagerService(mContext);

        // Act
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .adoptShellPermissionIdentity();
        try {
            Settings.Global.putInt(mContentResolver, Settings.Global.DEVICE_PROVISIONED, 1);
            service.handleDeviceProvisioned();
        } finally {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }

        // Assert
        assertThat(Settings.Secure.getStringForUser(mContentResolver,
                Settings.Secure.AUTOFILL_SERVICE, 10)).isEqualTo(defaultService);
    }

    @Test
    public void testHandleDeviceProvisioned_whenAlreadyProvisioned() throws Exception {
        // Arrange
        setResetInvalidServiceOnDeviceProvisioned("true");
        final String defaultService = "default/service";
        Settings.Global.putInt(mContentResolver, Settings.Global.DEVICE_PROVISIONED, 1);
        Settings.Secure.putStringForUser(
                mContentResolver, Settings.Secure.AUTOFILL_SERVICE, defaultService, 10);
        when(mResources.getString(com.android.internal.R.string.config_defaultAutofillService))
                .thenReturn(defaultService);
        AutofillManagerService service = new AutofillManagerService(mContext);

        // Act
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .adoptShellPermissionIdentity();
        try {
            service.handleDeviceProvisioned();
        } finally {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }

        // Assert
        assertThat(Settings.Secure.getStringForUser(mContentResolver,
                Settings.Secure.AUTOFILL_SERVICE, 10)).isEqualTo(defaultService);
    }

    private void setResetInvalidServiceOnDeviceProvisioned(String value) {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .adoptShellPermissionIdentity();
        try {
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_AUTOFILL,
                    RESET_INVALID_SERVICE_ON_DEVICE_PROVISIONED_FLAG, value, false);
        } finally {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }
}
