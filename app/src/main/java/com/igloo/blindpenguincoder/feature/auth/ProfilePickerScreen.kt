package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooAvatar
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooNotice
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.iglooSurface
import com.igloo.blindpenguincoder.data.model.ProfileSummary
import com.igloo.blindpenguincoder.images.avatarImageUrl

/**
 * "Who's watching?" — rendered entirely from stored profiles, so it appears instantly
 * and works with the server unreachable.
 */
@Composable
fun ProfilePickerScreen(
    viewModel: ProfilePickerViewModel,
    state: AppAuthState.ChooseProfile,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tileRequesters = remember(state.profiles) {
        state.profiles.associate { it.userId to FocusRequester() }
    }
    val addProfileFocus = remember { FocusRequester() }
    val changeServerFocus = remember { FocusRequester() }
    // Coming back up from the footer lands on whatever the user left, including the
    // "Add profile" tile — not just the last profile they happened to pass through.
    var lastFocusedInRow by remember(state.profiles) { mutableStateOf<FocusRequester?>(null) }

    AuthSurface(
        title = "Who's watching?",
        subtitle = state.serverAddress.origin,
        cardWidth = IglooTheme.layout.authCardWideWidth,
    ) {
        state.restoreError?.let { error ->
            IglooInlineError(
                message = error.toDisplayMessage(),
                actionText = "Try again",
                actionSemanticLabel = "Try connecting again",
                onAction = viewModel::retryRestore,
            )
        }
        uiState.error?.let { message ->
            IglooInlineError(message = message)
        }
        state.notice?.let { notice ->
            IglooNotice(text = notice)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
        ) {
            state.profiles.forEachIndexed { index, profile ->
                val tileFocus = tileRequesters.getValue(profile.userId)
                ProfileTile(
                    profile = profile,
                    serverOrigin = state.serverAddress.origin,
                    signingIn = uiState.signingInUserId == profile.userId,
                    enabled = uiState.signingInUserId == null,
                    onClick = { viewModel.select(profile) },
                    modifier = Modifier
                        .focusRequester(tileFocus)
                        .onFocusChanged { if (it.isFocused) lastFocusedInRow = tileFocus }
                        .focusProperties {
                            // Left and right fall to the geometric search, which already
                            // walks the row; only the far edge is pinned so it cannot wrap.
                            if (index == 0) left = FocusRequester.Cancel
                            down = changeServerFocus
                        },
                )
            }
            AddProfileTile(
                enabled = uiState.signingInUserId == null,
                onClick = { viewModel.addProfile(state.profiles.size) },
                modifier = Modifier
                    .focusRequester(addProfileFocus)
                    .onFocusChanged { if (it.isFocused) lastFocusedInRow = addProfileFocus }
                    .focusProperties {
                        right = FocusRequester.Cancel
                        down = changeServerFocus
                    },
            )
        }

        IglooButton(
            text = "Change server",
            onClick = viewModel::changeServer,
            variant = IglooButtonVariant.Ghost,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(changeServerFocus)
                .focusProperties {
                    up = lastFocusedInRow ?: addProfileFocus
                },
            semanticLabel = "Change server address",
        )
    }

    LaunchedEffect(state.profiles, state.initialFocusUserId) {
        val target = state.initialFocusUserId?.let { tileRequesters[it] }
            ?: state.profiles.firstOrNull()?.let { tileRequesters.getValue(it.userId) }
            ?: addProfileFocus
        target.requestFocus()
    }

    // Signing in disables every tile, so focus is cleared for the duration. A failure leaves the
    // picker on screen, and without this it would have nothing focused and no way back in.
    LaunchedEffect(uiState.completedAttempts) {
        val attempted = uiState.lastAttemptedUserId ?: return@LaunchedEffect
        tileRequesters[attempted]?.requestFocus()
    }
}

@Composable
private fun ProfileTile(
    profile: ProfileSummary,
    serverOrigin: String,
    signingIn: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (profile.hasPin) "${profile.name}, PIN required" else profile.name
    Tile(
        label = if (signingIn) "Signing in as ${profile.name}" else label,
        actionLabel = "Sign in as ${profile.name}",
        caption = profile.name,
        badge = if (profile.hasPin) "PIN" else null,
        announce = signingIn,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier.testTag("profile_tile_${profile.userId}"),
    ) {
        IglooAvatar(
            name = profile.name,
            avatarUrl = avatarImageUrl(serverOrigin, profile.avatarUrl),
            size = AVATAR_SIZE.scaled(),
        )
    }
}

@Composable
private fun AddProfileTile(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    Tile(
        label = "Add profile",
        actionLabel = "Add another profile",
        caption = "Add profile",
        badge = null,
        announce = false,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier.testTag("add_profile_tile"),
    ) {
        Box(
            modifier = Modifier
                .size(AVATAR_SIZE.scaled())
                .clip(CircleShape)
                .background(colors.muted),
            contentAlignment = Alignment.Center,
        ) {
            IglooText(
                text = "+",
                style = IglooTheme.typography.titleLarge,
                color = colors.mutedForeground,
            )
        }
    }
}

/** Shared frame: a circular target, its caption, and one optional badge. */
@Composable
private fun Tile(
    label: String,
    actionLabel: String,
    caption: String,
    badge: String?,
    announce: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatar: @Composable () -> Unit,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    // Cross-fades on the same micro driver focusRing uses for its own fill, so the tile's
    // backing and the avatar's ring arrive together instead of one snapping ahead of the other.
    val fill by animateColorAsState(
        targetValue = if (focused) colors.card.copy(alpha = 0.72f) else Color.Transparent,
        animationSpec = iglooTween(IglooMotion.MICRO_MS),
        label = "tileFill",
    )

    Column(
        modifier = modifier
            .width(TILE_WIDTH.scaled())
            // Rounds without clipping: nothing here needs masking (the avatar and the add-tile
            // clip themselves), and a clip would cut the avatar's focus glow at this boundary.
            .background(color = fill, shape = RoundedCornerShape(IglooTheme.radius.lg))
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                if (announce) liveRegion = LiveRegionMode.Polite
                if (!enabled) disabled()
                onClick(label = actionLabel) {
                    if (enabled) onClick()
                    enabled
                }
            }
            .padding(IglooTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier.focusRing(focused = focused, radius = IglooTheme.radius.pill),
        ) {
            avatar()
        }
        IglooText(
            text = caption,
            style = IglooTheme.typography.bodyLarge,
            color = colors.cardForeground,
            maxLines = 1,
        )
        if (badge != null) {
            IglooText(
                text = badge,
                style = IglooTheme.typography.label,
                color = colors.aurora,
                modifier = Modifier
                    .iglooSurface(
                        radius = IglooTheme.radius.pill,
                        fill = colors.aurora.copy(alpha = 0.16f),
                        border = colors.aurora.copy(alpha = 0.48f),
                    )
                    .padding(
                        horizontal = IglooTheme.spacing.sm,
                        vertical = IglooTheme.spacing.xs,
                    ),
            )
        }
    }
}

private val AVATAR_SIZE = 96.dp
private val TILE_WIDTH = 160.dp
