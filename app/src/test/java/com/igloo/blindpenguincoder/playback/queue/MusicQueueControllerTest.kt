package com.igloo.blindpenguincoder.playback.queue

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.ShuffleTracksData
import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.model.TrackListItem
import com.igloo.blindpenguincoder.data.model.TracksData
import com.igloo.blindpenguincoder.playback.model.MAX_QUEUE_TRACKS
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import com.igloo.blindpenguincoder.playback.queue.MusicQueueController.Companion.BATCH_SIZE
import com.igloo.blindpenguincoder.playback.queue.MusicQueueController.Companion.EXHAUSTED_NOTICE
import com.igloo.blindpenguincoder.playback.queue.MusicQueueController.Companion.FAILURE_NOTICE
import com.igloo.blindpenguincoder.playback.queue.MusicQueueController.Companion.MAX_EXCLUSIONS
import com.igloo.blindpenguincoder.playback.queue.MusicQueueController.Companion.REFILL_WHEN_REMAINING_BELOW
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refill rules of docs/music-shuffle.md, driven through a scripted fetcher: when a refill
 * fires, what it excludes, how a batch is deduplicated, and the two latches — the library
 * running out and the queue's own ceiling — plus the guarantee that a cancelled loop never
 * appends a batch that lands late.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicQueueControllerTest {

    private class Scripted : MusicQueueFetcher {
        val pageOffsets = mutableListOf<Long>()
        val excludes = mutableListOf<List<Long>>()
        var pages: (Long) -> ApiResult<TracksData> = { offset ->
            ApiResult.Success(TracksData(emptyList(), total = 0, offset = offset, limit = 50, hasMore = false))
        }
        var shuffle: suspend (List<Long>) -> ApiResult<ShuffleTracksData> = {
            ApiResult.Success(ShuffleTracksData(emptyList()))
        }

        override suspend fun tracks(limit: Long, offset: Long): ApiResult<TracksData> {
            pageOffsets += offset
            return pages(offset)
        }

        override suspend fun shuffleTracks(limit: Long, exclude: List<Long>): ApiResult<ShuffleTracksData> {
            excludes += exclude
            return shuffle(exclude)
        }
    }

    private fun row(id: Long) = TrackListItem(
        id = id,
        title = "Track $id",
        duration = 200_000,
        codec = "flac",
        bitRate = 900_000,
        albumId = SqlNullInt64(0, valid = false),
        albumTitle = SqlNullString("", valid = false),
        albumCover = SqlNullString("", valid = false),
        musicianId = SqlNullInt64(0, valid = false),
        musicianName = SqlNullString("", valid = false),
    )

    private fun tracks(ids: LongRange) = ids.map { MusicPlayTrack(it, "Track $it", 200.0) }

    private fun shuffleQueue(ids: LongRange = 1L..20L) =
        MusicPlayRequest(MusicQueueSource.LibraryShuffle, 0, tracks(ids))

    private fun inOrderQueue(ids: LongRange = 1L..20L, total: Long = 100) =
        MusicPlayRequest(MusicQueueSource.LibraryInOrder(nextOffset = ids.count().toLong(), total = total), 0, tracks(ids))

    private fun page(ids: LongRange, total: Long, hasMore: Boolean) = { offset: Long ->
        ApiResult.Success(TracksData(ids.map(::row), total = total, offset = offset, limit = 50, hasMore = hasMore))
    }

    /** The loop under test, on the unconfined dispatcher so an index change is handled inline. */
    private fun TestScope.running(controller: MusicQueueController, index: MutableStateFlow<Int>): Job =
        launch(UnconfinedTestDispatcher(testScheduler)) { controller.keepFilled(index) }

    @Test
    fun `no fetch while ten tracks of runway remain, one the moment it drops to nine`() = runTest {
        val fetcher = Scripted()
        val controller = MusicQueueController(shuffleQueue(1L..20L), fetcher)
        val index = MutableStateFlow(0)
        val job = running(controller, index)

        index.value = 20 - REFILL_WHEN_REMAINING_BELOW
        assertEquals(0, fetcher.excludes.size)

        index.value = 20 - REFILL_WHEN_REMAINING_BELOW + 1
        assertEquals(1, fetcher.excludes.size)
        job.cancel()
    }

    @Test
    fun `finite sources never fetch`() = runTest {
        val fetcher = Scripted()
        val finite = listOf(
            MusicQueueSource.Album(1, "A"),
            MusicQueueSource.Musician(1, "M"),
            MusicQueueSource.TrackList,
        )
        finite.forEach { source ->
            val controller = MusicQueueController(MusicPlayRequest(source, 0, tracks(1L..3L)), fetcher)
            val index = MutableStateFlow(2)
            val job = running(controller, index)
            index.value = 2
            job.cancel()
        }
        assertTrue(fetcher.pageOffsets.isEmpty())
        assertTrue(fetcher.excludes.isEmpty())
    }

    @Test
    fun `a shuffle batch is deduplicated within itself and against the queue and appended in order`() = runTest {
        val fetcher = Scripted()
        fetcher.shuffle = { ApiResult.Success(ShuffleTracksData(listOf(row(21), row(5), row(22), row(21), row(23)))) }
        val controller = MusicQueueController(shuffleQueue(1L..20L), fetcher)
        val appended = mutableListOf<List<Long>>()
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.appended.collect { batch -> appended += batch.map { it.id } }
        }
        val index = MutableStateFlow(0)
        val job = running(controller, index)

        index.value = 15

        assertEquals(listOf(listOf(21L, 22L, 23L)), appended)
        assertEquals((1L..23L).toList(), controller.state.value.request.tracks.map { it.id })
        assertFalse(controller.state.value.exhausted)
        assertNull(controller.state.value.notice)
        job.cancel()
        collecting.cancel()
    }

    @Test
    fun `a batch of only known tracks appends nothing and does not latch`() = runTest {
        val fetcher = Scripted()
        fetcher.shuffle = { ApiResult.Success(ShuffleTracksData(listOf(row(3), row(4)))) }
        val controller = MusicQueueController(shuffleQueue(1L..20L), fetcher)
        val index = MutableStateFlow(0)
        val job = running(controller, index)

        index.value = 15
        assertEquals(20, controller.state.value.request.tracks.size)
        assertFalse(controller.state.value.exhausted)

        // The next track change retries.
        index.value = 16
        assertEquals(2, fetcher.excludes.size)
        job.cancel()
    }

    @Test
    fun `an empty shuffle response latches exhaustion with one notice and stops fetching`() = runTest {
        val fetcher = Scripted()
        val controller = MusicQueueController(shuffleQueue(1L..20L), fetcher)
        val index = MutableStateFlow(0)
        val job = running(controller, index)

        index.value = 15
        assertTrue(controller.state.value.exhausted)
        assertEquals(EXHAUSTED_NOTICE, controller.state.value.notice)

        index.value = 16
        index.value = 19
        assertEquals(1, fetcher.excludes.size)
        job.cancel()
    }

    @Test
    fun `exclusions are the newest two hundred ids`() = runTest {
        val fetcher = Scripted()
        val controller = MusicQueueController(shuffleQueue(1L..300L), fetcher)
        val index = MutableStateFlow(0)
        val job = running(controller, index)

        index.value = 295

        assertEquals((101L..300L).toList(), fetcher.excludes.single())
        assertEquals(MAX_EXCLUSIONS, fetcher.excludes.single().size)
        job.cancel()
    }

    @Test
    fun `an in-order page advances the cursor and latches silently at the end of the library`() = runTest {
        val fetcher = Scripted()
        fetcher.pages = page(21L..30L, total = 30, hasMore = false)
        val controller = MusicQueueController(inOrderQueue(1L..20L, total = 30), fetcher)
        val index = MutableStateFlow(0)
        val job = running(controller, index)

        index.value = 15

        assertEquals(listOf(20L), fetcher.pageOffsets)
        val source = controller.state.value.request.source as MusicQueueSource.LibraryInOrder
        assertEquals(30L, source.nextOffset)
        assertEquals(30L, source.total)
        assertEquals(30, controller.state.value.request.tracks.size)
        assertTrue(controller.state.value.exhausted)
        assertNull(controller.state.value.notice)
        job.cancel()
    }

    @Test
    fun `an in-order page with more to come keeps fetching on the next threshold`() = runTest {
        val fetcher = Scripted()
        fetcher.pages = { offset -> page((offset + 1)..(offset + 50), total = 1000, hasMore = true)(offset) }
        val controller = MusicQueueController(inOrderQueue(1L..20L, total = 1000), fetcher)
        val index = MutableStateFlow(0)
        val job = running(controller, index)

        index.value = 15
        assertEquals(70, controller.state.value.request.tracks.size)
        assertFalse(controller.state.value.exhausted)

        index.value = 65
        assertEquals(listOf(20L, 70L), fetcher.pageOffsets)
        assertEquals(120, controller.state.value.request.tracks.size)
        job.cancel()
    }

    @Test
    fun `a failed refill keeps the queue and reports, and the next change retries and clears`() = runTest {
        val fetcher = Scripted()
        var fail = true
        fetcher.shuffle = {
            if (fail) ApiResult.Failure(AppError.Network) else ApiResult.Success(ShuffleTracksData(listOf(row(21))))
        }
        val controller = MusicQueueController(shuffleQueue(1L..20L), fetcher)
        val index = MutableStateFlow(0)
        val job = running(controller, index)

        index.value = 15
        assertEquals(20, controller.state.value.request.tracks.size)
        assertEquals(FAILURE_NOTICE, controller.state.value.notice)
        assertFalse(controller.state.value.exhausted)

        fail = false
        index.value = 16
        assertEquals(21, controller.state.value.request.tracks.size)
        assertNull(controller.state.value.notice)
        job.cancel()
    }

    @Test
    fun `exactly one fetch is in flight however often the index moves`() = runTest {
        val fetcher = Scripted()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        fetcher.shuffle = {
            calls += 1
            gate.await()
            ApiResult.Success(ShuffleTracksData(listOf(row(21))))
        }
        val controller = MusicQueueController(shuffleQueue(1L..20L), fetcher)
        val index = MutableStateFlow(0)
        val job = running(controller, index)

        index.value = 15
        index.value = 16
        index.value = 17
        assertEquals(1, calls)

        gate.complete(Unit)
        // The conflated index change is handled after the fetch, once.
        assertEquals(2, calls)
        job.cancel()
    }

    @Test
    fun `a cancelled loop never appends a batch that completes afterwards`() = runTest {
        val fetcher = Scripted()
        val gate = CompletableDeferred<Unit>()
        fetcher.shuffle = {
            gate.await()
            ApiResult.Success(ShuffleTracksData(listOf(row(21))))
        }
        val controller = MusicQueueController(shuffleQueue(1L..20L), fetcher)
        val index = MutableStateFlow(0)
        val job = running(controller, index)
        index.value = 15
        assertEquals(1, fetcher.excludes.size)

        job.cancel()
        gate.complete(Unit)

        assertEquals(20, controller.state.value.request.tracks.size)
    }

    @Test
    fun `refills stop at the queue ceiling`() = runTest {
        val fetcher = Scripted()
        fetcher.shuffle = { exclude -> ApiResult.Success(ShuffleTracksData(listOf(row(exclude.max() + 1)))) }
        val full = shuffleQueue(1L..(MAX_QUEUE_TRACKS - BATCH_SIZE + 1).toLong())
        val controller = MusicQueueController(full, fetcher)
        val index = MutableStateFlow(0)
        val job = running(controller, index)

        index.value = full.tracks.size - 1

        assertTrue(fetcher.excludes.isEmpty())
        job.cancel()
    }
}
