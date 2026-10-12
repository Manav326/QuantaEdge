package com.quantaedge.android.ui

import java.time.OffsetDateTime

/** Server-issued attempt deadline parser shared with deterministic unit tests. */
internal object AssessmentTimer {
    fun deadlineEpochMillis(value: String): Long? {
        if (value.isBlank()) return null
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
    }

    fun remainingSeconds(value: String, nowEpochMillis: Long): Long? =
        deadlineEpochMillis(value)?.let { ((it - nowEpochMillis) / 1000L).coerceAtLeast(0L) }
}
