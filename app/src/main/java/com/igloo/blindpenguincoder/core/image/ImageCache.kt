package com.igloo.blindpenguincoder.core.image

import android.content.Context
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drops cached images when a session ends.
 *
 * Sign-out removes a profile from this TV, and its avatar is the one piece of that profile Coil
 * has written to disk. Cache entries are keyed by URL with nothing tying them back to a profile,
 * so the whole cache goes rather than a guess at which entry belonged to whom; the profiles that
 * remain simply re-fetch one avatar each.
 */
interface ImageCache {
    suspend fun clear()
}

/** Clears the process-wide loader that [coil3.compose.AsyncImage] resolves by default. */
class CoilImageCache(context: Context) : ImageCache {
    private val appContext = context.applicationContext

    override suspend fun clear() {
        val loader = SingletonImageLoader.get(appContext)
        loader.memoryCache?.clear()
        // Disk work, and the caller is holding the session transition lock.
        withContext(Dispatchers.IO) { loader.diskCache?.clear() }
    }
}
