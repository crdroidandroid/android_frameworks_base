/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.security.rkp;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class DuckRkpManagerTest {
    @Test
    public void renewsOneDayBeforeLongLivedChainExpires() {
        long start = TimeUnit.DAYS.toMillis(20000);
        long expiry = start + TimeUnit.DAYS.toMillis(30);
        assertThat(DuckRkpManager.renewalTime(start, expiry))
                .isEqualTo(expiry - TimeUnit.DAYS.toMillis(1));
    }

    @Test
    public void shortLivedChainKeepsMostOfItsLifetime() {
        long start = TimeUnit.DAYS.toMillis(20000);
        long expiry = start + TimeUnit.HOURS.toMillis(1);
        assertThat(DuckRkpManager.renewalTime(start, expiry))
                .isEqualTo(expiry - TimeUnit.MINUTES.toMillis(12));
    }

    @Test
    public void retryBackoffPersistsAsACappedFailureCount() {
        long[] minutes = {30, 60, 120, 240, 360, 360};
        for (int i = 0; i < minutes.length; i++) {
            assertThat(DuckRkpManager.retryDelay(i + 1))
                    .isEqualTo(TimeUnit.MINUTES.toMillis(minutes[i]));
        }
        assertThat(DuckRkpManager.retryDelay(Integer.MAX_VALUE))
                .isEqualTo(TimeUnit.HOURS.toMillis(6));
    }

    @Test
    public void temporaryFailuresCanRetry() {
        assertThat(DuckRkpManager.isRetryable("network_error", 0)).isTrue();
        assertThat(DuckRkpManager.isRetryable("hal_unavailable", 0)).isTrue();
        for (int status : new int[] {408, 429, 500, 503, 599}) {
            assertThat(DuckRkpManager.isRetryable("http_error", status)).isTrue();
        }
    }

    @Test
    public void rejectedOrUncertainRequestsNeedAnExplicitRetry() {
        for (String error : new String[] {"hal_rejected", "hal_unsupported",
                "submission_unknown", "timeout", "certificate_invalid", "canceled",
                "save_failed", "settings_unavailable", "provision_failed"}) {
            assertThat(DuckRkpManager.isRetryable(error, 0)).isFalse();
        }
        for (int status : new int[] {400, 401, 403, 404, 409, 499, 600}) {
            assertThat(DuckRkpManager.isRetryable("http_error", status)).isFalse();
        }
    }
}
