/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.internal.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.view.inputmethod.InputMethodSubtype;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class InputMethodSubtypeSafeListTest {

    private static InputMethodSubtype createFakeInputMethodSubtype(String locale, String mode) {
        return new InputMethodSubtype.InputMethodSubtypeBuilder()
                .setSubtypeLocale(locale)
                .setSubtypeMode(mode)
                .build();
    }

    private static List<InputMethodSubtype> createTestInputMethodSubtypeList() {
        List<InputMethodSubtype> list = new ArrayList<>();
        list.add(createFakeInputMethodSubtype("en_US", "keyboard"));
        list.add(createFakeInputMethodSubtype("ja_JP", "keyboard"));
        list.add(createFakeInputMethodSubtype("en_GB", "voice"));
        return list;
    }

    private static void assertItemsAfterExtract(
            List<InputMethodSubtype> originals,
            Function<List<InputMethodSubtype>, InputMethodSubtypeSafeList> factory) {
        InputMethodSubtypeSafeList list = factory.apply(originals);
        List<InputMethodSubtype> extracted = InputMethodSubtypeSafeList.extractFrom(list);
        assertEquals(originals.size(), extracted.size());
        for (int i = 0; i < originals.size(); i++) {
            assertNotSame(
                    "InputMethodSubtypeSafeList.extractFrom() must clone each instance",
                    originals.get(i), extracted.get(i));
            assertEquals(
                    "Verify the cloned instances have the equal locale",
                    originals.get(i).getLocale(), extracted.get(i).getLocale());
            assertEquals(
                    "Verify the cloned instances have the equal mode",
                    originals.get(i).getMode(), extracted.get(i).getMode());
        }

        // Subsequent calls of InputMethodSubtypeSafeList.extractFrom() return an empty list.
        List<InputMethodSubtype> extracted2 = InputMethodSubtypeSafeList.extractFrom(list);
        assertTrue(extracted2.isEmpty());
    }

    private static InputMethodSubtypeSafeList cloneViaParcel(InputMethodSubtypeSafeList original) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            InputMethodSubtypeSafeList newInstance =
                    InputMethodSubtypeSafeList.CREATOR.createFromParcel(parcel);
            assertNotNull(newInstance);
            return newInstance;
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }

    @Test
    public void testCreate() {
        assertNotNull(InputMethodSubtypeSafeList.create(createTestInputMethodSubtypeList()));
    }

    @Test
    public void testExtract() {
        assertItemsAfterExtract(
                createTestInputMethodSubtypeList(),
                InputMethodSubtypeSafeList::create);
    }

    @Test
    public void testExtractAfterParceling() {
        assertItemsAfterExtract(
                createTestInputMethodSubtypeList(),
                originals -> cloneViaParcel(InputMethodSubtypeSafeList.create(originals)));
    }

    @Test
    public void testExtractEmptyList() {
        assertItemsAfterExtract(Collections.emptyList(), InputMethodSubtypeSafeList::create);
    }

    @Test
    public void testExtractAfterParcelingEmptyList() {
        assertItemsAfterExtract(Collections.emptyList(),
                originals -> cloneViaParcel(InputMethodSubtypeSafeList.create(originals)));
    }
}
