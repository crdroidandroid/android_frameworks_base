/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.UserHandle;
import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;

import com.android.server.uri.UriGrantsManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class UiServiceTestCase {
    @Mock protected PackageManagerInternal mPmi;
    @Mock protected UriGrantsManagerInternal mUgmInternal;

    protected static final String PKG_N_MR1 = "com.example.n_mr1";
    protected static final String PKG_O = "com.example.o";
    protected static final String PKG_P = "com.example.p";
    protected static final String PKG_R = "com.example.r";

    @Rule
    public TestableContext mContext =
            spy(new TestableContext(InstrumentationRegistry.getContext(), null));

    protected final int mUid = Binder.getCallingUid();
    protected final @UserIdInt int mUserId = UserHandle.getUserId(mUid);
    protected final UserHandle mUser = UserHandle.of(mUserId);
    protected final String mPkg = mContext.getPackageName();
    protected final int UID_N_MR1 = UserHandle.getUid(mUserId, 1);
    protected final int UID_O = UserHandle.getUid(mUserId, 2);
    protected final int UID_P = UserHandle.getUid(mUserId, 3);
    protected final int UID_R = UserHandle.getUid(mUserId, 4);

    protected TestableContext getContext() {
        return mContext;
    }

    @Before
    public final void setup() {
        MockitoAnnotations.initMocks(this);

        // Share classloader to allow package access.
        System.setProperty("dexmaker.share_classloader", "true");

        // Assume some default packages
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPmi);
        when(mPmi.getPackageTargetSdkVersion(anyString()))
                .thenAnswer((iom) -> {
                    switch ((String) iom.getArgument(0)) {
                        case PKG_N_MR1:
                            return Build.VERSION_CODES.N_MR1;
                        case PKG_O:
                            return Build.VERSION_CODES.O;
                        case PKG_P:
                            return Build.VERSION_CODES.P;
                        case PKG_R:
                            return Build.VERSION_CODES.R;
                        default:
                            return Build.VERSION_CODES.CUR_DEVELOPMENT;
                    }
                });
        when(mPmi.getPackageUid(eq(PKG_N_MR1), anyLong(), eq(mUserId))).thenReturn(UID_N_MR1);
        when(mPmi.getPackageUid(eq(PKG_O), anyLong(), eq(mUserId))).thenReturn(UID_O);
        when(mPmi.getPackageUid(eq(PKG_P), anyLong(), eq(mUserId))).thenReturn(UID_P);
        when(mPmi.getPackageUid(eq(PKG_R), anyLong(), eq(mUserId))).thenReturn(UID_R);
        when(mPmi.getPackageUid(eq(mContext.getPackageName()), anyLong(), eq(mUserId)))
                .thenReturn(mUid);
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, mUmi);
        LocalServices.removeServiceForTest(UriGrantsManagerInternal.class);
        LocalServices.addService(UriGrantsManagerInternal.class, mUgmInternal);
        when(mUgmInternal.checkGrantUriPermission(
                anyInt(), anyString(), any(Uri.class), anyInt(), anyInt())).thenReturn(-1);

        Mockito.doReturn(new Intent()).when(mContext).registerReceiverAsUser(
                any(), any(), any(), any(), any());
        Mockito.doReturn(new Intent()).when(mContext).registerReceiver(any(), any());
        Mockito.doReturn(new Intent()).when(mContext).registerReceiver(any(), any(), anyInt());
        Mockito.doNothing().when(mContext).unregisterReceiver(any());
    }

    @After
    public final void cleanUpMockito() {
        Mockito.framework().clearInlineMocks();
    }
}
