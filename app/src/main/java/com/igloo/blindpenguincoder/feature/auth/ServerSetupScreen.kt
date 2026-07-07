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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooTextField

@Composable
fun ServerSetupScreen(viewModel: ServerSetupViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val fieldFocus = remember { FocusRequester() }

    AuthSurface(
        title = "Connect to your Igloo server",
        subtitle = "Enter the address of your self-hosted Igloo backend.",
    ) {
        IglooTextField(
            value = state.input,
            onValueChange = viewModel::onInputChange,
            label = "Server address",
            placeholder = "e.g. http://10.0.2.2:8080/api",
            errorText = state.error,
            enabled = !state.isConnecting,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { viewModel.connect() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus),
        )
        IglooButton(
            text = if (state.isConnecting) "Connecting…" else "Connect",
            onClick = viewModel::connect,
            enabled = !state.isConnecting,
            modifier = Modifier.fillMaxWidth(),
            semanticLabel = if (state.isConnecting) "Connecting to server" else "Connect to server",
        )
    }

    LaunchedEffect(Unit) {
        fieldFocus.requestFocus()
    }
}
