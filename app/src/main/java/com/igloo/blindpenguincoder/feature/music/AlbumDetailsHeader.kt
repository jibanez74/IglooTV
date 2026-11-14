package com.igloo.blindpenguincoder.feature.music

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_SECONDARY
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.feature.shared.HeroGenresLine
import com.igloo.blindpenguincoder.feature.shared.HeroReadingStop
import com.igloo.blindpenguincoder.feature.shared.HeroTitle

/**
 * The hero's prose: title, artist, metadata chips and genres — one reading stop
 * under a screen reader ([HeroReadingStop]).
 */
@Composable
internal fun AlbumHeroInfo(
    album: AlbumDetailsUi,
    overMedia: Boolean,
    readingStop: Boolean,
    requester: FocusRequester,
    downRequester: FocusRequester,
) {
    val colors = IglooTheme.colors
    HeroReadingStop(
        enabled = readingStop,
        tag = "album_hero_info",
        overMedia = overMedia,
        requester = requester,
        downRequester = downRequester,
        description = album.heroInfoDescription,
    ) {
        HeroTitle(album.title, overMedia)
        if (album.artistName != null) {
            IglooText(
                text = album.artistName,
                style = IglooTheme.typography.bodyLarge.overMedia(overMedia),
                color = if (overMedia) OVER_MEDIA_SECONDARY else colors.mutedForeground,
                maxLines = 1,
            )
        }
        MusicMetadataChips(
            parts = listOfNotNull(album.releaseDateText, album.trackCountText, album.totalDurationText),
            overMedia = overMedia,
        )
        if (album.genresLine != null) HeroGenresLine(album.genresLine, overMedia)
    }
}
