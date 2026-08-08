package com.igloo.blindpenguincoder.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.navigation.IglooDestination
import com.igloo.blindpenguincoder.core.navigation.PrimaryIglooDestinations
import com.igloo.blindpenguincoder.core.ui.IglooBrandMark
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.data.model.AuthUser

@Composable
fun IglooApp(
    user: AuthUser,
    onSwitchProfile: () -> Unit,
    onLogout: () -> Unit,
) {
    var currentDestinationName by rememberSaveable { mutableStateOf(IglooDestination.Home.name) }
    val currentDestination = IglooDestination.valueOf(currentDestinationName)
    val contentStartRequester = remember { FocusRequester() }
    val navigationRequesters = remember {
        PrimaryIglooDestinations.associateWith { FocusRequester() }
    }

    IglooShell(
        user = user,
        currentDestination = currentDestination,
        contentStartRequester = contentStartRequester,
        navigationRequesters = navigationRequesters,
        onDestinationSelected = { currentDestinationName = it.name },
        onSwitchProfile = onSwitchProfile,
        onLogout = onLogout,
    )
}

@Composable
private fun IglooShell(
    user: AuthUser,
    currentDestination: IglooDestination,
    contentStartRequester: FocusRequester,
    navigationRequesters: Map<IglooDestination, FocusRequester>,
    onDestinationSelected: (IglooDestination) -> Unit,
    onSwitchProfile: () -> Unit,
    onLogout: () -> Unit,
) {
    val colors = IglooTheme.colors
    val layout = IglooTheme.layout

    // Backgrounds bleed to the physical edge; only chrome and text are inset for overscan.
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        NavigationSpine(
            user = user,
            currentDestination = currentDestination,
            contentStartRequester = contentStartRequester,
            navigationRequesters = navigationRequesters,
            onDestinationSelected = onDestinationSelected,
            onSwitchProfile = onSwitchProfile,
            onLogout = onLogout,
            modifier = Modifier
                .fillMaxHeight()
                .width(layout.navSpineWidth),
        )
        HomeContent(
            currentDestination = currentDestination,
            contentStartRequester = contentStartRequester,
            navigationRequesters = navigationRequesters,
            onDestinationSelected = onDestinationSelected,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = IglooTheme.spacing.xl,
                    end = layout.safeAreaHorizontal,
                    top = layout.safeAreaVertical,
                    bottom = layout.safeAreaVertical,
                ),
        )
    }
}

@Composable
private fun NavigationSpine(
    user: AuthUser,
    currentDestination: IglooDestination,
    contentStartRequester: FocusRequester,
    navigationRequesters: Map<IglooDestination, FocusRequester>,
    onDestinationSelected: (IglooDestination) -> Unit,
    onSwitchProfile: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors

    Column(
        modifier = modifier
            .background(colors.sidebar)
            .padding(
                start = IglooTheme.layout.safeAreaHorizontal,
                end = IglooTheme.spacing.lg,
                top = IglooTheme.layout.safeAreaVertical,
                bottom = IglooTheme.layout.safeAreaVertical,
            ),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        ) {
            IglooBrandMark()
            Column {
                IglooText(
                    text = "Igloo",
                    style = IglooTheme.typography.titleMedium,
                    color = colors.foreground,
                )
                IglooText(
                    text = "TV",
                    style = IglooTheme.typography.label,
                    color = colors.mutedForeground,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            PrimaryIglooDestinations.forEach { destination ->
                NavigationItem(
                    destination = destination,
                    selected = destination == currentDestination,
                    focusRequester = navigationRequesters.getValue(destination),
                    rightFocusRequester = contentStartRequester,
                    onClick = { onDestinationSelected(destination) },
                )
            }
        }

        IglooText(
            text = user.name,
            style = IglooTheme.typography.label,
            color = colors.mutedForeground,
            maxLines = 1,
        )
        // Handing the TV to someone else keeps this profile paired; signing out does not.
        AccountActionItem(
            label = "Switch profile",
            dotColor = colors.ring,
            onClick = onSwitchProfile,
        )
        AccountActionItem(
            label = "Sign out",
            dotColor = colors.destructive,
            onClick = onLogout,
        )
    }
}

@Composable
private fun AccountActionItem(
    label: String,
    dotColor: Color,
    onClick: () -> Unit,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = IglooTheme.sizes.navItemHeight)
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.lg,
                fill = if (focused) colors.card.copy(alpha = 0.72f) else Color.Transparent,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick(label = label) {
                    onClick()
                    true
                }
            }
            .padding(horizontal = IglooTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(IglooTheme.sizes.dot)
                .clip(CircleShape)
                .background(if (focused) dotColor else colors.border),
        )
        IglooText(
            text = label,
            style = IglooTheme.typography.bodyLarge,
            color = colors.foreground,
            maxLines = 1,
        )
    }
}

@Composable
private fun NavigationItem(
    destination: IglooDestination,
    selected: Boolean,
    focusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val background = when {
        selected -> colors.primary.copy(alpha = 0.18f)
        focused -> colors.card.copy(alpha = 0.72f)
        else -> Color.Transparent
    }
    val foreground = if (selected) colors.sidebarPrimary else colors.foreground
    val description = if (selected) "${destination.label}, selected" else destination.label

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = IglooTheme.sizes.navItemHeight)
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.lg,
                fill = background,
            )
            .focusRequester(focusRequester)
            .focusProperties {
                right = rightFocusRequester
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = "Open ${destination.label}") {
                    onClick()
                    true
                }
            }
            .padding(horizontal = IglooTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(IglooTheme.sizes.dot)
                .clip(CircleShape)
                .background(if (selected || focused) colors.primary else colors.border),
        )
        IglooText(
            text = destination.label,
            style = IglooTheme.typography.bodyLarge,
            color = foreground,
            maxLines = 1,
        )
    }
}

@Composable
private fun HomeContent(
    currentDestination: IglooDestination,
    contentStartRequester: FocusRequester,
    navigationRequesters: Map<IglooDestination, FocusRequester>,
    onDestinationSelected: (IglooDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
                IglooText(
                    text = currentDestination.label,
                    style = IglooTheme.typography.titleLarge,
                    color = colors.foreground,
                )
                IglooText(
                    text = currentDestination.supportingText,
                    style = IglooTheme.typography.bodyMedium,
                    color = colors.mutedForeground,
                )
            }
            StatusBadge()
        }

        HeroPanel()

        IglooText(
            text = "Start here",
            style = IglooTheme.typography.titleMedium,
            color = colors.foreground,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        ) {
            FeatureCard(
                title = "Movies",
                body = "API contract loaded. Poster rails and playback will plug into this shell.",
                modifier = Modifier.weight(1f),
                focusRequester = contentStartRequester,
                leftFocusRequester = navigationRequesters.getValue(currentDestination),
                onClick = { onDestinationSelected(IglooDestination.Movies) },
            )
            FeatureCard(
                title = "Music",
                body = "Coil, Media3, Ktor, and DataStore are ready for feature work.",
                modifier = Modifier.weight(1f),
                onClick = { onDestinationSelected(IglooDestination.Music) },
            )
            FeatureCard(
                title = "Settings",
                body = "Server setup and theme persistence can be added without changing the app frame.",
                modifier = Modifier.weight(1f),
                onClick = { onDestinationSelected(IglooDestination.Settings) },
            )
        }
    }
}

@Composable
private fun StatusBadge() {
    val colors = IglooTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(IglooTheme.radius.pill))
            .background(colors.aurora.copy(alpha = 0.16f))
            .border(
                width = IglooTheme.focus.restWidth,
                color = colors.aurora.copy(alpha = 0.48f),
                shape = RoundedCornerShape(IglooTheme.radius.pill),
            )
            .padding(horizontal = IglooTheme.spacing.md, vertical = IglooTheme.spacing.sm),
    ) {
        IglooText(
            text = "Base app",
            style = IglooTheme.typography.label,
            color = colors.aurora,
        )
    }
}

@Composable
private fun HeroPanel() {
    val colors = IglooTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(IglooTheme.radius.xl))
            .background(colors.card)
            .border(
                width = IglooTheme.focus.restWidth,
                color = colors.border,
                shape = RoundedCornerShape(IglooTheme.radius.xl),
            )
            .padding(IglooTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        IglooText(
            text = "Your library, ready",
            style = IglooTheme.typography.titleLarge,
            color = colors.cardForeground,
        )
        IglooText(
            text = "This Android TV foundation is remote-first, dark by default, and ready for the Igloo backend contract.",
            style = IglooTheme.typography.bodyLarge,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun FeatureCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .heightIn(min = 178.dp.scaled())
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.xl,
                fill = if (focused) colors.card.copy(alpha = 0.96f) else colors.muted,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (leftFocusRequester != null) {
                    Modifier.focusProperties {
                        left = leftFocusRequester
                    }
                } else {
                    Modifier
                },
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                contentDescription = "$title. $body"
                role = Role.Button
                onClick(label = "Open $title") {
                    onClick()
                    true
                }
            }
            .padding(IglooTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        IglooText(
            text = title,
            style = IglooTheme.typography.titleMedium,
            color = colors.foreground,
            maxLines = 1,
        )
        IglooText(
            text = body,
            style = IglooTheme.typography.bodyMedium,
            color = colors.mutedForeground,
            maxLines = 4,
        )
    }
}

