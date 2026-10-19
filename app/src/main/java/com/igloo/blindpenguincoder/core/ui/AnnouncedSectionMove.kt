package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Moves d-pad focus one section up or down a scrolling column so that a screen reader follows
 * it — [AnnouncedLazyMove] at section scale, for Home's hero and rails and a details page's
 * sections. Columns use it through [rememberAnnouncedSectionColumn].
 *
 * Compose reports a focus change only for a node its last accessibility snapshot held, and that
 * snapshot holds what is on screen. A rail below the fold is composed but not on screen, so
 * Compose's own move focuses its entry card and scrolls it into view in one frame, and the
 * focus goes unannounced; TalkBack for TV then reacts to the scroll by re-reading whatever stayed
 * on screen (the card just left) and never speaks the new one. So while a screen reader runs, a
 * press whose target section is not yet on screen is taken over: [reveal] shows part of it while
 * the origin stays on screen, a snapshot is awaited, the target's entry takes focus with the
 * scroll held so the origin does not leave the screen before TalkBack has followed, and [settle]
 * brings the section to rest. A press whose target is already on screen, or has nowhere to go,
 * is left to Compose, whose move is announced in that case. With no screen reader every press is
 * left to Compose.
 *
 * Sections are named by stable keys, not positions: one can appear or go (a Retry row) while a
 * move is in flight, and every step resolves its section afresh — a target that is gone by then
 * ends the move. The lambdas read live state, so one instance outlives recompositions and can
 * swallow key repeats while a move is in flight.
 */
internal class AnnouncedSectionMove<K : Any>(
    private val scope: CoroutineScope,
    private val scrollable: ScrollableState,
    private val screenReader: () -> Boolean,
    /** The section holding focus, or null. */
    private val focusedSection: () -> K?,
    /** The section after (forward) or before this one, or null at either end. */
    private val neighbourOf: (key: K, forward: Boolean) -> K?,
    /** Whether the section's focus target is at least partly on screen. */
    private val isOnScreen: (key: K) -> Boolean,
    /** Shows part of the section — its leading part when moving forward, trailing when back. */
    private val reveal: suspend (key: K, forward: Boolean) -> Unit,
    private val settle: suspend (key: K) -> Unit,
    /** The section's focus target, or null once the section is gone. */
    private val requesterFor: (key: K) -> FocusRequester?,
) {
    private var inFlight: Job? = null

    /**
     * Handles a previewed key event. Returns true when the press was taken over (or swallowed as
     * a repeat during a move) and false to leave it to Compose's focus search.
     */
    fun onPreviewKey(event: KeyEvent): Boolean {
        if (!screenReader() || event.type != KeyEventType.KeyDown) return false
        val forward = when (event.key) {
            Key.DirectionDown -> true
            Key.DirectionUp -> false
            else -> return false
        }
        if (inFlight?.isActive == true) return true
        val origin = focusedSection() ?: return false
        val target = neighbourOf(origin, forward) ?: return false
        if (isOnScreen(target)) return false
        inFlight = scope.launch { travel(origin, target, forward, revealFirst = true) }
        return true
    }

    /**
     * Hands focus to [target] from code rather than from a d-pad press — a press elsewhere whose
     * result is focus in another section, such as a season chip landing on its episodes. Under a
     * screen reader it travels like a key move, revealing the target first if it is off screen,
     * so the landing is heard; the target's nodes may be new this frame, so the snapshot wait
     * runs even when it is on screen. With no screen reader focus moves at once.
     */
    fun handOff(target: K, forward: Boolean) {
        if (!screenReader()) {
            requesterFor(target)?.requestFocusSafely()
            return
        }
        val origin = focusedSection()
        inFlight?.cancel()
        val revealFirst = !isOnScreen(target)
        inFlight = scope.launch { travel(origin, target, forward, revealFirst) }
    }

    private suspend fun travel(origin: K?, target: K, forward: Boolean, revealFirst: Boolean) {
        if (revealFirst) reveal(target, forward)
        awaitAccessibilitySnapshot(ANNOUNCED_LINE_WAIT_MS)
        val now = focusedSection()
        if (now != origin && now != target) return
        val requester = requesterFor(target) ?: return
        scrollable.scroll(MutatePriority.PreventUserInput) {
            requester.requestFocusSafely()
            delay(ANNOUNCED_LINE_WAIT_MS)
        }
        settle(target)
    }
}

/**
 * What a section move needs from one section of a scrolling column: where it is, whether it
 * holds focus, a way to scroll part of it on screen, and [entry], a requester for its focus
 * target. Attach it with [announcedSection].
 */
internal class AnnouncedSection(val key: Any) {
    val entry = FocusRequester()
    val bringIntoView = BringIntoViewRequester()
    var coordinates: LayoutCoordinates? = null
    var hasFocus by mutableStateOf(false)
}

/** Lets the column see where [section] is and whether it holds focus, and scroll it on screen. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.announcedSection(section: AnnouncedSection): Modifier = this
    .bringIntoViewRequester(section.bringIntoView)
    .onGloballyPositioned { section.coordinates = it }
    .onFocusChanged { section.hasFocus = it.hasFocus }

/**
 * A scrolling column whose vertical moves a screen reader follows: one [AnnouncedSection] per
 * key ([section]), an [AnnouncedSectionMove] over the column's current order, and [modifier],
 * which goes on the column *before* its `verticalScroll` so it sees the viewport and every press.
 * The sections outlive their coming and going, so a section can disappear and return without
 * losing its requester.
 */
internal class AnnouncedSectionColumn(
    scope: CoroutineScope,
    scrollState: ScrollState,
    screenReader: () -> Boolean,
    private val order: () -> List<Any>,
    private val requesterFor: (key: Any) -> FocusRequester?,
) {
    private val sections = mutableMapOf<Any, AnnouncedSection>()
    private var viewport: LayoutCoordinates? = null

    fun section(key: Any): AnnouncedSection = sections.getOrPut(key) { AnnouncedSection(key) }

    // Each step resolves its section by key, so one that came or went mid-move is found or
    // found missing rather than mistaken for its neighbour.
    private fun present(key: Any): AnnouncedSection? = if (key in order()) section(key) else null

    private val move = AnnouncedSectionMove<Any>(
        scope = scope,
        scrollable = scrollState,
        screenReader = screenReader,
        focusedSection = { order().firstOrNull { section(it).hasFocus } },
        neighbourOf = { key, forward ->
            val keys = order()
            val index = keys.indexOf(key)
            if (index < 0) null else keys.getOrNull(index + if (forward) 1 else -1)
        },
        isOnScreen = { key ->
            // Unclipped: a section scrolled out of the column must still report where it is.
            val bounds = present(key)?.coordinates
                ?.takeIf { it.isAttached }
                ?.unclippedBoundsInRoot()
            val visible = viewport?.takeIf { it.isAttached }?.unclippedBoundsInRoot()
            if (bounds == null || visible == null) {
                true
            } else {
                // A rail's heading leads its cards; the part that takes focus is below it.
                val targetTop = bounds.top + bounds.height * SECTION_HEADING_FRACTION
                targetTop < visible.bottom && bounds.bottom > visible.top
            }
        },
        reveal = { key, forward ->
            val section = present(key)
            val size = section?.coordinates?.size
            if (section != null && size == null) {
                section.bringIntoView.bringIntoView()
            } else if (section != null && size != null) {
                val height = size.height.toFloat()
                val shown = height * SECTION_REVEAL_FRACTION
                val rect = if (forward) {
                    Rect(0f, 0f, size.width.toFloat(), shown)
                } else {
                    Rect(0f, height - shown, size.width.toFloat(), height)
                }
                section.bringIntoView.bringIntoView(rect)
            }
        },
        settle = { key -> present(key)?.bringIntoView?.bringIntoView() },
        requesterFor = { key -> if (present(key) == null) null else requesterFor(key) },
    )

    val modifier: Modifier = Modifier
        .onGloballyPositioned { viewport = it }
        .onPreviewKeyEvent(move::onPreviewKey)

    /** Hands focus to [key]'s section from code, announced (see [AnnouncedSectionMove.handOff]). */
    fun handOff(key: Any) {
        val keys = order()
        val origin = keys.indexOfFirst { section(it).hasFocus }
        move.handOff(key, forward = keys.indexOf(key) >= origin)
    }
}

/**
 * The column for the current composition. [order] is the sections present, top to bottom, and
 * [requesterFor] names the node a move lands on (by default the section's own [entry]); both are
 * read live, so one column outlives recompositions.
 */
@Composable
internal fun rememberAnnouncedSectionColumn(
    scrollState: ScrollState,
    screenReader: Boolean,
    order: List<Any>,
    requesterFor: (AnnouncedSectionColumn, Any) -> FocusRequester? = { column, key ->
        column.section(key).entry
    },
): AnnouncedSectionColumn {
    val scope = rememberCoroutineScope()
    val currentOrder by rememberUpdatedState(order)
    val currentScreenReader by rememberUpdatedState(screenReader)
    val currentRequesterFor by rememberUpdatedState(requesterFor)
    return remember {
        lateinit var column: AnnouncedSectionColumn
        column = AnnouncedSectionColumn(
            scope = scope,
            scrollState = scrollState,
            screenReader = { currentScreenReader },
            order = { currentOrder },
            requesterFor = { key -> currentRequesterFor(column, key) },
        )
        column
    }
}

private fun LayoutCoordinates.unclippedBoundsInRoot(): Rect =
    Rect(positionInRoot(), size.toSize())

/** How much of a section is heading and gap before its focus target, as a fraction of it. */
private const val SECTION_HEADING_FRACTION = 0.3f

/** How much of the next section a screen-reader move shows before focusing into it. */
private const val SECTION_REVEAL_FRACTION = 0.6f
