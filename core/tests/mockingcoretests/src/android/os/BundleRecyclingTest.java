/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.os;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test for verifying {@link android.os.Bundle} recycles the underlying parcel where needed.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksMockingCoreTests:android.os.BundleRecyclingTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class BundleRecyclingTest {
    private Parcel mParcelSpy;
    private Bundle mBundle;

    @Test
    public void bundleClear_whenUnparcelledWithoutLazy_recyclesParcelOnce() {
        setUpBundle(/* lazy */ 0, /* nonLazy */ 1);
        // Will unparcel and immediately recycle parcel
        assertNotNull(mBundle.getString("nonLazy0"));
        verify(mParcelSpy, times(1)).recycle();
        assertFalse(mBundle.isDefinitelyEmpty());

        // Should not recycle again
        mBundle.clear();
        verify(mParcelSpy, times(1)).recycle();
        assertTrue(mBundle.isDefinitelyEmpty());
    }

    @Test
    public void bundleClear_whenParcelled_destroysParcel() {
        setUpBundle(/* lazy */ 1);
        assertTrue(mBundle.isParcelled());
        verify(mParcelSpy, times(0)).destroy();

        mBundle.clear();
        verify(mParcelSpy, times(1)).destroy();
        assertTrue(mBundle.isDefinitelyEmpty());

        // Should not destroy again
        mBundle.clear();
        verify(mParcelSpy, times(1)).destroy();
    }

    @Test
    public void bundleClear_whenUnparcelledWithLazy_destroysParcel() {
        setUpBundle(/* lazy */ 1);

        // Will unparcel but keep the CustomParcelable lazy
        assertFalse(mBundle.isEmpty());
        verify(mParcelSpy, times(0)).destroy();

        mBundle.clear();
        verify(mParcelSpy, times(1)).destroy();
        assertTrue(mBundle.isDefinitelyEmpty());

        // Should not recycle again
        mBundle.clear();
        verify(mParcelSpy, times(1)).destroy();
    }

    @Test
    public void bundleClear_whenClearedWithSharedParcel_doesNotDestroyParcel() {
        setUpBundle(/* lazy */ 1);

        Bundle copy = new Bundle();
        copy.putAll(mBundle);

        mBundle.clear();
        assertTrue(mBundle.isDefinitelyEmpty());

        copy.clear();
        assertTrue(copy.isDefinitelyEmpty());

        verify(mParcelSpy, never()).destroy();
    }

    @Test
    public void bundleClear_whenClearedWithCopiedParcel_doesNotDestroyParcel() {
        setUpBundle(/* lazy */ 1);

        // Will unparcel but keep the CustomParcelable lazy
        assertFalse(mBundle.isEmpty());

        Bundle copy = mBundle.deepCopy();
        copy.putAll(mBundle);

        mBundle.clear();
        assertTrue(mBundle.isDefinitelyEmpty());

        copy.clear();
        assertTrue(copy.isDefinitelyEmpty());

        verify(mParcelSpy, never()).destroy();
    }

    @Test
    public void bundleGet_whenUnparcelledWithLazyValueUnwrapped_destroysParcel() {
        setUpBundle(/* lazy */ 1);

        // Will unparcel with a lazy value, and immediately unwrap the lazy value,
        // with no lazy values left at the end of getParcelable
        // Ref counting should immediately recycle
        assertNotNull(mBundle.getParcelable("lazy0", CustomParcelable.class));
        verify(mParcelSpy, times(1)).destroy();

        // Should not recycle again
        assertNotNull(mBundle.getParcelable("lazy0", CustomParcelable.class));
        mBundle.clear();
        verify(mParcelSpy, times(1)).destroy();
    }

    @Test
    public void bundleGet_whenMultipleLazy_destroysParcelWhenAllUnwrapped() {
        setUpBundle(/* lazy */ 2);

        assertNotNull(mBundle.getParcelable("lazy0", CustomParcelable.class));
        verify(mParcelSpy, times(0)).destroy();

        assertNotNull(mBundle.getParcelable("lazy0", CustomParcelable.class));
        verify(mParcelSpy, times(0)).destroy();

        assertNotNull(mBundle.getParcelable("lazy1", CustomParcelable.class));
        verify(mParcelSpy, times(1)).destroy();

        // Should not recycle again
        assertNotNull(mBundle.getParcelable("lazy0", CustomParcelable.class));
        mBundle.clear();
        verify(mParcelSpy, times(1)).destroy();
        assertTrue(mBundle.isDefinitelyEmpty());
    }

    @Test
    public void bundleGet_whenLazyAndNonLazy_destroysParcelWhenAllUnwrapped() {
        setUpBundle(/* lazy */ 1, /* nonLazy */ 1);

        assertNotNull(mBundle.getParcelable("lazy0", CustomParcelable.class));
        verify(mParcelSpy, times(1)).destroy();

        // Should not destroy again
        assertNotNull(mBundle.getString("nonLazy0"));
        assertNotNull(mBundle.getParcelable("lazy0", CustomParcelable.class));
        mBundle.clear();
        verify(mParcelSpy, times(1)).destroy();
    }

    @Test
    public void bundleGet_whenLazyAndNonLazy_doesNotDestroyWhenOnlyNonLazyRetrieved() {
        setUpBundle(/* lazy */ 1, /* nonLazy */ 1);

        assertNotNull(mBundle.getString("nonLazy0"));
        verify(mParcelSpy, times(0)).destroy();

        assertNotNull(mBundle.getString("nonLazy0"));
        verify(mParcelSpy, times(0)).destroy();

        assertNotNull(mBundle.getParcelable("lazy0", CustomParcelable.class));
        verify(mParcelSpy, times(1)).destroy();
    }

    @Test
    public void bundleGet_withWithSharedParcel_doesNotDestroyParcel() {
        setUpBundle(/* lazy */ 1);

        Bundle copy = new Bundle();
        copy.putAll(mBundle);

        assertNotNull(mBundle.getParcelable("lazy0", CustomParcelable.class));
        mBundle.clear();

        assertNotNull(copy.getParcelable("lazy0", CustomParcelable.class));
        copy.clear();

        verify(mParcelSpy, never()).destroy();
    }

    @Test
    public void bundleGet_getAfterLazyCleared_doesNotDestroyAgain() {
        setUpBundle(/* lazy */ 1);
        mBundle.clear();
        verify(mParcelSpy, times(1)).destroy();

        assertNull(mBundle.getParcelable("lazy0", CustomParcelable.class));
        verify(mParcelSpy, times(1)).destroy();
    }

    private void setUpBundle(int lazy) {
        setUpBundle(lazy, /* nonLazy */ 0);
    }

    private void setUpBundle(int lazy, int nonLazy) {
        AtomicReference<Parcel> parcel = new AtomicReference<>();
        StaticMockitoSession session = mockitoSession()
                .strictness(Strictness.STRICT_STUBS)
                .spyStatic(Parcel.class)
                .startMocking();
        doAnswer((Answer<Parcel>) invocationOnSpy -> {
            Parcel spy = (Parcel) invocationOnSpy.callRealMethod();
            spyOn(spy);
            parcel.set(spy);
            return spy;
        }).when(() -> Parcel.obtain());

        Bundle bundle = new Bundle();
        bundle.setClassLoader(getClass().getClassLoader());
        Parcel p = createBundle(lazy, nonLazy);
        bundle.readFromParcel(p);
        p.recycle();

        session.finishMocking();

        mParcelSpy = parcel.get();
        mBundle = bundle;
    }

    /**
     * Create a test bundle, parcel it and return the parcel.
     */
    private Parcel createBundle(int lazy, int nonLazy) {
        final Bundle source = new Bundle();

        for (int i = 0; i < nonLazy; i++) {
            source.putString("nonLazy" + i, "Tiramisu");
        }

        for (int i = 0; i < lazy; i++) {
            source.putParcelable("lazy" + i, new CustomParcelable(13, "Tiramisu"));
        }

        return getParcelledBundle(source);
    }

    /**
     * Take a bundle, write it to a parcel and return the parcel.
     */
    private Parcel getParcelledBundle(Bundle bundle) {
        final Parcel p = Parcel.obtain();
        // Don't use p.writeParcelabe(), which would write the creator, which we don't need.
        bundle.writeToParcel(p, 0);
        p.setDataPosition(0);
        return p;
    }

    private static class CustomParcelable implements Parcelable {
        public final int integer;
        public final String string;

        CustomParcelable(int integer, String string) {
            this.integer = integer;
            this.string = string;
        }

        protected CustomParcelable(Parcel in) {
            integer = in.readInt();
            string = in.readString();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(integer);
            out.writeString(string);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CustomParcelable)) {
                return false;
            }
            CustomParcelable that = (CustomParcelable) other;
            return integer == that.integer && string.equals(that.string);
        }

        @Override
        public int hashCode() {
            return Objects.hash(integer, string);
        }

        public static final Creator<CustomParcelable> CREATOR = new Creator<CustomParcelable>() {
            @Override
            public CustomParcelable createFromParcel(Parcel in) {
                return new CustomParcelable(in);
            }
            @Override
            public CustomParcelable[] newArray(int size) {
                return new CustomParcelable[size];
            }
        };
    }
}
