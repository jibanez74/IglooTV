// MediaPeriod, LoadingInfo and SeekParameters are part of Media3's unstable surface.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleStreamPeriodTest {

    /** A subtitle stream's period: its loader state and position, as the test sets them. */
    private class FakeStream : MediaPeriod {
        var loading = false
        var nextLoadUs = 0L
        var bufferedUs = 0L
        var continued = 0
        lateinit var callback: MediaPeriod.Callback

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            this.callback = callback
        }

        override fun maybeThrowPrepareError() = Unit

        override fun getTrackGroups(): TrackGroupArray = TrackGroupArray.EMPTY

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long = positionUs

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit

        override fun readDiscontinuity(): Long = C.TIME_UNSET

        override fun seekToUs(positionUs: Long): Long = positionUs

        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs

        override fun getBufferedPositionUs(): Long = bufferedUs

        override fun getNextLoadPositionUs(): Long = nextLoadUs

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
            continued++
            return true
        }

        override fun isLoading(): Boolean = loading

        override fun reevaluateBuffer(positionUs: Long) = Unit
    }

    private class RecordingCallback : MediaPeriod.Callback {
        val prepared = mutableListOf<MediaPeriod>()
        val continueRequests = mutableListOf<MediaPeriod>()

        override fun onPrepared(mediaPeriod: MediaPeriod) {
            prepared += mediaPeriod
        }

        override fun onContinueLoadingRequested(source: MediaPeriod) {
            continueRequests += source
        }
    }

    private val loadingInfo = LoadingInfo.Builder().setPlaybackPositionUs(0).build()

    @Test
    fun aStreamLoadingOnItsOwnHoldsNoOtherLoaderBack() {
        // Its last cue at 10 s, the server reading on toward the next one.
        val stream = FakeStream().apply {
            loading = true
            nextLoadUs = 10_000_000
        }

        assertEquals(C.TIME_END_OF_SOURCE, SubtitleStreamPeriod(stream).nextLoadPositionUs)
    }

    @Test
    fun aStreamThatStoppedReportsItsPositionSoItIsContinued() {
        // Prepared, restarted after a seek, or paused for a continue: the merged source has to
        // see where it is to call it again.
        val stream = FakeStream().apply {
            loading = false
            nextLoadUs = 10_000_000
        }
        val period = SubtitleStreamPeriod(stream)

        assertEquals(10_000_000, period.nextLoadPositionUs)
        assertTrue(period.continueLoading(loadingInfo))
        assertEquals(1, stream.continued)
    }

    @Test
    fun aFinishedStreamStaysFinished() {
        val stream = FakeStream().apply {
            loading = false
            nextLoadUs = C.TIME_END_OF_SOURCE
        }

        assertEquals(C.TIME_END_OF_SOURCE, SubtitleStreamPeriod(stream).nextLoadPositionUs)
    }

    @Test
    fun theBufferedPositionIsTheStreamsOwn() {
        val stream = FakeStream().apply {
            loading = true
            bufferedUs = 10_000_000
        }

        assertEquals(10_000_000, SubtitleStreamPeriod(stream).bufferedPositionUs)
    }

    @Test
    fun callbacksNameThePeriodTheMergedSourceHolds() {
        // The merged source tracks preparation by period identity, so it must hear about the
        // wrapper it created, never the stream inside.
        val stream = FakeStream()
        val period = SubtitleStreamPeriod(stream)
        val callback = RecordingCallback()

        period.prepare(callback, /* positionUs= */ 0)
        stream.callback.onPrepared(stream)
        stream.callback.onContinueLoadingRequested(stream)

        assertSame(period, callback.prepared.single())
        assertSame(period, callback.continueRequests.single())
    }
}
