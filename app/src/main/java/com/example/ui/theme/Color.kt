package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush

// ============================================================================
// Drishti palette — Google Lookout / Material 3 dark
//
// Lookout is Google's own camera assistant for blind and low-vision users, so
// its design language is built around this exact reader rather than adapted to
// them: flat tonal surfaces, one calm accent, large plain type, and no
// decoration competing with content. These are Material 3's dark tokens.
//
// Deliberately gone from the previous passes: gradients, glass, glow and
// saturated brand colour. On a screen someone is squinting at, every one of
// those reduces contrast for the sake of looking impressive.
//
// Red is now reserved exclusively for emergencies, so it means one thing.
// ============================================================================

/** Material 3 dark surface — not pure black, which halates badly for low vision. */
val BackgroundDark = Color(0xFF131314)

/** The layer above the ground: bars and sheets. */
val SurfaceDark = Color(0xFF1B1B1F)

/** Cards and rows. */
val SurfaceCardDark = Color(0xFF1E1F20)

/** Pressed / hovered card state. */
val SurfaceCardElevated = Color(0xFF282A2C)

// ---- Accent ---------------------------------------------------------------
// Material 3 lightens the accent on dark surfaces rather than darkening it,
// which is why this is a pale blue and not Google's marketing blue.

/** Primary accent — the active action. ~9.4:1 on the dark surface. */
val AccentPrimary = Color(0xFFA8C7FA)

/** Container fill behind primary actions. */
val AccentSecondary = Color(0xFF0842A0)

/** Muted supporting accent. */
val AccentTertiary = Color(0xFF3B4A63)

/** Caution — attention needed, but not an emergency. */
val AccentWarn = Color(0xFFF9CC72)

/** Kept flat: a single colour, so "gradient" surfaces stop shimmering. */
val AccentGradient = Brush.linearGradient(
    colors = listOf(AccentPrimary, AccentPrimary)
)

/** Dark ink for text and icons sitting ON the pale accent. */
val ButtonBlack = Color(0xFF062E6F)

// ---- State ----------------------------------------------------------------

/** Emergency only, and the sole red in the palette, so red always means danger. */
val EmergencyRed = Color(0xFFF2B8B5)

/** Confirmation. */
val SafeGreen = Color(0xFF6DD58C)

/** Inactive controls and disabled text. */
val IdleGray = Color(0xFF8E918F)

// ---- Text -----------------------------------------------------------------

/** ~15:1 on the dark surface. */
val TextPrimaryDark = Color(0xFFE3E3E3)

/** ~7.5:1 — a readable secondary, not a decorative grey. */
val TextSecondaryDark = Color(0xFFC4C7C5)
