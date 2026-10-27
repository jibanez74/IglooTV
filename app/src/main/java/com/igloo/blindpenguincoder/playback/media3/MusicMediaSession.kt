// MediaSession's builder surface is marked unstable; like its siblings, this file keeps the
// instability below the engine seam.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.MediaSession
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack

/**
 * One queue entry's system face. Carried per media item so the session surfaces the current
 * track's title/artist/art on every transition with no manual update code — which is also
 * what lets a library queue that crosses albums stay right without any per-track lookup.
 * [artworkUrl] is the track's cover resolved to an absolute URL, since Media3 loads it itself.
 */
internal fun musicMediaMetadata(track: MusicPlayTrack, artworkUrl: String?): MediaMetadata = MediaMetadata.Builder()
    .setTitle(track.title)
    .setArtist(track.artistName)
    .setAlbumTitle(track.albumTitle)
    .setArtworkUri(artworkUrl?.let(Uri::parse))
    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
    .build()

/**
 * The music player's system face, on [buildIglooMediaSession]'s shared shape. Like the movie's,
 * artwork loads through the player's own authenticated stack: a cover is a Spotify or Apple URL,
 * which the stack leaves alone (it only decorates same-origin requests), or, with the server's
 * Download images setting on, the server's own `/api/static` copy, which needs the bearer that
 * system surfaces can't attach.
 */
internal fun buildMusicMediaSession(
    context: Context,
    player: Player,
    sessionKey: String,
    dataSourceFactory: DataSource.Factory,
): MediaSession = buildIglooMediaSession(
    context = context,
    player = player,
    idPrefix = "music-$sessionKey",
    bitmapLoader = DataSourceBitmapLoader.Builder(context)
        .setDataSourceFactory(dataSourceFactory)
        .build(),
)
