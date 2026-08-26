package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.playback.model.PlayableAudioTrack
import com.igloo.blindpenguincoder.playback.model.TrackOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On the device rather than the JVM: Media3's [Format] normalization goes through
 * android.text.TextUtils, which plain unit tests cannot load. The mapping itself is pure.
 */
@RunWith(AndroidJUnit4::class)
class TrackOptionsTest {

    private fun audioFormat(
        language: String? = "en",
        channels: Int = 6,
    ) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC)
        .setLanguage(language)
        .setChannelCount(channels)
        .build()

    private fun textFormat(
        language: String? = "en",
        label: String? = null,
        selectionFlags: Int = 0,
        id: String? = null,
    ) = Format.Builder()
        .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
        .setLanguage(language)
        .setLabel(label)
        .setSelectionFlags(selectionFlags)
        .setId(id)
        .build()

    private fun group(format: Format, type: Int, selected: Boolean): Tracks.Group = Tracks.Group(
        TrackGroup(format),
        false,
        intArrayOf(C.FORMAT_HANDLED),
        booleanArrayOf(selected),
    ).also { check(it.type == type) }

    private fun tracks(vararg groups: Tracks.Group) = Tracks(groups.toList())

    @Test
    fun audioOptionsLabelLanguageAndChannelsWithGlobalGroupIds() {
        val tracks = tracks(
            group(textFormat(), C.TRACK_TYPE_TEXT, selected = false),
            group(audioFormat("en", 8), C.TRACK_TYPE_AUDIO, selected = true),
            group(audioFormat("es", 2), C.TRACK_TYPE_AUDIO, selected = false),
        )
        assertEquals(
            listOf(
                // The generic word, not "7.1 surround": Media3 carries only a channel count, and
                // the web's rule (ported as describeChannelLayout) names a layout solely when
                // ffprobe reported one — a bare 8 could be 7.1 or 5.1.2.
                TrackOption("1:0", "English · Surround", selected = true),
                TrackOption("2:0", "Spanish · Stereo", selected = false),
            ),
            audioTrackOptions(tracks),
        )
    }

    @Test
    fun audioOptionsFallBackToTrackNumbersAndSkipUnknownChannelCounts() {
        val tracks = tracks(
            group(audioFormat(language = null, channels = Format.NO_VALUE), C.TRACK_TYPE_AUDIO, selected = false),
        )
        assertEquals(
            listOf(TrackOption("0:0", "Track 1", selected = false)),
            audioTrackOptions(tracks),
        )
    }

    @Test
    fun subtitleOptionsJoinLanguageTitleAndFlags() {
        val tracks = tracks(
            group(
                textFormat("en", label = "SDH", selectionFlags = C.SELECTION_FLAG_FORCED or C.SELECTION_FLAG_DEFAULT),
                C.TRACK_TYPE_TEXT,
                selected = true,
            ),
            group(textFormat(language = null), C.TRACK_TYPE_TEXT, selected = false),
        )
        assertEquals(
            listOf(
                TrackOption("0:0", "English · SDH · Forced · Default", selected = true),
                TrackOption("1:0", "Track 2", selected = false),
            ),
            subtitleTrackOptions(tracks),
        )
    }

    @Test
    fun aTitleEqualToTheLanguageIsDropped() {
        val tracks = tracks(
            group(textFormat("en", label = "English"), C.TRACK_TYPE_TEXT, selected = false),
        )
        assertEquals("English", subtitleTrackOptions(tracks).single().label)
    }

    @Test
    fun typeIndexResolvesToTheNthGroupOfThatTypeByGlobalId() {
        val tracks = tracks(
            group(audioFormat("en"), C.TRACK_TYPE_AUDIO, selected = true),
            group(textFormat("en"), C.TRACK_TYPE_TEXT, selected = false),
            group(audioFormat("es"), C.TRACK_TYPE_AUDIO, selected = false),
        )
        assertEquals("0:0", trackOptionId(tracks, C.TRACK_TYPE_AUDIO, 0))
        assertEquals("2:0", trackOptionId(tracks, C.TRACK_TYPE_AUDIO, 1))
        assertEquals("1:0", trackOptionId(tracks, C.TRACK_TYPE_TEXT, 0))
        assertNull(trackOptionId(tracks, C.TRACK_TYPE_AUDIO, 2))
    }

    @Test
    fun optionIdsParseAndMalformedOnesDoNot() {
        assertEquals(3 to 1, parseTrackOptionId("3:1"))
        assertNull(parseTrackOptionId("nonsense"))
        assertNull(parseTrackOptionId("1:2:3"))
        assertNull(parseTrackOptionId("a:0"))
    }

    // --- HLS ---

    private fun wireAudio(label: String) = PlayableAudioTrack(label = label, codec = "eac3")

    @Test
    fun hlsAudioOptionsComeFromTheWireBecauseTheMuxCarriesOnlyOne() {
        // The session muxes one track, so ExoPlayer's Tracks cannot list the alternatives.
        val options = hlsAudioTrackOptions(
            listOf(wireAudio("English · Surround"), wireAudio("Spanish · Stereo")),
            selectedTypeIndex = 1,
        )
        assertEquals(
            listOf(
                TrackOption("audio:0", "English · Surround", selected = false),
                TrackOption("audio:1", "Spanish · Stereo", selected = true),
            ),
            options,
        )
    }

    @Test
    fun aVideoOnlyMovieOffersNoHlsAudioRowsAndMarksNothing() {
        assertEquals(emptyList<TrackOption>(), hlsAudioTrackOptions(emptyList(), selectedTypeIndex = null))
        assertEquals(
            listOf(TrackOption("audio:0", "English", selected = false)),
            hlsAudioTrackOptions(listOf(wireAudio("English")), selectedTypeIndex = null),
        )
    }

    @Test
    fun anHlsAudioIdIsDistinguishableFromAnExoPlayerSelectionId() {
        // The two id shapes are how the engine tells a session restart from a selection override.
        assertEquals(2, parseHlsAudioOptionId("audio:2"))
        assertNull(parseHlsAudioOptionId("1:0"))
        assertNull(parseHlsAudioOptionId("audio:x"))
        assertNull(parseHlsAudioOptionId("audio"))
    }

    @Test
    fun aSideloadedSubtitleIsFoundByItsStampedIdNotItsGroupPosition() {
        // The wire list is [0] image-based, [1] text, [2] text. Only text tracks are sideloaded,
        // so group position and wire ordinal disagree — matching by position would pick track 2
        // when the user asked for track 1.
        val tracks = tracks(
            group(audioFormat("en"), C.TRACK_TYPE_AUDIO, selected = true),
            group(textFormat("en", id = "sub:1"), C.TRACK_TYPE_TEXT, selected = false),
            group(textFormat("es", id = "sub:2"), C.TRACK_TYPE_TEXT, selected = false),
        )

        assertEquals("1:0", trackOptionIdForFormatId(tracks, C.TRACK_TYPE_TEXT, "sub:1"))
        assertEquals("2:0", trackOptionIdForFormatId(tracks, C.TRACK_TYPE_TEXT, "sub:2"))
        // The image-based track was never sideloaded, so nothing carries its ordinal.
        assertNull(trackOptionIdForFormatId(tracks, C.TRACK_TYPE_TEXT, "sub:0"))
        assertNull(trackOptionIdForFormatId(tracks, C.TRACK_TYPE_AUDIO, "sub:1"))
    }

    @Test
    fun aSubtitleSelectionMapsBackToItsWireOrdinal() {
        val tracks = tracks(
            group(audioFormat("en"), C.TRACK_TYPE_AUDIO, selected = true),
            group(textFormat("es", id = "sub:2"), C.TRACK_TYPE_TEXT, selected = true),
        )

        assertEquals("sub:2", formatIdForOptionId(tracks, "1:0"))
        assertNull(formatIdForOptionId(tracks, "1:5"))
        assertNull(formatIdForOptionId(tracks, "9:0"))
        assertNull(formatIdForOptionId(tracks, "nonsense"))
    }

    @Test
    fun aDirectSelectionKeepsItsTypeOrdinalForALaterHlsSession() {
        val tracks = tracks(
            group(audioFormat("en"), C.TRACK_TYPE_AUDIO, selected = true),
            group(textFormat("en"), C.TRACK_TYPE_TEXT, selected = false),
            group(audioFormat("es"), C.TRACK_TYPE_AUDIO, selected = false),
        )

        assertEquals(0, typeIndexForOptionId(tracks, C.TRACK_TYPE_AUDIO, "0:0"))
        assertEquals(1, typeIndexForOptionId(tracks, C.TRACK_TYPE_AUDIO, "2:0"))
        assertEquals(0, typeIndexForOptionId(tracks, C.TRACK_TYPE_TEXT, "1:0"))
        // A text group is not the Nth audio group.
        assertNull(typeIndexForOptionId(tracks, C.TRACK_TYPE_AUDIO, "1:0"))
    }
}
