// Extractors, seek maps and load error policies are marked unstable; the engine seam keeps the
// instability from spreading above this package.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.IndexSeekMap
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import java.io.IOException

/**
 * One subtitle track as the server streams it next to an HLS session: the original track — PGS,
 * VobSub, DVB, SSA or SubRip — alone in a Matroska stream, its packets already on the session's
 * timeline. ExoPlayer merges it with the session's source and renders it itself, so image-based
 * tracks need no burn-in and the video keeps its copy.
 */
internal fun subtitleStreamSource(
    url: String,
    dataSourceFactory: DataSource.Factory,
    onLoadFailed: (IOException) -> Unit,
): MediaSource = SubtitleStreamMediaSource(
    ProgressiveMediaSource.Factory(dataSourceFactory, ExtractorsFactory { arrayOf(SubtitleStreamExtractor()) })
        .setLoadErrorHandlingPolicy(SubtitleStreamLoadErrorPolicy(onLoadFailed))
        .createMediaSource(
            MediaItem.Builder()
                .setUri(url)
                .setMimeType(MimeTypes.APPLICATION_MATROSKA)
                .build(),
        ),
)

/** Gives each period of the stream the loading behaviour [SubtitleStreamPeriod] describes. */
private class SubtitleStreamMediaSource(stream: MediaSource) : WrappingMediaSource(stream) {
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        SubtitleStreamPeriod(mediaSource.createPeriod(id, allocator, startPositionUs))

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        mediaSource.releasePeriod((mediaPeriod as SubtitleStreamPeriod).stream)
    }
}

/**
 * A merged source loads its children in step: only the child whose next load position is
 * furthest behind keeps loading, and the stream's position is its last cue's. Between a sparse
 * track's cues — a forced track's, or the credits after the last cue — the stream sends nothing
 * while the server reads on, which held the video's loading at that cue and stalled playback.
 * While the stream is loading on its own it reports no position, so the video loads by its own
 * buffer; once it stops — prepared, restarted, or paused for a continue — it reports its position
 * again and is continued as before.
 */
internal class SubtitleStreamPeriod(val stream: MediaPeriod) : MediaPeriod by stream {
    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        stream.prepare(
            object : MediaPeriod.Callback {
                override fun onPrepared(mediaPeriod: MediaPeriod) = callback.onPrepared(this@SubtitleStreamPeriod)

                override fun onContinueLoadingRequested(source: MediaPeriod) =
                    callback.onContinueLoadingRequested(this@SubtitleStreamPeriod)
            },
            positionUs,
        )
    }

    override fun getNextLoadPositionUs(): Long =
        if (stream.isLoading) C.TIME_END_OF_SOURCE else stream.nextLoadPositionUs
}

/**
 * The stream has neither a length nor an index, so Matroska reports it unseekable — and a
 * merged source refuses a child that cannot follow its seeks, failing every start past zero and
 * every seek. Like Media3's own sideloaded-subtitle extractor, this reports one seek point, the
 * stream's first byte: a seek requests the stream again, which the server answers from its
 * cached copy, and the samples before the target are dropped. The server writes the same bytes
 * up to the Segment's payload on every request, which re-reading the header requires.
 *
 * The track is also marked default. Only the chosen track is streamed, so it is always the one
 * to show; but its own flags come from the source, and a track without the default flag or a
 * preferred language was left unselected by the first track selection. Its period then cancelled
 * the load it had started, and the engine's override restarted the stream from byte 0, so every
 * such start opened the stream twice on the server.
 */
internal class SubtitleStreamExtractor(
    private val inner: Extractor = MatroskaExtractor(DefaultSubtitleParserFactory(), /* flags= */ 0),
) : Extractor by inner {
    override fun init(output: ExtractorOutput) {
        inner.init(
            object : ForwardingExtractorOutput(output) {
                override fun seekMap(seekMap: SeekMap) {
                    super.seekMap(STREAM_START_SEEK_MAP)
                }

                override fun track(id: Int, type: Int): TrackOutput {
                    val track = super.track(id, type)
                    return if (type == C.TRACK_TYPE_TEXT) DefaultSelectedTrackOutput(track) else track
                }
            },
        )
    }

    private companion object {
        val STREAM_START_SEEK_MAP = IndexSeekMap(
            /* positions= */ longArrayOf(0),
            /* timesUs= */ longArrayOf(0),
            /* durationUs= */ C.TIME_UNSET,
        )
    }
}

/** Adds [C.SELECTION_FLAG_DEFAULT] to the format of the track it forwards. */
internal class DefaultSelectedTrackOutput(private val inner: TrackOutput) : TrackOutput by inner {
    override fun format(format: Format) {
        inner.format(format.buildUpon().setSelectionFlags(format.selectionFlags or C.SELECTION_FLAG_DEFAULT).build())
    }
}

/**
 * A subtitle stream that fails is never retried: Media3's retry of a stream without a length or
 * a duration either restarts it at a jump or, once prepared, waits silently for the next seek.
 * The failure is reported instead, so the engine drops the track and keeps the movie playing.
 * Media3 calls this off the main thread.
 */
internal class SubtitleStreamLoadErrorPolicy(
    private val onLoadFailed: (IOException) -> Unit,
) : LoadErrorHandlingPolicy {
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = null

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        onLoadFailed(loadErrorInfo.exception)
        return C.TIME_UNSET
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 0
}
