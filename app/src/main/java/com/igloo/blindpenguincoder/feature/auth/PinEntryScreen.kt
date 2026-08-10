package com.igloo.blindpenguincoder.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
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
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.iglooSurface

/**
 * PIN gate for a protected profile. TV remotes have no number keys, so the digits come
 * from an on-screen keypad; hardware digits are accepted too where they exist. The
 * entered digits never appear on screen or in the accessibility tree.
 */
@Composable
fun PinEntryScreen(
    viewModel: PinEntryViewModel,
    state: AppAuthState.NeedsPin,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val keyFocus = remember { List(KEYPAD.size) { FocusRequester() } }
    val backFocus = remember { FocusRequester() }

    // This gate sits *below* the picker rather than at the top of the app, so Back belongs to the
    // screen: without this it reaches the Activity and closes Igloo, stranding whoever was simply
    // handed the wrong profile. Left enabled while verifying — a request in flight is exactly when
    // someone wants out, and the profile stays paired either way.
    BackHandler(onBack = viewModel::back)
    // Coming back up from the footer, and recovering from a rejected PIN, both land on the key
    // the user actually left rather than the top-left corner of the pad.
    var lastFocusedKey by remember { mutableStateOf<FocusRequester?>(null) }

    AuthSurface(
        title = "Enter ${state.profile.name}'s PIN",
        subtitle = state.serverAddress.origin,
    ) {
        uiState.error?.let { message ->
            IglooInlineError(message = message)
        }

        PinIndicator(
            enteredCount = uiState.enteredCount,
            isVerifying = uiState.isVerifying,
        )

        Keypad(
            enabled = !uiState.isVerifying,
            onDigit = viewModel::append,
            onDelete = viewModel::delete,
            keyFocus = keyFocus,
            onKeyFocused = { lastFocusedKey = it },
            downBoundary = backFocus,
        )

        IglooButton(
            text = "Back to profiles",
            onClick = viewModel::back,
            variant = IglooButtonVariant.Ghost,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(backFocus)
                .focusProperties { up = lastFocusedKey ?: keyFocus.first() },
            semanticLabel = "Back to profiles",
        )
    }

    // Verifying disables every key, which drops focus off the pad entirely. See
    // ServerSetupUiState.completedAttempts for why the key is a counter and not the error text.
    LaunchedEffect(uiState.rejections) {
        (lastFocusedKey ?: keyFocus.first()).requestFocus()
    }
}

/** One node for the whole row: the count is useful, the digits are not for sharing. */
@Composable
private fun PinIndicator(
    enteredCount: Int,
    isVerifying: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    val description = if (isVerifying) {
        "Checking PIN"
    } else {
        "PIN, $enteredCount of $PIN_LENGTH digits entered"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md, Alignment.CenterHorizontally),
    ) {
        repeat(PIN_LENGTH) { index ->
            Box(
                modifier = Modifier
                    .size(width = 56.dp.scaled(), height = IglooTheme.sizes.fieldHeight)
                    // Same hairline the text fields get from focusRing, so the two input
                    // affordances on adjacent auth screens are delineated the same way.
                    .iglooSurface(radius = IglooTheme.radius.lg, fill = colors.muted),
                contentAlignment = Alignment.Center,
            ) {
                if (index < enteredCount) {
                    Box(
                        modifier = Modifier
                            .size(IglooTheme.sizes.dot)
                            .clip(CircleShape)
                            .background(colors.cardForeground),
                    )
                }
            }
        }
    }
}

@Composable
private fun Keypad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    keyFocus: List<FocusRequester>,
    onKeyFocused: (FocusRequester) -> Unit,
    downBoundary: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val lastRowIndex = KEYPAD.size / KEYPAD_COLUMNS - 1

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Remotes that do have number keys should just work.
            .onPreviewKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val typed = event.utf16CodePoint.toChar()
                when {
                    typed.isDigit() -> {
                        onDigit(typed)
                        true
                    }
                    event.key == Key.Delete || event.key == Key.Backspace -> {
                        onDelete()
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        KEYPAD.chunked(KEYPAD_COLUMNS).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
            ) {
                row.forEachIndexed { columnIndex, key ->
                    if (key == null) {
                        Box(modifier = Modifier.weight(1f))
                        return@forEachIndexed
                    }
                    val requester = keyFocus[rowIndex * KEYPAD_COLUMNS + columnIndex]
                    KeypadKey(
                        key = key,
                        enabled = enabled,
                        onClick = { if (key == DELETE_KEY) onDelete() else onDigit(key) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(requester)
                            .onFocusChanged { if (it.isFocused) onKeyFocused(requester) }
                            .focusProperties {
                                if (rowIndex == lastRowIndex) down = downBoundary
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeypadKey(
    key: Char,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val label = if (key == DELETE_KEY) "Delete last digit" else key.toString()

    Box(
        modifier = modifier
            .heightIn(min = IglooTheme.sizes.controlHeight)
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.lg,
                fill = if (focused) colors.card.copy(alpha = 0.72f) else Color.Transparent,
            )
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
                if (!enabled) disabled()
                onClick(label = label) {
                    if (enabled) onClick()
                    enabled
                }
            }
            .testTag("pin_key_$key"),
        contentAlignment = Alignment.Center,
    ) {
        IglooText(
            text = if (key == DELETE_KEY) "⌫" else key.toString(),
            style = IglooTheme.typography.titleMedium,
            color = colors.foreground,
            maxLines = 1,
        )
    }
}

private const val DELETE_KEY = '⌫'
private const val KEYPAD_COLUMNS = 3
private val KEYPAD: List<Char?> = listOf(
    '1', '2', '3',
    '4', '5', '6',
    '7', '8', '9',
    DELETE_KEY, '0', null,
)
