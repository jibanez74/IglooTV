@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import com.igloo.blindpenguincoder.playback.model.MusicPlayerEvent
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Terminal playlist behavior that only a real ExoPlayer can exercise. */
@RunWith(AndroidJUnit4::class)
class ExoMusicPlayerEngineTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var engine: MusicPlayerEngine? = null

    @After
    fun releaseEngine() {
        instrumentation.runOnMainSync {
            engine?.release()
            engine = null
        }
    }

    @Test
    fun terminalFailureOnALaterTrackDoesNotPublishAQueueReset() {
        val request = MusicPlayRequest(
            source = MusicQueueSource.Album(albumId = 11, title = "Help!"),
            startIndex = 0,
            tracks = listOf(
                MusicPlayTrack(id = 901, title = "Yesterday", durationSec = 125.0),
                MusicPlayTrack(id = 902, title = "Ticket to Ride", durationSec = 190.0),
                MusicPlayTrack(id = 903, title = "Act Naturally", durationSec = 110.0),
            ),
        )

        instrumentation.runOnMainSync {
            engine = exoMusicPlayerEngine(
                context = context,
                request = request,
                dataSourceFactory = DataSource.Factory { MissingTrackDataSource() },
                trackStreamUrl = { id -> "https://example.invalid/tracks/$id.mp3" },
            ).also {
                it.startPlayback(
                    startTrackIndex = 1,
                    startPositionSec = 42.0,
                    initialPlayWhenReady = true,
                )
            }
        }

        waitFor("the non-retryable source failure") {
            engine?.events?.replayCache?.any { it is MusicPlayerEvent.Error } == true
        }

        val events = checkNotNull(engine).events.replayCache
        val trackChanges = events.filterIsInstance<MusicPlayerEvent.TrackChanged>()
        assertTrue("the selected later item must be announced", trackChanges.isNotEmpty())
        assertEquals(1, trackChanges.last().index)
        assertFalse(
            "queue teardown must not reset the item to zero",
            trackChanges.any { it.index == 0 },
        )

        val errorIndex = events.indexOfFirst { it is MusicPlayerEvent.Error }
        assertTrue(errorIndex >= 0)
        assertTrue(events.drop(errorIndex + 1).none { it is MusicPlayerEvent.TrackChanged })
        assertEquals(
            false,
            events.filterIsInstance<MusicPlayerEvent.PlayWhenReadyChanged>().last().playWhenReady,
        )
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private class MissingTrackDataSource : BaseDataSource(/* isNetwork= */ false) {
        private var openedUri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            openedUri = dataSpec.uri
            transferInitializing(dataSpec)
            throw FileNotFoundException("deterministic missing track")
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = openedUri

        override fun close() {
            openedUri = null
        }
    }
}
