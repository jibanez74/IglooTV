@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.media.session.MediaController as PlatformMediaController
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The session's contract with the system, on a real player: media-button events must carry the
 * chrome's intent semantics (dedicated play/pause never toggle, pause cancels pending
 * autoplay), and two sessions must be able to coexist — on error-Retry the replacement engine
 * is constructed before the old one's disposal releases it, which throws unless ids differ.
 */
@RunWith(AndroidJUnit4::class)
class VideoMediaSessionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val toRelease = mutableListOf<Pair<MediaSession, ExoPlayer>>()

    @After
    fun releaseAll() {
        instrumentation.runOnMainSync {
            toRelease.forEach { (session, player) ->
                session.release()
                player.release()
            }
            toRelease.clear()
        }
    }

    private fun playRequest(posterUrl: String? = null) = VideoPlayRequest(
        media = PlaybackMediaRef.Movie(7),
        title = "Heat",
        posterUrl = posterUrl,
        mimeType = "video/x-matroska",
        mode = PlaybackMode.Direct,
        audioTypeIndex = null,
        subtitleTypeIndex = null,
        resumeAtSec = null,
        durationSec = 7200.0,
    )

    /**
     * A real player and session over a TEST-NET address that never answers, so [prepare]d
     * playback holds in BUFFERING — the state the app is really in while a pending autoplay
     * exists. The button pipeline ignores pause-direction keys outside active playback (IDLE,
     * ERROR), which the app never exposes: controls are disabled and keys swallowed there.
     */
    private fun buildSession(): Pair<MediaSession, ExoPlayer> {
        lateinit var built: Pair<MediaSession, ExoPlayer>
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri("https://203.0.113.1/movie")
                    .setMediaMetadata(videoMediaMetadata(playRequest()))
                    .build(),
            )
            val session = buildVideoMediaSession(
                context = context,
                player = player,
                request = playRequest(),
                dataSourceFactory = DefaultHttpDataSource.Factory(),
            )
            built = session to player
        }
        toRelease += built
        return built
    }

    private fun pressMediaKey(session: MediaSession, keyCode: Int) {
        val controller = PlatformMediaController(context, session.platformToken)
        controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun playWhenReady(player: ExoPlayer): Boolean {
        var value = false
        instrumentation.runOnMainSync { value = player.playWhenReady }
        return value
    }

    private fun awaitPlayWhenReady(player: ExoPlayer, expected: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (playWhenReady(player) == expected) return
            Thread.sleep(50)
        }
        fail("playWhenReady never became $expected")
    }

    /** The button pipeline is a one-way binder call; a no-op leaves nothing to wait on. */
    private fun letDispatchSettle() = Thread.sleep(500)

    @Test
    fun metadataCarriesTitleArtworkAndMovieType() {
        val withPoster = videoMediaMetadata(
            playRequest(posterUrl = "https://server/api/tmdb/images/w500/heat.jpg"),
        )

        assertEquals("Heat", withPoster.title.toString())
        assertEquals(
            "https://server/api/tmdb/images/w500/heat.jpg",
            withPoster.artworkUri.toString(),
        )
        assertEquals(MediaMetadata.MEDIA_TYPE_MOVIE, withPoster.mediaType)

        assertEquals(null, videoMediaMetadata(playRequest()).artworkUri)
    }

    @Test
    fun anEpisodeIsTypedAsATvShowForTheSystemSurface() {
        val episode = videoMediaMetadata(
            playRequest().copy(media = PlaybackMediaRef.Episode(900), title = "Severance · S1 E3 · In Perpetuity"),
        )

        assertEquals("Severance · S1 E3 · In Perpetuity", episode.title.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_TV_SHOW, episode.mediaType)
    }

    @Test
    fun dedicatedPlayAndPauseButtonsNeverToggle() {
        val (session, player) = buildSession()

        pressMediaKey(session, KeyEvent.KEYCODE_MEDIA_PLAY)
        awaitPlayWhenReady(player, true)
        pressMediaKey(session, KeyEvent.KEYCODE_MEDIA_PLAY)
        letDispatchSettle()
        assertTrue("a second dedicated Play must not toggle", playWhenReady(player))

        pressMediaKey(session, KeyEvent.KEYCODE_MEDIA_PAUSE)
        awaitPlayWhenReady(player, false)
        pressMediaKey(session, KeyEvent.KEYCODE_MEDIA_PAUSE)
        letDispatchSettle()
        assertFalse("a second dedicated Pause must not toggle", playWhenReady(player))
    }

    /**
     * The legacy key path resolves PLAY_PAUSE against the session's playback state, and only
     * STATE_PLAYING counts as playing — so during buffering the key acts as play, never pause.
     * Harmless for the app: the focused player window handles the toggle itself (and pauses on
     * play intent even mid-buffer), and Assistant "pause" arrives as a controller command, not
     * a key. The toggle-down half of the key is a hardware-playback check on the Shield.
     */
    @Test
    fun playPauseButtonActsAsPlayWhilePlaybackIsNotActive() {
        val (session, player) = buildSession()

        pressMediaKey(session, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        awaitPlayWhenReady(player, true)

        // Outside the double-tap window, which media3 treats as a skip gesture.
        letDispatchSettle()
        pressMediaKey(session, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        letDispatchSettle()
        assertTrue(
            "PLAY_PAUSE while buffering must resolve to play, not toggle",
            playWhenReady(player),
        )
    }

    @Test
    fun pauseBeforeThePlayerIsReadyCancelsPendingAutoplay() {
        val (session, player) = buildSession()
        // The startPlayback shape: intent to play with preparation still in flight.
        instrumentation.runOnMainSync {
            player.playWhenReady = true
            player.prepare()
        }

        pressMediaKey(session, KeyEvent.KEYCODE_MEDIA_PAUSE)
        awaitPlayWhenReady(player, false)
    }

    @Test
    fun twoLiveSessionsCoexistAcrossAnErrorRetry() {
        // Construction order mirrors the Retry path: the replacement exists before the
        // original is released. Duplicate session ids would throw right here.
        val (first, firstPlayer) = buildSession()
        buildSession()

        instrumentation.runOnMainSync {
            first.release()
            firstPlayer.release()
        }
        toRelease.removeAt(0)
    }
}
