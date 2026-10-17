package org.spaceworks.app.network

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RetryAfterTest {
    @Test fun deadlinesPreserveNormalZeroNegativeAndSaturateMultiplicationOrAddition() {
        assertEquals(6000L, retryDeadlineMillis(1000, 5))
        assertEquals(1000L, retryDeadlineMillis(1000, 0))
        assertEquals(1000L, retryDeadlineMillis(1000, -1))
        assertEquals(Long.MAX_VALUE, retryDeadlineMillis(1000, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, retryDeadlineMillis(1000, Long.MAX_VALUE / 1000 + 1))
        assertEquals(Long.MAX_VALUE, retryDeadlineMillis(Long.MAX_VALUE - 500, 1))
        assertEquals(Long.MAX_VALUE, retryDeadlineMillis(Long.MAX_VALUE - 1000, 1))
    }
    @Test fun remainingSecondsUseSafeCeilAndLongWithoutWrappingHugeWait() {
        assertEquals(0L, retrySecondsRemaining(1000, 1000))
        assertEquals(0L, retrySecondsRemaining(1001, 1000))
        assertEquals(1L, retrySecondsRemaining(1000, 1001))
        assertEquals(1L, retrySecondsRemaining(0, 1000))
        assertEquals(2L, retrySecondsRemaining(0, 1001))
        assertEquals(Long.MAX_VALUE / 1000 + 1, retrySecondsRemaining(0, Long.MAX_VALUE))
        assertTrue(retrySecondsRemaining(1000, Long.MAX_VALUE) > Int.MAX_VALUE.toLong())
        assertTrue(retrySecondsRemaining(Long.MIN_VALUE, Long.MAX_VALUE) > 0)
    }
    @Test fun numericHeaderNormalZeroMaximumAndLargerDigitValuesRemainAuthoritative() {
        assertEquals(60L, parseRetryAfterDeltaSeconds("60"))
        assertEquals(0L, parseRetryAfterDeltaSeconds("0"))
        assertEquals(1L, parseRetryAfterDeltaSeconds("000000000000000000000000001"))
        assertEquals(Long.MAX_VALUE, parseRetryAfterDeltaSeconds(Long.MAX_VALUE.toString()))
        assertEquals(Long.MAX_VALUE, parseRetryAfterDeltaSeconds("9223372036854775808"))
        assertEquals(Long.MAX_VALUE, parseRetryAfterDeltaSeconds("9".repeat(100)))
        for (invalid in listOf(null, "", " ", "1.5", "60 seconds", "１２", "12x", " 60")) {
            assertNull(parseRetryAfterDeltaSeconds(invalid))
        }
    }
    @Test fun actualErrorMapperPreservesOverflowNumericAndDateAndMalformedSemantics() {
        val now = Instant.parse("2026-10-17T00:00:00Z")
        assertEquals(Long.MAX_VALUE, Errors.map(429, null, "9223372036854775808", now).retryAfterSeconds)
        assertEquals(0L, Errors.map(429, null, "0", now).retryAfterSeconds)
        assertEquals(60L, Errors.map(429, null, "Sat, 17 Oct 2026 00:01:00 GMT", now).retryAfterSeconds)
        assertNull(Errors.map(429, null, "invalid", now).retryAfterSeconds)
        assertNull(Errors.map(429, null, null, now).retryAfterSeconds)
    }
}
