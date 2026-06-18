package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.UserEntity
import com.example.ui.theme.*

@Composable
fun LandingScreen(
    userProfile: UserEntity?,
    onNavigateToOnboarding: () -> Unit,
    onNavigateToDashboard: () -> Unit
) {
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .verticalScroll(scrollState)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        // High-Contrast Glowing Ambient Backdrop
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .drawBehind {
                    val brush = Brush.radialGradient(
                        colors = listOf(
                            PrimarySafetyYellow.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, 80.dp.toPx()),
                        radius = 220.dp.toPx()
                    )
                    drawRect(brush)
                }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(28.dp))

            // Pulse-animated Golden Radar Identity Icon
            val infiniteTransition = rememberInfiniteTransition(label = "RadarIcon")
            val iconScale by infiniteTransition.animateFloat(
                initialValue = 0.95f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "Scale"
            )

            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(SurfaceDark)
                    .drawBehind {
                        drawCircle(
                            color = PrimarySafetyYellow.copy(alpha = 0.08f),
                            radius = (48.dp * iconScale).toPx()
                        )
                    }
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = "Drishti Eye Branding Logo",
                    tint = PrimarySafetyYellow,
                    modifier = Modifier.size(54.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Headline
            Text(
                text = "Drishti",
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                color = PrimarySafetyYellow,
                textAlign = TextAlign.Center,
                letterSpacing = 1.sp
            )

            Text(
                text = "AI Vision Assistance for the Blind",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            Text(
                text = "Voice-powered safety, navigation, and artificial intelligence designed exclusively for visually impaired users.",
                fontSize = 15.sp,
                color = TextSecondaryDark,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Glowing Tactile Core Action Button
            Button(
                onClick = {
                    if (userProfile?.onboardingCompleted == true) {
                        onNavigateToDashboard()
                    } else {
                        onNavigateToOnboarding()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .testTag("launch_drishti_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimarySafetyYellow,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(2.dp, Color.White)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (userProfile?.onboardingCompleted == true) Icons.Default.PlayArrow else Icons.Default.ArrowForward,
                        contentDescription = "Start Assistant Action",
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (userProfile?.onboardingCompleted == true) "Launch Drishti Eye" else "Get Started (Onboard)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Subheader: Features list
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "ACCESSIBILITY FEATURES READY",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SecondaryTactileCyan,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                FeatureCard(
                    icon = Icons.Default.Mic,
                    title = "Voice-Powered Navigation",
                    description = "Command the system naturally. Ask Where Am I or route to destinations in real-time."
                )

                FeatureCard(
                    icon = Icons.Default.PhotoCamera,
                    title = "AI Scene Description",
                    description = "Transmits photos to Gemini to describe local obstacles, vehicles, signs, and immediate hazards."
                )

                FeatureCard(
                    icon = Icons.Default.Warning,
                    title = "Guardian SOS Engine",
                    description = "A large red button that compiles GPS links and simulates notifications to guardian contacts immediately."
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun FeatureCard(
    icon: ImageVector,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SurfaceCardDark)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PrimarySafetyYellow.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = PrimarySafetyYellow,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = TextSecondaryDark,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
