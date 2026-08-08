package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooTextField
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.design.IglooTheme

@Composable
fun ServerSetupScreen(viewModel: ServerSetupViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val fieldFocus = remember { FocusRequester() }
    val connectFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        viewModel.connect()
    }

    AuthSurface(
        title = "Connect to your Igloo server",
        subtitle = "Enter the address of your self-hosted Igloo backend.",
    ) {
        state.error?.let { message ->
            IglooInlineError(message = message, modifier = Modifier.fillMaxWidth())
        }
        IglooTextField(
            value = state.input,
            onValueChange = viewModel::onInputChange,
            label = "Server address",
            placeholder = "http://192.168.1.5:8080",
            errorText = state.error,
            enabled = !state.isConnecting,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            focusRequester = fieldFocus,
            downFocusRequester = connectFocus,
            modifier = Modifier
                .fillMaxWidth(),
        )
        if (state.isConnecting) {
            IglooText(
                text = "Checking server…",
                style = IglooTheme.typography.bodyMedium,
                color = IglooTheme.colors.mutedForeground,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        IglooButton(
            text = if (state.isConnecting) "Connecting…" else "Connect",
            onClick = submit,
            enabled = !state.isConnecting,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(connectFocus)
                .focusProperties { up = fieldFocus },
            semanticLabel = if (state.isConnecting) "Connecting to server" else "Connect to server",
        )
    }

    // Connecting disables the field and the button, so focus is cleared for the duration and a
    // failure would otherwise leave the screen unnavigable. See ServerSetupUiState for why the
    // key is an attempt counter and not the error or the in-flight flag.
    LaunchedEffect(state.completedAttempts) {
        fieldFocus.requestFocus()
        // Order matters: requesting focus starts a text input session and re-shows the IME, so
        // the hide has to come after it. Only after a failure — on arrival the field is empty
        // and the keyboard is exactly what the user wants.
        if (state.error != null) keyboard?.hide()
    }
}
