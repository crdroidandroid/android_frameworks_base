/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.inputmethod;

import static android.view.inputmethod.InputMethodInfo.COMPONENT_NAME_MAX_LENGTH;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.XmlRes;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Parcel;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.inputmethod.InputMethodInfo.MetadataReadBytesTracker;
import android.view.inputmethod.InputMethodInfo.TypedArrayWrapper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.frameworks.inputmethodcoretests.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParserException;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InputMethodInfoTest {

    @Rule
    public SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private final DeviceFlagsValueProvider mFlagsValueProvider = new DeviceFlagsValueProvider();

    @Test
    public void testEqualsAndHashCode() throws Exception {
        final InputMethodInfo imi = buildInputMethodForTest(R.xml.ime_meta);
        final InputMethodInfo clone = cloneViaParcel(imi);

        assertThat(clone.equals(imi), is(true));
        assertThat(clone.hashCode(), equalTo(imi.hashCode()));
    }

    @Test
    public void testBooleanAttributes_DefaultValues() throws Exception {
        final InputMethodInfo imi = buildInputMethodForTest(R.xml.ime_meta);

        assertThat(imi.supportsSwitchingToNextInputMethod(), is(false));
        assertThat(imi.isInlineSuggestionsEnabled(), is(false));
        assertThat(imi.supportsInlineSuggestionsWithTouchExploration(), is(false));

        final InputMethodInfo clone = cloneViaParcel(imi);

        assertThat(clone.supportsSwitchingToNextInputMethod(), is(false));
        assertThat(imi.isInlineSuggestionsEnabled(), is(false));
        assertThat(imi.supportsInlineSuggestionsWithTouchExploration(), is(false));
        assertThat(imi.supportsStylusHandwriting(), is(false));
        assertThat(imi.createStylusHandwritingSettingsActivityIntent(), equalTo(null));
        assertThat(imi.createImeLanguageSettingsActivityIntent(), equalTo(null));
    }

    @Test
    public void testSupportsSwitchingToNextInputMethod() throws Exception {
        final InputMethodInfo imi = buildInputMethodForTest(R.xml.ime_meta_sw_next);

        assertThat(imi.supportsSwitchingToNextInputMethod(), is(true));

        final InputMethodInfo clone = cloneViaParcel(imi);

        assertThat(clone.supportsSwitchingToNextInputMethod(), is(true));
    }

    @Test
    public void testInlineSuggestionsEnabled() throws Exception {
        final InputMethodInfo imi = buildInputMethodForTest(R.xml.ime_meta_inline_suggestions);

        assertThat(imi.isInlineSuggestionsEnabled(), is(true));

        final InputMethodInfo clone = cloneViaParcel(imi);

        assertThat(clone.isInlineSuggestionsEnabled(), is(true));
    }

    @Test
    public void testInlineSuggestionsEnabledWithTouchExploration() throws Exception {
        final InputMethodInfo imi =
                buildInputMethodForTest(R.xml.ime_meta_inline_suggestions_with_touch_exploration);

        assertThat(imi.supportsInlineSuggestionsWithTouchExploration(), is(true));

        final InputMethodInfo clone = cloneViaParcel(imi);

        assertThat(clone.supportsInlineSuggestionsWithTouchExploration(), is(true));
    }

    @Test
    public void testIsVrOnly() throws Exception {
        final InputMethodInfo imi = buildInputMethodForTest(R.xml.ime_meta_vr_only);

        assertThat(imi.isVrOnly(), is(true));

        final InputMethodInfo clone = cloneViaParcel(imi);

        assertThat(clone.isVrOnly(), is(true));
    }

    @Test
    public void testIsVirtualDeviceOnly() throws Exception {
        final InputMethodInfo imi = buildInputMethodForTest(R.xml.ime_meta_virtual_device_only);

        assertThat(imi.isVirtualDeviceOnly(), is(true));

        final InputMethodInfo clone = cloneViaParcel(imi);

        assertThat(clone.isVirtualDeviceOnly(), is(true));
    }

    @Test
    public void testTypedArrayWrapper() throws Exception {
        final TypedArray mockTypedArray = mock(TypedArray.class);
        when(mockTypedArray.hasValue(0)).thenReturn(true);
        when(mockTypedArray.getInt(0, 0)).thenReturn(123);
        when(mockTypedArray.getString(1)).thenReturn("hello");
        when(mockTypedArray.hasValue(2)).thenReturn(true);
        when(mockTypedArray.getBoolean(2, false)).thenReturn(true);
        when(mockTypedArray.hasValue(3)).thenReturn(true);
        when(mockTypedArray.getResourceId(3, 0)).thenReturn(456);

        try (TypedArrayWrapper wrapper = TypedArrayWrapper.createForMethod(mockTypedArray,
                new MetadataReadBytesTracker())) {
            assertThat(wrapper.getInt(0, 0), is(123));
            assertThat(wrapper.getString(1), is("hello"));
            assertThat(wrapper.getBoolean(2, false), is(true));
            assertThat(wrapper.getResourceId(3, 0), is(456));
        }
    }

    @Test
    public void testTypedArrayWrapper_getString_throwsExceptionWhenStringTooLong()
            throws Exception {
        final TypedArray mockTypedArray = mock(TypedArray.class);
        final String longStringA = "a".repeat(COMPONENT_NAME_MAX_LENGTH + 1);
        final String longStringB = "b".repeat(COMPONENT_NAME_MAX_LENGTH + 1);
        when(mockTypedArray.getString(
                com.android.internal.R.styleable.InputMethod_settingsActivity))
                .thenReturn(longStringA);
        when(mockTypedArray.getString(
                com.android.internal.R.styleable.InputMethod_languageSettingsActivity))
                .thenReturn(longStringB);

        try (TypedArrayWrapper wrapper = TypedArrayWrapper.createForMethod(mockTypedArray,
                new MetadataReadBytesTracker())) {
            assertThrows(
                    XmlPullParserException.class,
                    () -> wrapper.getString(
                            com.android.internal.R.styleable.InputMethod_settingsActivity));
            assertThrows(
                    XmlPullParserException.class,
                    () -> wrapper.getString(
                            com.android.internal.R.styleable.InputMethod_languageSettingsActivity));
        }

        // The same index can be used for method and subtype for different attributes.
        // This verifies the same index returns the correct string for subtypes.
        try (TypedArrayWrapper wrapper = TypedArrayWrapper.createForSubtype(mockTypedArray,
                new MetadataReadBytesTracker())) {
            assertThat(wrapper.getString(
                            com.android.internal.R.styleable.InputMethod_settingsActivity),
                    is(longStringA));
            assertThat(wrapper.getString(
                            com.android.internal.R.styleable.InputMethod_languageSettingsActivity),
                    is(longStringB));
        }
    }

    @Test
    public void testTypedArrayWrapper_closeRecyclesTypedArray() {
        final TypedArray mockTypedArray = mock(TypedArray.class);
        final TypedArrayWrapper wrapper = TypedArrayWrapper.createForMethod(mockTypedArray,
                new MetadataReadBytesTracker());

        wrapper.close();

        verify(mockTypedArray).recycle();
    }

    @Test
    public void testTypedArrayWrapper_metadataReadBytesTracker_throwsExceptionWhenLimitExceeded() {
        final TypedArray mockTypedArray = mock(TypedArray.class);
        final String longString = "a".repeat(1000);
        when(mockTypedArray.getString(0)).thenReturn(longString);

        try (TypedArrayWrapper wrapper = TypedArrayWrapper.createForMethod(mockTypedArray,
                new MetadataReadBytesTracker())) {
            assertThrows(XmlPullParserException.class, () -> {
                // Each character is 2 bytes. 1000 chars * 2 = 2000 bytes per call.
                // Limit is 200 * 1024 = 204800 bytes.
                // 204800 / 2000 = 102.4. So 103 calls will exceed the limit.
                for (int i = 0; i < 103; ++i) {
                    wrapper.getString(0);
                }
            });
        }
    }

    private InputMethodInfo buildInputMethodForTest(final @XmlRes int metaDataRes)
            throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        final ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.applicationInfo = context.getApplicationInfo();
        serviceInfo.packageName = context.getPackageName();
        serviceInfo.name = "DummyImeForTest";
        serviceInfo.metaData = new Bundle();
        serviceInfo.metaData.putInt(InputMethod.SERVICE_META_DATA, metaDataRes);
        final ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = serviceInfo;
        return new InputMethodInfo(context, resolveInfo, null /* additionalSubtypesMap */);
    }

    private InputMethodInfo cloneViaParcel(final InputMethodInfo original) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return InputMethodInfo.CREATOR.createFromParcel(parcel);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }
}
