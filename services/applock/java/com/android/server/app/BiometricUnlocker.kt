/*
 * Copyright (C) 2022 FlamingoOS Project
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

package com.android.server.app

import android.content.Context
import android.hardware.biometrics.BiometricConstants
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.hardware.biometrics.BiometricPrompt.AuthenticationCallback
import android.hardware.biometrics.BiometricPrompt.AuthenticationResult
import android.os.CancellationSignal
import android.util.Slog

import com.android.internal.R

/**
 * Handles logic of unlocking an app with biometrics or device credentials.
 *
 * @hide
 */
internal class BiometricUnlocker(private val context: Context) {

    private val biometricManager = context.getSystemService(BiometricManager::class.java)

    // Set operation must be externally synchronized
    var biometricsAllowed = false

    /**
     * Determine whether biometrics or device credentials can be used for
     * unlocking operation.
     */
    fun canUnlock(): Boolean =
        biometricManager.canAuthenticate(
            Authenticators.BIOMETRIC_WEAK or
                Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Unlock an application. Should call this method only if
     * [canUnlock] returned true.
     *
     * @param title the title of the dialog prompt.
     * @param onSuccess the callback invoked on successfull authentication.
     * @param onCancel the callback invoked when authentication is cancelled.
     */
    fun unlock(
        packageLabel: String?,
        onSuccess: () -> Unit,
        onCancel: () -> Unit,
    ) {
        val callback = object : AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: AuthenticationResult) {
                logD {
                    "onAuthenticationSucceeded"
                }
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Slog.i(TAG, "onAuthenticationError, errorCode = " +
                    "$errorCode, errString = $errString")
                if (errorCode == BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED) {
                    onCancel()
                }
            }
        }
        showCredentialsPrompt(
            context.getString(R.string.unlock_application, packageLabel),
            callback
        )
    }

    private fun showCredentialsPrompt(
        title: String,
        callback: AuthenticationCallback,
    ) {
        var authenticators = Authenticators.DEVICE_CREDENTIAL
        if (biometricsAllowed) {
            authenticators = authenticators or Authenticators.BIOMETRIC_WEAK
        }
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setAllowedAuthenticators(authenticators)
            .setAllowBackgroundAuthentication(true)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticateUser(
            CancellationSignal(),
            context.mainExecutor,
            callback,
            context.userId,
        )
    }
}
