package com.igloo.blindpenguincoder.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.core.navigation.IglooDestination
import com.igloo.blindpenguincoder.core.navigation.PrimaryIglooDestinations
import com.igloo.blindpenguincoder.core.ui.IglooAvatar
import com.igloo.blindpenguincoder.core.ui.IglooBrandMark
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.images.avatarImageUrl

/**
 * The collapsible navigation rail. The caller owns the expansion state (it is a pure
 * function of d-pad focus being inside the rail) and animates the container width; this
 * composable only fades its labels between the two states. Every row is composed and
 * focusable in both states — collapse hides text, never targets — so TalkBack and the
 * d-pad see the same tree whether the rail is open or shut.
 */
@Composable
fun NavigationRail(
    user: AuthUser,
    serverOrigin: String,
    expanded: Boolean,
    currentDestination: IglooDestination,
    contentStartRequester: FocusRequester,
    /** Hoisted by the caller, which hands focus back here when the sign-out dialog closes. */
    signOutRequester: FocusRequester,
    navigationRequesters: Map<IglooDestination, FocusRequester>,
    onDestinationSelected: (IglooDestination) -> Unit,
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    // Labels fade in paint only (graphicsLayer, draw phase) — the rows themselves never
    // enter or leave composition, per the motion rules in design-system.md section 7.2.
    val labelAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.MICRO_MS),
        label = "railLabelAlpha",
    )
    val labelFade = Modifier.graphicsLayer { alpha = labelAlpha }
    // The content pane's first card sits geometrically closer below the last destination row
    // than the footer does, so the column -> footer boundary is hand-wired like the rail ->
    // content one; geometric search would collapse the rail mid-walk.
    val switchProfileFocus = remember { FocusRequester() }
    val lastDestination = PrimaryIglooDestinations.last()

    Column(
        modifier = modifier
            .semantics { isTraversalGroup = true }
            // The pane bleeds under the rail now (section 8.1), so the resting ground is a scrim
            // rather than a fill: art fades out beneath the icon strip instead of being cut off
            // by a solid column, which against `background` read as a black bar down the edge of
            // the screen. Expanding restores the opaque fill — an expanded rail is chrome over a
            // scrimmed pane, not a gradient over art — and it rides `labelAlpha` so the fill and
            // the labels can never disagree about which state the rail is in.
            .drawBehind {
                drawRect(
                    Brush.horizontalGradient(
                        // Not fully opaque even at the panel edge: at 1.0 the leftmost column of
                        // the screen is a flat wall of `sidebar`, which is the bar this whole
                        // change is removing. The rows carry their own surface fills, so the
                        // scrim owes legibility nothing — it only has to seat them.
                        0f to colors.sidebar.copy(alpha = 0.90f),
                        1f to colors.sidebar.copy(alpha = 0f),
                    ),
                )
                if (labelAlpha > 0f) drawRect(colors.sidebar, alpha = labelAlpha)
            }
            .padding(
                start = IglooTheme.layout.safeAreaHorizontal,
                end = IglooTheme.spacing.lg,
                top = IglooTheme.layout.safeAreaVertical,
                bottom = IglooTheme.layout.safeAreaVertical,
            ),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        // Decorative in both states: the lockup repeats the splash brand, and collapsed its
        // text is invisible (alpha 0) yet would still be announced without the empty subtree.
        Row(
            modifier = Modifier.clearAndSetSemantics { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        ) {
            IglooBrandMark()
            Column(modifier = labelFade) {
                RailLabel(text = "Igloo", style = IglooTheme.typography.titleMedium, color = colors.foreground)
                RailLabel(text = "TV", style = IglooTheme.typography.label, color = colors.mutedForeground)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            PrimaryIglooDestinations.forEach { destination ->
                val selected = destination == currentDestination
                RailRow(
                    icon = destination.icon,
                    label = destination.label,
                    selected = selected,
                    actionLabel = "Open ${destination.label}",
                    labelColor = if (selected) colors.sidebarPrimary else colors.foreground,
                    iconTint = { _ -> if (selected) colors.sidebarPrimary else colors.foreground },
                    fill = { focused ->
                        when {
                            selected -> colors.primary.copy(alpha = 0.18f)
                            focused -> colors.card.copy(alpha = 0.72f)
                            else -> Color.Transparent
                        }
                    },
                    labelFade = labelFade,
                    onClick = { onDestinationSelected(destination) },
                    modifier = Modifier
                        .focusRequester(navigationRequesters.getValue(destination))
                        .focusProperties {
                            right = contentStartRequester
                            if (destination == lastDestination) down = switchProfileFocus
                        },
                )
            }
        }

        // One merged node that is true in both states — the avatar stays visible collapsed,
        // while the bare name text would otherwise be announced at alpha 0.
        Row(
            modifier = Modifier
                .padding(horizontal = IglooTheme.spacing.md)
                .clearAndSetSemantics { contentDescription = "Signed in as ${user.name}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        ) {
            IglooAvatar(
                name = user.name,
                avatarUrl = avatarImageUrl(serverOrigin, user.avatar),
                size = IglooTheme.icons.lg,
                textStyle = IglooTheme.typography.label,
            )
            RailLabel(
                text = user.name,
                style = IglooTheme.typography.label,
                color = colors.mutedForeground,
                modifier = labelFade,
            )
        }
        // Handing the TV to someone else keeps this profile paired; signing out does not.
        RailRow(
            icon = IglooIcons.SwitchProfile,
            label = "Switch profile",
            actionLabel = "Switch profile",
            labelColor = colors.foreground,
            iconTint = { focused -> if (focused) colors.ring else colors.mutedForeground },
            fill = { focused -> if (focused) colors.card.copy(alpha = 0.72f) else Color.Transparent },
            labelFade = labelFade,
            onClick = onSwitchProfile,
            modifier = Modifier
                .focusRequester(switchProfileFocus)
                .focusProperties {
                    right = contentStartRequester
                    up = navigationRequesters.getValue(lastDestination)
                },
        )
        // The label says only "Sign out"; the dialog announces itself through its pane title, so
        // an ", opens a confirmation" suffix would be the announcement section 11.2 forbids.
        RailRow(
            icon = IglooIcons.SignOut,
            label = "Sign out",
            actionLabel = "Sign out",
            labelColor = colors.foreground,
            iconTint = { focused -> if (focused) colors.destructive else colors.mutedForeground },
            fill = { focused -> if (focused) colors.card.copy(alpha = 0.72f) else Color.Transparent },
            labelFade = labelFade,
            onClick = onSignOut,
            modifier = Modifier
                .focusRequester(signOutRequester)
                .focusProperties { right = contentStartRequester },
        )
    }
}

/**
 * One row recipe for every rail target, navigation and account actions alike. The caller's
 * [modifier] slots between the focus ring and the focus observers so per-row requester
 * wiring keeps the order the focus treatment depends on.
 */
@Composable
private fun RailRow(
    icon: ImageVector,
    label: String,
    actionLabel: String,
    labelColor: Color,
    iconTint: (focused: Boolean) -> Color,
    fill: (focused: Boolean) -> Color,
    labelFade: Modifier,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = IglooTheme.sizes.navItemHeight)
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.lg,
                fill = fill(focused),
            )
            .then(modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                contentDescription = label
                // Only ever set, never cleared: selected = false would make TalkBack append
                // "not selected" to every other row.
                if (selected) this.selected = true
                role = Role.Button
                onClick(label = actionLabel) {
                    onClick()
                    true
                }
            }
            .padding(horizontal = IglooTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(iconTint(focused)),
            modifier = Modifier.size(IglooTheme.icons.md),
        )
        RailLabel(
            text = label,
            style = IglooTheme.typography.label,
            color = labelColor,
            modifier = labelFade,
        )
    }
}

/** Rail text clips instead of wrapping or ellipsizing while the width animates. */
@Composable
private fun RailLabel(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    IglooText(
        text = text,
        style = style,
        color = color,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        softWrap = false,
    )
}

private val IglooDestination.icon: ImageVector
    get() = when (this) {
        IglooDestination.Search -> IglooIcons.Search
        IglooDestination.Home -> IglooIcons.Home
        IglooDestination.Movies -> IglooIcons.Movies
        IglooDestination.TvShows -> IglooIcons.TvShows
        IglooDestination.Music -> IglooIcons.Music
        IglooDestination.Photos -> IglooIcons.Photos
        IglooDestination.Settings -> IglooIcons.Settings
    }
