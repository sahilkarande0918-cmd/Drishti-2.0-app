package com.example.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Flat tonal container, the Material 3 / Lookout way of showing elevation.
 *
 * This replaced a frosted-glass panel. Translucent fills and sheens look good over a
 * photo, but they put text on an unpredictable background: contrast then depends on
 * whatever happens to be behind the panel, which is exactly the thing a low-vision reader
 * cannot afford. Material 3 signals depth with a lighter opaque fill instead, so contrast
 * is fixed and known.
 *
 * [strong] picks the higher of the two container tones.
 */
fun Modifier.tonalSurface(
    shape: Shape = RoundedCornerShape(20.dp),
    strong: Boolean = false
): Modifier = this
    .clip(shape)
    .background(if (strong) SurfaceCardElevated else SurfaceCardDark, shape)

/**
 * Same container with a thin cyan hairline — the techy edge treatment. On a pitch-black
 * ground a single crisp line reads as machined precision, where a soft glow would smear.
 */
fun Modifier.outlinedTonalSurface(
    shape: Shape = RoundedCornerShape(16.dp),
    strong: Boolean = false
): Modifier = this
    .tonalSurface(shape, strong)
    .border(BorderStroke(1.dp, AccentGradientSoft), shape)

/** Hairline-only panel (no fill) — for chrome that should feel drawn on the black itself. */
fun Modifier.hairlineSurface(
    shape: Shape = RoundedCornerShape(16.dp)
): Modifier = this
    .clip(shape)
    .border(BorderStroke(1.dp, IdleGray.copy(alpha = 0.6f)), shape)
