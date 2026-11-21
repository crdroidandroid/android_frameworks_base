/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.contextualsearch;

import static android.Manifest.permission.ACCESS_CONTEXTUAL_SEARCH;
import static android.Manifest.permission.CREATE_USERS;
import static android.Manifest.permission.MANAGE_USERS;
import static android.Manifest.permission.QUERY_USERS;
import static android.Manifest.permission.START_TASKS_FROM_RECENTS;
import static android.app.AppOpsManager.OP_ASSIST_SCREENSHOT;
import static android.app.AppOpsManager.OP_ASSIST_STRUCTURE;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.contextualsearch.ContextualSearchManager.FEATURE_CONTEXTUAL_SEARCH;
import static android.content.Context.CONTEXTUAL_SEARCH_SERVICE;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION;
import static android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.android.hardware.input.Flags.enableContextualSearchDesktopEntrypoints;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_CONTENT;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_STRUCTURE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.app.contextualsearch.CallbackToken;
import android.app.contextualsearch.ContextualSearchConfig;
import android.app.contextualsearch.ContextualSearchManager;
import android.app.contextualsearch.ContextualSearchState;
import android.app.contextualsearch.IContextualSearchCallback;
import android.app.contextualsearch.IContextualSearchManager;
import android.app.contextualsearch.flags.Flags;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionManager;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelableException;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.IWindowManager;
import android.window.ScreenCaptureInternal.ScreenshotHardwareBuffer;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.am.AssistDataRequester;
import com.android.server.am.AssistDataRequester.AssistDataRequesterCallbacks;
import com.android.server.wm.ActivityAssistInfo;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ContextualSearchManagerService extends SystemService {
    private static final String TAG = ContextualSearchManagerService.class.getSimpleName();
    private static final int MSG_RESET_TEMPORARY_PACKAGE = 0;
    private static final int MAX_TEMP_PACKAGE_DURATION_MS = 1_000 * 60 * 2; // 2 minutes
    private static final int MSG_INVALIDATE_TOKEN = 1;
    private static final int MAX_TOKEN_VALID_DURATION_MS = 1_000 * 60 * 10; // 10 minutes

    /**
     * Below are internal entrypoints not supported by the
     * {@link ContextualSearchManager#startContextualSearch(int entrypoint)} method.
     *
     * <p>These values should be negative to avoid conflicting with the system entrypoints.
     */

    /** Entrypoint to be used when a foreground app invokes Contextual Search. */
    private static final int INTERNAL_ENTRYPOINT_APP = -1;

    private static final boolean DEBUG = false;

    private final Context mContext;
    private final ActivityManagerInternal mActivityManagerInternal;
    private final ActivityTaskManagerInternal mAtmInternal;
    private final PackageManagerInternal mPackageManager;
    private final WindowManagerInternal mWmInternal;
    private final AudioManager mAudioManager;
    private final UserManager mUserManager;
    private final Object mLock = new Object();
    private final AssistDataRequester mAssistDataRequester;

    private final AssistDataRequesterCallbacks mAssistDataCallbacks =
            new AssistDataRequesterCallbacks() {
                @Override
                public boolean canHandleReceivedAssistDataLocked() {
                    synchronized (mLock) {
                        return mStateCallback != null;
                    }
                }

                @Override
                public void onAssistDataReceivedLocked(
                        final Bundle data,
                        final int activityIndex,
                        final int activityCount) {
                    final IContextualSearchCallback callback;
                    synchronized (mLock) {
                        callback = mStateCallback;
                    }

                    if (callback != null) {
                        try {
                            callback.onResult(new ContextualSearchState(
                                    data.getParcelable(ASSIST_KEY_STRUCTURE, AssistStructure.class),
                                    data.getParcelable(ASSIST_KEY_CONTENT, AssistContent.class),
                                    data));
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error invoking ContextualSearchCallback", e);
                        }
                    } else {
                        Log.w(TAG, "Callback went away!");
                    }
                }

                @Override
                public void onAssistRequestCompleted() {
                    synchronized (mLock) {
                        mStateCallback = null;
                    }
                }
            };

    @GuardedBy("this")
    private Handler mTemporaryHandler;
    @GuardedBy("this")
    private String mTemporaryPackage = null;
    @GuardedBy("this")
    private long mTokenValidDurationMs = MAX_TOKEN_VALID_DURATION_MS;

    @GuardedBy("mLock")
    private IContextualSearchCallback mStateCallback;

    public ContextualSearchManagerService(@NonNull Context context) {
        super(context);
        if (DEBUG) Log.d(TAG, "ContextualSearchManagerService created");
        mContext = context;
        mActivityManagerInternal = Objects.requireNonNull(
                LocalServices.getService(ActivityManagerInternal.class));
        mAtmInternal = Objects.requireNonNull(
                LocalServices.getService(ActivityTaskManagerInternal.class));
        mPackageManager = LocalServices.getService(PackageManagerInternal.class);
        mAudioManager = context.getSystemService(AudioManager.class);
        mUserManager = context.getSystemService(UserManager.class);

        mWmInternal = Objects.requireNonNull(LocalServices.getService(WindowManagerInternal.class));
        mAssistDataRequester = new AssistDataRequester(
                mContext,
                IWindowManager.Stub.asInterface(ServiceManager.getService(Context.WINDOW_SERVICE)),
                mContext.getSystemService(AppOpsManager.class),
                mAssistDataCallbacks, mLock, OP_ASSIST_STRUCTURE, OP_ASSIST_SCREENSHOT);

        updateSecureSetting();
    }

    @Override
    public void onStart() {
        publishBinderService(CONTEXTUAL_SEARCH_SERVICE, new ContextualSearchManagerStub());
    }

    private void updateSecureSetting() {
        // Write default package to secure setting every time there is a change. If OEM didn't
        // supply a new value in their config, then we would write empty string.
        Settings.Secure.putString(
            mContext.getContentResolver(),
            Settings.Secure.CONTEXTUAL_SEARCH_PACKAGE,
            getContextualSearchPackageName());
    }

    private String getContextualSearchPackageName() {
        synchronized (this) {
            if (mTemporaryPackage != null) {
                return mTemporaryPackage;
            }
            if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PC)
                    && !enableContextualSearchDesktopEntrypoints()) {
                return "";
            }
            return mContext.getResources()
                    .getString(R.string.config_defaultContextualSearchPackageName);
        }
    }

    void resetTemporaryPackage() {
        synchronized (this) {
            enforceOverridingPermission("resetTemporaryPackage");
            if (mTemporaryHandler != null) {
                mTemporaryHandler.removeMessages(MSG_RESET_TEMPORARY_PACKAGE);
                mTemporaryHandler = null;
            }
            if (DEBUG) Log.d(TAG, "mTemporaryPackage reset.");
            mTemporaryPackage = null;
            updateSecureSetting();
        }
    }

    void setTemporaryPackage(@NonNull String temporaryPackage, int durationMs) {
        synchronized (this) {
            enforceOverridingPermission("setTemporaryPackage");
            final int maxDurationMs = MAX_TEMP_PACKAGE_DURATION_MS;
            if (durationMs > maxDurationMs) {
                throw new IllegalArgumentException(
                        "Max duration is " + maxDurationMs + " (called with " + durationMs + ")");
            }
            if (mTemporaryHandler == null) {
                mTemporaryHandler = new Handler(Looper.getMainLooper(), null, true) {
                    @Override
                    public void handleMessage(Message msg) {
                        if (msg.what == MSG_RESET_TEMPORARY_PACKAGE) {
                            synchronized (this) {
                                resetTemporaryPackage();
                            }
                        } else {
                            Slog.wtf(TAG, "invalid handler msg: " + msg);
                        }
                    }
                };
            } else {
                mTemporaryHandler.removeMessages(MSG_RESET_TEMPORARY_PACKAGE);
            }
            mTemporaryPackage = temporaryPackage;
            updateSecureSetting();
            mTemporaryHandler.sendEmptyMessageDelayed(MSG_RESET_TEMPORARY_PACKAGE, durationMs);
            if (DEBUG) Log.d(TAG, "mTemporaryPackage set to " + mTemporaryPackage);
        }
    }

    void resetTokenValidDurationMs() {
        setTokenValidDurationMs(MAX_TOKEN_VALID_DURATION_MS);
    }

    void setTokenValidDurationMs(int durationMs) {
        synchronized (this) {
            enforceOverridingPermission("setTokenValidDurationMs");
            if (durationMs > MAX_TOKEN_VALID_DURATION_MS) {
                throw new IllegalArgumentException(
                        "Token max duration is " + MAX_TOKEN_VALID_DURATION_MS + " (called with "
                                + durationMs + ")");
            }
            mTokenValidDurationMs = durationMs;
            if (DEBUG) Log.d(TAG, "mTokenValidDurationMs set to " + durationMs);
        }
    }

    private long getTokenValidDurationMs() {
        synchronized (this) {
            return mTokenValidDurationMs;
        }
    }

    @Nullable
    private Intent getResolvedLaunchIntent(final int userId) {
        synchronized (this) {
            if (DEBUG) Log.d(TAG, "Attempting to getResolvedLaunchIntent");
            // If mTemporaryPackage is not null, use it to get the ContextualSearch intent.
            final String csPkgName = getContextualSearchPackageName();
            if (csPkgName.isEmpty()) {
                // Return null if csPackageName is not specified.
                if (DEBUG) Log.w(TAG, "getContextualSearchPackageName is empty");
                return null;
            }
            final Intent launchIntent = new Intent(
                    ContextualSearchManager.ACTION_LAUNCH_CONTEXTUAL_SEARCH);
            launchIntent.setPackage(csPkgName);
            final ResolveInfo resolveInfo = mContext.getPackageManager().resolveActivityAsUser(
                    launchIntent, MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE, userId);
            if (resolveInfo == null) {
                if (DEBUG) Log.w(TAG, "resolveInfo is null for package: " + csPkgName);
                return null;
            }
            final ComponentName componentName = resolveInfo.getComponentInfo().getComponentName();
            if (componentName == null) {
                if (DEBUG) Log.w(TAG, "componentName is null");
                return null;
            }
            launchIntent.setComponent(componentName);
            return launchIntent;
        }
    }

    @RequiresPermission(anyOf = {
            MANAGE_USERS,
            CREATE_USERS,
            QUERY_USERS
    })
    private Intent getContextualSearchIntent(final int entrypoint,
            @NonNull final ContextualSearchConfig config, final int userId,
            final String callingPackage, final CallbackToken token) {
        final Intent launchIntent = getResolvedLaunchIntent(userId);
        if (launchIntent == null) {
            if (DEBUG) Log.w(TAG, "Failed getContextualSearchIntent: launchIntent is null");
            return null;
        }

        if (DEBUG) Log.d(TAG, "Launch component: " + launchIntent.getComponent());

        launchIntent.setFlags(config.getLaunchFlags());

        // Add these first to avoid overwriting any extras set below.
        launchIntent.putExtras(config.getIntentExtras());

        launchIntent.putExtra(
                ContextualSearchManager.EXTRA_INVOCATION_TIME_MS,
                SystemClock.uptimeMillis());
        launchIntent.putExtra(ContextualSearchManager.EXTRA_ENTRYPOINT, entrypoint);
        launchIntent.putExtra(ContextualSearchManager.EXTRA_TOKEN, token);
        launchIntent.setSourceBounds(config.getSourceBounds());
        if (Flags.includeAudioPlayingStatus()) {
            launchIntent.putExtra(ContextualSearchManager.EXTRA_IS_AUDIO_PLAYING,
                    mAudioManager.isMusicActive());
        }
        launchIntent.putExtra(Intent.EXTRA_CALLING_PACKAGE, callingPackage);
        final boolean isAssistDataAllowed = mAtmInternal.isAssistDataAllowed();
        final List<ActivityAssistInfo> records = mAtmInternal.getTopVisibleActivities();
        final List<IBinder> activityTokens = new ArrayList<>(records.size());
        final ArrayList<String> visiblePackageNames = new ArrayList<>();
        boolean isManagedProfileVisible = false;
        for (final ActivityAssistInfo record : records) {
            // Add the package name to the list only if assist data is allowed.
            if (isAssistDataAllowed) {
                visiblePackageNames.add(record.getComponentName().getPackageName());
                activityTokens.add(record.getActivityToken());
            }
            if (mUserManager.isManagedProfile(record.getUserId())) {
                isManagedProfileVisible = true;
            }
        }
        final String csPackage = Objects.requireNonNull(launchIntent.getPackage());
        final int csUid = mPackageManager.getPackageUid(csPackage, /* flags */ 0L, userId);
        if (isAssistDataAllowed) {
            try {
                mAssistDataRequester.requestAssistData(
                        activityTokens,
                        /* fetchData */ true,
                        /* fetchScreenshot */ false,
                        /* fetchAssistStructureContent */ true,
                        /* allowFetchData */ true,
                        /* allowFetchScreenshot */ false,
                        /* allowFetchAssistStructureContent */ true,
                        csUid,
                        csPackage,
                        null);
            } catch (Exception e) {
                Log.e(TAG, "Could not request assist data", e);
            }
        }
        final int displayId = getDisplayIdFromConfig(config);
        if (DEBUG) Log.d(TAG, "Taking contextual search screenshot for displayId=" + displayId);
        final ScreenshotHardwareBuffer shb =
                mWmInternal.takeContextualSearchScreenshot(csUid, displayId);
        final Bitmap bm = shb != null ? shb.asBitmap() : null;
        // Now that everything is fetched, putting it in the launchIntent.
        if (bm != null) {
            launchIntent.putExtra(ContextualSearchManager.EXTRA_FLAG_SECURE_FOUND,
                    shb.containsSecureLayers());
            // Only put the screenshot if assist data is allowed
            if (isAssistDataAllowed) {
                launchIntent.putExtra(ContextualSearchManager.EXTRA_SCREENSHOT, bm.asShared());
            }
        }

        final IMediaProjection mediaProjection = getMediaProjection(csUid, csPackage, displayId);
        if (mediaProjection != null) {
            launchIntent.putExtra(MediaProjectionManager.EXTRA_MEDIA_PROJECTION,
                    mediaProjection.asBinder());
        }

        launchIntent.putExtra(ContextualSearchManager.EXTRA_IS_MANAGED_PROFILE_VISIBLE,
                isManagedProfileVisible);
        // Only put the list of visible package names if assist data is allowed
        if (isAssistDataAllowed) {
            launchIntent.putExtra(ContextualSearchManager.EXTRA_VISIBLE_PACKAGE_NAMES,
                    visiblePackageNames);
        }
        return launchIntent;
    }

    private IMediaProjection getMediaProjection(final int uid, final String packageName,
            final int displayId) {
        return Binder.withCleanCallingIdentity(() -> {
            final IBinder binder = ServiceManager.getService(Context.MEDIA_PROJECTION_SERVICE);
            final IMediaProjectionManager mediaProjectionManager =
                    IMediaProjectionManager.Stub.asInterface(binder);
            final IMediaProjection mediaProjection = mediaProjectionManager.createProjection(
                    uid, packageName, MediaProjectionManager.TYPE_SCREEN_CAPTURE, false,
                    displayId);
            mediaProjection.setRecordingOverlay(true);
            return mediaProjection;
        });
    }

    @RequiresPermission(START_TASKS_FROM_RECENTS)
    private int invokeContextualSearchIntent(final Intent launchIntent, final int userId,
            @NonNull final ContextualSearchConfig config) {
        // Contextual search starts with a frozen screen - so we launch without
        // any system animations or starting window.
        final ActivityOptions opts = ActivityOptions.makeCustomTaskAnimation(mContext,
                /* enterResId= */ 0, /* exitResId= */ 0, null, null, null);
        opts.setDisableStartingWindow(true);
        final int displayId = getDisplayIdFromConfig(config);
        opts.setLaunchDisplayId(displayId);
        opts.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
        opts.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
        return mAtmInternal.startActivityWithScreenshot(launchIntent,
                mContext.getPackageName(), Binder.getCallingUid(), Binder.getCallingPid(), null,
                opts.toBundle(), userId);
    }

    private void enforcePermission(@NonNull final String func) {
        final Context ctx = getContext();
        if (!(ctx.checkCallingOrSelfPermission(ACCESS_CONTEXTUAL_SEARCH) == PERMISSION_GRANTED
                || isCallerTemporary())) {
            final String msg = "Permission Denial: Cannot call " + func + " from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid();
            throw new SecurityException(msg);
        }
    }

    /**
     * Check that the calling process has an activity in the foreground.
     *
     * @param func The function being called, for logging purposes
     * @throws SecurityException if the calling process is not in the foreground
     */
    private void enforceForegroundApp(@NonNull final String func) {
        final int callingUid = Binder.getCallingUid();
        final String callingPackage = mPackageManager.getNameForUid(Binder.getCallingUid());
        if (mActivityManagerInternal.getUidProcessState(callingUid)
                > ActivityManager.PROCESS_STATE_TOP) {
            // The calling process must be displaying an activity in foreground to
            // trigger contextual search.
            String msg = "Permission Denial: Cannot call " + func + " from pid="
                    + Binder.getCallingPid() + ", uid=" + callingUid
                    + ", package=" + callingPackage + " without a foreground activity.";
            throw new SecurityException(msg);
        }
    }

    private void enforceOverridingPermission(@NonNull final String func) {
        if (!(Binder.getCallingUid() == Process.SHELL_UID
                || Binder.getCallingUid() == Process.ROOT_UID
                || Binder.getCallingUid() == Process.SYSTEM_UID)) {
            final String msg =
                    "Permission Denial: Cannot override Contextual Search. Called " + func
                            + " from pid=" + Binder.getCallingPid()
                            + ", uid=" + Binder.getCallingUid();
            throw new SecurityException(msg);
        }
    }

    private boolean isCallerTemporary() {
        synchronized (this) {
            return mTemporaryPackage != null
                    && mTemporaryPackage.equals(
                    getContext().getPackageManager().getNameForUid(Binder.getCallingUid()));
        }
    }

    private static int getDisplayIdFromConfig(@NonNull final ContextualSearchConfig config) {
        int displayId = config.getDisplayId();
        return displayId == Display.INVALID_DISPLAY ? Display.DEFAULT_DISPLAY : displayId;
    }

    private class ContextualSearchManagerStub extends IContextualSearchManager.Stub {
        @GuardedBy("this")
        private Handler mTokenHandler;
        private @Nullable CallbackToken mToken;
        private @Nullable ContextualSearchConfig mConfig;

        private void invalidateToken() {
            synchronized (this) {
                if (mTokenHandler != null) {
                    mTokenHandler.removeMessages(MSG_INVALIDATE_TOKEN);
                    mTokenHandler = null;
                }
                if (DEBUG) Log.d(TAG, "mToken invalidated.");
                mToken = null;
                mConfig = null;
            }
        }

        private void issueToken(@NonNull final ContextualSearchConfig config) {
            synchronized (this) {
                mToken = new CallbackToken();
                mConfig = config;
                if (mTokenHandler == null) {
                    mTokenHandler = new Handler(Looper.getMainLooper(), null, true) {
                        @Override
                        public void handleMessage(Message msg) {
                            if (msg.what == MSG_INVALIDATE_TOKEN) {
                                invalidateToken();
                            } else {
                                Slog.wtf(TAG, "invalid token handler msg: " + msg);
                            }
                        }
                    };
                } else {
                    mTokenHandler.removeMessages(MSG_INVALIDATE_TOKEN);
                }
                mTokenHandler.sendEmptyMessageDelayed(
                        MSG_INVALIDATE_TOKEN, getTokenValidDurationMs());
            }
        }

        @Override
        public boolean isContextualSearchAvailable() {
            return mContext.getPackageManager().hasSystemFeature(FEATURE_CONTEXTUAL_SEARCH)
                    && getResolvedLaunchIntent(Binder.getCallingUserHandle().getIdentifier())
                            != null;
        }

        @Override
        @RequiresPermission(START_TASKS_FROM_RECENTS)
        public void startContextualSearchForApp(@NonNull ContextualSearchConfig config) {
            Objects.requireNonNull(config);
            synchronized (this) {
                if (DEBUG) {
                    Log.d(TAG, "Starting contextual search from: "
                            + mPackageManager.getNameForUid(Binder.getCallingUid())
                            + ", config: " + config);
                }
                enforceForegroundApp("startContextualSearchForApp");
                startContextualSearchInternal(INTERNAL_ENTRYPOINT_APP, config);
            }
        }

        @Override
        @RequiresPermission(allOf = {START_TASKS_FROM_RECENTS, ACCESS_CONTEXTUAL_SEARCH})
        public void startContextualSearch(final int entrypoint,
                @Nullable ContextualSearchConfig config) {
            synchronized (this) {
                if (DEBUG) {
                    Log.d(TAG, "startContextualSearch entrypoint: " + entrypoint
                            + ", config: " + config);
                }
                enforcePermission("startContextualSearch");
                startContextualSearchInternal(entrypoint,
                        config != null ? config : ContextualSearchConfig.DEFAULT_CONFIG);
            }
        }

        @RequiresPermission(allOf = {START_TASKS_FROM_RECENTS}, anyOf = {
                MANAGE_USERS, CREATE_USERS, QUERY_USERS})
        private void startContextualSearchInternal(final int entrypoint,
                                                   @NonNull final ContextualSearchConfig config) {
            final String callingPackage = mPackageManager.getNameForUid(Binder.getCallingUid());
            final int callingUserId = Binder.getCallingUserHandle().getIdentifier();
            mAssistDataRequester.cancel();
            // Creates a new CallbackToken at mToken and an expiration handler.
            issueToken(config);
            // We get the launch intent with the system server's identity because the system
            // server has READ_FRAME_BUFFER permission to get the screenshot and because only
            // the system server can invoke non-exported activities.
            Binder.withCleanCallingIdentity(() -> {
                final Intent launchIntent = getContextualSearchIntent(entrypoint, config,
                        callingUserId, callingPackage, mToken);
                if (launchIntent != null) {
                    final int result =
                            invokeContextualSearchIntent(launchIntent, callingUserId,
                                    config);
                    if (DEBUG) {
                        Log.d(TAG, "Launch intent: " + launchIntent);
                        Log.d(TAG, "Launch result: " + result);
                    }
                }
            });
        }

        @Override
        public void getContextualSearchState(
                @NonNull final IBinder token,
                @NonNull final IContextualSearchCallback callback) {
            Objects.requireNonNull(token);
            Objects.requireNonNull(callback);
            if (DEBUG) {
                Log.i(TAG, "getContextualSearchState token: " + token + ", callback: " + callback);
            }
            if (mToken == null || !mToken.getToken().equals(token)) {
                if (DEBUG) {
                    Log.e(TAG, "getContextualSearchState: invalid token, returning error");
                }
                try {
                    callback.onError(
                            new ParcelableException(new IllegalArgumentException("Invalid token")));
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not invoke onError callback", e);
                }
                return;
            }

            final ContextualSearchConfig config = mConfig;
            invalidateToken();
            if (Flags.enableTokenRefresh()) {
                issueToken(config);
                final Bundle bundle = new Bundle();
                bundle.putParcelable(ContextualSearchManager.EXTRA_TOKEN, mToken);

                // We get take the screenshot with the system server's identity because the system
                // server has READ_FRAME_BUFFER permission to get the screenshot.
                final int callingUid = Binder.getCallingUid();
                final int displayId = getDisplayIdFromConfig(config);
                final IMediaProjection mediaProjection = getMediaProjection(callingUid,
                        getContextualSearchPackageName(), displayId);
                Binder.withCleanCallingIdentity(() -> {
                    final ScreenshotHardwareBuffer shb =
                            mWmInternal.takeContextualSearchScreenshot(callingUid, displayId);
                    final Bitmap bm = shb != null ? shb.asBitmap() : null;
                    if (bm != null) {
                        bundle.putParcelable(ContextualSearchManager.EXTRA_SCREENSHOT,
                                bm.asShared());
                        bundle.putBoolean(ContextualSearchManager.EXTRA_FLAG_SECURE_FOUND,
                                shb.containsSecureLayers());
                    }

                    if (mediaProjection != null) {
                        bundle.putBinder(MediaProjectionManager.EXTRA_MEDIA_PROJECTION,
                                mediaProjection.asBinder());
                    }
                    try {
                        callback.onResult(
                            new ContextualSearchState(null, null, bundle));
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error invoking ContextualSearchCallback", e);
                    }
                });
            }
            synchronized (mLock) {
                mStateCallback = callback;
            }
            mAssistDataRequester.processPendingAssistData();
        }

        public void onShellCommand(
                @Nullable FileDescriptor in,
                @Nullable FileDescriptor out,
                @Nullable FileDescriptor err,
                @NonNull String[] args,
                @Nullable ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) {
            new ContextualSearchManagerShellCommand(ContextualSearchManagerService.this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }
    }
}
