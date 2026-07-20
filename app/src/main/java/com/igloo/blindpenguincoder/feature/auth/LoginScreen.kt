package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.IglooTextField

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    serverOrigin: String,
    restoreError: AppError?,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = IglooTheme.colors
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    AuthSurface(
        title = "Sign in to Igloo",
        subtitle = serverOrigin,
    ) {
        if (restoreError != null) {
            Column(verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
                IglooText(
                    text = restoreError.toDisplayMessage(),
                    style = IglooTheme.typography.bodyMedium,
                    color = colors.destructive,
                )
                IglooButton(
                    text = "Retry",
                    onClick = viewModel::retryRestore,
                    variant = IglooButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                    semanticLabel = "Retry connecting to server",
                )
            }
        }
        IglooTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = "Email",
            enabled = !state.isSubmitting,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            focusRequester = emailFocus,
            downFocusRequester = passwordFocus,
            modifier = Modifier.fillMaxWidth(),
        )
        IglooTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = "Password",
            isPassword = true,
            errorText = state.error,
            enabled = !state.isSubmitting,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
            focusRequester = passwordFocus,
            upFocusRequester = emailFocus,
            modifier = Modifier.fillMaxWidth(),
        )
        IglooButton(
            text = if (state.isSubmitting) "Signing in…" else "Sign in",
            onClick = viewModel::submit,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
            semanticLabel = if (state.isSubmitting) "Signing in" else "Sign in",
        )
        IglooButton(
            text = "Change server",
            onClick = viewModel::changeServer,
            variant = IglooButtonVariant.Ghost,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
            semanticLabel = "Change server address",
        )
    }

    LaunchedEffect(Unit) {
        emailFocus.requestFocus()
    }
}
