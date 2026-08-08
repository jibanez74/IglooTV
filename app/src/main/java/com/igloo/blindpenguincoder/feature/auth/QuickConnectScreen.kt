package com.igloo.blindpenguincoder.feature.auth

import android.accessibilityservice.AccessibilityServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooQrCode
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing

@Composable
fun QuickConnectScreen(
    viewModel: QuickConnectViewModel,
    serverOrigin: String,
    restoreError: AppError?,
    canCancel: Boolean,
    onRetryRestore: () -> Unit,
    onSwitchToPassword: () -> Unit,
    onLeave: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleStartEffect(Unit) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }

    QuickConnectContent(
        phase = state.phase,
        serverOrigin = serverOrigin,
        restoreError = restoreError,
        canCancel = canCancel,
        onRetryRestore = onRetryRestore,
        onRetryPairing = viewModel::retry,
        onSwitchToPassword = onSwitchToPassword,
        onLeave = onLeave,
    )
}

/**
 * The screen without its view model, so the vertical budget in docs/design-system.md section 11.1.3
 * can be measured at a fixed phase instead of racing a live pairing loop.
 */
@Composable
internal fun QuickConnectContent(
    phase: QuickConnectPhase,
    serverOrigin: String,
    restoreError: AppError?,
    canCancel: Boolean,
    onRetryRestore: () -> Unit,
    onRetryPairing: () -> Unit,
    onSwitchToPassword: () -> Unit,
    onLeave: () -> Unit,
) {
    val switchFocus = remember { FocusRequester() }
    val changeServerFocus = remember { FocusRequester() }
    val approvalUrl = remember(serverOrigin) { buildQuickConnectApprovalUrl(serverOrigin) }
    val spokenAccessibilityEnabled = rememberSpokenAccessibilityEnabled()

    AuthSurface(
        title = "Sign in to Igloo",
        subtitle = serverOrigin,
        cardWidth = IglooTheme.layout.authCardWideWidth,
    ) {
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
                        onAction = onRetryPairing,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PairingCode(
                    phase = phase,
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                    leftBoundaryFocus = switchFocus,
                    rightBoundaryFocus = changeServerFocus,
                )
                IglooText(
                    text = "Scan the QR code to open Account settings. Sign in through your " +
                        "browser if asked, then enter the six-character TV code.",
                    style = IglooTheme.typography.bodyMedium,
                    color = IglooTheme.colors.mutedForeground,
                )
            }
            ApprovalDestination(
                approvalUrl = approvalUrl,
                modifier = Modifier.width(IglooTheme.layout.wideCardWidth),
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
                text = leaveActionText(canCancel),
                onClick = onLeave,
                variant = IglooButtonVariant.Ghost,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(changeServerFocus)
                    .focusProperties { left = switchFocus },
                semanticLabel = leaveActionSemanticLabel(canCancel),
            )
        }
    }

    // The pairing characters are only focusable while spoken accessibility is on and a code is
    // up; when they go away they take focus with them. Keyed on exactly that, and not on the
    // phase itself — a phase flip with spoken accessibility off destroys nothing, and re-firing
    // there would drag the user off whichever button they were on, mid-approval.
    val accessibleCodeShown = spokenAccessibilityEnabled && phase is QuickConnectPhase.CodeReady
    LaunchedEffect(accessibleCodeShown) {
        if (!accessibleCodeShown) switchFocus.requestFocus()
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
            modifier = Modifier.size(216.dp.scaled()),
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
internal fun PairingCode(
    phase: QuickConnectPhase,
    spokenAccessibilityEnabled: Boolean,
    leftBoundaryFocus: FocusRequester? = null,
    rightBoundaryFocus: FocusRequester? = null,
) {
    val colors = IglooTheme.colors
    var hasPresentedAccessibleCode by remember { mutableStateOf(false) }

    LaunchedEffect(spokenAccessibilityEnabled) {
        if (!spokenAccessibilityEnabled) {
            hasPresentedAccessibleCode = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 110.dp.scaled())
            .clip(RoundedCornerShape(IglooTheme.radius.lg))
            .background(colors.muted),
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            is QuickConnectPhase.CodeReady -> if (spokenAccessibilityEnabled) {
                AccessiblePairingCode(
                    code = phase.code,
                    isReplacement = hasPresentedAccessibleCode,
                    onPresented = { hasPresentedAccessibleCode = true },
                    leftBoundaryFocus = leftBoundaryFocus,
                    rightBoundaryFocus = rightBoundaryFocus,
                )
            } else {
                IglooText(
                    text = phase.code,
                    style = IglooTheme.typography.displayCode,
                    color = colors.cardForeground,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "Pairing code: " + phase.code.toCharArray().joinToString(" ")
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
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

@Composable
private fun AccessiblePairingCode(
    code: String,
    isReplacement: Boolean,
    onPresented: () -> Unit,
    leftBoundaryFocus: FocusRequester?,
    rightBoundaryFocus: FocusRequester?,
) {
    val colors = IglooTheme.colors
    val focusManager = LocalFocusManager.current
    // Read from the style rather than repeated: IglooTypography scales letterSpacing, so a copy
    // would lose tracking parity with the same code rendered in sighted mode.
    val characterSpacing = with(LocalDensity.current) {
        IglooTheme.typography.displayCode.letterSpacing.toDp()
    }
    val focusRequesters = remember(code.length) {
        List(code.length) { FocusRequester() }
    }
    var firstCharacterDescription by remember { mutableStateOf<String?>(null) }
    var arrivalFocusReceived by remember { mutableStateOf(false) }
    var focusedIndex by remember(code.length) { mutableStateOf<Int?>(null) }

    LaunchedEffect(code) {
        val firstCharacter = code.firstOrNull() ?: return@LaunchedEffect
        onPresented()
        // Re-anchors on every rotation, including from elsewhere on the screen. That looks like
        // focus theft and was nearly "fixed" as such, but it is deliberate and asserted by
        // PairingCodeAccessibilityTest: the code the user is being read has just stopped being
        // valid, so leaving them on a stale one is the worse failure.
        firstCharacterDescription = if (isReplacement) {
            "Pairing code changed. Focus moved to the first character. $firstCharacter."
        } else {
            "Pairing code ready. Focus moved to the first character. " +
                "Use Left and Right to review each character. $firstCharacter."
        }
        arrivalFocusReceived = false

        withFrameNanos { }
        focusManager.clearFocus(force = true)
        withFrameNanos { }
        focusRequesters.first().requestFocus()
    }

    Row(
        modifier = Modifier
            .testTag("pairing_code_group")
            .semantics { isTraversalGroup = true },
        horizontalArrangement = Arrangement.spacedBy(characterSpacing),
    ) {
        code.forEachIndexed { index, character ->
            IglooText(
                text = character.toString(),
                style = IglooTheme.typography.displayCode.copy(letterSpacing = 0.sp),
                color = colors.cardForeground,
                modifier = Modifier
                    .focusRequester(focusRequesters[index])
                    .onFocusChanged { focusState ->
                        // Written by index rather than a bare flag so stepping between two
                        // characters — which unfocuses one and focuses the next in either
                        // order — never reads as having left the group.
                        if (focusState.isFocused) {
                            focusedIndex = index
                        } else if (focusedIndex == index) {
                            focusedIndex = null
                        }
                        if (index != 0) return@onFocusChanged
                        if (focusState.isFocused) {
                            arrivalFocusReceived = true
                        } else if (arrivalFocusReceived) {
                            firstCharacterDescription = null
                            arrivalFocusReceived = false
                        }
                    }
                    // These characters are the reason this mode exists, and they were the only
                    // focusables in the app that showed no focus at all.
                    .focusRing(
                        focused = focusedIndex == index,
                        radius = IglooTheme.radius.md,
                    )
                    .padding(IglooTheme.spacing.xs)
                    .focusProperties {
                        if (index > 0) {
                            left = focusRequesters[index - 1]
                        } else if (leftBoundaryFocus != null) {
                            left = leftBoundaryFocus
                        }
                        if (index < focusRequesters.lastIndex) {
                            right = focusRequesters[index + 1]
                        } else if (rightBoundaryFocus != null) {
                            right = rightBoundaryFocus
                        }
                    }
                    .focusable()
                    .testTag("pairing_code_character_$index")
                    .semantics {
                        contentDescription = if (index == 0) {
                            firstCharacterDescription ?: character.toString()
                        } else {
                            character.toString()
                        }
                        traversalIndex = index.toFloat()
                    },
            )
        }
    }
}

@Composable
private fun rememberSpokenAccessibilityEnabled(): Boolean {
    val context = LocalContext.current
    val accessibilityManager = remember(context) {
        context.getSystemService(AccessibilityManager::class.java)
    }
    var enabled by remember(accessibilityManager) {
        mutableStateOf(accessibilityManager.hasSpokenFeedbackService())
    }

    DisposableEffect(accessibilityManager) {
        if (accessibilityManager == null) return@DisposableEffect onDispose { }

        val update = {
            enabled = accessibilityManager.hasSpokenFeedbackService()
        }
        val accessibilityStateListener =
            AccessibilityManager.AccessibilityStateChangeListener { update() }
        accessibilityManager.addAccessibilityStateChangeListener(accessibilityStateListener)
        val servicesStateObserver = object : ContentObserver(
            Handler(Looper.getMainLooper()),
        ) {
            override fun onChange(selfChange: Boolean) {
                update()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            servicesStateObserver,
        )

        onDispose {
            accessibilityManager.removeAccessibilityStateChangeListener(accessibilityStateListener)
            context.contentResolver.unregisterContentObserver(servicesStateObserver)
        }
    }

    return enabled
}

private fun AccessibilityManager?.hasSpokenFeedbackService(): Boolean =
    this?.isEnabled == true &&
        getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
