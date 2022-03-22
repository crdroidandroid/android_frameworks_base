package com.crdroid.android.systemui.keyguard;

import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Trace;
import android.text.TextUtils;
import android.util.Log;
import androidx.core.graphics.drawable.IconCompat;
import androidx.slice.Slice;
import androidx.slice.builders.ListBuilder;
import androidx.slice.builders.SliceAction;
import com.android.systemui.R;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.google.android.systemui.smartspace.SmartSpaceCard;
import com.google.android.systemui.smartspace.SmartSpaceController;
import com.google.android.systemui.smartspace.SmartSpaceData;
import com.google.android.systemui.smartspace.SmartSpaceUpdateListener;

import java.lang.ref.WeakReference;

import javax.inject.Inject;

public class CrDroidKeyguardSliceProvider extends KeyguardSliceProvider implements SmartSpaceUpdateListener {
    private static final String TAG = "CrDroidKeyguardSliceProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private boolean mHideSensitiveContent;
    @Inject
    public SmartSpaceController mSmartSpaceController;
    private SmartSpaceData mSmartSpaceData;
    private boolean mHideWorkContent = true;
    private final Uri mWeatherUri = Uri.parse("content://com.android.systemui.keyguard/smartSpace/weather");
    private final Uri mCalendarUri = Uri.parse("content://com.android.systemui.keyguard/smartSpace/calendar");

    @Override // com.android.systemui.keyguard.KeyguardSliceProvider, androidx.slice.SliceProvider
    public boolean onCreateSliceProvider() {
        boolean onCreateSliceProvider = super.onCreateSliceProvider();
        this.mSmartSpaceData = new SmartSpaceData();
        this.mSmartSpaceController.addListener(this);
        return onCreateSliceProvider;
    }

    @Override // com.android.systemui.keyguard.KeyguardSliceProvider
    public void onDestroy() {
        super.onDestroy();
        this.mSmartSpaceController.removeListener(this);
    }

    @Override // com.android.systemui.keyguard.KeyguardSliceProvider, androidx.slice.SliceProvider
    public Slice onBindSlice(Uri uri) {
        IconCompat iconCompat;
        Trace.beginSection(TAG + "#onBindSlice");
        ListBuilder listBuilder = new ListBuilder(getContext(), this.mSliceUri, -1);
        synchronized (this) {
            SmartSpaceCard currentCard = this.mSmartSpaceData.getCurrentCard();
            boolean z = false;
            if (currentCard != null && !currentCard.isExpired() && !TextUtils.isEmpty(currentCard.getTitle())) {
                boolean isSensitive = currentCard.isSensitive();
                boolean z2 = isSensitive && !this.mHideSensitiveContent && !currentCard.isWorkProfile();
                boolean z3 = isSensitive && !this.mHideWorkContent && currentCard.isWorkProfile();
                if (!isSensitive || z2 || z3) {
                    z = true;
                }
            }
            if (z) {
                Bitmap icon = currentCard.getIcon();
                SliceAction sliceAction = null;
                if (icon == null) {
                    iconCompat = null;
                } else {
                    iconCompat = IconCompat.createWithBitmap(icon);
                }
                PendingIntent pendingIntent = currentCard.getPendingIntent();
                if (!(iconCompat == null || pendingIntent == null)) {
                    sliceAction = SliceAction.create(pendingIntent, iconCompat, 1, currentCard.getTitle());
                }
                ListBuilder.HeaderBuilder title = new ListBuilder.HeaderBuilder(this.mHeaderUri).setTitle(currentCard.getFormattedTitle());
                if (sliceAction != null) {
                    title.setPrimaryAction(sliceAction);
                }
                listBuilder.setHeader(title);
                String subtitle = currentCard.getSubtitle();
                if (subtitle != null) {
                    ListBuilder.RowBuilder title2 = new ListBuilder.RowBuilder(this.mCalendarUri).setTitle(subtitle);
                    if (iconCompat != null) {
                        title2.addEndItem(iconCompat, 1);
                    }
                    if (sliceAction != null) {
                        title2.setPrimaryAction(sliceAction);
                    }
                    listBuilder.addRow(title2);
                }
                addZenModeLocked(listBuilder);
                addPrimaryActionLocked(listBuilder);
                Trace.endSection();
                return listBuilder.build();
            }
            if (needsMediaLocked()) {
                addMediaLocked(listBuilder);
            } else {
                listBuilder.addRow(new ListBuilder.RowBuilder(this.mDateUri).setTitle(getFormattedDateLocked()));
            }
            addWeather(listBuilder);
            addNextAlarmLocked(listBuilder);
            addZenModeLocked(listBuilder);
            addPrimaryActionLocked(listBuilder);
            Slice build = listBuilder.build();
            if (DEBUG) {
                Log.d("KeyguardSliceProvider", "Binding slice: " + build);
            }
            Trace.endSection();
            return build;
        }
    }

    private void addWeather(ListBuilder listBuilder) {
        SmartSpaceCard weatherCard = this.mSmartSpaceData.getWeatherCard();
        if (weatherCard != null && !weatherCard.isExpired()) {
            ListBuilder.RowBuilder title = new ListBuilder.RowBuilder(this.mWeatherUri).setTitle(weatherCard.getTitle());
            Bitmap icon = weatherCard.getIcon();
            if (icon != null) {
                IconCompat createWithBitmap = IconCompat.createWithBitmap(icon);
                createWithBitmap.setTintMode(PorterDuff.Mode.DST);
                title.addEndItem(createWithBitmap, 1);
            }
            listBuilder.addRow(title);
        }
    }

    @Override // com.google.android.systemui.smartspace.SmartSpaceUpdateListener
    public void onSensitiveModeChanged(boolean z, boolean z2) {
        boolean z3;
        boolean z4;
        synchronized (this) {
            z3 = true;
            if (this.mHideSensitiveContent != z) {
                this.mHideSensitiveContent = z;
                if (DEBUG) {
                    Log.d("KeyguardSliceProvider", "Public mode changed, hide data: " + z);
                }
                z4 = true;
            } else {
                z4 = false;
            }
            if (this.mHideWorkContent != z2) {
                this.mHideWorkContent = z2;
                if (DEBUG) {
                    Log.d("KeyguardSliceProvider", "Public work mode changed, hide data: " + z2);
                }
            } else {
                z3 = z4;
            }
        }
        if (z3) {
            notifyChange();
        }
    }

    @Override // com.google.android.systemui.smartspace.SmartSpaceUpdateListener
    public void onSmartSpaceUpdated(SmartSpaceData smartSpaceData) {
        synchronized (this) {
            this.mSmartSpaceData = smartSpaceData;
        }
        SmartSpaceCard weatherCard = smartSpaceData.getWeatherCard();
        if (weatherCard == null || weatherCard.getIcon() == null || weatherCard.isIconProcessed()) {
            notifyChange();
            return;
        }
        weatherCard.setIconProcessed(true);
        new AddShadowTask(this, weatherCard).execute(weatherCard.getIcon());
    }

    @Override // com.android.systemui.keyguard.KeyguardSliceProvider
    protected void updateClockLocked() {
        notifyChange();
    }

    private static class AddShadowTask extends AsyncTask<Bitmap, Void, Bitmap> {
        private final float mBlurRadius;
        private final WeakReference<CrDroidKeyguardSliceProvider> mProviderReference;
        private final SmartSpaceCard mWeatherCard;

        AddShadowTask(CrDroidKeyguardSliceProvider crdroidKeyguardSliceProvider, SmartSpaceCard smartSpaceCard) {
            this.mProviderReference = new WeakReference<>(crdroidKeyguardSliceProvider);
            this.mWeatherCard = smartSpaceCard;
            this.mBlurRadius = crdroidKeyguardSliceProvider.getContext().getResources().getDimension(R.dimen.smartspace_icon_shadow);
        }

        public Bitmap doInBackground(Bitmap... bitmapArr) {
            return applyShadow(bitmapArr[0]);
        }

        public void onPostExecute(Bitmap bitmap) {
            CrDroidKeyguardSliceProvider crdroidKeyguardSliceProvider;
            synchronized (this) {
                this.mWeatherCard.setIcon(bitmap);
                crdroidKeyguardSliceProvider = this.mProviderReference.get();
            }
            if (crdroidKeyguardSliceProvider != null) {
                crdroidKeyguardSliceProvider.notifyChange();
            }
        }

        private Bitmap applyShadow(Bitmap bitmap) {
            BlurMaskFilter blurMaskFilter = new BlurMaskFilter(this.mBlurRadius, BlurMaskFilter.Blur.NORMAL);
            Paint paint = new Paint();
            paint.setMaskFilter(blurMaskFilter);
            int[] iArr = new int[2];
            Bitmap extractAlpha = bitmap.extractAlpha(paint, iArr);
            Bitmap createBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(createBitmap);
            Paint paint2 = new Paint();
            paint2.setAlpha(70);
            canvas.drawBitmap(extractAlpha, (float) iArr[0], ((float) iArr[1]) + (this.mBlurRadius / 2.0f), paint2);
            extractAlpha.recycle();
            paint2.setAlpha(255);
            canvas.drawBitmap(bitmap, 0.0f, 0.0f, paint2);
            return createBitmap;
        }
    }
}
