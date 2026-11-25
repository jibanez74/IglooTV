package com.igloo.blindpenguincoder.core.network

/** [initialMillis] doubled for each of the [retries] already waited out, capped at [maxMillis]. */
internal fun backoffMillis(initialMillis: Long, retries: Int, maxMillis: Long): Long =
    (initialMillis * (1L shl retries.coerceIn(0, 30))).coerceAtMost(maxMillis)
