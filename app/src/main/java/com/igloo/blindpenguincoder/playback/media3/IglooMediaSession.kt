// MediaSession's builder surface is marked unstable; like its siblings, this file keeps the
// instability below the engine seam.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.app.PendingIntent
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.BitmapLoader
import androidx.media3.session.MediaSession
import java.util.concurrent.atomic.AtomicLong

/** Distinguishes sessions of the same media; see the id note in [buildIglooMediaSession]. */
private val sessionInstance = AtomicLong(0)

/**
 * Everything a player's system face has in common (section 11.8): a per-instance id, the
 * leanback launch activity, and Media3's default callback — used deliberately, because it maps
 * dedicated play/pause onto [Player.play]/[Player.pause] with only PLAY_PAUSE toggling, the
 * same three-way split the on-screen key map implements, and it maps the SKIP commands onto
 * [Player.seekToNext]/[Player.seekToPrevious], the split the music transport row implements.
 *
 * [idPrefix] names the media ("movie-7", "music-album-11"); the instance suffix is appended
 * here. [bitmapLoader] is null wherever Media3's default will do — the movie's poster proxy
 * needs a bearer token system surfaces cannot attach, while an album cover is an absolute URL
 * that needs none.
 */
internal fun buildIglooMediaSession(
    context: Context,
    player: Player,
    idPrefix: String,
    bitmapLoader: BitmapLoader? = null,
): MediaSession {
    val builder = MediaSession.Builder(context, player)
        // On error-Retry the replacement engine (and its session) is constructed before the
        // old one's disposal releases it; two live sessions with equal ids throw, so every
        // instance gets its own.
        .setId("$idPrefix-${sessionInstance.incrementAndGet()}")
    bitmapLoader?.let(builder::setBitmapLoader)
    context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)?.let { launch ->
        builder.setSessionActivity(
            PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE),
        )
    }
    return builder.build()
}
