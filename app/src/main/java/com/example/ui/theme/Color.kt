package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush

// Elegant Dark cohesive design colors (Gemini Theme)
val BackgroundDark = Color(0xFF090A0E) // Very dark, near-black slate background
val SurfaceDark = Color(0xFF0D0E12) // Deep slate surface
val SurfaceCardDark = Color(0xFF13151A) // Sleek card gray-slate

// Gemini signature accent gradient colors
val GeminiBlue = Color(0xFF078EFA) // Luminous Blue
val GeminiPurple = Color(0xFFAD89EB) // Warm Purple
val GeminiGold = Color(0xFFFBBC04) // Gold

// Primary Accent Gradient Brush (Blue to Purple)
val GeminiGradient = Brush.linearGradient(
    colors = listOf(GeminiBlue, GeminiPurple)
)

// Secondary Accent (UI)
val DeepBlueButton = Color(0xFF010304) // Deep Blue for buttons and active elements

// Legacy definitions mapped strictly to Gemini Blue and Purple
val PrimarySafetyYellow = GeminiBlue
val SecondaryTactileCyan = GeminiPurple
val TertiaryAmber = GeminiPurple

// Emergency Controls & Status Indicators
val EmergencyCrimson = Color(0xFFE53935)
val SafeGreen = GeminiPurple
val IdleGray = Color(0xFF49454F)

val TextPrimaryDark = Color(0xFFFFFFFF) // Luminous White text
val TextSecondaryDark = Color(0xFF94A3B8) // Soft slate gray secondary text



