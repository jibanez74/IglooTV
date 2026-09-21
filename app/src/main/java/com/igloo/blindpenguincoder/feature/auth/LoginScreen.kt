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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooNotice
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.IglooTextField

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    serverOrigin: String,
    restoreError: AppError?,
    canCancel: Boolean,
    onSwitchToQuickConnect: () -> Unit,
    notice: String? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        viewModel.submit()
    }

    AuthSurface(
        title = "Sign in to Igloo",
        subtitle = serverOrigin,
    ) {
        if (restoreError != null) {
            IglooInlineError(
                message = restoreError.toDisplayMessage(),
                actionText = "Retry",
                actionSemanticLabel = "Retry connecting to server",
                onAction = viewModel::retryRestore,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.error?.let { message ->
            IglooInlineError(message = message, modifier = Modifier.fillMaxWidth())
        }
        if (notice != null) {
            IglooNotice(text = notice)
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
            keyboardActions = KeyboardActions(onDone = { submit() }),
            focusRequester = passwordFocus,
            upFocusRequester = emailFocus,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.isSubmitting) {
            // The button carries the only other signal, and it is disabled and unfocused for the
            // whole request, so without this TalkBack says nothing at all while it runs.
            IglooText(
                text = "Signing in…",
                style = IglooTheme.typography.bodyMedium,
                color = IglooTheme.colors.mutedForeground,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        IglooButton(
            text = if (state.isSubmitting) "Signing in…" else "Sign in",
            onClick = submit,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
            semanticLabel = if (state.isSubmitting) "Signing in" else "Sign in",
        )
        IglooButton(
            text = "Use pairing code instead",
            onClick = {
                viewModel.clearPassword()
                onSwitchToQuickConnect()
            },
            variant = IglooButtonVariant.Ghost,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
            semanticLabel = "Use pairing code instead",
        )
        IglooButton(
            text = leaveActionText(canCancel),
            onClick = if (canCancel) viewModel::cancel else viewModel::changeServer,
            variant = IglooButtonVariant.Ghost,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
            semanticLabel = leaveActionSemanticLabel(canCancel),
        )
    }

    // Submitting disables every control on the screen, so focus is cleared for the duration and a
    // failure would otherwise leave nothing focused. See ServerSetupUiState.completedAttempts for
    // why the key is an attempt counter and not the error or the in-flight flag.
    LaunchedEffect(state.completedAttempts) {
        emailFocus.requestFocus()
    }
}
