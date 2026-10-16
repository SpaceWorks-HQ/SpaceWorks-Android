package org.spaceworks.app.network

/** Caller supplies the429 missing-header default. Saturation must never turn a wait into the past. */
fun retryDeadlineMillis(now: Long, seconds: Long): Long {
    val nonnegative = seconds.coerceAtLeast(0)
    if (nonnegative > Long.MAX_VALUE / 1000) return Long.MAX_VALUE
    val duration = nonnegative * 1000
    return if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
}

/** Preserve arbitrarily large valid delta-seconds; malformed headers still use the caller's fallback. */
fun parseRetryAfterDeltaSeconds(value: String?): Long? {
    if (value == null) return null
    if (value.isNotEmpty() && value.all { it in '0'..'9' }) return value.toLongOrNull() ?: Long.MAX_VALUE
    // Preserve previously accepted bounded signed values, without accepting Unicode numerals.
    if (value.firstOrNull() in listOf('+', '-') && value.drop(1).isNotEmpty() && value.drop(1).all { it in '0'..'9' }) {
        return value.toLongOrNull()?.coerceAtLeast(0)
    }
    return null
}

/** Ceil without +999 overflow or Int truncation, including a saturated deadline. */
fun retrySecondsRemaining(now: Long, deadline: Long): Long {
    if (deadline <= now) return 0
    val remaining = if (now < 0 && deadline > Long.MAX_VALUE + now) Long.MAX_VALUE else deadline - now
    return remaining / 1000 + if (remaining % 1000 == 0L) 0 else 1
}
