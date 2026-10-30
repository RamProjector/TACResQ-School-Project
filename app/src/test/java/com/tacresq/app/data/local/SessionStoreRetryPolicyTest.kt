package com.tacresq.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class SessionStoreRetryPolicyTest {
    @Test
    fun retryDelayUsesCappedExponentialBackoff() {
        val delays = (0L..7L).map(::sessionStoreReadRetryDelayMillis)

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            delays,
        )
    }

    @Test
    fun negativeAttemptUsesInitialDelay() {
        assertEquals(1_000L, sessionStoreReadRetryDelayMillis(-1L))
    }

    @Test
    fun veryLargeAttemptCannotOverflowTheDelayCalculation() {
        assertEquals(30_000L, sessionStoreReadRetryDelayMillis(Long.MAX_VALUE))
    }

    @Test
    fun onlyRecoverableIoFailuresAreRetried() {
        assertEquals(true, shouldRetrySessionStoreRead(IOException("temporary disk read failure")))
        assertEquals(false, shouldRetrySessionStoreRead(IllegalStateException("programming error")))
        assertEquals(false, shouldRetrySessionStoreRead(SecurityException("keystore access denied")))
        assertEquals(false, shouldRetrySessionStoreRead(CancellationException("caller cancelled")))
    }
}
