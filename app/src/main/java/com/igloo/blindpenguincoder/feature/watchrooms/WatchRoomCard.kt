package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.ArtworkOrGlyph
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.SkeletonStub
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.mediaCardInteraction

/**
 * One watch room (docs/design-system.md section 11.10): the movie's poster down the left edge,
 * then its title, who hosts the room, and who is in it — an avatar stack over the count.
 *
 * The whole card is one focus target and one node speaking [WatchRoomUi.description], which
 * carries everything drawn, and a press opens the room's screen ([onClick]), which TalkBack
 * offers as "Join room".
 */
@Composable
internal fun WatchRoomCard(room: WatchRoomUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    RoomCardFrame(
        focused = focused,
        modifier = modifier.mediaCardInteraction(
            onFocusChanged = { focused = it },
            onClick = onClick,
            description = room.description,
            actionLabel = "Join room",
        ),
        poster = { ArtworkOrGlyph(room.posterUrl, IglooIcons.Movies) },
        heading = {
            IglooText(
                text = room.title,
                style = IglooTheme.typography.bodyLarge,
                color = colors.cardForeground,
                maxLines = 2,
            )
            IglooText(
                text = room.hostLine,
                style = IglooTheme.typography.label,
                color = colors.mutedForeground,
                maxLines = 1,
            )
        },
        footing = {
            MemberStack(
                members = room.members,
                overflowCount = room.overflowCount,
                avatarSize = IglooTheme.icons.lg,
                rim = IglooTheme.colors.card,
            )
            IglooText(
                text = room.memberLine,
                style = IglooTheme.typography.label,
                color = colors.mutedForeground,
                maxLines = 1,
            )
        },
    )
}

/** The room card's loading placeholder, on the card's own geometry. */
@Composable
internal fun WatchRoomCardSkeleton(focused: Boolean, modifier: Modifier = Modifier) {
    RoomCardFrame(
        focused = focused,
        modifier = modifier,
        poster = {},
        heading = {
            SkeletonStub(height = 18.dp, modifier = Modifier.fillMaxWidth(0.7f))
            SkeletonStub(height = 12.dp, modifier = Modifier.fillMaxWidth(0.45f))
        },
        footing = { SkeletonStub(height = 12.dp, modifier = Modifier.fillMaxWidth(0.3f)) },
    )
}

/**
 * The geometry the card and its skeleton share: a `card` surface, the poster's box on the muted
 * fill down its left edge, and beside it the text column — [heading] at its top, [footing] at
 * its bottom.
 *
 * The poster's 2:3 height is the card's **minimum**, not its height (section 2.6): it holds a
 * two-line title at the default font scale, so every card in the grid is the same size there,
 * and at a larger system font scale the text makes the card taller instead of being clipped. The
 * text column is what sizes the card; the poster's box only matches whatever that comes to.
 */
@Composable
private fun RoomCardFrame(
    focused: Boolean,
    modifier: Modifier,
    poster: @Composable () -> Unit,
    heading: @Composable ColumnScope.() -> Unit,
    footing: @Composable ColumnScope.() -> Unit,
) {
    val posterWidth = IglooTheme.layout.roomPosterWidth
    val lineGap = Arrangement.spacedBy(IglooTheme.spacing.xs)
    Box(
        modifier = modifier
            .fillMaxWidth()
            // focusRing owns the clip, so the poster's box reaches the card's rounded corners
            // without clipping anything itself.
            .focusRing(focused = focused, radius = IglooTheme.radius.lg, fill = IglooTheme.colors.card),
    ) {
        Box(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .width(posterWidth)
                    .fillMaxHeight()
                    .background(IglooTheme.colors.muted),
                contentAlignment = Alignment.Center,
            ) {
                poster()
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = posterWidth / IglooTheme.layout.posterAspect)
                .padding(start = posterWidth)
                .padding(IglooTheme.spacing.md),
            // Whatever the minimum height leaves over goes between the two groups; a card that
            // has outgrown it keeps one line gap there instead.
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = lineGap, content = heading)
            Column(
                modifier = Modifier.padding(top = IglooTheme.spacing.xs),
                verticalArrangement = lineGap,
                content = footing,
            )
        }
    }
}
