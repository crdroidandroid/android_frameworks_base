/*
 * Copyright (C) 2018-2022 crDroid Android Project
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

package com.android.internal.util;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.JsonReader;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * Helper functions for uploading to Pasty
 */
public final class PastyUtils {
    private static final String TAG = "PastyUtils";
    private static final String BASE_URL = "https://paste.crdroid.net";
    private static final String API_URL = String.format("%s/documents", BASE_URL);
    private static Handler handler;

    private PastyUtils() {
    }

    /**
     * Uploads {@code content} to Pasty
     *
     * @param content the content to upload to Pasty
     * @param callback the callback to call on success / failure
     */
    public static void upload(String content, UploadResultCallback callback) {
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpsURLConnection urlConnection = (HttpsURLConnection) new URL(API_URL).openConnection();
                    try {
                        urlConnection.setRequestProperty("Content-Type", "text/plain");
                        urlConnection.setRequestProperty("Accept-Charset", "UTF-8");
                        urlConnection.setDoOutput(true);

                        try (OutputStream output = urlConnection.getOutputStream()) {
                            output.write(content.getBytes("UTF-8"));
                        }
                        String key = "";
                        try (JsonReader reader = new JsonReader(
                                new InputStreamReader(urlConnection.getInputStream(), "UTF-8"))) {
                            reader.beginObject();
                            while (reader.hasNext()) {
                                String name = reader.nextName();
                                if (name.equals("key")) {
                                    key = reader.nextString();
                                    break;
                                } else {
                                    reader.skipValue();
                                }
                            }
                            reader.endObject();
                        }
                        if (!key.isEmpty()) {
                            callback.onSuccess(getUrl(key));
                        } else {
                            String msg = "Failed to upload to Pasty: No key retrieved";
                            callback.onFail(msg, new PastyException(msg));
                        }
                    } finally {
                        urlConnection.disconnect();
                    }
                } catch (Exception e) {
                    callback.onFail("Failed to upload to Pasty", e);
                }
            }
        });
    }

    /**
     * Get the view URL from a key
     */
    private static String getUrl(String key) {
        return String.format("%s/%s", BASE_URL, key);
    }

    private static Handler getHandler() {
        if (handler == null) {
            HandlerThread handlerThread = new HandlerThread("PastyThread");
            if (!handlerThread.isAlive())
                handlerThread.start();
            handler = new Handler(handlerThread.getLooper());
        }
        return handler;
    }

    public interface UploadResultCallback {
        void onSuccess(String url);

        void onFail(String message, Exception e);
    }
}
