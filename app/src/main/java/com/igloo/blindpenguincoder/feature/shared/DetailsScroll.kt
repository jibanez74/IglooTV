package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged

/**
 * The scrolling column of a details page (docs/design-system.md section 11.4.1, Focus): the
 * hero first, then the sections, with one rule of its own about where focus parks the page.
 *
 * Compose's TV bring-into-view parks whatever takes focus 30% down the viewport. For a section
 * below the hero that is the convention and it stays; for a control *inside* the hero it is a
 * fault: the hero is bottom-anchored, so its action sits low in it, and parking that action at
 * 30% scrolls the hero's top off the screen by however much taller the hero is than its content
 * block. The in-theaters page, whose block is a single button, lost the top of its poster to it
 * (found on the Shield, 2026-10-19); the library movie and show pages lost exactly the safe
 * area above theirs. Here a target inside the hero scrolls the page back to the top instead:
 * the hero is the page's opening and is shown whole whenever focus is in it, on entry and on
 * the way back up from the sections. Everything below the hero, and every scrollable inside the
 * column (the rails keep their own pivot), still goes through the platform's spec.
 *
 * [modifier] goes on the column before its `verticalScroll`, where the show page's section
 * mover needs to sit. `LocalBringIntoViewSpec` is experimental; the navigation rail is the only
 * other use (section 11.2).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DetailsScrollColumn(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    hero: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val platform = LocalBringIntoViewSpec.current
    val heroFirst = remember(scrollState, platform) { HeroFirstScroll(scrollState, platform) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides heroFirst) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides platform) {
                Box(modifier = heroFirst.heroModifier) { hero() }
                content()
            }
        }
    }
}

/**
 * A target whose top lies inside the hero — the first [heroHeightPx] of the content — scrolls
 * the column back to its start; any other target is [platform]'s to place. A hero taller than
 * the viewport cannot be shown whole, so it is left to the platform too.
 */
@OptIn(ExperimentalFoundationApi::class)
private class HeroFirstScroll(
    private val scrollState: ScrollState,
    private val platform: BringIntoViewSpec,
) : BringIntoViewSpec {
    private var heroHeightPx by mutableFloatStateOf(0f)

    val heroModifier: Modifier = Modifier.onSizeChanged { heroHeightPx = it.height.toFloat() }

    override val scrollAnimationSpec: AnimationSpec<Float>
        get() = platform.scrollAnimationSpec

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val scrolled = scrollState.value.toFloat()
        val inHero = offset + scrolled < heroHeightPx && heroHeightPx <= containerSize
        return if (inHero) -scrolled else platform.calculateScrollDistance(offset, size, containerSize)
    }
}
