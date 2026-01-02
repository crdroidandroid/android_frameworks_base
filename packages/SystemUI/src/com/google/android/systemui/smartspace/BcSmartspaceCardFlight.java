package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import com.android.systemui.res.R;

public class BcSmartspaceCardFlight extends BcSmartspaceCardSecondary {
    public ImageView mQrCodeView;

    public BcSmartspaceCardFlight(Context context) {
        super(context);
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        mQrCodeView = (ImageView) findViewById(R.id.flight_qr_code);
    }

    @Override
    public final void resetUi() {
        BcSmartspaceTemplateDataUtils.updateVisibility(mQrCodeView, 8);
    }
    @Override
    public final boolean setSmartspaceActions(SmartspaceTarget target, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, BcSmartspaceCardLoggingInfo loggingInfo) {
        SmartspaceAction baseAction = target.getBaseAction();
        Bundle extras = baseAction == null ? null : baseAction.getExtras();
        if (extras == null || !extras.containsKey("qrCodeBitmap")) {
            return false;
        }
        Bitmap bitmap = (Bitmap) extras.get("qrCodeBitmap");
        ImageView imageView = mQrCodeView;
        if (imageView == null) {
            Log.w("BcSmartspaceCardFlight", "No flight QR code view to update");
        } else {
            imageView.setImageBitmap(bitmap);
        }
        BcSmartspaceTemplateDataUtils.updateVisibility(mQrCodeView, 0);
        return true;
    }

    public BcSmartspaceCardFlight(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }

    @Override
    public final void setTextColor(int color) {
    }
}
