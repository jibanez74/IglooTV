package com.igloo.blindpenguincoder.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class IglooColors(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val cardForeground: Color,
    val primary: Color,
    val primaryForeground: Color,
    val muted: Color,
    val mutedForeground: Color,
    val border: Color,
    val ring: Color,
    val aurora: Color,
    val auroraForeground: Color,
    val sidebar: Color,
    val sidebarPrimary: Color,
    val destructive: Color,
)

val IglooDarkColors = IglooColors(
    background = Color(0xFF0A1322),
    foreground = Color(0xFFF8FAFC),
    card = Color(0xFF15233A),
    cardForeground = Color(0xFFF8FAFC),
    primary = Color(0xFF38BDF8),
    primaryForeground = Color(0xFF08131F),
    muted = Color(0xFF0F1A2E),
    mutedForeground = Color(0xFF8094AE),
    border = Color(0xFF2A3C57),
    ring = Color(0xFF38BDF8),
    aurora = Color(0xFFF59E0B),
    auroraForeground = Color(0xFF08131F),
    sidebar = Color(0xFF0F1A2E),
    sidebarPrimary = Color(0xFF38BDF8),
    destructive = Color(0xFFF87171),
)

val IglooLightColors = IglooColors(
    background = Color(0xFFF2F7FC),
    foreground = Color(0xFF0A1322),
    card = Color(0xFFFFFFFF),
    cardForeground = Color(0xFF0A1322),
    primary = Color(0xFF0369A1),
    primaryForeground = Color(0xFFFFFFFF),
    muted = Color(0xFFE3EDF7),
    mutedForeground = Color(0xFF475569),
    border = Color(0xFFCBD9E8),
    ring = Color(0xFF0EA5E9),
    aurora = Color(0xFFF59E0B),
    auroraForeground = Color(0xFF08131F),
    sidebar = Color(0xFFE8F1FA),
    sidebarPrimary = Color(0xFF0369A1),
    destructive = Color(0xFFDC2626),
)

