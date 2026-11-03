package com.igloo.blindpenguincoder.playback.media3

import com.igloo.blindpenguincoder.core.network.causeChain
import java.io.IOException

/** Match the already-discarded load by identity; unrelated failures must still stop playback. */
internal fun isReportedSubtitleFailure(error: Throwable, discardedFailure: IOException?): Boolean =
    discardedFailure != null && error.causeChain().any { it === discardedFailure }
