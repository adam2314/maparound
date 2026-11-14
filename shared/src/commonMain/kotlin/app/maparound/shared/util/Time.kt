package app.maparound.shared.util

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Milliseconds since 1970 (`System.currentTimeMillis()`). */
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** ISO 8601 UTC time as GPX uses it: "2024-05-01T08:30:00Z" (milliseconds only when not zero). */
fun isoTime(epochMs: Long): String = Instant.fromEpochMilliseconds(epochMs).toString()

/** Milliseconds since 1970 from an ISO 8601 time ("…Z" or with an offset); null if it isn't one. */
fun parseIsoTime(s: String): Long? = runCatching { Instant.parse(s.trim()).toEpochMilliseconds() }.getOrNull()

/** This year (UTC). */
fun currentYear(): Int = isoTime(nowMillis()).take(4).toInt()
