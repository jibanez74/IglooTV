package com.igloo.blindpenguincoder.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** TV screen readers follow card focus and may never visit the section's static heading. */
internal val LocalMediaSectionTitle = staticCompositionLocalOf<String?> { null }

@Composable
internal fun mediaSectionDescription(description: String): String =
    LocalMediaSectionTitle.current?.let { "$it. $description" } ?: description
