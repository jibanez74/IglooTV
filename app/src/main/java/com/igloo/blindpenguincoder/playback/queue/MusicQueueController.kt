package com.igloo.blindpenguincoder.playback.queue

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.api.MusicApi
import com.igloo.blindpenguincoder.data.repository.MusicQueueFetcher
import com.igloo.blindpenguincoder.playback.model.MAX_QUEUE_TRACKS
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import com.igloo.blindpenguincoder.playback.model.isEndless
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The live queue, plus the two things the chrome shows about its refills. */
data class MusicQueueState(
    val request: MusicPlayRequest,
    /** Latched once the source has nothing more; a new launch is a new controller, so it clears. */
    val exhausted: Boolean = false,
    /** One notice line: a failed refill, or the library running out; cleared by the next success. */
    val notice: String? = null,
)

/**
 * The refill rules of docs/music-shuffle.md for an endless queue, pure Kotlin so they run on
 * the JVM: when the playhead is within [REFILL_WHEN_REMAINING_BELOW] tracks of the end, one
 * batch of [BATCH_SIZE] is fetched, deduplicated against the batch and the whole queue, and
 * appended. Exactly one fetch is ever in flight — [keepFilled] is sequential by construction —
 * and cancelling it is the generation guard: a fetch that completes after the loop is gone can
 * never append.
 *
 * Nothing is trimmed from the queue's head (a deliberate deviation from the spec's history
 * cap, recorded in docs/design-system.md section 11.8.2): the queue lives only while the
 * player overlay is up, and stable indices are what keep the reducer and ExoPlayer agreeing.
 * [MAX_QUEUE_TRACKS] bounds it instead.
 */
class MusicQueueController(
    initial: MusicPlayRequest,
    private val fetcher: MusicQueueFetcher,
) {
    private val _state = MutableStateFlow(MusicQueueState(initial))
    val state: StateFlow<MusicQueueState> = _state.asStateFlow()

    private val _appended = MutableSharedFlow<List<MusicPlayTrack>>(extraBufferCapacity = 8)

    /** Batches actually appended, in order; hot, so a rebuilt engine is seeded from [state]. */
    val appended: SharedFlow<List<MusicPlayTrack>> = _appended.asSharedFlow()

    /** Runs until cancelled, refilling whenever [currentIndex] leaves too little runway. */
    suspend fun keepFilled(currentIndex: StateFlow<Int>) {
        currentIndex.collect { index ->
            if (shouldRefill(index)) refillOnce()
        }
    }

    private fun shouldRefill(index: Int): Boolean {
        val current = _state.value
        val tracks = current.request.tracks
        // The current track counts as runway, and a negative index is an invalid runway, not
        // an invitation to fetch (docs/music-shuffle.md).
        val remaining = tracks.size - index
        return current.request.source.isEndless &&
            !current.exhausted &&
            index >= 0 &&
            remaining < REFILL_WHEN_REMAINING_BELOW &&
            tracks.size + BATCH_SIZE <= MAX_QUEUE_TRACKS
    }

    private suspend fun refillOnce() {
        when (val source = _state.value.request.source) {
            is MusicQueueSource.LibraryInOrder -> refillInOrder(source)
            MusicQueueSource.LibraryShuffle -> refillShuffle()
            else -> Unit
        }
    }

    private suspend fun refillInOrder(source: MusicQueueSource.LibraryInOrder) {
        when (val result = fetcher.tracks(BATCH_SIZE.toLong(), source.nextOffset)) {
            is ApiResult.Success -> {
                val page = result.value
                val fresh = freshTracks(page.tracks.map { it.toMusicPlayTrack() })
                val nextOffset = source.nextOffset + page.tracks.size
                _state.update {
                    it.copy(
                        request = it.request.copy(
                            source = source.copy(nextOffset = nextOffset, total = page.total),
                            tracks = it.request.tracks + fresh,
                        ),
                        // Reaching the end of the library in order is not an event worth a
                        // notice; the tail simply plays out.
                        exhausted = !page.hasMore || page.tracks.isEmpty() || nextOffset >= page.total,
                        notice = null,
                    )
                }
                if (fresh.isNotEmpty()) _appended.emit(fresh)
            }

            is ApiResult.Failure -> reportFailure()
        }
    }

    private suspend fun refillShuffle() {
        val exclude = _state.value.request.tracks.map { it.id }.takeLast(MusicApi.SHUFFLE_MAX_EXCLUDE)
        when (val result = fetcher.shuffleTracks(BATCH_SIZE.toLong(), exclude)) {
            is ApiResult.Success -> {
                val batch = result.value.tracks
                val fresh = freshTracks(batch.map { it.toMusicPlayTrack() })
                _state.update {
                    it.copy(
                        request = it.request.copy(tracks = it.request.tracks + fresh),
                        // An empty server response is the library exhausted; a batch of tracks
                        // the queue already holds is not, and the next track change retries.
                        exhausted = batch.isEmpty(),
                        notice = if (batch.isEmpty()) EXHAUSTED_NOTICE else null,
                    )
                }
                if (fresh.isNotEmpty()) _appended.emit(fresh)
            }

            is ApiResult.Failure -> reportFailure()
        }
    }

    /** Duplicates within the batch and against the whole queue are dropped; order is kept. */
    private fun freshTracks(batch: List<MusicPlayTrack>): List<MusicPlayTrack> {
        val known = _state.value.request.tracks.mapTo(HashSet()) { it.id }
        return batch.distinctBy { it.id }.filterNot { it.id in known }
    }

    /** The queue is kept; the next track change retries. */
    private fun reportFailure() {
        _state.update { it.copy(notice = FAILURE_NOTICE) }
    }

    companion object {
        const val BATCH_SIZE = 50
        const val REFILL_WHEN_REMAINING_BELOW = 10
        const val FAILURE_NOTICE = "Couldn't load more tracks. The queue will play out."
        const val EXHAUSTED_NOTICE = "That's every track in the library."
    }
}
