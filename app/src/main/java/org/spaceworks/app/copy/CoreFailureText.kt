package org.spaceworks.app.copy

import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.platform.MobileText

/** A server string matching an English fallback is still server-owned, never a resource key. */
fun MobileText.apiFailure(failure:ApiFailure):String = failure.localCopy?.let { invoke(it) } ?: failure.detail
