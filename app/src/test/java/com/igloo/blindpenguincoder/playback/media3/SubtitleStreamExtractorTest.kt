// Extractor, ExtractorOutput, TrackOutput and DataReader are part of Media3's unstable surface.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleStreamExtractorTest {

    /** Records the last format each track was given. */
    private class RecordingTrackOutput : TrackOutput {
        var format: Format? = null

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = 0

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = Unit

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) = Unit
    }

    private class RecordingOutput : ExtractorOutput {
        val tracks = mutableMapOf<Int, RecordingTrackOutput>()

        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { RecordingTrackOutput() }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    /** Declares the tracks it is given as the Matroska extractor would, with the source's flags. */
    private class DeclaringExtractor(private val tracks: List<Pair<Int, Format>>) : Extractor {
        override fun sniff(input: ExtractorInput): Boolean = true

        override fun init(output: ExtractorOutput) {
            tracks.forEachIndexed { id, (type, format) -> output.track(id, type).format(format) }
            output.endTracks()
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = Extractor.RESULT_END_OF_INPUT

        override fun seek(position: Long, timeUs: Long) = Unit

        override fun release() = Unit
    }

    private fun subtitleFormat(selectionFlags: Int) = Format.Builder()
        .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
        .setSelectionFlags(selectionFlags)
        .build()

    // A track the first selection skipped had its load cancelled and restarted from byte 0.
    @Test
    fun theStreamedTrackIsMarkedDefaultWhateverItsSourceFlags() {
        val output = RecordingOutput()
        SubtitleStreamExtractor(DeclaringExtractor(listOf(C.TRACK_TYPE_TEXT to subtitleFormat(0))))
            .init(output)

        assertEquals(C.SELECTION_FLAG_DEFAULT, output.tracks.getValue(0).format!!.selectionFlags)
    }

    @Test
    fun aForcedTrackKeepsItsForcedFlag() {
        val output = RecordingOutput()
        SubtitleStreamExtractor(
            DeclaringExtractor(listOf(C.TRACK_TYPE_TEXT to subtitleFormat(C.SELECTION_FLAG_FORCED))),
        ).init(output)

        assertEquals(
            C.SELECTION_FLAG_FORCED or C.SELECTION_FLAG_DEFAULT,
            output.tracks.getValue(0).format!!.selectionFlags,
        )
    }

    @Test
    fun otherTracksAreLeftAlone() {
        val output = RecordingOutput()
        val video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        SubtitleStreamExtractor(DeclaringExtractor(listOf(C.TRACK_TYPE_VIDEO to video))).init(output)

        assertEquals(0, output.tracks.getValue(0).format!!.selectionFlags)
    }
}
