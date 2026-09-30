package com.igloo.blindpenguincoder.core.image

/** An [ImageCache] with no image loader behind it, for suites that never assert on clearing. */
object NoOpImageCache : ImageCache {
    override suspend fun clear() = Unit
}
