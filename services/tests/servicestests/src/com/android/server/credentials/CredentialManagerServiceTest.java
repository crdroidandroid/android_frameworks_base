/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.credentials;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.cert.CertificateException;
import java.util.HashSet;
import java.util.Set;

/** atest FrameworksServicesTests:com.android.server.credentials.CredentialManagerServiceTest */
@RunWith(AndroidJUnit4.class)
@SmallTest
public final class CredentialManagerServiceTest {

    private static final String TEST_PACKAGE_1 = "com.example.test";
    private static final String TEST_PACKAGE_2 = "com.example.test2";
    private static final String TEST_ACTIVITY_1 = ".TestActivity";
    private static final String TEST_ACTIVITY_2 = ".TestActivity2";
    private static final String AUTOFILL_ACTIVITY = ".AutofillProvider";

    private static final ComponentName AUTOFILL_CREDMAN_SERVICE = ComponentName
            .unflattenFromString("com.android.credentialmanager/"
                    + "com.android.credentialmanager.autofill.CredentialAutofillService");

    private static final ComponentName COMPONENT_1 =
            new ComponentName(TEST_PACKAGE_1, TEST_PACKAGE_1 + TEST_ACTIVITY_1);
    private static final ComponentName COMPONENT_2 =
            new ComponentName(TEST_PACKAGE_2, TEST_PACKAGE_2 + TEST_ACTIVITY_2);
    private static final ComponentName AUTOFILL_COMPONENT =
            new ComponentName(TEST_PACKAGE_1, TEST_PACKAGE_1 + AUTOFILL_ACTIVITY);

    private static final int USER_ID_0 = 0;
    private static final int USER_ID_10 = 10;

    private Context mContext = null;
    private MockSettingsWrapper mSettingsWrapper = null;

    @Before
    public void setUp() throws CertificateException {
        mContext = ApplicationProvider.getApplicationContext();
        mSettingsWrapper = new MockSettingsWrapper(mContext);
    }

    @Test
    public void getStoredProvidersExceptPackage_emptyValue_success() {
        Set<String> providers = CredentialManagerService.getStoredProvidersExceptPackage(
                "", "");
        assertThat(providers.size()).isEqualTo(0);
    }

    @Test
    public void getStoredProvidersExceptPackage_nullValue_success() {
        Set<String> providers = CredentialManagerService.getStoredProvidersExceptPackage(
                null, null);
        assertThat(providers.size()).isEqualTo(0);
    }

    @Test
    public void getStoredProvidersExceptPackage_success() {
        Set<String> providers =
                CredentialManagerService.getStoredProvidersExceptPackage(
                        "com.example.test/.TestActivity:com.example.test/.TestActivity2:"
                                + "com.example.test2/.TestActivity:blank",
                        "com.example.test");
        assertThat(providers.size()).isEqualTo(1);
        assertThat(providers.contains("com.example.test2/com.example.test2.TestActivity")).isTrue();
    }

    @Test
    public void getStoredProvidersExceptService_emptyValue_success() {
        Set<String> providers = CredentialManagerService.getStoredProvidersExceptService(
                "", new ComponentName("", ""));
        assertThat(providers.size()).isEqualTo(0);
    }

    @Test
    public void getStoredProvidersExceptService_nullValue_success() {
        Set<String> providers = CredentialManagerService.getStoredProvidersExceptService(
                null, null);
        assertThat(providers.size()).isEqualTo(0);
    }

    @Test
    public void getStoredProvidersExceptService_success() {
        Set<String> providers =
                CredentialManagerService.getStoredProvidersExceptService(
                        COMPONENT_1.flattenToString()
                                + ":" + COMPONENT_2.flattenToString() + ":blank",
                        COMPONENT_1);
        assertThat(providers.size()).isEqualTo(1);
        assertThat(providers.contains(COMPONENT_2.flattenToString())).isTrue();
    }

    @Test
    public void updateProvidersWhenPackageRemoved_removePrimary_clearsAll() {
        int userId = UserHandle.myUserId();
        Assert.assertNotNull(AUTOFILL_CREDMAN_SERVICE);
        setSettingsKey(
                Settings.Secure.AUTOFILL_SERVICE,
                AUTOFILL_CREDMAN_SERVICE.flattenToString(), userId);
        setSettingsKey(
                Settings.Secure.CREDENTIAL_SERVICE,
                COMPONENT_1.flattenToString() + ":" + COMPONENT_2.flattenToString(), userId);
        setSettingsKey(
                Settings.Secure.CREDENTIAL_SERVICE_PRIMARY,
                COMPONENT_1.flattenToString(), userId);

        CredentialManagerService.updateProvidersWhenPackageRemoved(
                mSettingsWrapper, TEST_PACKAGE_1, userId);

        assertThat(getSettingsKey(Settings.Secure.AUTOFILL_SERVICE, userId)).isEqualTo(
                AUTOFILL_CREDMAN_SERVICE.flattenToString());
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, userId)).isNull();
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, userId)).isNull();
    }

    @Test
    public void updateProvidersWhenPackageRemoved_unknownPackageRemoved_noChanges() {
        int userId = UserHandle.myUserId();
        final String testCredentialPrimaryValue = COMPONENT_1.flattenToString();
        final String testCredentialValue =
                COMPONENT_1.flattenToString() + ":" + COMPONENT_2.flattenToString();

        setSettingsKey(
                Settings.Secure.AUTOFILL_SERVICE,
                AUTOFILL_COMPONENT.flattenToString(), userId);
        setSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, testCredentialValue, userId);
        setSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, testCredentialPrimaryValue,
                userId);

        CredentialManagerService.updateProvidersWhenPackageRemoved(
                mSettingsWrapper, "com.example.test3", userId);

        assertThat(getSettingsKey(Settings.Secure.AUTOFILL_SERVICE, userId))
                .isEqualTo(AUTOFILL_COMPONENT.flattenToString());
        assertCredentialPropertyEquals(
                getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, userId), testCredentialValue);
        assertCredentialPropertyEquals(
                getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, userId),
                testCredentialPrimaryValue);
    }

    @Test
    public void updateProvidersWhenPackageRemoved_primaryRemoved_clearsAllProviders() {
        int userId = UserHandle.myUserId();
        setSettingsKey(
                Settings.Secure.AUTOFILL_SERVICE,
                AUTOFILL_COMPONENT.flattenToString(), userId);
        setSettingsKey(
                Settings.Secure.CREDENTIAL_SERVICE,
                COMPONENT_1.flattenToString() + ":" + COMPONENT_2.flattenToString(), userId);
        setSettingsKey(
                Settings.Secure.CREDENTIAL_SERVICE_PRIMARY,
                COMPONENT_1.flattenToString(), userId);

        CredentialManagerService.updateProvidersWhenPackageRemoved(
                mSettingsWrapper, TEST_PACKAGE_1, userId);

        assertThat(getSettingsKey(Settings.Secure.AUTOFILL_SERVICE, userId)).isEqualTo("");
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, userId)).isNull();
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, userId)).isNull();
    }

    @Test
    public void updateProvidersWhenServiceRemoved_primaryProviderRemoved_clearsAllProviders() {
        int userId = UserHandle.myUserId();
        setSettingsKey(
                Settings.Secure.AUTOFILL_SERVICE,
                "", userId);
        setSettingsKey(
                Settings.Secure.CREDENTIAL_SERVICE,
                COMPONENT_1.flattenToString() + ":" + COMPONENT_2.flattenToString(), userId);
        setSettingsKey(
                Settings.Secure.CREDENTIAL_SERVICE_PRIMARY,
                COMPONENT_1.flattenToString(), userId);

        CredentialManagerService.updateProvidersWhenServiceRemoved(
                mSettingsWrapper, COMPONENT_1, userId);

        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, userId)).isNull();
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, userId)).isNull();
        assertThat(getSettingsKey(Settings.Secure.AUTOFILL_SERVICE, userId)).isEqualTo("");
    }

    @Test
    public void updateProvidersWhenServiceRemoved_primaryServiceRemoved_otherServicesStay() {
        int userId = UserHandle.myUserId();
        setSettingsKey(
                Settings.Secure.AUTOFILL_SERVICE,
                AUTOFILL_COMPONENT.flattenToString(), userId);
        setSettingsKey(
                Settings.Secure.CREDENTIAL_SERVICE,
                COMPONENT_1.flattenToString() + ":" + COMPONENT_2.flattenToString(), userId);
        setSettingsKey(
                Settings.Secure.CREDENTIAL_SERVICE_PRIMARY,
                COMPONENT_1.flattenToString(), userId);

        CredentialManagerService.updateProvidersWhenServiceRemoved(
                mSettingsWrapper, COMPONENT_1, userId);

        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, userId)).isEqualTo(
                "");
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, userId))
                .isEqualTo(COMPONENT_2.flattenToString());
        assertThat(getSettingsKey(Settings.Secure.AUTOFILL_SERVICE, userId))
                .isEqualTo(AUTOFILL_COMPONENT.flattenToString());
    }

    @Test
    public void updateProvidersWhenServiceRemoved_secondaryServiceRemoved_success() {
        int userId = UserHandle.myUserId();
        setSettingsKey(
                Settings.Secure.AUTOFILL_SERVICE,
                AUTOFILL_COMPONENT.flattenToString(), userId);
        setSettingsKey(
                Settings.Secure.CREDENTIAL_SERVICE,
                COMPONENT_1.flattenToString() + ":" + COMPONENT_2.flattenToString(), userId);
        setSettingsKey(
                Settings.Secure.CREDENTIAL_SERVICE_PRIMARY,
                COMPONENT_1.flattenToString(), userId);

        CredentialManagerService.updateProvidersWhenServiceRemoved(
                mSettingsWrapper, COMPONENT_2, userId);

        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, userId))
                .isEqualTo(COMPONENT_1.flattenToString());
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, userId))
                .isEqualTo(COMPONENT_1.flattenToString());
        assertThat(getSettingsKey(Settings.Secure.AUTOFILL_SERVICE, userId))
                .isEqualTo(AUTOFILL_COMPONENT.flattenToString());
    }

    @Test
    public void updateProvidersWhenServiceRemoved_emptyInitialSettings_doesNothing() {
        int userId = UserHandle.myUserId();
        setSettingsKey(Settings.Secure.AUTOFILL_SERVICE, "", userId);
        setSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, "", userId);
        setSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, "", userId);

        CredentialManagerService.updateProvidersWhenServiceRemoved(
                mSettingsWrapper, COMPONENT_1, userId);

        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, userId)).isEqualTo(
                "");
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, userId))
                .isEqualTo("");
        assertThat(getSettingsKey(Settings.Secure.AUTOFILL_SERVICE, userId)).isEqualTo("");
    }

    @Test
    public void updateProvidersWhenServiceRemoved_serviceNotInSettings_doesNothing() {
        int userId = UserHandle.myUserId();
        final String primary = COMPONENT_1.flattenToString();
        final String enabled = COMPONENT_1.flattenToString();
        setSettingsKey(Settings.Secure.AUTOFILL_SERVICE, "", userId);
        setSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, enabled, userId);
        setSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, primary, userId);

        CredentialManagerService.updateProvidersWhenServiceRemoved(
                mSettingsWrapper, COMPONENT_2, userId);

        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, userId)).isEqualTo(
                primary);
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, userId)).isEqualTo(enabled);
        assertThat(getSettingsKey(Settings.Secure.AUTOFILL_SERVICE, userId)).isEqualTo("");
    }

    @Test
    public void updateProvidersWhenServiceRemoved_multiUser_otherUserUnchanged() {
        // Arrange: Settings for User 0
        final String primaryUser0 = COMPONENT_1.flattenToString();
        final String enabledUser0 =
                COMPONENT_1.flattenToString() + ":" + COMPONENT_2.flattenToString();
        setSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, primaryUser0, USER_ID_0);
        setSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, enabledUser0, USER_ID_0);
        setSettingsKey(Settings.Secure.AUTOFILL_SERVICE,
                AUTOFILL_COMPONENT.flattenToString(), USER_ID_0);

        // Arrange: Settings for User 10
        final String primaryUser10 = COMPONENT_1.flattenToString();
        final String enabledUser10 =
                COMPONENT_1.flattenToString() + ":" + COMPONENT_2.flattenToString();
        setSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, primaryUser10, USER_ID_10);
        setSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, enabledUser10, USER_ID_10);
        setSettingsKey(Settings.Secure.AUTOFILL_SERVICE, AUTOFILL_COMPONENT.flattenToString(),
                USER_ID_10);

        // Act: Remove a service for User 10, which is the only primary provider.
        CredentialManagerService.updateProvidersWhenServiceRemoved(
                mSettingsWrapper, COMPONENT_1, USER_ID_10);

        // Assert: Settings for User 10 are cleared because the last primary provider was removed
        // and no autofill service was set.
        assertThat(
                getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, USER_ID_10)).isEmpty();
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, USER_ID_10)).isEqualTo(
                COMPONENT_2.flattenToString());

        // Assert: Settings for User 0 are completely unaffected
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, USER_ID_0)).isEqualTo(
                primaryUser0);
        assertThat(getSettingsKey(Settings.Secure.CREDENTIAL_SERVICE, USER_ID_0)).isEqualTo(
                enabledUser0);
    }

    private void assertCredentialPropertyEquals(String actualValue, String newValue) {
        Set<ComponentName> actualValueSet = new HashSet<>();
        for (String rawComponentName : actualValue.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(rawComponentName);
            if (cn != null) {
                actualValueSet.add(cn);
            }
        }

        Set<ComponentName> newValueSet = new HashSet<>();
        for (String rawComponentName : newValue.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(rawComponentName);
            if (cn != null) {
                newValueSet.add(cn);
            }
        }

        assertThat(actualValueSet).isEqualTo(newValueSet);
    }

    private void setSettingsKey(String name, String value, int userId) {
        // In tests, we want to clear the setting before setting a new value to avoid
        // any side effects from previous tests.
        Settings.Secure.putStringForUser(
                mContext.getContentResolver(), name, "", userId);
        if (value != null && !value.isEmpty()) {
            assertThat(
                    mSettingsWrapper.putStringForUser(
                            name, value, userId, true))
                    .isTrue();
        }
    }

    private String getSettingsKey(String name, int userId) {
        return mSettingsWrapper.getStringForUser(name, userId);
    }

    private static final class MockSettingsWrapper
            extends CredentialManagerService.SettingsWrapper {

        MockSettingsWrapper(@NonNull Context context) {
            super(context);
        }

        /** Updates the string value of a system setting */
        @Override
        public boolean putStringForUser(
                String name,
                String value,
                int userHandle,
                boolean overrideableByRestore) {
            // This will ensure that when the settings putStringForUser method is called by
            // CredentialManagerService that the overrideableByRestore bit is true.
            assertThat(overrideableByRestore).isTrue();

            return Settings.Secure.putStringForUser(getContentResolver(), name, value, userHandle);
        }
    }
}
