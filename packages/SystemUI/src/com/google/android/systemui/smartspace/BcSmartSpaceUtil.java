package com.google.android.systemui.smartspace;

import android.app.PendingIntent;
import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceTargetEvent;
import android.app.smartspace.uitemplatedata.TapAction;
import android.content.ActivityNotFoundException;
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
import android.widget.RemoteViews;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.FalsingManager;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLogger;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import com.android.systemui.res.R;

import java.util.List;
import java.util.Map;

public abstract class BcSmartSpaceUtil {
    public static final Map<Integer, Integer> FEATURE_TYPE_TO_SECONDARY_CARD_RESOURCE_MAP;
    public static FalsingManager sFalsingManager;

    public static class InteractionHandler implements RemoteViews.InteractionHandler {
        public final BcSmartspaceCardLoggingInfo loggingInfo;
        public final BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier;
        public final SmartspaceTarget target;
        public final SmartspaceAction action;

        public InteractionHandler(
                BcSmartspaceCardLoggingInfo loggingInfo,
                BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier,
                SmartspaceTarget target,
                SmartspaceAction action) {
            this.loggingInfo = loggingInfo;
            this.eventNotifier = eventNotifier;
            this.target = target;
            this.action = action;
        }

        @Override
        public final boolean onInteraction(View view, PendingIntent pendingIntent, RemoteViews.RemoteResponse remoteResponse) {
            BcSmartspaceDataPlugin.IntentStarter intentStarter = BcSmartSpaceUtil.getIntentStarter(eventNotifier, "BcSmartspaceRemoteViewsCard");
            if (pendingIntent != null) {
                BcSmartspaceCardLogger.log(BcSmartspaceEvent.SMARTSPACE_CARD_CLICK, loggingInfo);
                if (eventNotifier != null) {
                    eventNotifier.notifySmartspaceEvent(new SmartspaceTargetEvent.Builder(1).setSmartspaceTarget(target).setSmartspaceActionId(action.getId()).build());
                }
                intentStarter.startPendingIntent(view, pendingIntent, false);
            }
            return true;
        }
    }

    public static class DefaultIntentStarter implements BcSmartspaceDataPlugin.IntentStarter {
        public final String tag;

        public DefaultIntentStarter(String tag) {
            this.tag = tag;
        }

        @Override
        public final void startIntent(View view, Intent intent, boolean showOnLockscreen) {
            try {
                view.getContext().startActivity(intent);
            } catch (ActivityNotFoundException | NullPointerException | SecurityException e) {
                Log.e(tag, "Cannot invoke smartspace intent", e);
            }
        }

        @Override
        public final void startPendingIntent(View view, PendingIntent pendingIntent, boolean showOnLockscreen) {
            try {
                pendingIntent.send();
            } catch (PendingIntent.CanceledException e) {
                Log.e(tag, "Cannot invoke canceled smartspace intent", e);
            }
        }
    }

    static {
        FEATURE_TYPE_TO_SECONDARY_CARD_RESOURCE_MAP = Map.ofEntries(Map.entry(-1, Integer.valueOf(R.layout.smartspace_card_combination)), Map.entry(-2, Integer.valueOf(R.layout.smartspace_card_combination_at_store)), Map.entry(3, Integer.valueOf(R.layout.smartspace_card_generic_landscape_image)), Map.entry(18, Integer.valueOf(R.layout.smartspace_card_generic_landscape_image)), Map.entry(4, Integer.valueOf(R.layout.smartspace_card_flight)), Map.entry(14, Integer.valueOf(R.layout.smartspace_card_loyalty)), Map.entry(13, Integer.valueOf(R.layout.smartspace_card_shopping_list)), Map.entry(9, Integer.valueOf(R.layout.smartspace_card_sports)), Map.entry(10, Integer.valueOf(R.layout.smartspace_card_weather_forecast)), Map.entry(30, Integer.valueOf(R.layout.smartspace_card_doorbell)), Map.entry(20, Integer.valueOf(R.layout.smartspace_card_doorbell)));
    }

    public static String getDimensionRatio(Bundle bundle) {
        if (!bundle.containsKey("imageRatioWidth") || !bundle.containsKey("imageRatioHeight")) {
            return null;
        }
        int width = bundle.getInt("imageRatioWidth");
        int height = bundle.getInt("imageRatioHeight");
        if (width <= 0 || height <= 0) {
            return null;
        }
        return width + ":" + height;
    }

    public static int getFeatureType(SmartspaceTarget target) {
        List actionChips = target.getActionChips();
        int featureType = target.getFeatureType();
        return (actionChips == null || actionChips.isEmpty()) ? featureType : (featureType == 13 && actionChips.size() == 1) ? -2 : -1;
    }

    public static Drawable getIconDrawableWithCustomSize(Icon icon, Context context, int size) {
        if (icon == null) {
            return null;
        }
        Drawable drawable = (icon.getType() == 1 || icon.getType() == 5) ? new BitmapDrawable(context.getResources(), icon.getBitmap()) : icon.loadDrawable(context);
        if (drawable != null) {
            drawable.setBounds(0, 0, size, size);
        }
        return drawable;
    }

    public static BcSmartspaceDataPlugin.IntentStarter getIntentStarter(BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, String tag) {
        BcSmartspaceDataPlugin.IntentStarter intentStarter = eventNotifier != null ? eventNotifier.getIntentStarter() : null;
        if (intentStarter != null) {
            return intentStarter;
        }
        return new DefaultIntentStarter(tag);
    }

    public static int getLoggingDisplaySurface(String uiSurface, float dozeAmount) {
        if (uiSurface == null) {
            return 0;
        }

        switch (uiSurface) {
            case BcSmartspaceDataPlugin.UI_SURFACE_HOME_SCREEN:
                return 1;
            case BcSmartspaceDataPlugin.UI_SURFACE_DREAM:
                return 5;
            case BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD:
                if (dozeAmount == 1.0f) {
                    return 3;
                } else if (dozeAmount == 0.0f) {
                    return 2;
                } else {
                    return -1;
                }
            default:
                return 0;
        }
    }

    public static Intent getOpenCalendarIntent() {
        return new Intent("android.intent.action.VIEW").setData(ContentUris.appendId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time"), System.currentTimeMillis()).build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    public static void setOnClickListener(View view, SmartspaceTarget target, SmartspaceAction action, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, String tag, BcSmartspaceCardLoggingInfo loggingInfo, int index) {
        if (view == null || action == null) {
            Log.e(tag, "No tap action can be set up");
            return;
        }

        boolean isNoIntent = action.getIntent() == null && action.getPendingIntent() == null;
        boolean showOnLockscreen = action.getExtras() != null && action.getExtras().getBoolean("show_on_lockscreen");
        BcSmartspaceDataPlugin.IntentStarter intentStarter = getIntentStarter(eventNotifier, tag);

        view.setOnClickListener(v -> {
            if (sFalsingManager != null && sFalsingManager.isFalseTap(1)) {
                return;
            }

            if (loggingInfo != null) {
                if (loggingInfo.mSubcardInfo != null) {
                    loggingInfo.mSubcardInfo.mClickedSubcardIndex = index;
                }
                BcSmartspaceCardLogger.log(BcSmartspaceEvent.SMARTSPACE_CARD_CLICK, loggingInfo);
            }

            if (!isNoIntent) {
                intentStarter.startFromAction(action, v, showOnLockscreen);
            }

            if (eventNotifier == null) {
                Log.w(tag, "Cannot notify target interaction smartspace event: event notifier null.");
            } else {
                eventNotifier.notifySmartspaceEvent(new SmartspaceTargetEvent.Builder(SmartspaceTargetEvent.EVENT_TARGET_INTERACTION)
                        .setSmartspaceTarget(target)
                        .setSmartspaceActionId(action.getId())
                        .build());
            }
        });
    }

    public static void setOnClickListener(View view, SmartspaceTarget target, TapAction tapAction, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, String tag, BcSmartspaceCardLoggingInfo loggingInfo, int index) {
        if (view == null || tapAction == null) {
            Log.e(tag, "No tap action can be set up");
            return;
        }

        boolean showOnLockscreen = tapAction.shouldShowOnLockscreen();

        view.setOnClickListener(v -> {
            if (sFalsingManager != null && sFalsingManager.isFalseTap(1)) {
                return;
            }

            if (loggingInfo != null) {
                if (loggingInfo.mSubcardInfo != null) {
                    loggingInfo.mSubcardInfo.mClickedSubcardIndex = index;
                }
                BcSmartspaceCardLogger.log(BcSmartspaceEvent.SMARTSPACE_CARD_CLICK, loggingInfo);
            }

            BcSmartspaceDataPlugin.IntentStarter intentStarter = getIntentStarter(eventNotifier, tag);
            if (tapAction.getIntent() != null || tapAction.getPendingIntent() != null) {
                intentStarter.startFromAction(tapAction, v, showOnLockscreen);
            }

            if (eventNotifier == null) {
                Log.w(tag, "Cannot notify target interaction smartspace event: event notifier null.");
            } else {
                eventNotifier.notifySmartspaceEvent(new SmartspaceTargetEvent.Builder(SmartspaceTargetEvent.EVENT_TARGET_INTERACTION)
                        .setSmartspaceTarget(target)
                        .setSmartspaceActionId(tapAction.getId().toString())
                        .build());
            }
        });
    }
}
