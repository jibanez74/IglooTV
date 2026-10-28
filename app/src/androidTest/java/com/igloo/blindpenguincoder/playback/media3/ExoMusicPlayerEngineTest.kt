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
import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.network.ActiveCredential
import com.igloo.blindpenguincoder.core.network.DeviceCredentialSource
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import com.igloo.blindpenguincoder.playback.model.MusicPlayerEvent
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Playlist failures and session artwork routing exercised through a real ExoPlayer. */
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
                artworkDataSourceFactory = DataSource.Factory { MissingTrackDataSource() },
                trackStreamUrl = { id -> "https://example.invalid/tracks/$id.mp3" },
                artworkUrl = { it },
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

    @Test
    fun artworkUsesItsSeparateFactoryAndFailureLeavesTheStreamReady() {
        MusicArtworkTestServer().use { server ->
            val credentials = DeviceCredentialSource { ActiveCredential(42L, "test-token") }
            val serverUrl = ServerUrlProvider().apply {
                set(checkNotNull(ServerAddress.fromApiBaseUrl("${server.origin}/api")))
            }
            val revoked = CopyOnWriteArrayList<Long?>()
            val streamRequests = CopyOnWriteArrayList<Uri>()
            val artworkRequests = CopyOnWriteArrayList<Uri>()
            val artworkFinished = CountDownLatch(1)
            val streams = recordingFactory(
                bearerStreamDataSourceFactory(credentials, serverUrl, revoked::add),
                streamRequests,
            )
            val artwork = recordingFactory(
                bearerArtworkDataSourceFactory(credentials, serverUrl),
                artworkRequests,
                artworkFinished,
            )
            val request = MusicPlayRequest(
                source = MusicQueueSource.Album(albumId = 11, title = "Help!"),
                startIndex = 0,
                tracks = listOf(
                    MusicPlayTrack(
                        id = 901,
                        title = "Yesterday",
                        durationSec = 1.0,
                        coverUrl = "${server.origin}/cover",
                    ),
                ),
            )

            instrumentation.runOnMainSync {
                engine = exoMusicPlayerEngine(
                    context = context,
                    request = request,
                    dataSourceFactory = streams,
                    artworkDataSourceFactory = artwork,
                    trackStreamUrl = { "${server.origin}/stream" },
                    artworkUrl = { it },
                ).also { it.startPlayback(0, 0.0, initialPlayWhenReady = false) }
            }

            waitFor("the artwork failure and a ready audio stream") {
                artworkFinished.count == 0L &&
                    engine?.events?.replayCache?.any { it is MusicPlayerEvent.Ready } == true
            }

            assertEquals(listOf(Uri.parse("${server.origin}/cover")), artworkRequests.toList())
            assertTrue(streamRequests.isNotEmpty())
            assertTrue(streamRequests.all { it.toString() == "${server.origin}/stream" })
            assertTrue("a cover failure must not revoke the profile", revoked.isEmpty())
            assertFalse(checkNotNull(engine).events.replayCache.any { it is MusicPlayerEvent.Error })
        }
    }

    private fun recordingFactory(
        delegate: DataSource.Factory,
        requested: MutableList<Uri>,
        finished: CountDownLatch? = null,
    ) = DataSource.Factory {
        val source = delegate.createDataSource()
        object : DataSource by source {
            override fun open(dataSpec: DataSpec): Long {
                requested.add(dataSpec.uri)
                return try {
                    source.open(dataSpec)
                } finally {
                    finished?.countDown()
                }
            }
        }
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
