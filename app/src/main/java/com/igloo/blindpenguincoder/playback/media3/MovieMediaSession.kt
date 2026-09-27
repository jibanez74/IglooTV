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
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef

/** What the system's now-playing surface shows, carried by the engine's media item. */
internal fun movieMediaMetadata(request: MoviePlayRequest): MediaMetadata =
    MediaMetadata.Builder()
        .setTitle(request.title)
        .setArtworkUri(request.posterUrl?.let(Uri::parse))
        .setMediaType(
            when (request.media) {
                is PlaybackMediaRef.Movie -> MediaMetadata.MEDIA_TYPE_MOVIE
                is PlaybackMediaRef.Episode -> MediaMetadata.MEDIA_TYPE_TV_SHOW
            },
        )
        .build()

/**
 * The movie player's system face (section 11.8), on [buildIglooMediaSession]'s shared shape.
 * What is movie-specific is the bitmap loader: the poster proxy needs the bearer token, which
 * system surfaces can't attach, so loading through the player's own authenticated stack hands
 * them the bitmap instead. A failed load degrades to title-only metadata — artwork must never
 * surface an error.
 */
internal fun buildMovieMediaSession(
    context: Context,
    player: Player,
    request: MoviePlayRequest,
    dataSourceFactory: DataSource.Factory,
): MediaSession = buildIglooMediaSession(
    context = context,
    player = player,
    idPrefix = when (val media = request.media) {
        is PlaybackMediaRef.Movie -> "movie-${media.id}"
        is PlaybackMediaRef.Episode -> "episode-${media.id}"
    },
    bitmapLoader = DataSourceBitmapLoader.Builder(context)
        .setDataSourceFactory(dataSourceFactory)
        .build(),
)
