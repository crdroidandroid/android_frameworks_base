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

import android.os.Parcel;
import android.os.Parcelable;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class AbstractSafeListTest {

    private static class TestParcelable implements Parcelable {
        final int mData;

        TestParcelable(int data) {
            mData = data;
        }

        TestParcelable(Parcel parcel) {
            mData = parcel.readInt();
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeInt(mData);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @SuppressWarnings("EffectivelyPrivate") // Parcelable must have CREATOR.
        public static final Creator<TestParcelable> CREATOR = new Creator<TestParcelable>() {
            @Override
            public TestParcelable createFromParcel(Parcel parcel) {
                return new TestParcelable(parcel);
            }

            @Override
            public TestParcelable[] newArray(int size) {
                return new TestParcelable[size];
            }
        };
    }

    @Test
    public void testMarshallThenUnmarshall() {
        List<TestParcelable> originalArray = List.of(new TestParcelable(1), new TestParcelable(2));
        byte[] marshalled = AbstractSafeList.marshall(originalArray);
        assertNotNull(marshalled);
        List<TestParcelable> unmarshalled =
                AbstractSafeList.unmarshall(marshalled, TestParcelable.CREATOR);
        assertNotNull(unmarshalled);
        assertEquals(originalArray.size(), unmarshalled.size());
        for (int i = 0; i < originalArray.size(); i++) {
            assertEquals(originalArray.get(i).mData, unmarshalled.get(i).mData);
        }
    }

    @Test
    public void testMarshallEmptyArray() {
        List<TestParcelable> originalArray = List.of();
        byte[] marshalled = AbstractSafeList.marshall(originalArray);
        assertNotNull(marshalled);
        List<TestParcelable> unmarshalled =
                AbstractSafeList.unmarshall(marshalled, TestParcelable.CREATOR);
        assertNotNull(unmarshalled);
        assertEquals(0, unmarshalled.size());
    }
}
