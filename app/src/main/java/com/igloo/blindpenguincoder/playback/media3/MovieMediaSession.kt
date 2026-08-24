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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.MediaSession
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import java.util.concurrent.atomic.AtomicLong

/** Distinguishes sessions of the same movie; see the id note in [buildMovieMediaSession]. */
private val sessionInstance = AtomicLong(0)

/** What the system's now-playing surface shows, carried by the engine's media item. */
internal fun movieMediaMetadata(request: MoviePlayRequest): MediaMetadata =
    MediaMetadata.Builder()
        .setTitle(request.title)
        .setArtworkUri(request.posterUrl?.let(Uri::parse))
        .setMediaType(MediaMetadata.MEDIA_TYPE_MOVIE)
        .build()

/**
 * The movie player's system face (section 11.8): now-playing surface, Assistant voice
 * transport, and media-button routing for whatever the focused window leaves unhandled. The
 * default callback is used deliberately — it maps dedicated play/pause onto
 * [Player.play]/[Player.pause] and only PLAY_PAUSE toggles, the same three-way split as the
 * on-screen key map, so session-driven commands carry the exact intent semantics the chrome
 * implements, and every resulting change reaches the screen through the engine's listener.
 */
internal fun buildMovieMediaSession(
    context: Context,
    player: Player,
    request: MoviePlayRequest,
    dataSourceFactory: DataSource.Factory,
): MediaSession {
    val builder = MediaSession.Builder(context, player)
        // On error-Retry the replacement engine (and its session) is constructed before the
        // old one's disposal releases it; two live sessions with equal ids throw, so every
        // instance gets its own.
        .setId("movie-${request.movieId}-${sessionInstance.incrementAndGet()}")
        // The poster proxy needs the bearer token, which system surfaces can't attach; loading
        // through the player's own authenticated stack hands them the bitmap instead. A failed
        // load degrades to title-only metadata — artwork must never surface an error.
        .setBitmapLoader(
            DataSourceBitmapLoader.Builder(context)
                .setDataSourceFactory(dataSourceFactory)
                .build(),
        )
    context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)?.let { launch ->
        builder.setSessionActivity(
            PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE),
        )
    }
    return builder.build()
}
