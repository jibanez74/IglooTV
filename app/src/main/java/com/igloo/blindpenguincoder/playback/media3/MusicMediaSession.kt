// MediaSession's builder surface is marked unstable; like its siblings, this file keeps the
// instability below the engine seam.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
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
 * The music player's system face, on [buildIglooMediaSession]'s shared shape. Artwork uses a
 * separate authenticated stack without stream session-revocation reporting. External covers
 * need no bearer; the server's own `/api/static` copies do. Either way, a rejected cover stays
 * an artwork failure rather than invalidating the active profile.
 */
internal fun buildMusicMediaSession(
    context: Context,
    player: Player,
    sessionKey: String,
    artworkDataSourceFactory: DataSource.Factory,
): MediaSession = buildIglooMediaSession(
    context = context,
    player = player,
    idPrefix = "music-$sessionKey",
    bitmapLoader = musicArtworkBitmapLoader(context, artworkDataSourceFactory),
)

/**
 * The session's cover loader over [artworkDataSourceFactory], capped at the decode dimension
 * Media3's own default loader uses (twice the session's bitmap limit, less one): a large
 * provider cover is subsampled on the way in rather than allocated whole and scaled afterwards,
 * which on a TV is the difference between a cover and an out-of-memory artwork failure.
 */
internal fun musicArtworkBitmapLoader(
    context: Context,
    artworkDataSourceFactory: DataSource.Factory,
): BitmapLoader = DataSourceBitmapLoader.Builder(context)
    .setDataSourceFactory(artworkDataSourceFactory)
    .setMaximumOutputDimension(MediaSession.getBitmapDimensionLimit(context) * 2 - 1)
    .build()
