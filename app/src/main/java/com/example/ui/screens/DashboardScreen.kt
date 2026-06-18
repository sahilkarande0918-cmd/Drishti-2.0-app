package com.example.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.annotation.SuppressLint
import androidx.core.content.ContextCompat
import com.example.data.database.UserEntity
import com.example.ui.theme.*
import com.example.ui.viewmodel.DrishtiViewModel
import com.example.ui.viewmodel.OrbState
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    userProfile: UserEntity?,
    orbState: OrbState,
    transcript: String,
    onMicTriggered: () -> Unit,
    onSpeechSimulated: (String) -> Unit,
    onNavigateToPage: (String) -> Unit,
    viewModel: DrishtiViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 4 })
    var showVoiceAssistantInteractive by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.scrollPagerEvent.collect { pageIndex ->
            pagerState.animateScrollToPage(pageIndex)
        }
    }

    // Unified infinite sweep/pulse animations
    val infiniteTransition = rememberInfiniteTransition(label = "MainUiAnimations")
    val angleSweep by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "AngleSweepAnim"
    )
    val scanOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ScanlineOffset"
    )

    // Camera hardware handle inside Left Mockup
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraBindingFailed by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    fun capturePhotoAndProcess(onCaptured: (Bitmap) -> Unit) {
        if (!cameraPermissionGranted) {
            val simulated = generateSimulatedBitmap(context, "scene")
            onCaptured(simulated)
            return
        }
        val file = File(context.cacheDir, "temp_capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        onCaptured(bitmap)
                    } else {
                        onCaptured(generateSimulatedBitmap(context, "scene"))
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    onCaptured(generateSimulatedBitmap(context, "scene"))
                }
            }
        )
    }

    LaunchedEffect(Unit) {
        viewModel.capturePhotoForPlaceEvent.collect { placeName ->
            capturePhotoAndProcess { bitmap ->
                viewModel.processCapturedPhotoForPlace(placeName, bitmap)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.capturePhotoForArrivalEvent.collect { place ->
            capturePhotoAndProcess { bitmap ->
                viewModel.processCapturedPhotoForArrival(place, bitmap)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.capturePhotoForProactiveEvent.collect {
            capturePhotoAndProcess { bitmap ->
                viewModel.processCapturedPhotoForProactive(bitmap)
            }
        }
    }

    // Voice-triggered camera capture (reliable event flow, not state-watcher)
    LaunchedEffect(Unit) {
        viewModel.capturePhotoForVoiceAnalyzeEvent.collect { intent ->
            if (pagerState.currentPage != 0) {
                pagerState.animateScrollToPage(0)
                delay(1200)
            }
            var waited = 0
            while (!cameraReady && waited < 8000) {
                delay(300)
                waited += 300
            }
            delay(500)
            if (cameraReady && cameraPermissionGranted) {
                capturePhotoAndProcess { bitmap ->
                    viewModel.runAutoAnalyzeOnBitmap(bitmap, intent)
                }
            } else {
                viewModel.runAutoAnalyzeOnBitmap(generateSimulatedBitmap(context, intent), intent)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
            .drawBehind {
                val canvasWidth = size.width
                val canvasHeight = size.height

                // Glow 1: Top-Left Deep Blue
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(GeminiBlue.copy(alpha = 0.15f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = canvasWidth * 0.9f
                    ),
                    center = Offset(0f, 0f),
                    radius = canvasWidth * 0.9f
                )

                // Glow 2: Center-Right Warm Purple
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(GeminiPurple.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(canvasWidth, canvasHeight * 0.4f),
                        radius = canvasWidth * 1.0f
                    ),
                    center = Offset(canvasWidth, canvasHeight * 0.4f),
                    radius = canvasWidth * 1.0f
                )

                // Glow 3: Bottom-Left Purple
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(GeminiPurple.copy(alpha = 0.08f), Color.Transparent),
                        center = Offset(0f, canvasHeight),
                        radius = canvasWidth * 0.9f
                    ),
                    center = Offset(0f, canvasHeight),
                    radius = canvasWidth * 0.9f
                )
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Main Outer App Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "DRISHTI ASSIST",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = GeminiPurple,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "Hello, ${userProfile?.name ?: "User"}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(DeepBlueButton)
                        .border(BorderStroke(1.dp, GeminiGradient), CircleShape)
                        .clickable { onNavigateToPage("settings") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = GeminiGold,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Three-Phone Composition Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 40.dp),
                pageSpacing = 16.dp
            ) { page ->
                val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                val scale = 1f - (kotlin.math.abs(pageOffset) * 0.12f).coerceIn(0f, 0.15f)
                val alpha = 1f - (kotlin.math.abs(pageOffset) * 0.3f).coerceIn(0f, 0.4f)
                val density = LocalDensity.current
                val translationX = with(density) { pageOffset * 28.dp.toPx() }

                PhoneMockup(
                    modifier = Modifier
                        .graphicsLayer {
                            this.scaleX = scale
                            this.scaleY = scale
                            this.alpha = alpha
                            this.translationX = -translationX
                        }
                ) {
                    when (page) {
                        0 -> MockupVisionScreen(
                            viewModel = viewModel,
                            cameraPermissionGranted = cameraPermissionGranted,
                            cameraBindingFailed = cameraBindingFailed,
                            cameraReady = cameraReady,
                            imageCapture = imageCapture,
                            lifecycleOwner = lifecycleOwner,
                            scanOffset = scanOffset,
                            onUpdateCameraReady = { cameraReady = it },
                            onUpdateCameraFailed = { cameraBindingFailed = it },
                            onCapturePhoto = { capturePhotoAndProcess(it) }
                        )
                        1 -> MockupVoiceAssistantScreen(
                            orbState = orbState,
                            transcript = transcript,
                            angleSweep = angleSweep,
                            onMicTriggered = onMicTriggered,
                            viewModel = viewModel
                        )
                        2 -> MockupNavigationScreen(
                            viewModel = viewModel,
                            angleSweep = angleSweep
                        )
                        3 -> MockupRuViewScreen(
                            viewModel = viewModel,
                            angleSweep = angleSweep
                        )
                    }
                }
            }

            // Interconnected dots indicator
            Row(
                modifier = Modifier
                    .padding(top = 16.dp, bottom = 100.dp)
                    .height(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(4) { index ->
                    val isSelected = pagerState.currentPage == index
                    val indicatorWidth by animateDpAsState(if (isSelected) 24.dp else 8.dp)
                    val indicatorColor = when (index) {
                        0 -> GeminiBlue
                        1 -> GeminiPurple
                        2 -> GeminiBlue
                        else -> SecondaryTactileCyan
                    }
                    Box(
                        modifier = Modifier
                            .size(width = indicatorWidth, height = 8.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) indicatorColor else Color.White.copy(alpha = 0.2f))
                    )
                }
            }
        }

        // Voice Assistant Dialog overlay
        if (showVoiceAssistantInteractive) {
            VoiceAssistantConsoleOverlay(
                orbState = orbState,
                viewModel = viewModel,
                onMicTriggered = onMicTriggered,
                onSpeechSimulated = onSpeechSimulated,
                onNavigateToPage = onNavigateToPage,
                onCloseConsole = { showVoiceAssistantInteractive = false }
            )
        }
    }
}

// ------------------------------------
// Mockup Phone Frame Shell Wrapper
// ------------------------------------
@Composable
fun PhoneMockup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxHeight(0.95f)
            .fillMaxWidth()
            .border(
                border = BorderStroke(
                    width = 2.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.25f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    )
                ),
                shape = RoundedCornerShape(32.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(32.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .background(BackgroundDark, shape = RoundedCornerShape(26.dp))
                .clip(RoundedCornerShape(26.dp))
        ) {
            content()

            // Realistic Device Notch Overlay
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .width(80.dp)
                    .height(18.dp)
                    .background(Color.Black, shape = RoundedCornerShape(9.dp))
            )
        }
    }
}

// ------------------------------------
// PAGER PAGE 0: Vision Mockup Content
// ------------------------------------
@Composable
fun MockupVisionScreen(
    viewModel: DrishtiViewModel,
    cameraPermissionGranted: Boolean,
    cameraBindingFailed: Boolean,
    cameraReady: Boolean,
    imageCapture: ImageCapture,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    scanOffset: Float,
    onUpdateCameraReady: (Boolean) -> Unit,
    onUpdateCameraFailed: (Boolean) -> Unit,
    onCapturePhoto: ((Bitmap) -> Unit) -> Unit
) {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionGranted && !cameraBindingFailed) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().apply {
                                setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner, cameraSelector, preview, imageCapture
                            )
                            onUpdateCameraReady(true)
                        } catch (e: Exception) {
                            onUpdateCameraFailed(true)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val scanY = size.height * scanOffset
                        drawLine(
                            color = GeminiBlue.copy(alpha = 0.5f),
                            start = Offset(0f, scanY),
                            end = Offset(size.width, scanY),
                            strokeWidth = 3.dp.toPx()
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Sensors,
                        contentDescription = null,
                        tint = GeminiBlue.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Vision Viewfinder Ready",
                        color = TextSecondaryDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Gemini scan line HUD guide overlay
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(160.dp, 100.dp)
                .border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.2f)), RoundedCornerShape(12.dp))
        )

        // Mock UI overlay at the bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                    )
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Display analysis description results inside mockup
            val displayResult = when {
                viewModel.isAnalyzing -> "Analyzing Scene surroundings..."
                viewModel.aiDescriptionResult.isNotBlank() -> viewModel.aiDescriptionResult
                viewModel.aiTextReaderResult.isNotBlank() -> viewModel.aiTextReaderResult
                else -> "Visual Radar Standby"
            }
            Text(
                text = displayResult,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Deep Blue click to Scan Button
            Button(
                onClick = {
                    if (!viewModel.isAnalyzing) {
                        onCapturePhoto { bitmap ->
                            viewModel.analyzeSceneSurroundings(bitmap)
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DeepBlueButton),
                border = BorderStroke(1.dp, GeminiGradient),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = GeminiBlue, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("TAP TO SCAN", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ------------------------------------
// PAGER PAGE 1: Center Listening Voice Mockup
// ------------------------------------
@Composable
fun MockupVoiceAssistantScreen(
    orbState: OrbState,
    transcript: String,
    angleSweep: Float,
    onMicTriggered: () -> Unit,
    viewModel: DrishtiViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // App Identity Header Inside Phone screen mockup
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Text(
                text = "GEMINI AGENT",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = GeminiPurple,
                letterSpacing = 2.sp
            )
            Text(
                text = "Voice Assistant",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        }

        val voiceAmp = when (orbState) {
            OrbState.LISTENING -> 0.1f + viewModel.micAmplitude * 0.9f
            OrbState.PROCESSING -> 0.4f + 0.1f * kotlin.math.sin(angleSweep * 0.15f)
            OrbState.EMERGENCY -> 0.7f + 0.3f * kotlin.math.sin(angleSweep * 0.3f)
            OrbState.IDLE -> {
                if (viewModel.isSpeaking) {
                    0.5f + 0.35f * kotlin.math.sin(angleSweep * 0.15f)
                } else {
                    0.08f + 0.02f * kotlin.math.sin(angleSweep * 0.03f)
                }
            }
        }

        val baseScale = when (orbState) {
            OrbState.LISTENING -> 1.0f + viewModel.micAmplitude * 0.2f
            OrbState.PROCESSING -> 1.0f + 0.05f * kotlin.math.sin(angleSweep * 0.12f)
            OrbState.EMERGENCY -> 1.0f + 0.1f * kotlin.math.sin(angleSweep * 0.25f)
            OrbState.IDLE -> {
                if (viewModel.isSpeaking) {
                    1.0f + 0.08f * kotlin.math.sin(angleSweep * 0.15f)
                } else {
                    1.0f + 0.02f * kotlin.math.sin(angleSweep * 0.03f)
                }
            }
        }
        val animatedScale by animateFloatAsState(
            targetValue = baseScale,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "OrbScaleSpring"
        )

        // Float hovering translations
        val floatX = 5.dp * kotlin.math.sin((angleSweep * 0.04f).toDouble()).toFloat()
        val floatY = 8.dp * kotlin.math.cos((angleSweep * 0.03f).toDouble()).toFloat()

        Box(
            modifier = Modifier
                .size(195.dp)
                .graphicsLayer {
                    translationX = floatX.toPx()
                    translationY = floatY.toPx()
                    scaleX = animatedScale
                    scaleY = animatedScale
                }
                .clickable(onClick = onMicTriggered)
                .testTag("voice_orb_tap_target"),
            contentAlignment = Alignment.Center
        ) {
            NativeVoiceOrb(
                orbState = orbState,
                isSpeaking = viewModel.isSpeaking,
                voiceAmp = voiceAmp,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Active State Centered Text Below Torus
        Text(
            text = when (orbState) {
                OrbState.IDLE -> "TAP SPHERE TO CHAT"
                OrbState.LISTENING -> "Listening..."
                OrbState.PROCESSING -> "Thinking..."
                OrbState.EMERGENCY -> "SOS ALERTING"
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            color = when (orbState) {
                OrbState.EMERGENCY -> EmergencyCrimson
                OrbState.LISTENING -> GeminiPurple
                OrbState.PROCESSING -> GeminiBlue
                else -> Color.White
            },
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Transcript Panel Inside Phone mockup
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
            colors = CardDefaults.cardColors(containerColor = DeepBlueButton),
            border = BorderStroke(1.dp, GeminiGradient),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "TRANSCRIPT",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = GeminiPurple,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = transcript.ifBlank { "\"Vocal feedback inactive. Tap above to dictate.\"" },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (transcript.isNotBlank()) Color.White else TextSecondaryDark,
                    lineHeight = 16.sp,
                    maxLines = 3
                )
            }
        }
    }
}

// ------------------------------------
// PAGER PAGE 2: Navigation Mockup Content
// ------------------------------------
@Composable
fun MockupNavigationScreen(
    viewModel: DrishtiViewModel,
    angleSweep: Float
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Mockup Screen header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Text(
                text = "GPS RADAR MAP",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = GeminiPurple,
                letterSpacing = 2.sp
            )
            Text(
                text = viewModel.selectedDestinationName.ifBlank { "Standby Mode" },
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }

        // Compass Radar center element
        Box(
            modifier = Modifier.size(130.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width / 2.2f

                // Outer Compass ring
                drawCircle(
                    color = GeminiBlue.copy(alpha = 0.2f),
                    center = center,
                    radius = radius,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Shifting dynamic compass pointers
                val arrowLen = radius * 0.8f
                val rad = (angleSweep * Math.PI.toFloat() / 180f)
                val tipX = center.x + arrowLen * cos(rad)
                val tipY = center.y + arrowLen * sin(rad)

                drawLine(
                    color = GeminiPurple,
                    start = center,
                    end = Offset(tipX, tipY),
                    strokeWidth = 3.dp.toPx()
                )

                // Central radar sweep point
                drawCircle(
                    color = GeminiPurple,
                    center = center,
                    radius = 6.dp.toPx()
                )
            }
        }

        // Navigation Steps & Distances HUD info
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Distance metrics calculated in real-time
            val simulatedLat = viewModel.currentLatitude
            val simulatedLng = viewModel.currentLongitude
            val destLat = viewModel.destinationLatitude
            val destLng = viewModel.destinationLongitude
            val routeRemaining = if (viewModel.activeNavigationSteps.isNotEmpty()) {
                val stepsLeft = viewModel.activeNavigationSteps.drop(viewModel.currentStepIndex)
                stepsLeft.sumOf { it.distance ?: 0.0 } / 1000.0
            } else 0.0

            // Direct line Haversine distance
            val dLat = Math.toRadians(destLat - simulatedLat)
            val dLng = Math.toRadians(destLng - simulatedLng)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(simulatedLat)) * cos(Math.toRadians(destLat)) *
                    sin(dLng / 2) * sin(dLng / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            val directLineDistance = 6371.0 * c // Earth radius in km

            val directFormatted = String.format("%.2f KM", if (destLat == 0.0) 0.0 else directLineDistance)
            val pathFormatted = String.format("%.2f KM", routeRemaining)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = DeepBlueButton),
                    border = BorderStroke(1.dp, GeminiBlue),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("DIRECT DIST", fontSize = 8.sp, color = TextSecondaryDark, fontWeight = FontWeight.Bold)
                        Text(directFormatted, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = DeepBlueButton),
                    border = BorderStroke(1.dp, GeminiPurple),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("PATH DIST", fontSize = 8.sp, color = TextSecondaryDark, fontWeight = FontWeight.Bold)
                        Text(pathFormatted, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Pulse Emergency SOS Action button
            Button(
                onClick = { viewModel.activateEmergencySOS() },
                colors = ButtonDefaults.buttonColors(containerColor = EmergencyCrimson),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("TRIGGER SOS ALERT", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

// ------------------------------------
// VOICE ASSISTANT INTERACTIVE CONSOLE OVERLAY
// ------------------------------------
@Composable
fun VoiceAssistantConsoleOverlay(
    orbState: OrbState,
    viewModel: DrishtiViewModel,
    onMicTriggered: () -> Unit,
    onSpeechSimulated: (String) -> Unit,
    onNavigateToPage: (String) -> Unit,
    onCloseConsole: () -> Unit
) {
    var inputCommandText by remember { mutableStateOf("") }
    val waveTransition = rememberInfiniteTransition(label = "ConsoleWaveAnim")
    val wavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WavePhase"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.96f))
            .clickable { /* Blocks background clicks */ }
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "DRISHTI A.I. VOICE INTERCOM",
                color = GeminiPurple,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp,
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when (orbState) {
                    OrbState.IDLE -> "SYSTEM ACTIVE & LISTENING"
                    OrbState.LISTENING -> "LISTENING IN PROGRESS..."
                    OrbState.PROCESSING -> "INTERPRETING DIRECTIVES..."
                    OrbState.EMERGENCY -> "EMERGENCY STATE ACTIVE!"
                },
                color = when (orbState) {
                    OrbState.EMERGENCY -> EmergencyCrimson
                    OrbState.LISTENING -> GeminiBlue
                    else -> GeminiPurple
                },
                fontWeight = FontWeight.Black,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            Box(
                modifier = Modifier
                    .size(150.dp)
                    .padding(bottom = 8.dp)
                    .clickable(onClick = onMicTriggered)
                    .testTag("voice_orb_overlay_tap_target"),
                contentAlignment = Alignment.Center
            ) {
                val voiceAmp = when (orbState) {
                    OrbState.LISTENING -> 0.1f + viewModel.micAmplitude * 0.9f
                    OrbState.PROCESSING -> 0.4f + 0.1f * kotlin.math.sin(wavePhase * 0.15f)
                    OrbState.EMERGENCY -> 0.7f + 0.3f * kotlin.math.sin(wavePhase * 0.3f)
                    OrbState.IDLE -> {
                        if (viewModel.isSpeaking) {
                            0.5f + 0.35f * kotlin.math.sin(wavePhase * 0.15f)
                        } else {
                            0.08f + 0.02f * kotlin.math.sin(wavePhase * 0.03f)
                        }
                    }
                }
                NativeVoiceOrb(
                    orbState = orbState,
                    isSpeaking = viewModel.isSpeaking,
                    voiceAmp = voiceAmp,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Spoken text feedback panel
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DeepBlueButton),
                border = BorderStroke(1.dp, GeminiGradient),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "LAST SPOKEN FEEDBACK",
                        color = TextSecondaryDark,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "\"${viewModel.lastSpokenText.ifBlank { "Speak to activate system responses." }}\"",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { viewModel.replayLastSpeech() },
                            colors = ButtonDefaults.buttonColors(containerColor = DeepBlueButton),
                            border = BorderStroke(1.dp, GeminiBlue),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(36.dp)
                                .weight(1f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = GeminiBlue, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Replay", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Button(
                            onClick = {
                                viewModel.stopSpeaking()
                                onMicTriggered()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DeepBlueButton),
                            border = BorderStroke(1.dp, EmergencyCrimson),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(36.dp)
                                .weight(1f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Mic, contentDescription = null, tint = EmergencyCrimson, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Interrupt", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Outlined typing field fallback
            OutlinedTextField(
                value = inputCommandText,
                onValueChange = { inputCommandText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Write command/simulate voice...", color = TextSecondaryDark, fontSize = 13.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    focusedBorderColor = GeminiPurple,
                    unfocusedBorderColor = SurfaceCardDark
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (inputCommandText.isNotBlank()) {
                                onSpeechSimulated(inputCommandText)
                                inputCommandText = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = GeminiPurple)
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Presets Scrollable Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onSpeechSimulated("Where am I") },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Where am I?", color = Color.White, fontSize = 11.sp)
                }
                Button(
                    onClick = {
                        onSpeechSimulated("describe surroundings")
                        onCloseConsole()
                        onNavigateToPage("vision")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Describe Surroundings", color = Color.White, fontSize = 11.sp)
                }
                Button(
                    onClick = {
                        onSpeechSimulated("read text")
                        onCloseConsole()
                        onNavigateToPage("vision")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Read Signs", color = Color.White, fontSize = 11.sp)
                }
                Button(
                    onClick = { onSpeechSimulated("emergency") },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyCrimson.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("SOS Alert", color = Color.White, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Exit Console Button
            Button(
                onClick = onCloseConsole,
                colors = ButtonDefaults.buttonColors(containerColor = DeepBlueButton),
                border = BorderStroke(1.dp, EmergencyCrimson),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = EmergencyCrimson, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("EXIT VOICE INTERCOM", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}

// ------------------------------------
// PAGER PAGE 3: RuView Occupancy Mockup Screen
// ------------------------------------
@Composable
fun MockupRuViewScreen(
    viewModel: DrishtiViewModel,
    angleSweep: Float
) {
    val isScanning = viewModel.isRuviewScanning
    val isActive = viewModel.isRuviewActive
    val peopleCount = viewModel.ruviewPeopleCount
    val presence = viewModel.ruviewPresence
    val isConnected = viewModel.isRuviewConnected
    val heartRate = viewModel.ruviewHeartRate
    val breathingRate = viewModel.ruviewBreathingRate
    val motion = viewModel.ruviewMotion

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Text(
                text = "RUVIEW INDOOR RADAR",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = SecondaryTactileCyan,
                letterSpacing = 2.sp
            )
            Text(
                text = if (isScanning) "Scanning Room..." else if (isActive && isConnected) "Sensing Active" else if (isActive) "Sensor Offline" else "Standby Mode",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        }

        Box(
            modifier = Modifier.size(130.dp),
            contentAlignment = Alignment.Center
        ) {
            val radarSweepAngle = if (isScanning) angleSweep * 1.5f else angleSweep * 0.4f
            
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width / 2.2f

                drawCircle(
                    color = SecondaryTactileCyan.copy(alpha = if (isScanning) 0.35f else 0.15f),
                    center = center,
                    radius = radius,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawCircle(
                    color = SecondaryTactileCyan.copy(alpha = if (isScanning) 0.25f else 0.1f),
                    center = center,
                    radius = radius * 0.66f,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawCircle(
                    color = SecondaryTactileCyan.copy(alpha = if (isScanning) 0.15f else 0.05f),
                    center = center,
                    radius = radius * 0.33f,
                    style = Stroke(width = 1.dp.toPx())
                )

                val beamRad = Math.toRadians(radarSweepAngle.toDouble())
                val endX = center.x + radius * cos(beamRad).toFloat()
                val endY = center.y + radius * sin(beamRad).toFloat()

                if (isActive || isScanning) {
                    drawLine(
                        color = SecondaryTactileCyan.copy(alpha = if (isScanning) 0.8f else 0.3f),
                        start = center,
                        end = Offset(endX, endY),
                        strokeWidth = 2.dp.toPx()
                    )
                }

                if (isActive && !isScanning && peopleCount > 0) {
                    val seedPoints = listOf(
                        Offset(center.x - radius * 0.4f, center.y - radius * 0.3f),
                        Offset(center.x + radius * 0.5f, center.y + radius * 0.2f),
                        Offset(center.x - radius * 0.2f, center.y + radius * 0.4f),
                        Offset(center.x + radius * 0.1f, center.y - radius * 0.5f)
                    )
                    for (i in 0 until (peopleCount.coerceAtMost(seedPoints.size))) {
                        drawCircle(
                            color = PrimarySafetyYellow,
                            center = seedPoints[i],
                            radius = 6.dp.toPx()
                        )
                        drawCircle(
                            color = PrimarySafetyYellow.copy(alpha = 0.4f),
                            center = seedPoints[i],
                            radius = 12.dp.toPx(),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                }
            }
            
            if (!isActive && !isScanning) {
                Text(
                    text = "INACTIVE\nSay 'detect people'",
                    color = TextSecondaryDark,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 14.sp
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = DeepBlueButton),
                    border = BorderStroke(1.dp, if (isConnected && isActive) SecondaryTactileCyan else Color.Transparent),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("CONNECTION", fontSize = 8.sp, color = TextSecondaryDark, fontWeight = FontWeight.Bold)
                        val connText = if (!isActive) "STANDBY" else if (isConnected) "LIVE SERVER" else "OFFLINE"
                        Text(
                            text = connText,
                            fontSize = 9.sp,
                            color = if (isConnected && isActive) Color.Green else if (isActive) Color.Red else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = DeepBlueButton),
                    border = BorderStroke(1.dp, if (presence && isActive) PrimarySafetyYellow else Color.Transparent),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("OCCUPANCY", fontSize = 8.sp, color = TextSecondaryDark, fontWeight = FontWeight.Bold)
                        val occupancyText = if (!isActive) "N/A" else "$peopleCount ${if (peopleCount == 1) "Person" else "People"}"
                        Text(occupancyText, fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DeepBlueButton),
                border = BorderStroke(1.dp, SurfaceCardDark),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "RADAR VITALS FEEDBACK",
                        fontSize = 8.sp,
                        color = TextSecondaryDark,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Heart Rate", fontSize = 8.sp, color = TextSecondaryDark)
                            Text(
                                text = if (isActive && peopleCount > 0) "${heartRate.toInt()} BPM" else "--",
                                fontSize = 9.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Respiration", fontSize = 8.sp, color = TextSecondaryDark)
                            Text(
                                text = if (isActive && peopleCount > 0) "${breathingRate.toInt()} BPM" else "--",
                                fontSize = 9.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Micro-Motion", fontSize = 8.sp, color = TextSecondaryDark)
                            Text(
                                text = if (isActive && peopleCount > 0) (if (motion) "Active" else "Still") else "--",
                                fontSize = 9.sp,
                                color = if (motion && isActive) Color.Green else Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.scanRoomHeadcount() },
                colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) SecondaryTactileCyan.copy(alpha = 0.5f) else SecondaryTactileCyan),
                shape = RoundedCornerShape(12.dp),
                enabled = !isScanning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Sensors,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isScanning) "SCANNING ROOM..." else "SCAN ROOM NOW",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

// ─── Native Compose Canvas Voice Orb ───
// A 3D bubble orb rendered entirely in Jetpack Compose Canvas.
// Cyan + Purple color scheme, voice-reactive, no WebView needed.

private data class OrbParticle(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var life: Float, var decay: Float,
    var radius: Float, var color: Color
)

@Composable
fun NativeVoiceOrb(
    orbState: OrbState,
    isSpeaking: Boolean,
    voiceAmp: Float,
    modifier: Modifier = Modifier
) {
    // Animated time value
    val infiniteTransition = rememberInfiniteTransition(label = "orbTime")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbTimeValue"
    )

    // Smooth volume
    val smoothVol by animateFloatAsState(
        targetValue = voiceAmp,
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "smoothVol"
    )

    // State-dependent colors and params
    val stateKey = when {
        orbState == OrbState.IDLE && isSpeaking -> "speaking"
        orbState == OrbState.IDLE -> "idle"
        orbState == OrbState.LISTENING -> "listening"
        orbState == OrbState.PROCESSING -> "thinking"
        orbState == OrbState.EMERGENCY -> "emergency"
        else -> "idle"
    }

    // Colors
    val cyan = Color(0xFF00E5FF)
    val purple = Color(0xFF7B2FBE)
    val lavender = Color(0xFFA855F7)
    val amber = Color(0xFFFFAA00)
    val red = Color(0xFFFF1111)

    val color1: Color
    val color2: Color
    val speed: Float
    val pulseAmt: Float
    val showParticles: Boolean
    val volReactive: Boolean

    when (stateKey) {
        "idle" -> {
            color1 = cyan; color2 = purple
            speed = 0.6f; pulseAmt = 0.03f
            showParticles = false; volReactive = false
        }
        "listening" -> {
            color1 = cyan; color2 = lavender
            speed = 1.0f; pulseAmt = 0.08f
            showParticles = false; volReactive = true
        }
        "thinking" -> {
            color1 = amber; color2 = Color(0xFFFF6600)
            speed = 0.8f; pulseAmt = 0.04f
            showParticles = false; volReactive = false
        }
        "speaking" -> {
            color1 = lavender; color2 = cyan
            speed = 1.4f; pulseAmt = 0.1f
            showParticles = true; volReactive = true
        }
        "emergency" -> {
            color1 = red; color2 = Color(0xFFFF4444)
            speed = 2.0f; pulseAmt = 0.12f
            showParticles = true; volReactive = true
        }
        else -> {
            color1 = cyan; color2 = purple
            speed = 0.6f; pulseAmt = 0.03f
            showParticles = false; volReactive = false
        }
    }

    // Particle state
    val particles = remember { mutableStateListOf<OrbParticle>() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val centerX = w / 2f
        val centerY = h / 2f
        val t = time * speed * 0.01f

        // Pulse calculation
        val pulse = if (volReactive) {
            1f + pulseAmt * 0.3f + smoothVol * pulseAmt * 3f
        } else {
            1f + pulseAmt * sin(t * 1.5f)
        }

        val baseR = minOf(w, h) * 0.32f * pulse

        // ── Outer glow ──
        val glowR = baseR * 2.5f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color1.copy(alpha = 0.15f),
                    color1.copy(alpha = 0.04f),
                    Color.Transparent
                ),
                center = Offset(centerX, centerY),
                radius = glowR
            ),
            radius = glowR,
            center = Offset(centerX, centerY)
        )

        // ── Layered 3D sphere ──
        val layers = 10
        for (i in layers downTo 0) {
            val frac = i.toFloat() / layers
            val layerR = baseR * (0.35f + 0.65f * frac)

            // Organic displacement via sine combinations
            val nx = sin(t * 0.5f + frac * 3f) * cos(t * 0.3f + frac * 7f)
            val ny = cos(t * 0.4f + frac * 5f) * sin(t * 0.6f + frac * 2f)
            val displaceAmt = baseR * 0.07f * (if (volReactive) 1f + smoothVol * 2f else 1f) * frac
            val lx = centerX + nx * displaceAmt
            val ly = centerY + ny * displaceAmt

            // Mix colors: outer=color1, inner=color2
            val mixT = frac * 0.7f
            val mixedColor = Color(
                red = color1.red + (color2.red - color1.red) * mixT,
                green = color1.green + (color2.green - color1.green) * mixT,
                blue = color1.blue + (color2.blue - color1.blue) * mixT
            )
            val alpha = if (frac < 0.3f) 0.55f + 0.35f * (1f - frac / 0.3f) else 0.12f + 0.13f * frac

            // Radial gradient for 3D lighting
            val lightX = lx - layerR * 0.3f
            val lightY = ly - layerR * 0.3f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        mixedColor.copy(alpha = alpha),
                        mixedColor.copy(alpha = alpha * 0.6f),
                        mixedColor.copy(alpha = 0f)
                    ),
                    center = Offset(lightX, lightY),
                    radius = layerR
                ),
                radius = layerR,
                center = Offset(lx, ly)
            )
        }

        // ── Specular highlight (top-left) ──
        val specR = baseR * 0.55f
        val specX = centerX - baseR * 0.22f
        val specY = centerY - baseR * 0.22f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.28f),
                    Color.White.copy(alpha = 0.06f),
                    Color.Transparent
                ),
                center = Offset(specX, specY),
                radius = specR
            ),
            radius = specR,
            center = Offset(specX, specY)
        )

        // ── Inner core glow ──
        val coreR = baseR * 0.35f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.2f),
                    color2.copy(alpha = 0.12f),
                    Color.Transparent
                ),
                center = Offset(centerX, centerY),
                radius = coreR
            ),
            radius = coreR,
            center = Offset(centerX, centerY)
        )

        // ── Edge rim light ──
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    color1.copy(alpha = 0.1f),
                    Color.Transparent
                ),
                center = Offset(centerX, centerY),
                radius = baseR * 1.05f
            ),
            radius = baseR * 1.05f,
            center = Offset(centerX, centerY)
        )

        // ── Floating wisps ──
        val wispCount = 5
        for (wi in 0 until wispCount) {
            val wa = t * 0.4f * (if (wi % 2 == 0) 1f else -1f) + wi * (Math.PI.toFloat() * 2f / wispCount)
            val wr = baseR * (1.05f + 0.15f * sin(t * 0.7f + wi * 2f))
            val wx = centerX + cos(wa) * wr
            val wy = centerY + sin(wa) * wr * 0.6f
            val wSize = 3f + 2f * sin(t + wi.toFloat())
            val wAlpha = 0.35f + 0.2f * sin(t * 1.3f + wi.toFloat())
            val wc = if (wi % 2 == 0) color1 else color2

            drawCircle(
                color = wc.copy(alpha = wAlpha),
                radius = wSize,
                center = Offset(wx, wy)
            )
            // Wisp glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        wc.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = Offset(wx, wy),
                    radius = wSize * 4f
                ),
                radius = wSize * 4f,
                center = Offset(wx, wy)
            )
        }

        // ── Particles ──
        if (showParticles && (smoothVol > 0.08f || !volReactive)) {
            val count = (1 + smoothVol * 4f).toInt()
            for (pi in 0 until count) {
                val angle = (Math.random() * Math.PI * 2).toFloat()
                val dist = baseR * (0.9f + Math.random().toFloat() * 0.3f)
                val px = centerX + cos(angle) * dist
                val py = centerY + sin(angle) * dist
                val pSpeed = 0.3f + Math.random().toFloat() * 1.2f
                particles.add(OrbParticle(
                    x = px, y = py,
                    vx = cos(angle) * pSpeed, vy = sin(angle) * pSpeed - 0.3f,
                    life = 1f, decay = 0.015f + Math.random().toFloat() * 0.02f,
                    radius = 1.5f + Math.random().toFloat() * 2f,
                    color = if (Math.random() > 0.5) color1 else color2
                ))
            }
        }

        // Draw & update particles
        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.x += p.vx; p.y += p.vy; p.vy -= 0.01f; p.life -= p.decay
            if (p.life <= 0f) { iter.remove(); continue }
            drawCircle(
                color = p.color.copy(alpha = p.life * 0.6f),
                radius = p.radius * p.life,
                center = Offset(p.x, p.y)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        p.color.copy(alpha = p.life * 0.12f),
                        Color.Transparent
                    ),
                    center = Offset(p.x, p.y),
                    radius = p.radius * p.life * 4f
                ),
                radius = p.radius * p.life * 4f,
                center = Offset(p.x, p.y)
            )
        }

        // Cap particle count
        while (particles.size > 120) particles.removeAt(0)
    }
}
