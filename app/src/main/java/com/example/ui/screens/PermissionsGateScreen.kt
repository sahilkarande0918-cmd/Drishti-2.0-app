package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.*

@Composable
fun PermissionsGateScreen(
    onPermissionsApproved: () -> Unit,
    onSpeakMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Query immediate system state
    var hasCamera by remember { mutableStateOf(false) }
    var hasMic by remember { mutableStateOf(false) }
    var hasLocation by remember { mutableStateOf(false) }

    fun refreshState() {
        hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        refreshState()
        val allGranted = result.values.all { it }
        if (allGranted) {
            onSpeakMessage("System initialized successfully. Opening google workspace login.")
            onPermissionsApproved()
        } else {
            // Give vocal guidance on what was left
            val missing = mutableListOf<String>()
            if (result[Manifest.permission.CAMERA] != true) missing.add("camera lens")
            if (result[Manifest.permission.RECORD_AUDIO] != true) missing.add("microphone audio")
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] != true) missing.add("GPS navigation")
            
            if (missing.isNotEmpty()) {
                onSpeakMessage("Some sensor nodes are remaining: ${missing.joinToString(" and ")}.")
            } else {
                onPermissionsApproved()
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshState()
        val alreadyGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        
        if (alreadyGranted) {
            onPermissionsApproved()
        } else {
            onSpeakMessage(
                "Welcome to Drishti. System initialization pending. " +
                "Drishti requires camera, microphone, and location services to assist your travel and vision. " +
                "Please tap the initialize button to approve permission nodes."
            )
            permissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        // High-Contrast Glowing Tech Backdrop
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            colors = listOf(
                                PrimarySafetyYellow.copy(alpha = 0.12f),
                                Color.Transparent
                            )
                        )
                    )
                }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Tech Header (Double typography pairing with futuristic diagonal lines)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "DRISHTI EYE",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    lineHeight = 32.sp,
                    letterSpacing = 1.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "INITIALIZATION",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = PrimarySafetyYellow,
                        lineHeight = 32.sp,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "//////////",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = PrimarySafetyYellow.copy(alpha = 0.85f),
                        letterSpacing = (-2).sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "System Peripheral Requirements",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Text(
                text = "To act as your assistant, Drishti initializes physical sensors to perceive nearby environments, hear vocal requests, and locate GPS paths.",
                fontSize = 14.sp,
                color = TextSecondaryDark,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp, start = 12.dp, end = 12.dp)
            )

            // Dynamic Sensor Checklist Blocks
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PermissionItemCard(
                    icon = Icons.Default.CameraAlt,
                    title = "AI CAMERA SENSOR",
                    description = "Feeds images to Gemini models to scan objects, read labels, and notice local hazards.",
                    isGranted = hasCamera
                )

                PermissionItemCard(
                    icon = Icons.Default.Mic,
                    title = "MICROPHONE ENGINE",
                    description = "Captures clear vocal frequencies to run continuous tactile speech commands offline.",
                    isGranted = hasMic
                )

                PermissionItemCard(
                    icon = Icons.Default.MyLocation,
                    title = "SATELLITE COMPASS GPS",
                    description = "Determines surrounding walking steps, address codes, and dynamic compass maps.",
                    isGranted = hasLocation
                )
            }

            Spacer(modifier = Modifier.height(30.dp))

            // Primary Activation Interactive Row (Touch Target 64dp)
            Button(
                onClick = {
                    permissionsLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .testTag("request_onboarding_permissions_btn"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimarySafetyYellow,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, Color.White)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Trigger Authorization Dials",
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "INITIALIZE ALL PLATFORMS",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Skip / Bypass Button if already accepted or wanting login fallback layout
            val isReady = hasCamera && hasMic && hasLocation
            TextButton(
                onClick = {
                    onPermissionsApproved()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("permissions_skip_btn")
            ) {
                Text(
                    text = if (isReady) "CONTINUE TO LOGIN →" else "PROCEED ANYWAY WITH LIMITED FEATURES",
                    color = if (isReady) SecondaryTactileCyan else TextSecondaryDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun PermissionItemCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(
            width = if (isGranted) 1.5.dp else 1.dp,
            color = if (isGranted) Color(0xFF7EFF22) else Color.White.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isGranted) Color(0xFF7EFF22) else PrimarySafetyYellow,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1.0f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = TextSecondaryDark,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // High-Contrast status badge matching EV Cockpit architecture
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isGranted) Color(0xFF7EFF22).copy(alpha = 0.15f)
                        else Color.White.copy(alpha = 0.06f)
                    )
                    .border(
                        BorderStroke(
                            width = 1.dp,
                            color = if (isGranted) Color(0xFF7EFF22) else Color.White.copy(alpha = 0.15f)
                        ),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isGranted) "GRANTED" else "PENDING",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isGranted) Color(0xFF7EFF22) else Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}
