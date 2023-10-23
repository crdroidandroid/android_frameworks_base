package com.android.server.ext;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.server.LocalServices;
import com.android.server.art.model.DexoptResult;
import com.android.server.pm.PackageManagerService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public class BgDexoptUi {
    static final String TAG = BgDexoptUi.class.getSimpleName();

    private static final int NOTIF_ID = SystemMessageProto.SystemMessage.NOTE_BACKGROUND_DEXOPT;

    public static void onBgDexoptProgressUpdate(PackageManagerService pm, @ElapsedRealtimeLong long start,
                                                int percentage, int current, int total) {
        long elapsedMs = SystemClock.elapsedRealtime() - start;
        if (elapsedMs < 15_000) {
            Slog.d(TAG, "skipping progress update, elapsed time since start: " + elapsedMs + " ms");
            return;
        }

        Context ctx = pm.getContext();

        var b = new Notification.Builder(ctx, SystemNotificationChannels.BACKGROUND_DEXOPT_PROGRESS);
        b.setSmallIcon(R.drawable.ic_update);
        b.setContentTitle(ctx.getString(R.string.bg_dexopt_notif_title, current, total));
        b.setContentText(ctx.getText(R.string.bg_dexopt_notif_text));
        b.setProgress(total, current, false);
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        showNotif(ctx, b);
    }

    // null result indicates an error, durationMs parameter is invalid in that case
    public static void onBgDexoptCompleted(PackageManagerService pm, @Nullable DexoptResult result, long durationMs) {
        Context ctx = pm.getContext();

        var notifM = ctx.getSystemService(NotificationManager.class);
        if (notifM != null) {
            notifM.cancel(NOTIF_ID);
        }

        if (result == null) {
            return;
        }

        HashSet<String> changedPackages = getChangedPackages(result);

        if (changedPackages.isEmpty()) {
            return;
        }

        // in case user doesn't tap on the notification
        killProcessesWhenImperceptible(ctx, changedPackages);

        var b = new Notification.Builder(ctx, SystemNotificationChannels.BACKGROUND_DEXOPT_COMPLETED);
        b.setSmallIcon(R.drawable.ic_pending);
        b.setContentTitle(ctx.getText(R.string.bg_dexopt_completed_notif_title));
        b.setStyle(new Notification.BigTextStyle());
        b.setContentText(ctx.getString(R.string.bg_dexopt_completed_notif_text,
            DateUtils.formatDuration(durationMs)));
        b.setAutoCancel(true);

        var args = new Bundle();
        args.putStringArrayList(Intent.EXTRA_PACKAGES, new ArrayList<>(changedPackages));
        b.setContentIntent(IntentReceiver.getPendingIntent(NotifActionReceiver.class, ctx, args));

        showNotif(ctx, b);
    }

    static class NotifActionReceiver extends IntentReceiver {
        @Override
        public void onReceive(Context ctx, Bundle args) {
            HashSet<String> set = new HashSet<>(args.getStringArrayList(Intent.EXTRA_PACKAGES));
            var am = requireNonNull(ctx.getSystemService(ActivityManager.class));

            for (RunningAppProcessInfo proc : getDependantAppProcesses(am, set)) {
                am.killUid(proc.uid, "bg-dexopt-completed");
            }
        }
    }

    private static void killProcessesWhenImperceptible(Context ctx, HashSet<String> changedPackages) {
        var am = requireNonNull(ctx.getSystemService(ActivityManager.class));
        List<RunningAppProcessInfo> affected = getDependantAppProcesses(am, changedPackages);
        int[] pids = affected.stream().mapToInt(e -> e.pid).toArray();
        am.killProcessesWhenImperceptible(pids, "imperceptible-after-bg-dexopt");
    }

    private static HashSet<String> getChangedPackages(DexoptResult dexoptResult) {
        var pm = LocalServices.getService(PackageManagerInternal.class);

        var results = dexoptResult.getPackageDexoptResults();
        var set = new HashSet<String>(results.size());

        for (DexoptResult.PackageDexoptResult r : results) {
            if (!r.hasUpdatedArtifacts()) {
                continue;
            }

            String pkgName = r.getPackageName();
            var psi = pm.getPackageStateInternal(pkgName);
            if (psi == null || psi.isSystem()) {
                continue;
            }
            for (DexoptResult.DexContainerFileDexoptResult cr : r.getDexContainerFileDexoptResults()) {
                Slog.d(TAG, cr.toString());
                if (cr.getDexContainerFile().endsWith("base.apk") && cr.getStatus() == DexoptResult.DEXOPT_PERFORMED) {
                    set.add(r.getPackageName());
                    break;
                }
            }
        }

        return set;
    }

    private static List<RunningAppProcessInfo> getDependantAppProcesses(ActivityManager am, HashSet<String> pkgs) {
        return am.getRunningAppProcesses().stream()
            .filter(proc -> containsAny(pkgs, proc.pkgList) || containsAny(pkgs, proc.pkgDeps))
            .collect(Collectors.toList());
    }

    private static void showNotif(Context ctx, Notification.Builder notif) {
        var nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) {
            Slog.e(TAG, "NotificationManager is null");
        } else {
            nm.notify(NOTIF_ID, notif.build());
        }
    }

    private static boolean containsAny(HashSet<String> set, @Nullable String[] arr) {
        if (arr == null) {
            return false;
        }
        for (String s : arr) {
            if (set.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
