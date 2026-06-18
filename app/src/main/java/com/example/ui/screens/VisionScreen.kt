package com.example.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.example.util.IndoorFrameAnalyzer
import java.util.concurrent.Executors
import java.io.File
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.*
import com.example.ui.viewmodel.DrishtiViewModel
import kotlinx.coroutines.delay
import java.io.InputStream

@Composable
fun VisionScreen(
    aiDescriptionResult: String,
    aiTextReaderResult: String,
    isAnalyzing: Boolean,
    isLiveScanning: Boolean,
    onToggleLiveScanning: (Boolean) -> Unit,
    onAnalyzeScene: (Bitmap) -> Unit,
    onAnalyzeSignText: (Bitmap) -> Unit,
    onSpeakMessage: (String) -> Unit,
    viewModel: DrishtiViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
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

    // Capture photo and trigger unified agent analysis
    fun capturePhotoAndProcess(onCaptured: (Bitmap) -> Unit) {
        if (!cameraPermissionGranted) {
            onSpeakMessage("Camera access is inactive.")
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
                        onSpeakMessage("Processing failed. Invoking radar fallback.")
                        onCaptured(generateSimulatedBitmap(context, "scene"))
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("VisionScreen", "Hardware photo capture failed", exception)
                    onCaptured(generateSimulatedBitmap(context, "scene"))
                }
            }
        )
    }

    // Launcher to request camera permissions
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted) {
            onSpeakMessage("Camera viewfinder enabled.")
        } else {
            onSpeakMessage("Camera permission declined. Standby radar active.")
        }
    }

    // Checking permission state on load
    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        cameraPermissionGranted = hasPermission
        if (!hasPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
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

    // --- AI Indoor Navigation: feed live camera frames to the AI vision layer ---
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    // Bind camera use cases. When indoor or outdoor navigation is on, an extra ImageAnalysis
    // use case streams fresh frames to the navigation layers; otherwise just preview + capture.
    val navFramesNeeded = viewModel.isIndoorNavActive || viewModel.isOutdoorNavActive || viewModel.isSmartNavActive
    LaunchedEffect(cameraPermissionGranted, navFramesNeeded) {
        if (!cameraPermissionGranted) return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                if (navFramesNeeded) {
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { it.setAnalyzer(cameraExecutor, IndoorFrameAnalyzer { bitmap -> viewModel.updateNavFrame(bitmap) }) }
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, analysis)
                } else {
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                }
                cameraReady = true
            } catch (e: Exception) {
                Log.e("VisionScreen", "CameraX binding failed", e)
                cameraBindingFailed = true
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Visual scanning line animation
    val infiniteTransition = rememberInfiniteTransition(label = "ScannerGlow")
    val scanOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ScanlineOffset"
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
            // Screen Header
            Text(
                text = "DRISHTI A.I. VISION",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = SecondaryTactileCyan,
                letterSpacing = 1.5.sp
            )

            Text(
                text = "Smart Camera Agent",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = PrimarySafetyYellow,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            // Dynamic Proactive Hazard Warning Card (if anything dangerous is reported)
            if (viewModel.isProactiveScanningEnabled && viewModel.lastProactiveHazardAlert.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = EmergencyCrimson.copy(alpha = 0.2f)),
                    border = BorderStroke(1.5.dp, EmergencyCrimson),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = "Hazard Alert", tint = EmergencyCrimson, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = viewModel.lastProactiveHazardAlert,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }
            }

            // Visual Status Indicator for Walk With Me Mode / Continuous Scanning
            if (viewModel.isProactiveScanningEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(
                        if (viewModel.isWalkWithMeActive) 2.dp else 1.5.dp,
                        if (viewModel.isWalkWithMeActive) PrimarySafetyYellow else Color.Green
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (viewModel.isWalkWithMeActive) PrimarySafetyYellow else Color.Green)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (viewModel.isWalkWithMeActive) "Walk With Me Mode Enabled" else "Continuous Camera Scanning",
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (viewModel.isWalkWithMeActive) "Active trusted guidance. Alerting only when needed." else "Speaks real-time hazard info",
                                fontSize = 10.sp,
                                color = TextSecondaryDark
                            )
                        }
                    }
                }
            }

            // Indoor / Outdoor / Smart Navigation live status banner
            if (viewModel.isIndoorNavActive || viewModel.isOutdoorNavActive || viewModel.isSmartNavActive) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(2.dp, SecondaryTactileCyan),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(SecondaryTactileCyan)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = when {
                                    viewModel.isSmartNavActive -> when (viewModel.smartNavEnvironment) {
                                        "outdoor" -> "Smart Navigation — Outdoors"
                                        "indoor" -> "Smart Navigation — Indoors"
                                        else -> "Smart Navigation — Sensing..."
                                    }
                                    viewModel.isOutdoorNavActive -> "Outdoor Navigation Active"
                                    else -> "Indoor Navigation Active"
                                },
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when {
                                    viewModel.isSmartNavActive ->
                                        viewModel.smartNavStatus.ifBlank { "Sensing your surroundings" }
                                    viewModel.isOutdoorNavActive ->
                                        viewModel.outdoorNavStatus.ifBlank { "Watching for vehicles, people, poles and potholes" }
                                    else ->
                                        viewModel.indoorNavStatus.ifBlank { "Looking ahead for stairs, doors, people and obstacles" }
                                },
                                fontSize = 10.sp,
                                color = TextSecondaryDark
                            )
                        }
                    }
                }
            }

            // Immersive Touch-to-Scan Full Viewport Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(SurfaceDark)
                    .border(
                        BorderStroke(
                            2.dp,
                            if (isAnalyzing) SecondaryTactileCyan else PrimarySafetyYellow
                        ),
                        RoundedCornerShape(24.dp)
                    )
                    .clickable {
                        if (!isAnalyzing) {
                            capturePhotoAndProcess { bitmap ->
                                selectedBitmap = bitmap
                                viewModel.analyzeSceneSurroundings(bitmap)
                            }
                        }
                    }
                    .testTag("camera_viewer_box"),
                contentAlignment = Alignment.Center
            ) {
                if (cameraPermissionGranted && !cameraBindingFailed) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Tactile fallback sonar scanlines
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val scanY = size.height * scanOffset
                                drawLine(
                                    color = PrimarySafetyYellow.copy(alpha = 0.5f),
                                    start = androidx.compose.ui.geometry.Offset(0f, scanY),
                                    end = androidx.compose.ui.geometry.Offset(size.width, scanY),
                                    strokeWidth = 4.dp.toPx()
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Sensors,
                                contentDescription = null,
                                tint = PrimarySafetyYellow.copy(alpha = 0.6f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tactile Scanning Interface Ready",
                                color = TextSecondaryDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Superimposed scanning guide indicator overlay
                Box(
                    modifier = Modifier
                        .size(200.dp, 120.dp)
                        .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.25f)), RoundedCornerShape(16.dp))
                )

                // High-Contrast Transparent Overlay Banner signaling accessibility scan gesture
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = if (isAnalyzing) "Processing surroundings..." else "TAP ANYWHERE TO SCAN",
                        color = if (isAnalyzing) SecondaryTactileCyan else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // AI Verbal Feedback Subtitles Log
            if (aiDescriptionResult.isNotBlank() || aiTextReaderResult.isNotBlank() || isAnalyzing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, PrimarySafetyYellow.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "LATEST VERBAL DESCRIPTION",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimarySafetyYellow,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        if (isAnalyzing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = SecondaryTactileCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Analyzing frame with camera intelligence...", color = SecondaryTactileCyan, fontSize = 12.sp)
                            }
                        } else {
                            val displayText = if (aiDescriptionResult.isNotBlank()) aiDescriptionResult else aiTextReaderResult
                            Text(
                                text = "\"$displayText\"",
                                fontSize = 15.sp,
                                color = Color.White,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Clear visual indicators showing they can do everything with voice
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 120.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.6f)),
                border = BorderStroke(1.dp, SurfaceCardDark)
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = PrimarySafetyYellow, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "VOICE AGENT ACTIVE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SecondaryTactileCyan
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Just say \"start navigation\" — Drishti senses by itself whether you're indoors or outdoors and warns you, like \"stairs going down ahead\" or \"car on your left, very close\". You can still say \"start indoor navigation\" or \"start outdoor navigation\" to force a mode. Say \"stop\" to end.",
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        color = TextSecondaryDark,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

fun generateSimulatedBitmap(context: android.content.Context, type: String): Bitmap {
    val size = 256
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint()

    val bgColor = when {
        type.contains("Street", ignoreCase = true) || type.contains("scene") -> android.graphics.Color.DKGRAY
        type.contains("Bus stop", ignoreCase = true) || type.contains("sign") -> android.graphics.Color.YELLOW
        else -> android.graphics.Color.BLUE
    }

    canvas.drawColor(bgColor)

    paint.color = android.graphics.Color.WHITE
    paint.strokeWidth = 10f
    paint.style = android.graphics.Paint.Style.STROKE
    canvas.drawRect(20f, 20f, (size - 20).toFloat(), (size - 20).toFloat(), paint)

    paint.style = android.graphics.Paint.Style.FILL
    paint.textSize = 28f
    canvas.drawText("Drishti Eye Cam", 30f, 100f, paint)

    if (type.contains("sign") || type.contains("Bus")) {
        canvas.drawText("BUS TRANSITDELAY", 30f, 160f, paint)
        canvas.drawText("ROUTE 44", 30f, 200f, paint)
    } else {
        canvas.drawText("OBSTACLE AHEAD", 30f, 160f, paint)
    }

    return bitmap
}
