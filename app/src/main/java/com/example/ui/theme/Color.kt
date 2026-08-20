package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush

// ============================================================================
// Drishti palette — Gemini
//
// The previous pass used Material's neutral greys, which read as flat and
// washed out. Gemini's look is not "grey plus an accent": the surfaces are
// darker and cooler (blue-tinted, never neutral), the text is pure white
// rather than off-white, and colour arrives as one vivid multi-stop gradient
// instead of a single flat hue.
//
// Three things are held over from the accessibility pass and should not be
// traded away for looks:
//  * Text stays pure white on near-black (~18:1).
//  * Red is reserved for emergencies, so red always means danger.
//  * The gradient decorates; it never carries meaning on its own.
// ============================================================================

/** Near-black with a cool cast, so surfaces above it never read as neutral grey. */
val BackgroundDark = Color(0xFF0D0E11)

/** The layer above the ground: bars and sheets. */
val SurfaceDark = Color(0xFF16181D)

/** Cards and rows — blue-tinted rather than neutral. */
val SurfaceCardDark = Color(0xFF1C1F26)

/** Pressed / hovered card state. */
val SurfaceCardElevated = Color(0xFF252932)

// ---- The Gemini gradient --------------------------------------------------
// Blue → violet → coral. This is the signature, and the reason the UI reads as
// Gemini rather than as a generic dark theme.

val GeminiBlue = Color(0xFF4285F4)
val GeminiViolet = Color(0xFF9B72CB)
val GeminiCoral = Color(0xFFD96570)

/** Full three-stop sweep, for the orb and hero elements. */
val AccentGradient = Brush.linearGradient(
    colors = listOf(GeminiBlue, GeminiViolet, GeminiCoral)
)

/** Shorter two-stop sweep, for borders and smaller fills. */
val AccentGradientSoft = Brush.linearGradient(
    colors = listOf(GeminiBlue, GeminiViolet)
)

// ---- Solid accents --------------------------------------------------------

/** Primary accent where a single colour is needed. ~8.9:1 on the ground. */
val AccentPrimary = Color(0xFFA8C7FA)

/** Container fill behind primary actions. */
val AccentSecondary = Color(0xFF4285F4)

/** Muted supporting accent. */
val AccentTertiary = Color(0xFF9B72CB)

/** Caution — attention needed, but not an emergency. */
val AccentWarn = Color(0xFFF9CC72)

/** Dark ink for text and icons sitting ON a pale accent. */
val ButtonBlack = Color(0xFF0A1633)

// ---- State ----------------------------------------------------------------

/** Emergency only, and the sole red in the palette. */
val EmergencyRed = Color(0xFFFF5449)

/** Confirmation. */
val SafeGreen = Color(0xFF6DD58C)

/** Inactive controls and disabled text. */
val IdleGray = Color(0xFF8A8F9A)

// ---- Text -----------------------------------------------------------------

/** Pure white, ~18:1. Off-white was a large part of why this looked washed out. */
val TextPrimaryDark = Color(0xFFFFFFFF)

/** ~9:1 with a faint cool cast to match the surfaces. */
val TextSecondaryDark = Color(0xFFC5C9D3)
