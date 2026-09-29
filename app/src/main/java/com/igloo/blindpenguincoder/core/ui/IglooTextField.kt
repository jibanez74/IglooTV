package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.igloo.blindpenguincoder.core.design.IglooTheme

@Composable
fun IglooTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isPassword: Boolean = false,
    errorText: String? = null,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = IglooTheme.colors
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        IglooText(
            text = label,
            style = IglooTheme.typography.label,
            color = colors.mutedForeground,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = IglooTheme.sizes.fieldHeight)
                .focusRing(
                    focused = focused,
                    radius = IglooTheme.radius.lg,
                    fill = colors.muted,
                    hasError = errorText != null,
                    // The one opt-out from the focus scale, for two mechanical reasons:
                    // localToWindow composes the layer matrix, so a scaled field would report a
                    // cursor rect that mis-anchors the IME; and glyphs rasterised at 1x then
                    // resampled to 1.05x are visibly soft on the one control a user reads
                    // character by character while typing.
                    scaleOnFocus = false,
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IglooTheme.spacing.md)
                    .withRequester(focusRequester)
                    .focusProperties {
                        upFocusRequester?.let { up = it }
                        downFocusRequester?.let { down = it }
                    }
                    .onFocusChanged { focused = it.isFocused }
                    // BasicTextField consumes D-pad up/down for cursor movement,
                    // trapping remote focus when the IME is closed; move focus instead.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.DirectionDown -> {
                                    focusManager.moveFocus(FocusDirection.Down)
                                    true
                                }
                                Key.DirectionUp -> {
                                    focusManager.moveFocus(FocusDirection.Up)
                                    true
                                }
                                else -> false
                            }
                        } else {
                            false
                        }
                    }
                    .semantics {
                        contentDescription = label
                        if (isPassword) password()
                        if (errorText != null) error(errorText)
                    },
                enabled = enabled,
                singleLine = true,
                textStyle = IglooTheme.typography.bodyLarge.copy(color = colors.foreground),
                cursorBrush = SolidColor(colors.primary),
                visualTransformation = if (isPassword) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
            )
            if (value.isEmpty() && placeholder != null) {
                IglooText(
                    text = placeholder,
                    style = IglooTheme.typography.bodyLarge,
                    color = colors.mutedForeground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = IglooTheme.spacing.md),
                    maxLines = 1,
                )
            }
        }
    }
}
