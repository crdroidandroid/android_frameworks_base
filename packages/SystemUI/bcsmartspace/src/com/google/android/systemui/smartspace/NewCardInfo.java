package com.google.android.systemui.smartspace;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import com.android.systemui.smartspace.nano.SmartspaceProto;
import java.io.ByteArrayOutputStream;

public final class NewCardInfo {
    public final SmartspaceProto.SmartspaceUpdate.SmartspaceCard mCard;
    public final Intent mIntent;
    public final boolean mIsPrimary;
    public final PackageInfo mPackageInfo;
    public final long mPublishTime;

    public NewCardInfo(SmartspaceProto.SmartspaceUpdate.SmartspaceCard smartspaceCard, Intent intent, boolean z, long j, PackageInfo packageInfo) {
        this.mCard = smartspaceCard;
        this.mIsPrimary = z;
        this.mIntent = intent;
        this.mPublishTime = j;
        this.mPackageInfo = packageInfo;
    }

    public boolean isPrimary() {
        return this.mIsPrimary;
    }

    public Bitmap retrieveIcon(Context context) {
        if (this.mCard.icon == null) {
            return null;
        }
        Bitmap bitmap = (Bitmap) retrieveFromIntent(this.mCard.icon.key, this.mIntent);
        if (bitmap != null) {
            return bitmap;
        }
        try {
            if (!TextUtils.isEmpty(this.mCard.icon.uri)) {
                return MediaStore.Images.Media.getBitmap(context.getContentResolver(), Uri.parse(this.mCard.icon.uri));
            }
            if (!TextUtils.isEmpty(this.mCard.icon.gsaResourceName)) {
                Intent.ShortcutIconResource shortcutIconResource = new Intent.ShortcutIconResource();
                shortcutIconResource.packageName = "com.google.android.googlequicksearchbox";
                shortcutIconResource.resourceName = this.mCard.icon.gsaResourceName;
                return createIconBitmap(shortcutIconResource, context);
            }
            return null;
        } catch (Exception e) {
            Log.e("NewCardInfo", "retrieving bitmap uri=" + this.mCard.icon.uri + " gsaRes=" + this.mCard.icon.gsaResourceName);
            return null;
        }
    }

    public SmartspaceProto.CardWrapper toWrapper(Context context) {
        SmartspaceProto.CardWrapper cardWrapper = new SmartspaceProto.CardWrapper();
        Bitmap retrieveIcon = retrieveIcon(context);
        if (retrieveIcon != null) {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            retrieveIcon.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
            cardWrapper.icon = byteArrayOutputStream.toByteArray();
        }
        cardWrapper.card = this.mCard;
        cardWrapper.publishTime = this.mPublishTime;
        PackageInfo packageInfo = this.mPackageInfo;
        if (packageInfo != null) {
            cardWrapper.gsaVersionCode = packageInfo.versionCode;
            cardWrapper.gsaUpdateTime = packageInfo.lastUpdateTime;
        }
        return cardWrapper;
    }

    private static <T> T retrieveFromIntent(String str, Intent intent) {
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        return (T) intent.getParcelableExtra(str);
    }

    static Bitmap createIconBitmap(Intent.ShortcutIconResource shortcutIconResource, Context context) {
        try {
            Resources resourcesForApplication = context.getPackageManager().getResourcesForApplication(shortcutIconResource.packageName);
            if (resourcesForApplication != null) {
                return BitmapFactory.decodeResource(resourcesForApplication, resourcesForApplication.getIdentifier(shortcutIconResource.resourceName, null, null));
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public int getUserId() {
        return this.mIntent.getIntExtra("uid", -1);
    }

    public boolean shouldDiscard() {
        return this.mCard == null || this.mCard.shouldDiscard;
    }
}
