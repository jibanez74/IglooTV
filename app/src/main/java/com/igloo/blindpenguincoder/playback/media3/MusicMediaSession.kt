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
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack

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
 * The music player's system face, on [buildIglooMediaSession]'s shared shape. Unlike the
 * movie's, the bitmap loader stays Media3's default: the cover is an absolute Spotify URL that
 * needs no bearer, and routing it through the authenticated stack would attach nothing anyway
 * (it only decorates same-origin requests).
 */
internal fun buildMusicMediaSession(
    context: Context,
    player: Player,
    albumId: Long,
): MediaSession = buildIglooMediaSession(
    context = context,
    player = player,
    idPrefix = "music-album-$albumId",
)
