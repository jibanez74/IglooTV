// MediaSession's builder surface is marked unstable; like its siblings, this file keeps the
// instability below the engine seam.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack

/**
 * One queue entry's system face. Carried per media item so the session surfaces the current
 * track's title/artist/art on every transition with no manual update code — which is also
 * what lets a library queue that crosses albums stay right without any per-track lookup.
 */
internal fun musicMediaMetadata(track: MusicPlayTrack): MediaMetadata = MediaMetadata.Builder()
    .setTitle(track.title)
    .setArtist(track.artistName)
    .setAlbumTitle(track.albumTitle)
    .setArtworkUri(track.coverUrl?.let(Uri::parse))
    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
    .build()

/**
 * The music player's system face, on [buildIglooMediaSession]'s shared shape. Unlike the
 * movie's, the bitmap loader stays Media3's default: the cover is an absolute Spotify URL that
 * needs no bearer, and routing it through the authenticated stack would attach nothing anyway
 * (it only decorates same-origin requests).
 */
internal fun buildMusicMediaSession(
    context: Context,
    player: Player,
    sessionKey: String,
): MediaSession = buildIglooMediaSession(
    context = context,
    player = player,
    idPrefix = "music-$sessionKey",
)
