package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled

/**
 * Watch progress shown on a poster card. The description joins the card's one TalkBack
 * announcement, so a bar can never render without being announced (design system section 12).
 */
data class PosterCardProgress(
    val fraction: Float,
    val description: String,
)

/**
 * A 2:3 poster with its title and one optional line of context below. The poster carries the
 * focus treatment — ring, scale, and glow stay on the artwork while the text keeps still —
 * but the whole card is one focus target and one TalkBack node.
 *
 * A null or failed image falls back to the film glyph on the muted fill; the text below is
 * unchanged, so the card loses nothing but the artwork. An optional progress bar sits on the
 * poster's bottom edge and is announced through [PosterCardProgress.description].
 *
 * A null [onClick] still renders a focusable card — the rails' focus model needs every card to
 * be a landing site — but it drops the button role and the "Open …" action, because announcing
 * an action that does nothing is worse for a screen reader than announcing none.
 */
@Composable
fun IglooPosterCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    progress: PosterCardProgress? = null,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    var imageFailed by remember(imageUrl) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .width(IglooTheme.layout.posterWidth)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier.focusable()
                },
            )
            .clearAndSetSemantics {
                contentDescription =
                    listOfNotNull(title, subtitle, progress?.description).joinToString(", ")
                if (onClick != null) {
                    role = Role.Button
                    onClick(label = "Open $title") {
                        onClick()
                        true
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(IglooTheme.layout.posterAspect)
                // focusRing owns the clip, so the muted fill doubles as the image backdrop and
                // nothing here may clip over it.
                .focusRing(
                    focused = focused,
                    radius = IglooTheme.radius.lg,
                    fill = colors.muted,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (imageUrl != null && !imageFailed) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Error) imageFailed = true
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Image(
                    imageVector = IglooIcons.Movies,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colors.mutedForeground),
                    modifier = Modifier.size(IglooTheme.icons.lg),
                )
            }
            if (progress != null) {
                // Over-media literal per design system section 3.2; focusRing clips the corners.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp.scaled())
                        .background(Color.Black.copy(alpha = 0.40f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.fraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(colors.primary),
                    )
                }
            }
        }
        IglooText(
            text = title,
            style = IglooTheme.typography.bodyMedium,
            color = colors.foreground,
            maxLines = 2,
        )
        if (subtitle != null) {
            IglooText(
                text = subtitle,
                style = IglooTheme.typography.label,
                color = colors.mutedForeground,
                maxLines = 1,
            )
        }
    }
}
