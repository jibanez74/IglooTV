// MediaSession's builder surface is marked unstable; like its siblings, this file keeps the
// instability below the engine seam.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import java.util.concurrent.atomic.AtomicLong

/** Distinguishes sessions of the same album; see the id note in [buildMusicMediaSession]. */
private val sessionInstance = AtomicLong(0)

/**
 * One queue entry's system face. Carried per media item so the session surfaces the current
 * track's title/artist/art on every transition with no manual update code.
 */
internal fun musicMediaMetadata(
    track: MusicPlayTrack,
    request: MusicPlayRequest,
): MediaMetadata = MediaMetadata.Builder()
    .setTitle(track.title)
    .setArtist(request.artistName)
    .setAlbumTitle(request.albumTitle)
    .setArtworkUri(request.coverUrl?.let(Uri::parse))
    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
    .build()

/**
 * The music player's system face: now-playing surface, Assistant voice transport, and
 * media-button routing. The default callback is used deliberately, the movie session's
 * reasoning — it also maps dedicated SKIP commands onto [Player.seekToNext]/[Player.seekToPrevious],
 * the same split as the on-screen transport row. Unlike the movie's, the bitmap loader stays
 * Media3's default: the cover is an absolute Spotify URL that needs no bearer, and routing it
 * through the authenticated stack would attach nothing anyway (it only decorates same-origin
 * requests).
 */
internal fun buildMusicMediaSession(
    context: Context,
    player: Player,
    albumId: Long,
): MediaSession {
    val builder = MediaSession.Builder(context, player)
        // On error-Retry the replacement engine (and its session) is constructed before the
        // old one's disposal releases it; two live sessions with equal ids throw, so every
        // instance gets its own.
        .setId("music-album-$albumId-${sessionInstance.incrementAndGet()}")
    context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)?.let { launch ->
        builder.setSessionActivity(
            PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE),
        )
    }
    return builder.build()
}
