package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.res.R;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

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
        BcSmartspaceTemplateDataUtils.updateVisibility(mQrCodeView, View.GONE);
    }

    @Override
    public final boolean setSmartspaceActions(
            SmartspaceTarget target,
            BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier,
            BcSmartspaceCardLoggingInfo loggingInfo) {
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
        BcSmartspaceTemplateDataUtils.updateVisibility(mQrCodeView, View.VISIBLE);
        return true;
    }

    public BcSmartspaceCardFlight(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }

    @Override
    public final void setTextColor(int color) {}
}
