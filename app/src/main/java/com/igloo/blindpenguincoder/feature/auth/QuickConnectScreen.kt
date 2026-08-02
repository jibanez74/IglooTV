package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooQrCode
import com.igloo.blindpenguincoder.core.ui.IglooText

@Composable
fun QuickConnectScreen(
    viewModel: QuickConnectViewModel,
    serverOrigin: String,
    restoreError: AppError?,
    onRetryRestore: () -> Unit,
    onSwitchToPassword: () -> Unit,
    onChangeServer: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val switchFocus = remember { FocusRequester() }
    val changeServerFocus = remember { FocusRequester() }
    val approvalUrl = remember(serverOrigin) { buildQuickConnectApprovalUrl(serverOrigin) }

    LifecycleStartEffect(Unit) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }

    AuthSurface(
        title = "Sign in to Igloo",
        subtitle = serverOrigin,
        cardWidth = 840.dp,
    ) {
        val phase = state.phase
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xl),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
            ) {
                if (restoreError != null) {
                    IglooInlineError(
                        message = restoreError.toDisplayMessage(),
                        actionText = "Retry",
                        actionSemanticLabel = "Retry connecting to server",
                        onAction = onRetryRestore,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (phase is QuickConnectPhase.Failed) {
                    IglooInlineError(
                        message = phase.message,
                        actionText = "Try again",
                        actionSemanticLabel = "Request a new pairing code",
                        onAction = viewModel::retry,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PairingCode(phase)
                IglooText(
                    text = "Scan the QR code to open Account settings. Sign in through your " +
                        "browser if asked, then enter the six-character TV code.",
                    style = IglooTheme.typography.bodyMedium,
                    color = IglooTheme.colors.mutedForeground,
                )
            }
            ApprovalDestination(
                approvalUrl = approvalUrl,
                modifier = Modifier.width(264.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        ) {
            IglooButton(
                text = "Use email & password instead",
                onClick = onSwitchToPassword,
                variant = IglooButtonVariant.Ghost,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(switchFocus)
                    .focusProperties { right = changeServerFocus },
                semanticLabel = "Use email and password instead",
            )
            IglooButton(
                text = "Change server",
                onClick = onChangeServer,
                variant = IglooButtonVariant.Ghost,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(changeServerFocus)
                    .focusProperties { left = switchFocus },
                semanticLabel = "Change server address",
            )
        }
    }

    LaunchedEffect(Unit) {
        switchFocus.requestFocus()
    }
}

@Composable
private fun ApprovalDestination(
    approvalUrl: String,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        IglooQrCode(
            value = approvalUrl,
            modifier = Modifier.size(216.dp),
        )
        IglooText(
            text = "Approval URL",
            style = IglooTheme.typography.label,
            color = colors.cardForeground,
            modifier = Modifier.fillMaxWidth(),
        )
        IglooText(
            text = wrapApprovalUrlForDisplay(approvalUrl),
            style = IglooTheme.typography.bodyMedium,
            color = colors.mutedForeground,
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { text = AnnotatedString(approvalUrl) },
        )
    }
}

@Composable
private fun PairingCode(phase: QuickConnectPhase) {
    val colors = IglooTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(IglooTheme.radius.lg))
            .background(colors.muted),
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            is QuickConnectPhase.CodeReady -> IglooText(
                text = phase.code,
                style = IglooTheme.typography.displayCode,
                color = colors.cardForeground,
                modifier = Modifier.semantics {
                    contentDescription =
                        "Pairing code: " + phase.code.toCharArray().joinToString(" ")
                    liveRegion = LiveRegionMode.Polite
                },
            )
            QuickConnectPhase.SigningIn -> IglooText(
                text = "Signing you in…",
                style = IglooTheme.typography.titleLarge,
                color = colors.cardForeground,
                modifier = Modifier.semantics {
                    contentDescription = "Code approved. Signing you in."
                    liveRegion = LiveRegionMode.Polite
                },
            )
            else -> IglooText(
                text = "· · · · · ·",
                style = IglooTheme.typography.displayCode,
                color = colors.mutedForeground,
                modifier = Modifier.semantics {
                    contentDescription = "Requesting pairing code"
                },
            )
        }
    }
}
