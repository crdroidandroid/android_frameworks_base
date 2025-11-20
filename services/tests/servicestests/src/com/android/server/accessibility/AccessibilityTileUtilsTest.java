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

package com.android.server.accessibility;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityShortcutInfo;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.test.mock.MockContentResolver;
import android.util.AttributeSet;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.accessibility.utils.TileServiceUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

/**
 * Tests for {@link AccessibilityTileUtils}.
 */
@RunWith(AndroidJUnit4.class)
public class AccessibilityTileUtilsTest {
    private static final String A11Y_FEATURE_PACKAGE = "com.example.test";
    private static final String A11Y_SERVICE_NAME = "TestAccessibilityService";
    private static final String A11Y_SHORTCUT_NAME = "TestAccessibilityShortcut";
    private static final String TILE_SERVICE_NAME = "TestTileService";
    private static final String TILE_SERVICE_NAME2 = "TestTileService2";
    private static final int USER_ID = 0;

    private final ComponentName mTileServiceComponent =
            new ComponentName(A11Y_FEATURE_PACKAGE, TILE_SERVICE_NAME);
    private final ComponentName mTileServiceComponent2 =
            new ComponentName(A11Y_FEATURE_PACKAGE, TILE_SERVICE_NAME2);
    private final ComponentName mA11yFeatureComponent =
            new ComponentName(A11Y_FEATURE_PACKAGE, A11Y_SERVICE_NAME);
    private final ComponentName mA11yShortcutComponent =
            new ComponentName(A11Y_FEATURE_PACKAGE, A11Y_SHORTCUT_NAME);

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock
    private Context mMockContext;
    @Mock
    private PackageManagerInternal mMockPackageManagerInternal;
    private final MockContentResolver mMockResolver = new MockContentResolver();

    @Before
    public void setUp() {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mMockPackageManagerInternal);
        when(mMockContext.getContentResolver()).thenReturn(mMockResolver);
    }

    @After
    public void cleanUp() {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
    }

    @Test
    public void getValidA11yTileServices_pmIsNull_returnsEmptySet() {
        TileServiceUtil.setupPackageManagerForValidTileService(mMockPackageManagerInternal, USER_ID,
                mTileServiceComponent);

        Set<ComponentName> result = AccessibilityTileUtils.getValidA11yTileServices(
                mMockContext, /* pm= */ null, Collections.singletonList(
                        createMockAccessibilityServiceInfo(mA11yFeatureComponent,
                                mTileServiceComponent)),
                Collections.emptyList(), USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    public void getValidA11yTileServices_nullLists_returnsEmptySet() {
        Set<ComponentName> result = AccessibilityTileUtils.getValidA11yTileServices(
                mMockContext, mMockPackageManagerInternal, null, null, USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    public void getValidA11yTileServices_emptyLists_returnsEmptySet() {
        Set<ComponentName> result = AccessibilityTileUtils.getValidA11yTileServices(
                mMockContext, mMockPackageManagerInternal, Collections.emptyList(),
                Collections.emptyList(), USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    public void getValidA11yTileServices_withA11yService_returnsComponent() {
        TileServiceUtil.setupPackageManagerForValidTileService(mMockPackageManagerInternal, USER_ID,
                mTileServiceComponent);
        AccessibilityServiceInfo a11yServiceInfo = createMockAccessibilityServiceInfo(
                mA11yFeatureComponent, mTileServiceComponent
        );

        Set<ComponentName> result = AccessibilityTileUtils.getValidA11yTileServices(
                mMockContext, mMockPackageManagerInternal,
                Collections.singletonList(a11yServiceInfo), /* accessibilityShortcutInfos=*/null,
                USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result).contains(mTileServiceComponent);
    }

    @Test
    public void getValidA11yTileServices_withShortcutInfo_returnsComponent() throws Exception {
        TileServiceUtil.setupPackageManagerForValidTileService(mMockPackageManagerInternal, USER_ID,
                mTileServiceComponent2);
        AccessibilityShortcutInfo a11yShortcutInfo = createFakeAccessibilityShortcutInfo(
                mA11yShortcutComponent, mTileServiceComponent2
        );

        Set<ComponentName> result = AccessibilityTileUtils.getValidA11yTileServices(
                mMockContext, mMockPackageManagerInternal,
                /* accessibilityServiceInfos=*/ null, Collections.singletonList(a11yShortcutInfo),
                USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result).contains(mTileServiceComponent2);
    }

    @Test
    public void getValidA11yTileServices_serviceWithNoTileName_returnsEmptySet() {
        TileServiceUtil.setupPackageManagerForValidTileService(mMockPackageManagerInternal, USER_ID,
                mTileServiceComponent);
        AccessibilityServiceInfo a11yServiceInfo = createMockAccessibilityServiceInfo(
                mA11yFeatureComponent, /* tileServiceName= */ null
        );

        Set<ComponentName> result = AccessibilityTileUtils.getValidA11yTileServices(
                mMockContext, mMockPackageManagerInternal,
                Collections.singletonList(a11yServiceInfo), /* accessibilityShortcutInfos=*/ null,
                USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    public void getValidA11yTileServices_shortcutWithNoTileName_returnsEmptySet() throws Exception {
        TileServiceUtil.setupPackageManagerForValidTileService(mMockPackageManagerInternal, USER_ID,
                mTileServiceComponent2);
        AccessibilityShortcutInfo a11yShortcutInfo = createFakeAccessibilityShortcutInfo(
                mA11yShortcutComponent, /* tileServiceName= */ null
        );

        Set<ComponentName> result = AccessibilityTileUtils.getValidA11yTileServices(
                mMockContext, mMockPackageManagerInternal,
                /* accessibilityServiceInfos=*/ null, Collections.singletonList(a11yShortcutInfo),
                USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    public void getValidA11yTileServices_tileServiceNotExist_returnsEmptySet() {
        TileServiceUtil.setupPackageManagerForValidTileService(
                mMockPackageManagerInternal, USER_ID, mTileServiceComponent);
        AccessibilityServiceInfo a11yServiceInfo = createMockAccessibilityServiceInfo(
                mA11yFeatureComponent, /* tileServiceName= */ mTileServiceComponent2
        );

        Set<ComponentName> result = AccessibilityTileUtils.getValidA11yTileServices(
                mMockContext, mMockPackageManagerInternal,
                Collections.singletonList(a11yServiceInfo), /* accessibilityShortcutInfos=*/ null,
                USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    public void getValidA11yTileServices_tileServiceNotExported_returnsEmptySet() {
        TileServiceUtil.setupPackageManagerForTileService(
                mMockPackageManagerInternal, USER_ID, mTileServiceComponent,
                serviceInfo -> serviceInfo.exported = false);
        AccessibilityServiceInfo a11yServiceInfo = createMockAccessibilityServiceInfo(
                mA11yFeatureComponent, /* tileServiceName= */ mTileServiceComponent
        );

        Set<ComponentName> result = AccessibilityTileUtils.getValidA11yTileServices(
                mMockContext, mMockPackageManagerInternal,
                Collections.singletonList(a11yServiceInfo), /* accessibilityShortcutInfos=*/ null,
                USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    public void getValidA11yTileServices_tileServiceWrongPermission_returnsEmptySet() {
        TileServiceUtil.setupPackageManagerForTileService(
                mMockPackageManagerInternal, USER_ID, mTileServiceComponent,
                serviceInfo -> serviceInfo.permission = "some.wrong.permission");
        AccessibilityServiceInfo a11yServiceInfo = createMockAccessibilityServiceInfo(
                mA11yFeatureComponent, /* tileServiceName= */ mTileServiceComponent
        );

        Set<ComponentName> result = AccessibilityTileUtils.getValidA11yTileServices(
                mMockContext, mMockPackageManagerInternal,
                Collections.singletonList(a11yServiceInfo), /* accessibilityShortcutInfos=*/ null,
                USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    public void getValidA11yTileServices_tileServiceDisabled_returnsEmptySet() {
        TileServiceUtil.setupPackageManagerForTileService(
                mMockPackageManagerInternal, USER_ID, mTileServiceComponent,
                serviceInfo -> serviceInfo.enabled = false);
        AccessibilityServiceInfo a11yServiceInfo = createMockAccessibilityServiceInfo(
                mA11yFeatureComponent, /* tileServiceName= */ mTileServiceComponent
        );

        Set<ComponentName> result = AccessibilityTileUtils.getValidA11yTileServices(
                mMockContext, mMockPackageManagerInternal,
                Collections.singletonList(a11yServiceInfo), /* accessibilityShortcutInfos=*/ null,
                USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    public void getValidA11yTileServices_tileServiceEnabledByDefault_returnsComponent() {
        TileServiceUtil.setupPackageManagerForTileService(
                mMockPackageManagerInternal, USER_ID, mTileServiceComponent,
                serviceInfo -> serviceInfo.enabled = true);
        AccessibilityServiceInfo a11yServiceInfo = createMockAccessibilityServiceInfo(
                mA11yFeatureComponent, /* tileServiceName= */ mTileServiceComponent
        );
        when(mMockPackageManagerInternal.getComponentEnabledSetting(
                eq(mTileServiceComponent), anyInt(), anyInt()))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);

        Set<ComponentName> result = AccessibilityTileUtils.getValidA11yTileServices(
                mMockContext, mMockPackageManagerInternal,
                Collections.singletonList(a11yServiceInfo), /* accessibilityShortcutInfos=*/ null,
                USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result).contains(mTileServiceComponent);
    }

    @Test
    public void getValidA11yTileServices_tileServiceDisabledByDefault_returnsEmptySet() {
        TileServiceUtil.setupPackageManagerForTileService(
                mMockPackageManagerInternal, USER_ID, mTileServiceComponent,
                serviceInfo -> serviceInfo.enabled = false);
        AccessibilityServiceInfo a11yServiceInfo = createMockAccessibilityServiceInfo(
                mA11yFeatureComponent, /* tileServiceName= */ mTileServiceComponent
        );
        when(mMockPackageManagerInternal.getComponentEnabledSetting(
                eq(mTileServiceComponent), anyInt(), anyInt()))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);

        Set<ComponentName> result = AccessibilityTileUtils.getValidA11yTileServices(
                mMockContext, mMockPackageManagerInternal,
                Collections.singletonList(a11yServiceInfo), /* accessibilityShortcutInfos=*/ null,
                USER_ID);

        assertThat(result).isEmpty();
    }

    private AccessibilityShortcutInfo createFakeAccessibilityShortcutInfo(
            @NonNull ComponentName shortcutInfoComponentName,
            @Nullable ComponentName tileServiceName)
            throws XmlPullParserException, IOException, PackageManager.NameNotFoundException {
        Context context = mock(Context.class);
        Resources resources = mock(Resources.class);
        PackageManager packageManager = mock(PackageManager.class);
        ActivityInfo activityInfo = mock(ActivityInfo.class);
        XmlResourceParser xmlParser = mock(XmlResourceParser.class);
        TypedArray typedArray = mock(TypedArray.class);

        // 1. Configure the mock ActivityInfo
        activityInfo.applicationInfo = mock(ApplicationInfo.class);
        when(activityInfo.getComponentName()).thenReturn(shortcutInfoComponentName);
        // 2. Mock the context to return the mock PackageManager
        when(context.getPackageManager()).thenReturn(packageManager);
        // 3. Mock the PackageManager to return mock Resources
        when(packageManager.getResourcesForApplication(any(ApplicationInfo.class))).thenReturn(
                resources);

        // 4. Mock the XML parsing flow
        // Simulate finding the <accessibility-shortcut-target> tag
        when(activityInfo.loadXmlMetaData(packageManager,
                AccessibilityShortcutInfo.META_DATA)).thenReturn(xmlParser);
        when(xmlParser.next())
                .thenReturn(XmlPullParser.START_TAG)
                .thenReturn(XmlPullParser.END_DOCUMENT);
        when(xmlParser.getName()).thenReturn("accessibility-shortcut-target");

        // 5. Mock the Resources to return our mock TypedArray for attributes
        when(resources.obtainAttributes(any(AttributeSet.class), any(int[].class)))
                .thenReturn(typedArray);

        // 6. Configure the mock TypedArray to return our test data
        if (tileServiceName != null) {
            when(typedArray.getString(
                    com.android.internal.R.styleable.AccessibilityShortcutTarget_tileService))
                    .thenReturn(tileServiceName.getClassName());
        }

        return new AccessibilityShortcutInfo(context, activityInfo);
    }

    private AccessibilityServiceInfo createMockAccessibilityServiceInfo(
            @NonNull ComponentName a11yServiceComponent, @Nullable ComponentName tileServiceName) {
        AccessibilityServiceInfo serviceInfo = mock(AccessibilityServiceInfo.class);
        when(serviceInfo.getComponentName()).thenReturn(a11yServiceComponent);
        if (tileServiceName != null) {
            when(serviceInfo.getTileServiceName()).thenReturn(tileServiceName.getClassName());
        }
        ResolveInfo a11yResolveInfo = new ResolveInfo();
        a11yResolveInfo.serviceInfo = new ServiceInfo();
        a11yResolveInfo.serviceInfo.packageName = a11yServiceComponent.getPackageName();
        a11yResolveInfo.serviceInfo.name = a11yServiceComponent.getClassName();
        when(serviceInfo.getResolveInfo()).thenReturn(a11yResolveInfo);
        return serviceInfo;
    }
}
