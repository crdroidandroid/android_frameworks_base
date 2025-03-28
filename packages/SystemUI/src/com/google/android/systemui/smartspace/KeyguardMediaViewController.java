package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.os.UserHandle;
import android.text.TextUtils;
import android.view.View;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.media.NotificationMediaManager;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.concurrency.DelayableExecutor;

import kotlin.Unit;
import kotlin.jvm.internal.Intrinsics;

import javax.inject.Inject;

/* compiled from: KeyguardMediaViewController.kt */
@SysUISingleton
public final class KeyguardMediaViewController {
    private CharSequence artist;
    private final BroadcastDispatcher broadcastDispatcher;
    private final Context context;
    private final ComponentName mediaComponent;
    private final NotificationMediaManager.MediaListener mediaListener =
            new NotificationMediaManager.MediaListener() {
                @Override
                public void onPrimaryMetadataOrStateChanged(
                        final MediaMetadata mediaMetadata, final int i) {
                    DelayableExecutor uiExecutor = getUiExecutor();
                    final KeyguardMediaViewController keyguardMediaViewController =
                            KeyguardMediaViewController.this;
                    uiExecutor.execute(
                            new Runnable() {
                                @Override
                                public final void run() {
                                    updateMediaInfo(mediaMetadata, i);
                                }
                            });
                }
            };
    private final NotificationMediaManager mediaManager;
    private final BcSmartspaceDataPlugin plugin;
    private BcSmartspaceDataPlugin.SmartspaceView smartspaceView;
    private CharSequence title;
    private final DelayableExecutor uiExecutor;
    private UserTracker userTracker;

    @Inject
    public KeyguardMediaViewController(
            Context context,
            BcSmartspaceDataPlugin plugin,
            DelayableExecutor uiExecutor,
            NotificationMediaManager mediaManager,
            BroadcastDispatcher broadcastDispatcher,
            UserTracker userTracker) {
        Intrinsics.checkNotNullParameter(context, "context");
        Intrinsics.checkNotNullParameter(plugin, "plugin");
        Intrinsics.checkNotNullParameter(uiExecutor, "uiExecutor");
        Intrinsics.checkNotNullParameter(mediaManager, "mediaManager");
        Intrinsics.checkNotNullParameter(broadcastDispatcher, "broadcastDispatcher");
        this.context = context;
        this.plugin = plugin;
        this.uiExecutor = uiExecutor;
        this.mediaManager = mediaManager;
        this.broadcastDispatcher = broadcastDispatcher;
        this.userTracker = userTracker;
        mediaComponent = new ComponentName(context, KeyguardMediaViewController.class);
    }

    public final DelayableExecutor getUiExecutor() {
        return uiExecutor;
    }

    public final BcSmartspaceDataPlugin.SmartspaceView getSmartspaceView() {
        return smartspaceView;
    }

    public final void setSmartspaceView(BcSmartspaceDataPlugin.SmartspaceView smartspaceView) {
        this.smartspaceView = smartspaceView;
    }

    public final void init() {
        plugin.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        NotificationMediaManager notificationMediaManager;
                        NotificationMediaManager.MediaListener keyguardMediaViewController;
                        Intrinsics.checkNotNullParameter(v, "v");
                        setSmartspaceView((BcSmartspaceDataPlugin.SmartspaceView) v);
                        notificationMediaManager = mediaManager;
                        keyguardMediaViewController = mediaListener;
                        notificationMediaManager.addCallback(keyguardMediaViewController);
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        NotificationMediaManager notificationMediaManager;
                        NotificationMediaManager.MediaListener keyguardMediaViewController;
                        Intrinsics.checkNotNullParameter(v, "v");
                        setSmartspaceView(null);
                        notificationMediaManager = mediaManager;
                        keyguardMediaViewController = mediaListener;
                        notificationMediaManager.removeCallback(keyguardMediaViewController);
                    }
                });
    }

    public final void updateMediaInfo(MediaMetadata mediaMetadata, int i) {
        CharSequence charSequence;
        if (!NotificationMediaManager.isPlayingState(i)) {
            reset();
            return;
        }
        Unit unit = null;
        if (mediaMetadata == null) {
            charSequence = null;
        } else {
            charSequence = mediaMetadata.getText("android.media.metadata.TITLE");
            if (TextUtils.isEmpty(charSequence)) {
                charSequence =
                        context.getResources()
                                .getString(
                                        com.android.systemui.res.R.string.music_controls_no_title);
            }
        }
        CharSequence text =
                mediaMetadata == null
                        ? null
                        : mediaMetadata.getText("android.media.metadata.ARTIST");
        if (TextUtils.equals(title, charSequence) && TextUtils.equals(artist, text)) {
            return;
        }
        title = charSequence;
        artist = text;
        if (charSequence != null) {
            SmartspaceAction build =
                    new SmartspaceAction.Builder("deviceMediaTitle", charSequence.toString())
                            .setSubtitle(artist)
                            .setIcon(mediaManager.getMediaIcon())
                            .build();
            if (userTracker == null) {
                Intrinsics.throwUninitializedPropertyAccessException("userTracker");
                throw null;
            }
            SmartspaceTarget build2 =
                    new SmartspaceTarget.Builder(
                                    "deviceMedia",
                                    mediaComponent,
                                    UserHandle.of(userTracker.getUserId()))
                            .setFeatureType(15)
                            .setHeaderAction(build)
                            .build();
            BcSmartspaceDataPlugin.SmartspaceView smartspaceView = getSmartspaceView();
            if (smartspaceView != null) {
                smartspaceView.setMediaTarget(build2);
                unit = Unit.INSTANCE;
            }
        }
        if (unit != null) {
            return;
        }
        reset();
    }

    public final void reset() {
        title = null;
        artist = null;
        BcSmartspaceDataPlugin.SmartspaceView smartspaceView = getSmartspaceView();
        if (smartspaceView == null) {
            return;
        }
        smartspaceView.setMediaTarget(null);
    }
}
