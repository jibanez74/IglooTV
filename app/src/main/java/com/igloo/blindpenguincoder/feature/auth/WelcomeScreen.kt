package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.iglooSafeArea
import com.igloo.blindpenguincoder.core.design.rememberAmbientProgress
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooBrandMark
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.iglooAuroraBackdrop

internal const val WELCOME_HEADLINE = "Welcome to Igloo"

internal const val WELCOME_INTRO =
    "Your own media library, streamed from your own server. " +
        "No accounts, no subscriptions, no cloud."

internal const val WELCOME_ACTION = "Get started"

internal data class WelcomeStep(val number: Int, val title: String, val body: String) {
    val accessibilityLabel: String
        get() = "Step $number of ${WelcomeSteps.size}. $title. $body"
}

/**
 * Step three deliberately does not promise the profile picker: a single profile with no PIN skips
 * that screen, which is exactly the first-run case. It describes profiles as a capability instead.
 */
internal val WelcomeSteps = listOf(
    WelcomeStep(1, "Connect to your server", "Enter the address of your Igloo backend."),
    WelcomeStep(2, "Scan a code to sign in", "Or use your email and password."),
    WelcomeStep(3, "Everyone gets a profile", "Add more people later, with their own history."),
)

/**
 * The first screen a new user ever sees. Full-bleed hero form of the auth canvas — no card, no
 * internal scroll, one focusable. See docs/design-system.md section 11.1.0.
 *
 * No entrance animation: the splash covers the launch for `SPLASH_HOLD_MS` and this screen has
 * long since settled by the time it lifts, so a stagger here would only ever play to nobody.
 */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    val ambient = rememberAmbientProgress()
    val actionFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { actionFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .iglooAuroraBackdrop(ambient),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .iglooSafeArea(),
            verticalArrangement = Arrangement.spacedBy(
                space = IglooTheme.spacing.xl,
                alignment = Alignment.CenterVertically,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // fill = false so the block sits as one centered group rather than leaving a
                    // gap above the button, while the row still yields space before the button
                    // does if the copy ever grows.
                    .weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xxl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Hero(modifier = Modifier.weight(1f))
                Steps(modifier = Modifier.weight(1f))
            }

            IglooButton(
                text = WELCOME_ACTION,
                onClick = onGetStarted,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(280.dp.scaled())
                    .focusRequester(actionFocus),
            )
        }
    }
}

@Composable
private fun Hero(modifier: Modifier = Modifier) {
    val colors = IglooTheme.colors
    Column(
        modifier = modifier.semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IglooBrandMark(size = 64.dp.scaled(), textStyle = IglooTheme.typography.titleLarge)
            IglooText(
                text = WELCOME_HEADLINE,
                style = IglooTheme.typography.display,
                color = colors.foreground,
                overflow = TextOverflow.Visible,
                modifier = Modifier.semantics { heading() },
            )
        }
        IglooText(
            text = WELCOME_INTRO,
            style = IglooTheme.typography.bodyLarge,
            color = colors.foreground,
            overflow = TextOverflow.Visible,
        )
    }
}

@Composable
private fun Steps(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        WelcomeSteps.forEach { step -> StepRow(step = step) }
    }
}

@Composable
private fun StepRow(step: WelcomeStep, modifier: Modifier = Modifier) {
    val colors = IglooTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = step.accessibilityLabel },
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        IglooText(
            text = step.number.toString(),
            style = IglooTheme.typography.titleMedium,
            color = colors.mutedForeground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs)) {
            IglooText(
                text = step.title,
                style = IglooTheme.typography.bodyLarge,
                color = colors.foreground,
                overflow = TextOverflow.Visible,
            )
            IglooText(
                text = step.body,
                style = IglooTheme.typography.bodyMedium,
                color = colors.foreground,
                overflow = TextOverflow.Visible,
            )
        }
    }
}
