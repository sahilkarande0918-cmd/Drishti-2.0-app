package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush

// ============================================================================
// Drishti palette — Terminal / Midnight (pitch-black techy)
//
// Design discipline (Hallmark): a true-black ground, near-black elevated
// surfaces separated by hairlines rather than washes, ONE sharp cool accent
// (electric cyan) and nothing competing with it, and pure-white text at max
// contrast. No decorative gradients spread across the whole UI — colour is
// spent only where the user acts or is warned.
//
// Held from the accessibility work: pure-white body text (~21:1 on black),
// red reserved for emergencies alone, and orb state carried by motion as well
// as hue so it reads without colour discrimination.
// ============================================================================

/** Pitch black. */
val BackgroundDark = Color(0xFF000000)

/** First elevated layer — bars, sheets. Barely lifted off black. */
val SurfaceDark = Color(0xFF0A0B0D)

/** Cards and rows. */
val SurfaceCardDark = Color(0xFF101216)

/** Pressed / hovered card state. */
val SurfaceCardElevated = Color(0xFF181B20)

// ---- The one accent: electric cyan --------------------------------------

/** Primary accent — the active action / focus. Bright on black (~11:1). */
val AccentPrimary = Color(0xFF5CE1E6)

/** Deeper cyan for large fills and pressed states. */
val AccentSecondary = Color(0xFF06B6D4)

/** Dim cyan for supporting marks that must not compete with the primary. */
val AccentTertiary = Color(0xFF0E7490)

/** Caution — attention, not emergency. */
val AccentWarn = Color(0xFFF5C542)

/** Two-stop techy sweep (cyan -> electric blue) for the orb and hero marks. */
val AccentGradient = Brush.linearGradient(
    colors = listOf(AccentPrimary, Color(0xFF3B82F6))
)

/** Shorter cyan sweep for hairline edges. */
val AccentGradientSoft = Brush.linearGradient(
    colors = listOf(AccentPrimary.copy(alpha = 0.7f), AccentSecondary.copy(alpha = 0.5f))
)

/** Near-black ink for text/icons sitting ON the bright cyan. */
val ButtonBlack = Color(0xFF00171A)

// ---- State ----------------------------------------------------------------

/** Emergency only, and the sole red in the palette. */
val EmergencyRed = Color(0xFFFF3B4E)

/** Confirmation. */
val SafeGreen = Color(0xFF34E7A1)

/** Inactive controls, hairlines, disabled text. */
val IdleGray = Color(0xFF3A3F46)

// ---- Text -----------------------------------------------------------------

/** Pure white, ~21:1 on black. */
val TextPrimaryDark = Color(0xFFFFFFFF)

/** Cool grey secondary, ~8:1. */
val TextSecondaryDark = Color(0xFF9BA3AE)
