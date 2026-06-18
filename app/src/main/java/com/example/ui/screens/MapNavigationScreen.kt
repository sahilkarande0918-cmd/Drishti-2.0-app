package com.example.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.example.data.api.OsrmStep
import com.example.ui.theme.*
import com.example.ui.viewmodel.DrishtiViewModel
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MapNavigationScreen(
    currentLatitude: Double,
    currentLongitude: Double,
    currentAddress: String,
    selectedDestination: String,
    activeSteps: List<OsrmStep>,
    currentStepIndex: Int,
    isNavigating: Boolean,
    isCalculating: Boolean,
    onSearchAndNavigate: (String) -> Unit,
    onNextStep: () -> Unit,
    onPrevStep: () -> Unit,
    onStopNavigation: () -> Unit,
    onSpeakCurrentLocation: () -> Unit,
    viewModel: DrishtiViewModel
) {
    var searchQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    // Walkthrough simulation state
    var simulatedLat by remember { mutableStateOf(currentLatitude) }
    var simulatedLng by remember { mutableStateOf(currentLongitude) }

    var startLat by remember { mutableStateOf(currentLatitude) }
    var startLng by remember { mutableStateOf(currentLongitude) }

    // Sync simulated coordinates with real coordinates when not active
    LaunchedEffect(currentLatitude, currentLongitude, isNavigating) {
        if (!isNavigating) {
            simulatedLat = currentLatitude
            simulatedLng = currentLongitude
            startLat = currentLatitude
            startLng = currentLongitude
        }
    }

    // Rotational Sweep animation
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val angleSweep by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Angle"
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
                .padding(24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "REAL-TIME 3D NAVIGATION",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimarySafetyYellow,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = "Tactile Route Guidance",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }
            }

            // Current Coordinates Overview Header
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, SurfaceCardDark)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PrimarySafetyYellow.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = null,
                            tint = PrimarySafetyYellow,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "CURRENT GPS LOCATION",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SecondaryTactileCyan
                        )
                        Text(
                            text = currentAddress,
                            fontSize = 14.sp,
                            color = Color.White,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Text(
                            text = "Coordinates: ${String.format("%.5f", simulatedLat)}, ${String.format("%.5f", simulatedLng)}",
                            fontSize = 11.sp,
                            color = TextSecondaryDark,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            // Saved Places Memory Section
            val savedPlacesList by viewModel.savedPlaces.collectAsState()
            
            if (savedPlacesList.isNotEmpty()) {
                Text(
                    text = "A.I. MEMORY OF PLACES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimarySafetyYellow,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    savedPlacesList.forEach { place ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.navigateToSavedPlace(place)
                                },
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                            border = BorderStroke(1.dp, GeminiPurple.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Place,
                                            contentDescription = null,
                                            tint = GeminiPurple,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = place.name.uppercase(),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                    
                                    IconButton(
                                        onClick = {
                                            viewModel.navigateToSavedPlace(place)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Navigation,
                                            contentDescription = "Navigate to ${place.name}",
                                            tint = PrimarySafetyYellow
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = place.address,
                                    fontSize = 12.sp,
                                    color = TextSecondaryDark
                                )
                                
                                if (place.description.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = BackgroundDark.copy(alpha = 0.6f)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Visibility,
                                                    contentDescription = null,
                                                    tint = SecondaryTactileCyan,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "Visual Memory",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = SecondaryTactileCyan
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = place.description,
                                                fontSize = 12.sp,
                                                color = Color.White,
                                                lineHeight = 16.sp
                                            )
                                            if (place.landmarks.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "Landmarks: ${place.landmarks}",
                                                    fontSize = 11.sp,
                                                    color = TextSecondaryDark,
                                                    lineHeight = 14.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Search input field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search location to navigate...") },
                placeholder = { Text("e.g. Central Market, Pune") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = PrimarySafetyYellow) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("destination_search_input"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (searchQuery.isNotBlank()) {
                        onSearchAndNavigate(searchQuery)
                        focusManager.clearFocus()
                    }
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = PrimarySafetyYellow,
                    unfocusedBorderColor = SurfaceCardDark,
                    focusedLabelColor = PrimarySafetyYellow,
                    unfocusedLabelColor = TextSecondaryDark
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Location actions button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { onSpeakCurrentLocation() },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("voice_geocode_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceDark,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PrimarySafetyYellow)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = PrimarySafetyYellow)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Where Am I?", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                Button(
                    onClick = {
                        if (searchQuery.isNotBlank()) {
                            onSearchAndNavigate(searchQuery)
                            focusManager.clearFocus()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("route_navigate_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimarySafetyYellow,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DirectionsWalk, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Calculate Walk", fontWeight = FontWeight.Black, fontSize = 13.sp)
                    }
                }
            }

            if (isCalculating) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = PrimarySafetyYellow, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Calculating optimal route segments...", color = PrimarySafetyYellow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            // 3D Spatial Guidance Map Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .padding(vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Virtual3DMap(
                        simulatedLat = simulatedLat,
                        simulatedLng = simulatedLng,
                        startLat = startLat,
                        startLng = startLng,
                        destLat = viewModel.destinationLatitude,
                        destLng = viewModel.destinationLongitude,
                        isNavigating = isNavigating,
                        angleSweep = angleSweep,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Live Navigation Steps and walkthrough
            if (isNavigating && activeSteps.isNotEmpty()) {
                val step = activeSteps[currentStepIndex]
                val maneuverText = (step.maneuver?.instruction ?: "Walk straight ahead.").uppercase()
                
                // Left/Right Direction Arrows graphics based on command
                val directionIcon = when {
                    maneuverText.contains("LEFT", ignoreCase = true) -> Icons.Default.ArrowBack
                    maneuverText.contains("RIGHT", ignoreCase = true) -> Icons.Default.ArrowForward
                    maneuverText.contains("TURN", ignoreCase = true) -> Icons.Default.Refresh
                    else -> Icons.Default.ArrowUpward
                }

                // Header Street Bar
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PinDrop,
                            contentDescription = null,
                            tint = PrimarySafetyYellow,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) searchQuery.uppercase() else "ROUTE MANEUVER CRITICAL SEGMENT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // Giant Cockpit Direction Panel (Screen 2 matching architecture)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.5.dp, PrimarySafetyYellow),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Massive Neon Green arrow circle representation on the left
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(PrimarySafetyYellow),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = directionIcon,
                                    contentDescription = "HUD Direction Arrow",
                                    tint = Color.Black,
                                    modifier = Modifier.size(38.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(20.dp))

                            Column {
                                Text(
                                    text = maneuverText,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    lineHeight = 22.sp
                                )
                                Text(
                                    text = "for ${step.distance?.toInt() ?: 200} meters",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimarySafetyYellow,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                 val remainingSteps = activeSteps.drop(currentStepIndex)
                                 val totalRemainingDistance = remainingSteps.sumOf { it.distance ?: 0.0 }
                                 val totalRemainingDurationSeconds = totalRemainingDistance / 1.4
                                 val minutesLeft = (totalRemainingDurationSeconds / 60).toInt()
                                 val secondsLeft = (totalRemainingDurationSeconds % 60).toInt()
                                 val timeFormatted = String.format("%02d:%02d", minutesLeft, secondsLeft)

                                 // Calculate exact straight-line distance from current location to destination using Haversine formula
                                 val dLat = Math.toRadians(viewModel.destinationLatitude - simulatedLat)
                                 val dLng = Math.toRadians(viewModel.destinationLongitude - simulatedLng)
                                 val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                                         kotlin.math.cos(Math.toRadians(simulatedLat)) * kotlin.math.cos(Math.toRadians(viewModel.destinationLatitude)) *
                                         kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
                                 val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
                                 val directDistanceMeters = 6371000.0 * c

                                 Text(
                                     text = "TIME LEFT: $timeFormatted | PATH: ${String.format("%.2f", totalRemainingDistance / 1000.0)} KM | DIRECT: ${String.format("%.2f", directDistanceMeters / 1000.0)} KM",
                                     fontSize = 10.sp,
                                     fontWeight = FontWeight.Bold,
                                     color = TextSecondaryDark,
                                     modifier = Modifier.padding(top = 4.dp)
                                 )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Simulation / progression controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (currentStepIndex > 0) {
                                        simulatedLat -= 0.0006
                                        simulatedLng -= 0.0006
                                    }
                                    onPrevStep()
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardDark),
                                enabled = currentStepIndex > 0,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Prev", tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("PREV NODE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    simulatedLat += 0.0006
                                    simulatedLng += 0.0006
                                    onNextStep()
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardDark),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("NEXT NODE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.Default.ArrowForward, contentDescription = "Next", tint = Color.White)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Finish assistance button (glorious neon rectangular button representing the big bottom button in Screen 2)
                        Button(
                            onClick = { onStopNavigation() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimarySafetyYellow,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "FINISH ASSISTANCE & TERMINATE WALK",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Screen 3: Upcoming Steps detail list panel (listed cleanly under the active map directions)
                Text(
                    text = "UPCOMING ROUTE SEGMENTS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimarySafetyYellow,
                    letterSpacing = 1.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp)
                )

                activeSteps.drop(currentStepIndex + 1).take(3).forEachIndexed { index, futureStep ->
                    val nextManeuver = (futureStep.maneuver?.instruction ?: "Proceed forward").uppercase()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${String.format("%.1f", (futureStep.distance ?: 120.0) / 1000.0)} KM",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    color = PrimarySafetyYellow
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = nextManeuver,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "Node Intersection #${currentStepIndex + index + 2}",
                                        fontSize = 9.sp,
                                        color = TextSecondaryDark
                                    )
                                }
                            }

                            // Tactile speaker icon matching Screen 3's phone/alert shortcut buttons
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceCardDark)
                                    .clickable {
                                        viewModel.speakCurrentLocation()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = "Speak next block directions",
                                    tint = SecondaryTactileCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // INDOOR NO-GPS NAVIGATION (Feature 6)
            // ==========================================
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, if (viewModel.isIndoorMode) SecondaryTactileCyan else SurfaceCardDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(if (viewModel.isIndoorMode) SecondaryTactileCyan.copy(alpha = 0.15f) else Color.DarkGray),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = if (viewModel.isIndoorMode) SecondaryTactileCyan else Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Indoor Navigation Mode",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "WiFi Beacons + Accelerometer Tracker",
                                    fontSize = 11.sp,
                                    color = TextSecondaryDark
                                )
                            }
                        }
                        Switch(
                            checked = viewModel.isIndoorMode,
                            onCheckedChange = { viewModel.toggleIndoorNavigationMode() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PrimarySafetyYellow,
                                checkedTrackColor = SecondaryTactileCyan
                            )
                        )
                    }

                    if (viewModel.isIndoorMode) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = BackgroundDark),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color.Green)
                                    )
                                    Text(
                                        text = "Position Estimator: Triangulated Signal Solid",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Green
                                    )
                                }
                                Text(
                                    text = "Steps: 14 steps taken | Current Room: Conference Room B floor area",
                                    fontSize = 12.sp,
                                    color = Color.White,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // PERSONAL LANDMARK LEARNING (Feature 10)
            // ==========================================
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, SecondaryTactileCyan.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = PrimarySafetyYellow,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "A.I. Landmark Learning",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    var newLandmarkName by remember { mutableStateOf("") }

                    OutlinedTextField(
                        value = newLandmarkName,
                        onValueChange = { newLandmarkName = it },
                        label = { Text("Landmark Name (e.g. Office Desk)", color = TextSecondaryDark) },
                        placeholder = { Text("Describe coordinates label to pin", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SecondaryTactileCyan,
                            unfocusedBorderColor = SurfaceCardDark,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (newLandmarkName.isNotBlank()) {
                                viewModel.learnPersonalLandmarkTag(newLandmarkName)
                                newLandmarkName = ""
                                focusManager.clearFocus()
                            }
                        })
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (newLandmarkName.isNotBlank()) {
                                viewModel.learnPersonalLandmarkTag(newLandmarkName)
                                newLandmarkName = ""
                                focusManager.clearFocus()
                            } else {
                                viewModel.learnPersonalLandmarkTag("Elevator Entrance Doorway")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimarySafetyYellow),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text("Record Current Coordinates", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    if (viewModel.learnedLandmarks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Learned Customized Landmark Pins:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimarySafetyYellow
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        viewModel.learnedLandmarks.take(4).forEach { landmark ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = BackgroundDark.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.PinDrop, contentDescription = null, tint = SecondaryTactileCyan, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(landmark.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text("Lat: ${landmark.latitude} | Lng: ${landmark.longitude}", fontSize = 10.sp, color = TextSecondaryDark)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(120.dp))
        }
    }
}

@Composable
fun Virtual3DMap(
    simulatedLat: Double,
    simulatedLng: Double,
    startLat: Double,
    startLng: Double,
    destLat: Double,
    destLng: Double,
    isNavigating: Boolean,
    angleSweep: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "MapGridAnim")
    
    // Smooth grid scroll phase based on constant animation
    val gridScrollPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ScrollPhase"
    )

    // Pulse animation for markers
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = EaseOutQuad),
            repeatMode = RepeatMode.Restart
        ),
        label = "MarkerPulse"
    )

    // Math to normalize relative offsets and calculate walk progress fraction
    val totalDistance = kotlin.math.abs(destLat - startLat) + kotlin.math.abs(destLng - startLng)
    val currentDistance = kotlin.math.abs(simulatedLat - startLat) + kotlin.math.abs(simulatedLng - startLng)
    val progressFraction = if (totalDistance > 0.0) {
        (currentDistance / totalDistance).coerceIn(0.0, 1.0).toFloat()
    } else {
        0.0f
    }

    // Animate progress fraction for smooth camera transition
    val animatedProgressFraction by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = tween(durationMillis = 1800, easing = LinearOutSlowInEasing),
        label = "CameraProgress"
    )

    // Define walking route street segments
    val userX: Float
    val userZ: Float
    val targetHeading: Float
    
    if (isNavigating) {
        if (animatedProgressFraction <= 0.35f) {
            val s = animatedProgressFraction / 0.35f
            userX = 0f
            userZ = 1.0f + 1.5f * s
            targetHeading = 0f
        } else if (animatedProgressFraction <= 0.65f) {
            val s = (animatedProgressFraction - 0.35f) / 0.30f
            userX = 0.8f * s
            userZ = 2.5f
            targetHeading = 90f
        } else {
            val s = (animatedProgressFraction - 0.65f) / 0.35f
            userX = 0.8f
            userZ = 2.5f + 1.7f * s
            targetHeading = 0f
        }
    } else {
        userX = 0f
        userZ = 2.5f
        targetHeading = 0f
    }

    // Animate camera heading rotation smoothly at intersections
    val animatedHeading by animateFloatAsState(
        targetValue = if (isNavigating) targetHeading else 20f,
        animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
        label = "CameraHeading"
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val horizonY = 0f
        val groundHeight = height

        // Camera positioning coordinates based on view mode
        val radH = Math.toRadians(animatedHeading.toDouble()).toFloat()
        val cosH = kotlin.math.cos(radH)
        val sinH = kotlin.math.sin(radH)
        
        val camX: Float
        val camZ: Float
        
        if (isNavigating) {
            // Standard tracking mode: camera stays 0.45 units behind the user
            camX = userX - sinH * 0.45f
            camZ = userZ - cosH * 0.45f
        } else {
            // Standby map view centered around starting coordinates
            camX = 0f
            camZ = 1.0f
        }

        // Professional 2.5D oblique top-down projection engine
        fun project3DWithHeight(x: Float, y: Float, z: Float): Offset {
            val dx = x - camX
            val dz = z - camZ
            
            // Yaw angle heading rotation around camera
            val rotX = dx * cosH - dz * sinH
            val rotZ = dz * cosH + dx * sinH
            
            // Map scale factor: size of 1 unit in pixels
            val mapScale = width * 0.42f
            
            val centerX = width / 2f
            val centerY = if (isNavigating) height * 0.72f else height * 0.5f
            
            val screenX = centerX + rotX * mapScale
            val screenY = centerY - rotZ * mapScale
            
            // Shift upwards on screen for 3D heights (2.5D oblique offset)
            val heightScale = width * 0.15f
            return Offset(screenX, screenY - y * heightScale)
        }

        fun project3D(x: Float, z: Float): Offset {
            return project3DWithHeight(x, 0f, z)
        }

        // Polygon drawing helpers
        val polyPath = androidx.compose.ui.graphics.Path()
        
        fun drawFilledPolygon(points: List<Offset>, color: Color) {
            if (points.size < 3) return
            polyPath.reset()
            polyPath.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                polyPath.lineTo(points[i].x, points[i].y)
            }
            polyPath.close()
            drawPath(path = polyPath, color = color)
        }

        fun drawFilledPolygonBorder(points: List<Offset>, color: Color) {
            if (points.size < 2) return
            for (i in 0 until points.size) {
                val next = (i + 1) % points.size
                drawLine(color, points[i], points[next], 0.8.dp.toPx())
            }
        }

        // 1. Draw clean slate-dark background
        drawRect(
            color = Color(0xFF14151B), // Solid slate dark
            size = size
        )

        // 2. Draw ground street grid layout lines
        for (gx in -4..4) {
            drawLine(
                color = Color(0xFF2A2C38),
                start = project3D(gx * 0.5f, 0.5f),
                end = project3D(gx * 0.5f, 5.5f),
                strokeWidth = 0.8.dp.toPx()
            )
        }
        for (gz in 1..11) {
            drawLine(
                color = Color(0xFF2A2C38),
                start = project3D(-2.0f, gz * 0.5f),
                end = project3D(2.0f, gz * 0.5f),
                strokeWidth = 0.8.dp.toPx()
            )
        }

        // 3. Draw Route Ribbon (Start to Destination)
        fun drawNavigationRibbon(xa: Float, za: Float, xb: Float, zb: Float) {
            val dx = xb - xa
            val dz = zb - za
            val len = kotlin.math.sqrt(dx * dx + dz * dz)
            if (len == 0f) return
            val pw = 0.035f
            val nx = -dz / len * pw
            val nz = dx / len * pw
            
            val p0 = project3D(xa - nx, za - nz)
            val p1 = project3D(xa + nx, za + nz)
            val p2 = project3D(xb + nx, zb + nz)
            val p3 = project3D(xb - nx, zb - nz)
            
            drawFilledPolygon(listOf(p0, p1, p2, p3), Color(0xFF1A73E8))
            val centerStart = project3D(xa, za)
            val centerEnd = project3D(xb, zb)
            drawLine(Color(0xFF8AB4F8), centerStart, centerEnd, 1.5.dp.toPx())
        }

        if (isNavigating) {
            val routeProg = animatedProgressFraction
            // Start to current position (completed ribbon)
            if (routeProg <= 0.35f) {
                drawNavigationRibbon(0.0f, 1.0f, userX, userZ)
            } else {
                drawNavigationRibbon(0.0f, 1.0f, 0.0f, 2.5f)
                if (routeProg <= 0.65f) {
                    drawNavigationRibbon(0.0f, 2.5f, userX, userZ)
                } else {
                    drawNavigationRibbon(0.0f, 2.5f, 0.8f, 2.5f)
                    drawNavigationRibbon(0.8f, 2.5f, userX, userZ)
                }
            }
            // Remaining path segments (uncompleted ribbon)
            if (routeProg < 0.35f) {
                drawNavigationRibbon(userX, userZ, 0.0f, 2.5f)
                drawNavigationRibbon(0.0f, 2.5f, 0.8f, 2.5f)
                drawNavigationRibbon(0.8f, 2.5f, 0.8f, 4.2f)
            } else if (routeProg < 0.65f) {
                drawNavigationRibbon(userX, userZ, 0.8f, 2.5f)
                drawNavigationRibbon(0.8f, 2.5f, 0.8f, 4.2f)
            } else {
                drawNavigationRibbon(userX, userZ, 0.8f, 4.2f)
            }
        }

        // 4. Draw 3D Building Blocks (solid dark slate-gray blocks)
        fun drawProfessionalBuilding(cx: Float, cz: Float, w: Float, d: Float, h: Float) {
            val hw = w / 2f
            val hd = d / 2f
            
            val g0 = project3D(cx - hw, cz - hd)
            val g1 = project3D(cx + hw, cz - hd)
            val g2 = project3D(cx + hw, cz + hd)
            val g3 = project3D(cx - hw, cz + hd)
            
            val r0 = project3DWithHeight(cx - hw, h, cz - hd)
            val r1 = project3DWithHeight(cx + hw, h, cz - hd)
            val r2 = project3DWithHeight(cx + hw, h, cz + hd)
            val r3 = project3DWithHeight(cx - hw, h, cz + hd)
            
            val outlineColor = Color(0xFF4E5260)
            
            // Roof
            drawFilledPolygon(listOf(r0, r1, r2, r3), Color(0xFF252732))
            drawFilledPolygonBorder(listOf(r0, r1, r2, r3), outlineColor)
            
            // Front Face
            drawFilledPolygon(listOf(g0, r0, r1, g1), Color(0xFF333642))
            drawFilledPolygonBorder(listOf(g0, r0, r1, g1), outlineColor)
            
            // Left Face
            drawFilledPolygon(listOf(g0, r0, r3, g3), Color(0xFF1D1F26))
            drawFilledPolygonBorder(listOf(g0, r0, r3, g3), outlineColor)
            
            // Right Face
            drawFilledPolygon(listOf(g1, r1, r2, g2), Color(0xFF22242D))
            drawFilledPolygonBorder(listOf(g1, r1, r2, g2), outlineColor)
        }

        // Place buildings along grid blocks
        drawProfessionalBuilding(-0.9f, 1.3f, 0.35f, 0.3f, 0.25f) // Building A
        drawProfessionalBuilding(-0.8f, 2.3f, 0.35f, 0.3f, 0.40f) // Building B
        drawProfessionalBuilding(-0.9f, 3.3f, 0.35f, 0.3f, 0.30f) // Building C
        drawProfessionalBuilding(-0.8f, 4.3f, 0.35f, 0.3f, 0.50f) // Building D
        
        drawProfessionalBuilding(0.9f, 1.5f, 0.35f, 0.3f, 0.35f)  // Building E
        drawProfessionalBuilding(0.8f, 2.5f, 0.35f, 0.3f, 0.45f)  // Building F
        drawProfessionalBuilding(0.9f, 3.5f, 0.35f, 0.3f, 0.30f)  // Building G
        drawProfessionalBuilding(0.8f, 4.5f, 0.35f, 0.3f, 0.40f)  // Building H

        // 5. Render Destination Red Pin
        val pinPt = project3D(0.8f, 4.2f)
        val destScale = 1.0f
        val pinRadius = 7.dp.toPx()
        val pinOffset = 22.dp.toPx()
        val pinTopCenter = Offset(pinPt.x, pinPt.y - pinOffset)
        
        drawCircle(Color.Black.copy(alpha = 0.35f), 4.dp.toPx() * destScale, pinPt)
        
        val pinPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(pinPt.x, pinPt.y)
            lineTo(pinTopCenter.x - pinRadius, pinTopCenter.y)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    pinTopCenter.x - pinRadius,
                    pinTopCenter.y - pinRadius,
                    pinTopCenter.x + pinRadius,
                    pinTopCenter.y + pinRadius
                ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false
            )
            lineTo(pinPt.x, pinPt.y)
            close()
        }
        drawPath(pinPath, Color(0xFFEA4335))
        drawCircle(Color.White, 3.dp.toPx() * destScale, pinTopCenter)

        // 6. Render Start Green Pin
        val startPt = project3D(0.0f, 1.0f)
        val startScale = 1f / 1.0f
        drawCircle(Color(0xFF34A853), 5.dp.toPx() * startScale, startPt)
        drawCircle(Color.White, 2.dp.toPx() * startScale, startPt)

        // 7. Render User Chevron Pointer
        if (isNavigating) {
            val userPt = project3D(userX, userZ)
            val userScale = 1.0f
            
            // Blue radar pulse
            drawCircle(
                color = Color(0xFF1A73E8).copy(alpha = (0.24f * (1f - (pulseRadius - 4f) / 16f)).coerceIn(0f, 0.24f)),
                center = userPt,
                radius = pulseRadius.dp.toPx() * userScale
            )
            
            // Direction beam (field-of-view cone)
            val beamLength = 0.28f
            val beamAngle = 30f
            val coneNose = project3D(userX, userZ)
            val coneLeft = project3D(
                userX + beamLength * kotlin.math.sin(radH - Math.toRadians(beamAngle.toDouble()).toFloat()),
                userZ + beamLength * kotlin.math.cos(radH - Math.toRadians(beamAngle.toDouble()).toFloat())
            )
            val coneRight = project3D(
                userX + beamLength * kotlin.math.sin(radH + Math.toRadians(beamAngle.toDouble()).toFloat()),
                userZ + beamLength * kotlin.math.cos(radH + Math.toRadians(beamAngle.toDouble()).toFloat())
            )
            
            val conePath = androidx.compose.ui.graphics.Path().apply {
                moveTo(coneNose.x, coneNose.y)
                lineTo(coneLeft.x, coneLeft.y)
                lineTo(coneRight.x, coneRight.y)
                close()
            }
            drawPath(conePath, Color(0xFF1A73E8).copy(alpha = 0.15f))
            
            // Chevron pointer mesh
            val cNose = project3DWithHeight(userX, 0f, userZ + 0.04f)
            val cLeft = project3DWithHeight(userX - 0.03f, 0f, userZ - 0.03f)
            val cRight = project3DWithHeight(userX + 0.03f, 0f, userZ - 0.03f)
            val cCenter = project3DWithHeight(userX, 0f, userZ - 0.015f)
            
            drawFilledPolygon(listOf(cNose, cLeft, cCenter), Color(0xFF1A73E8))
            drawFilledPolygon(listOf(cNose, cRight, cCenter), Color(0xFF4285F4))
            drawFilledPolygonBorder(listOf(cNose, cLeft, cCenter), Color.White)
            drawFilledPolygonBorder(listOf(cNose, cRight, cCenter), Color.White)
        }

        // 8. Draw Minimalist Compass Dial in Corner
        val compassCenter = Offset(width - 28.dp.toPx(), 28.dp.toPx())
        val compassRadius = 14.dp.toPx()
        
        drawCircle(
            color = Color(0xFF3A3B45),
            radius = compassRadius,
            center = compassCenter,
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = Color(0x991E1F22),
            radius = compassRadius,
            center = compassCenter
        )
        
        val needleRad = Math.toRadians(animatedHeading.toDouble())
        val needleLen = compassRadius * 0.75f
        val tipNX = compassCenter.x + needleLen * kotlin.math.sin(needleRad).toFloat()
        val tipNY = compassCenter.y - needleLen * kotlin.math.cos(needleRad).toFloat()
        val tipSX = compassCenter.x - needleLen * kotlin.math.sin(needleRad).toFloat()
        val tipSY = compassCenter.y + needleLen * kotlin.math.cos(needleRad).toFloat()
        
        drawLine(Color(0xFFEA4335), compassCenter, Offset(tipNX, tipNY), 2.dp.toPx())
        drawLine(Color(0xFF9E9E9E), compassCenter, Offset(tipSX, tipSY), 2.dp.toPx())
        drawCircle(Color.White, 2.dp.toPx(), compassCenter)

        // 9. Render Floating Text Labels (Pill Caps)
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 20f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
        }
        
        fun drawTextLabel(text: String, pt: Offset, yOffset: Float, bgColor: Color) {
            val rectWidth = textPaint.measureText(text) + 16f
            val rectHeight = 28f
            val rectLeft = pt.x - rectWidth / 2f
            val rectTop = pt.y - yOffset - rectHeight / 2f
            
            drawRoundRect(
                color = bgColor,
                topLeft = Offset(rectLeft, rectTop),
                size = androidx.compose.ui.geometry.Size(rectWidth, rectHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
            drawContext.canvas.nativeCanvas.drawText(text, pt.x, pt.y - yOffset + 6f, textPaint)
        }
        
        drawTextLabel("START", startPt, 12.dp.toPx(), Color(0xE634A853))
        drawTextLabel("DESTINATION", pinPt, 32.dp.toPx() * destScale, Color(0xE6EA4335))
    }
}
