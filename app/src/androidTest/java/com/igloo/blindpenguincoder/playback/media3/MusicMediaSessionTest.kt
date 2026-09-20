@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.media.session.MediaController as PlatformMediaController
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The album session's contract with the system (design-system.md section 11.8.2), on a real
 * player: the now-playing metadata is per track and follows the queue with no manual update
 * code, the system's SKIP buttons move the queue rather than seek, and two sessions coexist —
 * on error-Retry the replacement engine is constructed before the old one's disposal releases
 * it, which throws unless ids differ.
 */
@RunWith(AndroidJUnit4::class)
class MusicMediaSessionTest {

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

    private fun album(
        artistName: String? = "The Beatles",
        coverUrl: String? = "https://i.scdn.co/image/help.jpg",
    ) = MusicPlayRequest(
        albumId = 11,
        albumTitle = "Help!",
        artistName = artistName,
        coverUrl = coverUrl,
        tracks = listOf(
            MusicPlayTrack(id = 901, title = "Yesterday", durationSec = 125.0),
            MusicPlayTrack(id = 902, title = "Ticket to Ride", durationSec = 190.0),
        ),
    )

    /**
     * A real player and session over a TEST-NET address that never answers, so the queue's
     * shape is exercised without any decoding: every assertion here is about the playlist and
     * the session, neither of which needs a byte of audio.
     */
    private fun buildSession(): Pair<MediaSession, ExoPlayer> {
        val request = album()
        lateinit var built: Pair<MediaSession, ExoPlayer>
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            player.setMediaItems(
                request.tracks.map { track ->
                    MediaItem.Builder()
                        .setUri("https://203.0.113.1/music/tracks/${track.id}/stream")
                        .setMediaId(track.id.toString())
                        .setMediaMetadata(musicMediaMetadata(track, request))
                        .build()
                },
            )
            built = buildMusicMediaSession(context, player, request.albumId) to player
        }
        toRelease += built
        return built
    }

    private fun pressMediaKey(session: MediaSession, keyCode: Int) {
        val controller = PlatformMediaController(context, session.platformToken)
        controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun currentIndex(player: ExoPlayer): Int {
        var value = -1
        instrumentation.runOnMainSync { value = player.currentMediaItemIndex }
        return value
    }

    private fun awaitIndex(player: ExoPlayer, expected: Int) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (currentIndex(player) == expected) return
            Thread.sleep(50)
        }
        fail("the queue never reached item $expected")
    }

    @Test
    fun metadataCarriesTheTrackTheAlbumAndTheMusicType() {
        val request = album()
        val first = musicMediaMetadata(request.tracks[0], request)

        assertEquals("Yesterday", first.title.toString())
        assertEquals("The Beatles", first.artist.toString())
        assertEquals("Help!", first.albumTitle.toString())
        assertEquals("https://i.scdn.co/image/help.jpg", first.artworkUri.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, first.mediaType)

        // The album page's rule: a missing artist or cover is absent, never an empty string.
        val bare = album(artistName = null, coverUrl = null)
        assertNull(musicMediaMetadata(bare.tracks[0], bare).artist)
        assertNull(musicMediaMetadata(bare.tracks[0], bare).artworkUri)
    }

    @Test
    fun everyQueueItemCarriesItsOwnMetadataSoTheSystemFollowsAutoAdvance() {
        val (_, player) = buildSession()
        var titles = emptyList<String>()
        instrumentation.runOnMainSync {
            titles = (0 until player.mediaItemCount).map {
                player.getMediaItemAt(it).mediaMetadata.title.toString()
            }
        }
        assertEquals(listOf("Yesterday", "Ticket to Ride"), titles)
    }

    @Test
    fun theSystemSkipButtonsMoveTheQueueRatherThanSeek() {
        val (session, player) = buildSession()
        assertEquals(0, currentIndex(player))

        pressMediaKey(session, KeyEvent.KEYCODE_MEDIA_NEXT)
        awaitIndex(player, 1)

        pressMediaKey(session, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        awaitIndex(player, 0)
    }

    @Test
    fun theSessionAdvertisesTheQueueCommandsTheTransportRowImplements() {
        val (_, player) = buildSession()
        var commands = Player.Commands.EMPTY
        instrumentation.runOnMainSync { commands = player.availableCommands }
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test
    fun twoLiveSessionsCoexistAcrossAnErrorRetry() {
        // Construction order mirrors the Retry path: the replacement exists before the
        // original is released. Duplicate session ids would throw right here.
        val (first, firstPlayer) = buildSession()
        val (second, _) = buildSession()
        assertNotEquals(first.id, second.id)

        instrumentation.runOnMainSync {
            first.release()
            firstPlayer.release()
        }
        toRelease.removeAt(0)
    }
}
