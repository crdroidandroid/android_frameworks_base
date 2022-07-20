package com.google.android.systemui.smartspace;

import android.app.PendingIntent;
import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceTargetEvent;
import android.app.smartspace.uitemplatedata.TapAction;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.util.Log;
import android.view.View;
import com.android.systemui.bcsmartspace.R;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.FalsingManager;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLogger;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

public final class BcSmartSpaceUtil {
    private static final String GSA_PACKAGE = "com.google.android.googlequicksearchbox";
    private static final String GSA_WEATHER_ACTIVITY = "com.google.android.apps.search.weather.WeatherExportedActivity";

    public static FalsingManager sFalsingManager;
    public static BcSmartspaceDataPlugin.IntentStarter sIntentStarter;

    public static void setOnClickListener(View view, final SmartspaceTarget smartspaceTarget, final SmartspaceAction smartspaceAction, final BcSmartspaceDataPlugin.SmartspaceEventNotifier smartspaceEventNotifier, final String str, final BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo, final int i) {
        if (view != null && smartspaceAction != null) {
            final boolean z = smartspaceAction.getExtras() != null && smartspaceAction.getExtras().getBoolean("show_on_lockscreen");
            final boolean z2 = smartspaceAction.getIntent() == null && smartspaceAction.getPendingIntent() == null;
            BcSmartspaceDataPlugin.IntentStarter intentStarter = sIntentStarter;
            if (intentStarter == null) {
                intentStarter = new SmartspaceIntentStarter(str);
            }
            final BcSmartspaceDataPlugin.IntentStarter intentStarter2 = intentStarter;
            view.setOnClickListener(new View.OnClickListener() { // from class: com.google.android.systemui.smartspace.BcSmartSpaceUtil.1
                @Override // android.view.View.OnClickListener
                public void onClick(View v) {
                    FalsingManager falsingManager = BcSmartSpaceUtil.sFalsingManager;
                    if (falsingManager == null || !falsingManager.isFalseTap(1)) {
                        if (bcSmartspaceCardLoggingInfo != null) {
                            if (bcSmartspaceCardLoggingInfo.mSubcardInfo != null) {
                                bcSmartspaceCardLoggingInfo.mSubcardInfo.mClickedSubcardIndex = i;
                            }
                            BcSmartspaceCardLogger.log(BcSmartspaceEvent.SMARTSPACE_CARD_CLICK, bcSmartspaceCardLoggingInfo);
                        }
                        if (!z2 && !hijackIntent(smartspaceTarget, intentStarter2, v)) {
                            intentStarter2.startFromAction(smartspaceAction, v, z);
                        }
                        if (smartspaceEventNotifier == null) {
                            Log.w(str, "Cannot notify target interaction smartspace event: event notifier null.");
                        } else {
                            smartspaceEventNotifier.notifySmartspaceEvent(new SmartspaceTargetEvent.Builder(1).setSmartspaceTarget(smartspaceTarget).setSmartspaceActionId(smartspaceAction.getId()).build());
                        }
                    }
                }
            });
            return;
        }
        Log.e(str, "No tap action can be set up");
    }

    public static void setOnClickListener(View view, final SmartspaceTarget smartspaceTarget, final TapAction tapAction, final BcSmartspaceDataPlugin.SmartspaceEventNotifier smartspaceEventNotifier, final String str, final BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo, final int i) {
        if (view != null && tapAction != null) {
            final boolean shouldShowOnLockscreen = tapAction.shouldShowOnLockscreen();
            view.setOnClickListener(new View.OnClickListener() { // from class: com.google.android.systemui.smartspace.BcSmartSpaceUtil.2
                @Override // android.view.View.OnClickListener
                public void onClick(View view2) {
                    FalsingManager falsingManager = BcSmartSpaceUtil.sFalsingManager;
                    if (falsingManager == null || !falsingManager.isFalseTap(1)) {
                        if (bcSmartspaceCardLoggingInfo != null) {
                            if (bcSmartspaceCardLoggingInfo.mSubcardInfo != null) {
                                bcSmartspaceCardLoggingInfo.mSubcardInfo.mClickedSubcardIndex = i;
                            }
                            BcSmartspaceCardLogger.log(BcSmartspaceEvent.SMARTSPACE_CARD_CLICK, bcSmartspaceCardLoggingInfo);
                        }
                        BcSmartspaceDataPlugin.IntentStarter intentStarter = BcSmartSpaceUtil.sIntentStarter;
                        if (intentStarter == null) {
                            intentStarter = new SmartspaceIntentStarter(str);
                        }
                        boolean z = tapAction == null || (tapAction.getIntent() == null && tapAction.getPendingIntent() == null);
                        if (!z && !hijackIntent(smartspaceTarget, intentStarter, view2)) {
                            intentStarter.startFromAction(tapAction, view2, shouldShowOnLockscreen);
                        }
                        if (smartspaceEventNotifier == null) {
                            Log.w(str, "Cannot notify target interaction smartspace event: event notifier null.");
                        } else {
                            smartspaceEventNotifier.notifySmartspaceEvent(new SmartspaceTargetEvent.Builder(1).setSmartspaceTarget(smartspaceTarget).setSmartspaceActionId(tapAction.getId().toString()).build());
                        }
                    }
                }
            });
            return;
        }
        Log.e(str, "No tap action can be set up");
    }

    public static void setOnClickListener(BcSmartspaceCardSecondary bcSmartspaceCardSecondary, SmartspaceTarget smartspaceTarget, TapAction tapAction, BcSmartspaceDataPlugin.SmartspaceEventNotifier smartspaceEventNotifier, String str, BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo) {
        setOnClickListener(bcSmartspaceCardSecondary, smartspaceTarget, tapAction, smartspaceEventNotifier, str, bcSmartspaceCardLoggingInfo, 0);
    }

    public static Drawable getIconDrawable(Context context, Icon icon) {
        Drawable bitmapDrawable;
        if (icon == null) {
            return null;
        }
        if (icon.getType() == 1 || icon.getType() == 5) {
            bitmapDrawable = new BitmapDrawable(context.getResources(), icon.getBitmap());
        } else {
            bitmapDrawable = icon.loadDrawable(context);
        }
        if (bitmapDrawable != null) {
            int dimensionPixelSize = context.getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_icon_size);
            bitmapDrawable.setBounds(0, 0, dimensionPixelSize, dimensionPixelSize);
        }
        return bitmapDrawable;
    }

    public static void setFalsingManager(FalsingManager falsingManager) {
        sFalsingManager = falsingManager;
    }

    public static void setIntentStarter(BcSmartspaceDataPlugin.IntentStarter intentStarter) {
        sIntentStarter = intentStarter;
    }

    public static Intent getOpenCalendarIntent() {
        return new Intent("android.intent.action.VIEW").setData(ContentUris.appendId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time"), System.currentTimeMillis()).build()).addFlags(270532608);
    }

    // Workaround for Google weather
    private static boolean hijackIntent(SmartspaceTarget smartspaceTarget, BcSmartspaceDataPlugin.IntentStarter intentStarter, View v) {
        if (v instanceof IcuDateTextView) {
            // Ensure we don't change date view
            return false;
        }
        if (smartspaceTarget != null && smartspaceTarget.getFeatureType() == SmartspaceTarget.FEATURE_WEATHER) {
            Intent intent = new Intent().setComponent(new ComponentName(GSA_PACKAGE, GSA_WEATHER_ACTIVITY))
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intentStarter.startIntent(v, intent, true);
            return true;
        }
        return false;
    }

    /* renamed from: com.google.android.systemui.smartspace.BcSmartSpaceUtil$AnonymousClass1  reason: case insensitive filesystem */
    public static class SmartspaceIntentStarter implements BcSmartspaceDataPlugin.IntentStarter {
        public final String tag;

        public SmartspaceIntentStarter(String str) {
            this.tag = str;
        }

        public void startIntent(View view, Intent intent, boolean z) {
            try {
                view.getContext().startActivity(intent);
            } catch (ActivityNotFoundException | NullPointerException | SecurityException e) {
                Log.e(this.tag, "Cannot invoke smartspace intent", e);
            }
        }

        public void startPendingIntent(View view, PendingIntent pendingIntent, boolean z) {
            try {
                pendingIntent.send();
            } catch (PendingIntent.CanceledException e) {
                Log.e(this.tag, "Cannot invoke canceled smartspace intent", e);
            }
        }
    }

    public static String getDimensionRatio(Bundle bundle) {
        if (bundle.containsKey("imageRatioWidth") && bundle.containsKey("imageRatioHeight")) {
            int i = bundle.getInt("imageRatioWidth");
            int i2 = bundle.getInt("imageRatioHeight");
            if (i > 0 && i2 > 0) {
                return i + ":" + i2;
            }
            return null;
        }
        return null;
    }

    public static int getLoggingDisplaySurface(String str, boolean z, float f) {
        if (str.equals("com.google.android.apps.nexuslauncher")) {
            return 1;
        }
        if (str.equals("com.android.systemui")) {
            if (f == 1.0f) {
                return 3;
            }
            return f == 0.0f ? 2 : -1;
        } else if (z) {
            return 5;
        } else {
            return 0;
        }
    }
}
