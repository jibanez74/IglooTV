package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooText

internal const val NO_ROOMS_HEADING = "No watch rooms right now"
internal const val NO_ROOMS_EXPLANATION = "Rooms you host or are invited to show up here."
internal const val NO_ROOMS_HINT = "Start one from a movie on the web with Watch Together."

/**
 * The empty scene as the node that speaks in its place says it. No closing full stop: TalkBack
 * joins a state to its label with one of its own.
 */
internal val NO_ROOMS_SPOKEN =
    "$NO_ROOMS_HEADING. $NO_ROOMS_EXPLANATION ${NO_ROOMS_HINT.removeSuffix(".")}"

/**
 * The Watch Rooms pane with no rooms (docs/design-system.md sections 10 and 11.10): a screen
 * over a row of empty seats, the heading, and what would put a room here.
 *
 * Nothing in it takes focus and none of it reaches TalkBack. The pane's anchor in this state is
 * the header's Refresh, the one thing there is to do, and that button speaks [NO_ROOMS_SPOKEN]
 * as its state — a d-pad user's screen reader follows focus and would never visit text that has
 * none. Hidden rather than cleared, so the text stays in the tree the tests read.
 */
@Composable
internal fun WatchRoomsEmptyScene(contentInset: PaddingValues) {
    val colors = IglooTheme.colors
    val hidden = Modifier.semantics { hideFromAccessibility() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentInset)
            .padding(bottom = IglooTheme.layout.safeAreaVertical)
            .testTag("watch_rooms_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm, Alignment.CenterVertically),
    ) {
        EmptyRoomIllustration(Modifier.padding(bottom = IglooTheme.spacing.lg))
        IglooText(
            text = NO_ROOMS_HEADING,
            style = IglooTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
            color = colors.foreground,
            modifier = hidden,
        )
        for (line in listOf(NO_ROOMS_EXPLANATION, NO_ROOMS_HINT)) {
            IglooText(
                text = line,
                style = IglooTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                color = colors.mutedForeground,
                modifier = hidden,
            )
        }
    }
}

/**
 * A lit screen with the play mark on it and nobody in the five seats in front. Flat token
 * fills in the icon set's manner, and still: nothing in the product loops (section 7.2).
 */
@Composable
private fun EmptyRoomIllustration(modifier: Modifier = Modifier) {
    val colors = IglooTheme.colors
    Canvas(modifier.size(width = 300.dp.scaled(), height = 190.dp.scaled())) {
        val width = size.width
        val height = size.height
        val hairline = 1.5.dp.toPx()

        val screen = Rect(
            offset = Offset(width * 0.14f, 0f),
            size = Size(width * 0.72f, height * 0.60f),
        )
        // The screen's light, spilling a little past its frame.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.primary.copy(alpha = SCREEN_GLOW_ALPHA), Color.Transparent),
                center = screen.center,
                radius = width * 0.5f,
            ),
            radius = width * 0.5f,
            center = screen.center,
        )
        val screenCorner = CornerRadius(height * 0.05f)
        drawRoundRect(colors.card, screen.topLeft, screen.size, screenCorner)
        drawRoundRect(colors.border, screen.topLeft, screen.size, screenCorner, Stroke(hairline))

        val mark = screen.height * 0.17f
        drawPath(
            path = Path().apply {
                moveTo(screen.center.x - mark * 0.7f, screen.center.y - mark)
                lineTo(screen.center.x + mark, screen.center.y)
                lineTo(screen.center.x - mark * 0.7f, screen.center.y + mark)
                close()
            },
            color = colors.primary,
        )

        // Seat backs: rounded on top, cut off by the scene's bottom edge.
        val seatWidth = width * 0.13f
        val seatGap = width * 0.045f
        val seatTop = height * 0.74f
        val rowWidth = seatWidth * SEATS + seatGap * (SEATS - 1)
        val shoulder = CornerRadius(seatWidth * 0.38f)
        val foot = CornerRadius(seatWidth * 0.08f)
        repeat(SEATS) { seat ->
            val left = (width - rowWidth) / 2f + seat * (seatWidth + seatGap)
            val back = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(left, seatTop, left + seatWidth, height),
                        topLeft = shoulder,
                        topRight = shoulder,
                        bottomRight = foot,
                        bottomLeft = foot,
                    ),
                )
            }
            drawPath(back, colors.muted)
            drawPath(back, colors.border, style = Stroke(hairline))
        }
    }
}

private const val SEATS = 5
private const val SCREEN_GLOW_ALPHA = 0.18f
