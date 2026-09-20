package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.iglooSafeArea
import com.igloo.blindpenguincoder.core.design.rememberAmbientProgress
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooBrandMark
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.iglooAuroraBackdrop

/** The two shapes the auth canvas takes, chosen by what the screen's content actually is. */
enum class AuthCanvas {
    /** Identity left, a column of controls right. A form: server setup, password login, PIN. */
    Split,

    /** Identity across the top, content spanning the full inset measure below. A row: the
     * profile picker's tiles and quick connect's code-beside-QR pair, neither of which fits a
     * half-panel column. */
    Stacked,
}

/**
 * The auth canvas: a full-bleed TV screen over the ambient backdrop the welcome screen and the
 * splash already use.
 *
 * It was a 480dp card centred on the 960dp reference viewport, which put half the panel's width
 * into empty background — the phone form factor rendered on a television, and the single worst
 * offender behind "the app doesn't fill the screen". A TV has one viewer at ten feet and no
 * portrait mode to defend against; the width is there to be used.
 *
 * In [AuthCanvas.Split] both columns are traversal groups, or TalkBack's geometric sort
 * interleaves the identity block with the form rows that share its y position (section 12).
 */
@Composable
fun AuthSurface(
    title: String,
    subtitle: String,
    canvas: AuthCanvas = AuthCanvas.Split,
    content: @Composable () -> Unit,
) {
    val ambient = rememberAmbientProgress()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .iglooAuroraBackdrop(ambient)
            .testTag("auth_surface"),
    ) {
        when (canvas) {
            AuthCanvas.Split -> Row(
                // A full-screen non-shell surface owes the overscan safe area (section 2.5) — not
                // spacing.xl, which is both narrower and shrinks at Compact.
                modifier = Modifier
                    .fillMaxSize()
                    .iglooSafeArea(),
                horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xxl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AuthHeadline(
                    title = title,
                    subtitle = subtitle,
                    stacked = true,
                    modifier = Modifier.weight(1f),
                )
                AuthFormColumn(modifier = Modifier.weight(1f), content = content)
            }

            AuthCanvas.Stacked -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .iglooSafeArea()
                    .verticalScroll(rememberScrollState())
                    .testTag("auth_form"),
                verticalArrangement = Arrangement.spacedBy(
                    space = IglooTheme.spacing.lg,
                    alignment = Alignment.CenterVertically,
                ),
            ) {
                AuthHeadline(
                    title = title,
                    subtitle = subtitle,
                    stacked = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                content()
            }
        }
    }
}

/**
 * Brand, title and origin — the "which server, which step" block, shared by both canvases.
 *
 * [stacked] puts the mark above the words, which is right in [AuthCanvas.Split]'s half-panel
 * column where vertical room is the abundant axis. [AuthCanvas.Stacked] gets the mark *beside*
 * them instead: there the headline sits above content that already fills the panel, and the tall
 * form costs ~80dp that pushed quick connect's own header off the top of a 540dp screen.
 */
@Composable
private fun AuthHeadline(
    title: String,
    subtitle: String,
    stacked: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    val words: @Composable () -> Unit = {
        IglooText(
            text = title,
            style = if (stacked) IglooTheme.typography.titleLarge else IglooTheme.typography.titleMedium,
            color = colors.foreground,
            modifier = Modifier.semantics { heading() },
        )
        IglooText(
            text = subtitle,
            style = IglooTheme.typography.bodyMedium,
            color = colors.mutedForeground,
        )
    }
    val mark: @Composable () -> Unit = {
        IglooBrandMark(
            size = 64.dp.scaled(),
            textStyle = IglooTheme.typography.titleLarge,
        )
    }

    if (stacked) {
        Column(
            modifier = modifier.semantics { isTraversalGroup = true },
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        ) {
            mark()
            words()
        }
    } else {
        Row(
            modifier = modifier.semantics { isTraversalGroup = true },
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            mark()
            Column { words() }
        }
    }
}

@Composable
private fun AuthFormColumn(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        // The scroll stays for the degraded states section 11.1 names — an inline error present,
        // a larger UiScale, a raised font scale. In the resting state at Standard nothing here
        // overflows, so it never engages.
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .semantics { isTraversalGroup = true }
            .testTag("auth_form"),
        verticalArrangement = Arrangement.spacedBy(
            space = IglooTheme.spacing.lg,
            alignment = Alignment.CenterVertically,
        ),
    ) {
        content()
    }
}
