// HlsMediaSource and MergingMediaSource are part of Media3's unstable surface.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The subtitle stream through the real Media3 pipeline — an HLS session merged with the
 * server's Matroska stream of one track, played on the device clock — over the fixtures
 * [SubtitleFixtureServer] serves. What the engine tests cannot show without real media: the
 * merged source prepares at a position past zero, survives a seek back, and the cues land on the
 * session's timeline.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleStreamSourceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val server = SubtitleFixtureServer()
    private lateinit var player: ExoPlayer

    /** One onCues delivery: where the player was, and what was on screen from then on. */
    private data class CueChange(val positionMs: Long, val cues: List<Cue>)

    private val cueChanges = Collections.synchronizedList(mutableListOf<CueChange>())
    private val errors = Collections.synchronizedList(mutableListOf<PlaybackException>())
    private val states = Collections.synchronizedList(mutableListOf<Int>())
    private val loadFailures = Collections.synchronizedList(mutableListOf<IOException>())

    @Before
    fun buildPlayer() {
        onMain {
            player = ExoPlayer.Builder(context).build()
            player.volume = 0f
            player.addListener(
                object : Player.Listener {
                    override fun onCues(cueGroup: CueGroup) {
                        cueChanges += CueChange(player.currentPosition, cueGroup.cues)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        errors += error
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        states += playbackState
                    }
                },
            )
            // Both fixture tracks are the only text in their merged source; a language list
            // that names them keeps the selection independent of their default flags.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguages("en", "es")
                .build()
        }
    }

    @After
    fun releasePlayer() {
        onMain { player.release() }
    }

    private fun play(trackIndex: Int, startMs: Long) {
        onMain {
            val session = HlsMediaSource.Factory(server.factory)
                .createMediaSource(MediaItem.fromUri("https://203.0.113.1/api/movies/7/hls/remux/playlist.m3u8"))
            val subtitles = subtitleStreamSource(
                url = "https://203.0.113.1/api/movies/7/subtitles/$trackIndex/stream.mkv",
                dataSourceFactory = server.factory,
                onLoadFailed = { loadFailures += it },
            )
            player.setMediaSource(MergingMediaSource(session, subtitles), startMs)
            player.playWhenReady = true
            player.prepare()
        }
    }

    private fun position(): Long {
        var positionMs = 0L
        onMain { positionMs = player.currentPosition }
        return positionMs
    }

    private fun cueSummary(): String = synchronized(cueChanges) {
        cueChanges.joinToString { change ->
            "${change.positionMs}ms:" + change.cues.joinToString("|") { it.text?.toString() ?: "bitmap" }
        }
    }

    private fun firstChange(after: Int = 0, predicate: (CueChange) -> Boolean): CueChange? =
        synchronized(cueChanges) { cueChanges.drop(after).firstOrNull(predicate) }

    private fun assertWithin(what: String, positionMs: Long, fromMs: Long, toMs: Long) {
        assertTrue("$what at $positionMs ms, expected $fromMs..$toMs ms; ${diagnostics()}", positionMs in fromMs..toMs)
    }

    private fun waitFor(what: String, condition: () -> Boolean) =
        com.igloo.blindpenguincoder.playback.media3.waitFor(what, 15_000, { "; ${diagnostics()}" }, condition)

    private fun diagnostics(): String {
        var tracks = ""
        onMain {
            tracks = player.currentTracks.groups.joinToString { group ->
                val format = group.getTrackFormat(0)
                "${format.id}/${format.sampleMimeType}/${format.codecs}/${format.language}" +
                    if (group.isSelected) "*" else ""
            }
        }
        return "cues: ${cueSummary()}; tracks: $tracks; requests: ${server.requests}; errors: $errors"
    }

    private fun selectedTextFormatId(): String? {
        var id: String? = null
        onMain {
            id = player.currentTracks.groups
                .firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
                ?.getTrackFormat(0)
                ?.id
        }
        return id
    }

    /**
     * A seek reads the stream again from its first byte, and Media3 replays the cues before the
     * target in one instant at the target, ending on the right state; only changes after the
     * playhead moved say when a cue really showed.
     */
    private fun CueChange.afterStart(startMs: Long): Boolean = positionMs > startMs + 50

    @Test
    fun aPgsTrackRendersOnTheSessionTimelineFromAStartPastZero() {
        // A start past zero is what used to make a merged child that cannot seek fail.
        play(trackIndex = 0, startMs = 2_800)

        val isShown = { change: CueChange -> change.afterStart(2_800) && change.cues.any { it.bitmap != null } }
        waitFor("the PGS cue at 3.0 s") { firstChange(predicate = isShown) != null }
        val shown = requireNotNull(firstChange(predicate = isShown))
        assertWithin("the PGS cue", shown.positionMs, 2_900, 3_700)
        val shownAt = cueChanges.indexOf(shown)
        waitFor("the PGS cue to clear at 4.5 s") { firstChange(after = shownAt + 1) { it.cues.isEmpty() } != null }
        val cleared = requireNotNull(firstChange(after = shownAt + 1) { it.cues.isEmpty() })
        assertWithin("the PGS clear", cleared.positionMs, 4_400, 5_200)

        assertTrue("selected text format ${selectedTextFormatId()}", selectedTextFormatId()?.startsWith(SUBTITLE_STREAM_ID_PREFIX) == true)
        assertTrue("player errors: $errors", errors.isEmpty())
        assertTrue("load failures: $loadFailures", loadFailures.isEmpty())
    }

    @Test
    fun aTextTrackSurvivesASeekBackInsideTheSession() {
        play(trackIndex = 1, startMs = 5_500)
        val isTwo = { change: CueChange -> change.afterStart(5_500) && change.cues.any { it.text?.toString() == "Two" } }
        waitFor("the cue at 6.0 s") { firstChange(predicate = isTwo) != null }
        val two = requireNotNull(firstChange(predicate = isTwo))
        assertWithin("Two", two.positionMs, 5_900, 6_700)
        val requestsBeforeSeek = server.subtitleRequests(1)

        // Media3 keeps nothing behind the playhead, so going back means reading the stream again
        // from its first byte — the one seek point the extractor reports.
        val before = cueChanges.size
        onMain { player.seekTo(800) }
        val isOne = { change: CueChange -> change.afterStart(800) && change.cues.any { it.text?.toString() == "One" } }
        waitFor("the cue at 1.0 s after the seek") { firstChange(after = before, predicate = isOne) != null }
        val one = requireNotNull(firstChange(after = before, predicate = isOne))
        assertWithin("One", one.positionMs, 900, 1_700)

        assertTrue("the stream was not requested again", server.subtitleRequests(1) > requestsBeforeSeek)
        assertTrue("player errors: $errors", errors.isEmpty())
    }

    /** A session played from its very start: no seek, so the stream is read once from byte 0. */
    @Test
    fun aTextTrackRendersFromTheSessionStart() {
        play(trackIndex = 1, startMs = 0)
        val isOne = { change: CueChange -> change.cues.any { it.text?.toString() == "One" } }
        waitFor("the cue at 1.0 s") { firstChange(predicate = isOne) != null }
        assertWithin("One", requireNotNull(firstChange(predicate = isOne)).positionMs, 900, 1_700)
        assertTrue("player errors: $errors", errors.isEmpty())
    }

    /**
     * Between a sparse track's cues — a forced track's, or the credits after the last cue — the
     * server's stream sends only keepalives while FFmpeg reads on. A merged source loads its
     * children in step, so a stream with no new cue must not hold the video's loading back; and
     * the cues after the silence still land on time.
     */
    @Test
    fun aStreamWaitingForItsNextCueDoesNotHoldBackTheVideo() {
        server.subtitleHoldMs = 8_000
        val startedAt = SystemClock.uptimeMillis()
        play(trackIndex = 1, startMs = 0)

        waitFor("playback past 6 s") { position() >= 6_000 }
        val tookMs = SystemClock.uptimeMillis() - startedAt
        assertTrue("6 s of video took $tookMs ms: it waited for the subtitle stream", tookMs < 7_500)
        val afterReady = synchronized(states) { states.dropWhile { it != Player.STATE_READY } }
        assertTrue("rebuffered: $afterReady", Player.STATE_BUFFERING !in afterReady)

        val isThree = { change: CueChange -> change.cues.any { it.text?.toString() == "Three" } }
        waitFor("the cue at 8.0 s after the silence") { firstChange(predicate = isThree) != null }
        assertWithin("Three", requireNotNull(firstChange(predicate = isThree)).positionMs, 7_900, 8_700)
        assertTrue("player errors: $errors", errors.isEmpty())
        assertTrue("load failures: $loadFailures", loadFailures.isEmpty())
    }

    @Test
    fun aFailedStreamIsReportedOnceAndNeverRetried() {
        server.subtitleFailure = 404
        play(trackIndex = 1, startMs = 0)

        waitFor("the reported failure") { loadFailures.isNotEmpty() }
        SystemClock.sleep(1_000)

        val failure = loadFailures.single()
        assertTrue("$failure", failure is HttpDataSource.InvalidResponseCodeException)
        assertEquals(404, (failure as HttpDataSource.InvalidResponseCodeException).responseCode)
        assertEquals(1, server.subtitleRequests(1))
    }
}
