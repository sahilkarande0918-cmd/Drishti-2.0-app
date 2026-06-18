package com.example.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.GuardianEntity
import com.example.data.database.SosAlertEntity
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SosScreen(
    guardian: GuardianEntity?,
    alertsHistory: List<SosAlertEntity>,
    onTriggerSos: () -> Unit,
    onClearHistory: () -> Unit
) {
    // Red pulsate animation for the SOS emergency trigger
    val infiniteTransition = rememberInfiniteTransition(label = "SosPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = "EMERGENCY GUARDIAN PANEL",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = EmergencyCrimson,
                letterSpacing = 1.5.sp
            )

            Text(
                text = "SOS Safety Shield",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = PrimarySafetyYellow,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )

            Text(
                text = "Tapping the giant crimson button below will instantly log your location and alert your registered guardian.",
                fontSize = 13.sp,
                color = TextSecondaryDark,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp)
            )

            // Giant RED Tactile SOS Button
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .testTag("sos_trigger_container"),
                contentAlignment = Alignment.Center
            ) {
                // Pulse waves
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                        .background(EmergencyCrimson.copy(alpha = 0.2f * (2f - pulseScale)))
                )

                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(EmergencyCrimson, Color(0xFFC62828))
                            )
                        )
                        .clickable { onTriggerSos() }
                        .testTag("large_sos_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "S.O.S.",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Guardian contact information display
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, if (guardian != null) SecondaryTactileCyan.copy(alpha = 0.4f) else SurfaceCardDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "ACTIVE EMERGENCY RESPONDER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SecondaryTactileCyan,
                            letterSpacing = 1.sp
                        )
                        Icon(Icons.Default.Shield, contentDescription = null, tint = SecondaryTactileCyan, modifier = Modifier.size(16.dp))
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (guardian != null) {
                        Text(
                            text = guardian.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Email: ${guardian.email}",
                            fontSize = 13.sp,
                            color = TextSecondaryDark,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Text(
                            text = "Phone: ${guardian.phone}",
                            fontSize = 13.sp,
                            color = TextSecondaryDark,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    } else {
                        Text(
                            text = "No guardian registered yet.",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = EmergencyCrimson
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Historical SOS Alerts list from SQLite Database
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "HISTORICAL ALERTS LOG",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimarySafetyYellow,
                    letterSpacing = 1.sp
                )

                if (alertsHistory.isNotEmpty()) {
                    Text(
                        text = "Clear All",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = EmergencyCrimson,
                        modifier = Modifier.clickable { onClearHistory() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (alertsHistory.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 100.dp),
                    color = SurfaceDark,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = IdleGray, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Alert journal is empty.",
                            fontSize = 13.sp,
                            color = TextSecondaryDark,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("sos_logs_list"),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(alertsHistory) { alert ->
                        val dateText = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(alert.createdAt))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                            border = BorderStroke(1.dp, SurfaceCardDark)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(EmergencyCrimson.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.NotificationImportant, contentDescription = null, tint = EmergencyCrimson, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = alert.address,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "GPS: ${alert.latitude}, ${alert.longitude}",
                                        fontSize = 11.sp,
                                        color = TextSecondaryDark,
                                        modifier = Modifier.padding(top = 1.dp)
                                    )
                                }
                                Text(
                                    text = dateText,
                                    fontSize = 10.sp,
                                    color = TextSecondaryDark,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
