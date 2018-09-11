/*
 * Copyright (C) 2018 Projekt Substratum
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
package projekt.substratum.helper;

import android.app.Service;
import android.content.Intent;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.Session;
import android.content.pm.PackageInstaller.SessionParams;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.Process;
import android.os.SELinux;
import android.util.Log;

import com.android.internal.substratum.ISubstratumHelperService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

public class SubstratumHelperService extends Service {
    private static final String TAG = "SubstratumHelperService";

    private final File EXTERNAL_CACHE_DIR =
            new File(Environment.getExternalStorageDirectory(), ".substratum");
    private final File SYSTEM_THEME_DIR = new File(Environment.getDataSystemDirectory(), "theme");

    ISubstratumHelperService mISubstratumHelperService = new ISubstratumHelperService.Stub() {
        @Override
        public void applyBootAnimation() {
            if (!isAuthorized(Binder.getCallingUid())) return;

            File src = new File(EXTERNAL_CACHE_DIR, "bootanimation.zip");
            File dst = new File(SYSTEM_THEME_DIR, "bootanimation.zip");
            int perms = FileUtils.S_IRWXU | FileUtils.S_IRGRP | FileUtils.S_IROTH;

            if (dst.exists()) dst.delete();
            FileUtils.copyFile(src, dst);
            FileUtils.setPermissions(dst, perms, -1, -1);
            SELinux.restorecon(dst);
            src.delete();
        }

        @Override
        public void applyShutdownAnimation() {
            if (!isAuthorized(Binder.getCallingUid())) return;

            File src = new File(EXTERNAL_CACHE_DIR, "shutdownanimation.zip");
            File dst = new File(SYSTEM_THEME_DIR, "shutdownanimation.zip");
            int perms = FileUtils.S_IRWXU | FileUtils.S_IRGRP | FileUtils.S_IROTH;

            if (dst.exists()) dst.delete();
            FileUtils.copyFile(src, dst);
            FileUtils.setPermissions(dst, perms, -1, -1);
            SELinux.restorecon(dst);
            src.delete();
        }

        @Override
        public void applyProfile(String name) {
            if (!isAuthorized(Binder.getCallingUid())) return;

            FileUtils.deleteContents(SYSTEM_THEME_DIR);

            File profileDir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() +
                    "/substratum/profiles/" + name + "/theme");
            if (profileDir.exists()) {
                File profileFonts = new File(profileDir, "fonts");
                if (profileFonts.exists()) {
                    File dst = new File(SYSTEM_THEME_DIR, "fonts");
                    copyDir(profileFonts, dst);
                }

                File profileSounds = new File(profileDir, "audio");
                if (profileSounds.exists()) {
                    File dst = new File(SYSTEM_THEME_DIR, "audio");
                    copyDir(profileSounds, dst);
                }

                File profileBootAnimation = new File(profileDir, "bootanimation.zip");
                if (profileBootAnimation.exists()) {
                    File dst = new File(SYSTEM_THEME_DIR, "bootanimation.zip");
                    FileUtils.copyFile(profileBootAnimation, dst);
                    int perms = FileUtils.S_IRWXU | FileUtils.S_IRGRP | FileUtils.S_IROTH;
                    FileUtils.setPermissions(dst, perms, -1, -1);
                }

                File profileShutdownAnimation = new File(profileDir, "shutdownanimation.zip");
                if (profileShutdownAnimation.exists()) {
                    File dst = new File(SYSTEM_THEME_DIR, "shutdownanimation.zip");
                    FileUtils.copyFile(profileShutdownAnimation, dst);
                    int perms = FileUtils.S_IRWXU | FileUtils.S_IRGRP | FileUtils.S_IROTH;
                    FileUtils.setPermissions(dst, perms, -1, -1);
                }

                SELinux.restorecon(SYSTEM_THEME_DIR);
            }
        }

        @Override
        public void installOverlay(List<String> paths) {
            if (!isAuthorized(Binder.getCallingUid())) return;
            LocalIntentReceiver receiver = new LocalIntentReceiver();
            for (String path : paths) {
                //Settings.Global.putInt(mContext.getContentResolver(),
                //        Settings.Global.PACKAGE_VERIFIER_ENABLE, 0);
                File apkFile = new File(path);
                try {
                    //PackageParser.PackageLite pkg = PackageParser.parsePackageLite(apkFile, 0);
                    PackageInstaller installer = getPackageManager().getPackageInstaller();
                    SessionParams params = new SessionParams(SessionParams.MODE_FULL_INSTALL);
                    int sessionId = installer.createSession(params);
                    try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                        try (InputStream in = new FileInputStream(apkFile);
                            OutputStream apkStream = session.openWrite(
                                    "base.apk", 0, apkFile.length())) {
                            byte[] buffer = new byte[32 * 1024];
                            long size = apkFile.length();
                            while (size > 0) {
                                long toRead = (buffer.length < size) ? buffer.length : size;
                                int didRead = in.read(buffer, 0, (int) toRead);
                                apkStream.write(buffer, 0, didRead);
                                size -= didRead;
                            }
                        }
                        session.commit(receiver.getIntentSender());
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Install failed", e);
                    continue;
                }

                Intent result = receiver.getResult();
                int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_SUCCESS) {
                String installedPackageName = result.getStringExtra(
                        PackageInstaller.EXTRA_PACKAGE_NAME);
                } else {
                    // ¯\_(ツ)_/¯
                }
            }
        }

        private boolean isAuthorized(int uid) {
            return Process.SYSTEM_UID == uid;
        }

        private boolean copyDir(File src, File dst) {
            File[] files = src.listFiles();
            boolean success = true;

            if (files != null) {
                for (File file : files) {
                    File newFile = new File(dst, file.getName());
                    if (file.isDirectory()) {
                        success &= copyDir(file, newFile);
                    } else {
                        success &= FileUtils.copyFile(file, newFile);
                    }
                }
            } else {
                // not a directory
                success = false;
            }
            return success;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mISubstratumHelperService.asBinder();
    }

    private static class LocalIntentReceiver {
        private final SynchronousQueue<Intent> mResult = new SynchronousQueue<>();

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                try {
                    mResult.offer(intent, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        public Intent getResult() {
            try {
                return mResult.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
