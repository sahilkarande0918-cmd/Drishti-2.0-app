package com.example

import android.Manifest
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.api.DrishtiApiClient
import com.example.data.database.DrishtiDatabase
import com.example.data.database.SavedPlaceDao
import com.example.data.repository.DrishtiRepository
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.DrishtiViewModel
import com.example.ui.viewmodel.DrishtiViewModelFactory
import com.example.ui.viewmodel.OrbState
import android.util.Log
import kotlinx.coroutines.flow.collect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.media.AudioManager
import android.content.Context
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.common.api.ResolvableApiException
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

class MainActivity : ComponentActivity() {
    private var triggerSpeechCaptureCallback: (() -> Unit)? = null
    private var volumeDownJob: Job? = null

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) {
                if (volumeDownJob != null) {
                    volumeDownJob?.cancel()
                    volumeDownJob = null
                    triggerSpeechCaptureCallback?.invoke()
                } else {
                    volumeDownJob = lifecycleScope.launch {
                        delay(300)
                        try {
                            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            audioManager.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_LOWER,
                                AudioManager.FLAG_SHOW_UI
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        volumeDownJob = null
                    }
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize Firebase Firestore with programmatic configuration fallback
        com.example.data.firebase.DrishtiFirestoreManager.initialize(this)
        // Enable edge-to-edge full screen drawing
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current

            // Setup real database, Api and Repository Singletons
            val database = remember { DrishtiDatabase.getDatabase(context) }
            val repository = remember {
                DrishtiRepository(
                    userDao = database.userDao(),
                    guardianDao = database.guardianDao(),
                    sosAlertDao = database.sosAlertDao(),
                    userSettingsDao = database.userSettingsDao(),
                    savedPlaceDao = database.savedPlaceDao(),
                    geminiService = DrishtiApiClient.geminiService,
                    nominatimService = DrishtiApiClient.nominatimService,
                    osrmService = DrishtiApiClient.osrmService
                )
            }

            // Factory-provided ViewModel
            val dViewModel: DrishtiViewModel = viewModel(
                factory = DrishtiViewModelFactory(application, repository)
            )

            // Permissions Handler (Declares Camera, Fine Location and Microphone RECORD_AUDIO)
            val permissionsToRequest = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )

            var forceRecreateRecognizer by remember { mutableStateOf(0) }

            val locationSettingsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    dViewModel.refreshLocation()
                    Toast.makeText(context, "Location Services Enabled.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "GPS is required for real-time visual navigation guidance.", Toast.LENGTH_LONG).show()
                }
            }

            val checkAndEnableGPS = {
                val locationRequest = LocationRequest.Builder(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 5000L).build()
                val builder = LocationSettingsRequest.Builder()
                    .addLocationRequest(locationRequest)
                    .setAlwaysShow(true)

                val client = LocationServices.getSettingsClient(this@MainActivity)
                val task = client.checkLocationSettings(builder.build())

                task.addOnSuccessListener {
                    dViewModel.refreshLocation()
                }

                task.addOnFailureListener { exception: Exception ->
                    if (exception is ResolvableApiException) {
                        try {
                            val intentSenderRequest = IntentSenderRequest.Builder(exception.resolution.intentSender).build()
                            locationSettingsLauncher.launch(intentSenderRequest)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            val permissionsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                val allGranted = result.values.all { it }
                if (result[Manifest.permission.RECORD_AUDIO] == true) {
                    forceRecreateRecognizer++
                }
                if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                    checkAndEnableGPS()
                }
                if (allGranted) {
                    Toast.makeText(context, "Core assistant permissions approved.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Microphone or other details declined. Tap mic to re-grant.", Toast.LENGTH_LONG).show()
                }
            }

            // Launch permissions request when dashboard launches
            LaunchedEffect(Unit) {
                permissionsLauncher.launch(permissionsToRequest)
                val hasFineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarseLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (hasFineLocation || hasCoarseLocation) {
                    checkAndEnableGPS()
                }
            }

            // High reliability official dialog launcher (vital for emulator runs and error-free execution)
            val voiceAssistantLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    if (!matches.isNullOrEmpty()) {
                        dViewModel.stopSpeaking()
                        dViewModel.processSpeachTextCommand(matches[0])
                    } else {
                        dViewModel.cancelVoiceListening()
                    }
                } else {
                    dViewModel.cancelVoiceListening()
                }
            }

            var activeRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

            fun startRecognizerSession(useGoogleService: Boolean) {
                if (dViewModel.orbState != OrbState.LISTENING) {
                    Log.d("MainActivity", "startRecognizerSession: Ignored because orbState is ${dViewModel.orbState}")
                    return
                }
                try {
                    activeRecognizer?.destroy()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to destroy active SpeechRecognizer", e)
                }
                activeRecognizer = null

                try {
                    val newRecognizer = if (useGoogleService && SpeechRecognizer.isRecognitionAvailable(context)) {
                        try {
                            val comp = android.content.ComponentName(
                                "com.google.android.googlequicksearchbox",
                                "com.google.android.voicesearch.service.SpeechRecognitionService"
                            )
                            SpeechRecognizer.createSpeechRecognizer(context, comp)
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Targeted Google Speech Service unavailable, falling back to system default", e)
                            SpeechRecognizer.createSpeechRecognizer(context)
                        }
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }

                    activeRecognizer = newRecognizer

                    newRecognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.d("MainActivity", "SpeechRecognizer onReadyForSpeech")
                        }

                        override fun onBeginningOfSpeech() {
                            Log.d("MainActivity", "SpeechRecognizer onBeginningOfSpeech")
                        }

                        override fun onRmsChanged(rmsdB: Float) {
                            val minRms = -2.0f
                            val maxRms = 10.0f
                            val normalized = ((rmsdB - minRms) / (maxRms - minRms)).coerceIn(0.0f, 1.0f)
                            dViewModel.updateMicAmplitude(normalized)
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            Log.d("MainActivity", "SpeechRecognizer onEndOfSpeech")
                            dViewModel.updateMicAmplitude(0.0f)
                        }

                        override fun onError(error: Int) {
                            val message = when (error) {
                                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                                SpeechRecognizer.ERROR_SERVER -> "Server error"
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests"
                                else -> "Unknown error: $error"
                            }
                            Log.e("MainActivity", "SpeechRecognizer onError ($error): $message")

                            try {
                                newRecognizer.destroy()
                            } catch (e: Exception) {}
                            if (activeRecognizer == newRecognizer) {
                                activeRecognizer = null
                            }

                            if (useGoogleService && (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS || error == SpeechRecognizer.ERROR_CLIENT)) {
                                Log.w("MainActivity", "Google voice service failed with error $error, retrying with system default recognizer")
                                (context as? android.app.Activity)?.runOnUiThread {
                                    startRecognizerSession(false)
                                }
                            } else {
                                (context as? android.app.Activity)?.runOnUiThread {
                                    Toast.makeText(context, "Mic error: $message", Toast.LENGTH_SHORT).show()
                                }
                                dViewModel.cancelVoiceListening()
                                dViewModel.updateMicAmplitude(0.0f)
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                val speechText = matches[0]
                                dViewModel.stopSpeaking()
                                dViewModel.processSpeachTextCommand(speechText)
                            } else {
                                dViewModel.cancelVoiceListening()
                            }
                            dViewModel.updateMicAmplitude(0.0f)

                            try {
                                newRecognizer.destroy()
                            } catch (e: Exception) {}
                            if (activeRecognizer == newRecognizer) {
                                activeRecognizer = null
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })

                    // Voice commands are always recognized in English. Output language is handled
                    // separately by DrishtiViewModel.ttsLanguage for AI responses and TTS.
                    val speechLanguage = "en-IN"
                    val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, speechLanguage)
                        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, speechLanguage)
                    }

                    (context as? android.app.Activity)?.runOnUiThread {
                        newRecognizer.startListening(intent)
                    }

                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to start SpeechRecognizer session", e)
                    Toast.makeText(context, "Voice system unavailable.", Toast.LENGTH_LONG).show()
                    dViewModel.cancelVoiceListening()
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    try {
                        activeRecognizer?.destroy()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            LaunchedEffect(dViewModel.orbState) {
                if (dViewModel.orbState == OrbState.IDLE) {
                    try {
                        activeRecognizer?.cancel()
                        activeRecognizer?.destroy()
                        activeRecognizer = null
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    dViewModel.updateMicAmplitude(0.0f)
                }
            }

            // Active voice capture activation function - Uses background SpeechRecognizer with no popups
            val triggerSpeechCapture: () -> Unit = {
                // Instantly halt/interrupt any ongoing speak outputs (making it fully interruptible like Gemini Voice)
                dViewModel.stopSpeaking()

                val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                if (!hasMicPermission) {
                    Toast.makeText(context, "Microphone access requested for Voice Commands.", Toast.LENGTH_LONG).show()
                    permissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                } else {
                    dViewModel.startListeningCommand()
                    lifecycleScope.launch {
                        delay(350)
                        startRecognizerSession(true)
                    }
                }
            }

            triggerSpeechCaptureCallback = triggerSpeechCapture

            DrishtiTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                // Flow collection from DB
                val userProfile by dViewModel.userProfile.collectAsStateWithLifecycle()
                val guardianProfile by dViewModel.guardianProfile.collectAsStateWithLifecycle()
                val alertsHistory by dViewModel.alertsHistory.collectAsStateWithLifecycle()
                val userSettings by dViewModel.userSettings.collectAsStateWithLifecycle()

                // Register global voice actions event receiver
                LaunchedEffect(Unit) {
                    dViewModel.navigationEvents.collect { route ->
                        if (route.isNotBlank() && currentRoute != route) {
                            navController.navigate(route) {
                                popUpTo("dashboard") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                }

                // Bottom Tab Items definition
                val navTabs = listOf(
                    NavTabItem("dashboard", Icons.Default.Mic, "Dashboard"),
                    NavTabItem("vision", Icons.Default.PhotoCamera, "AI Vision"),
                    NavTabItem("navigation", Icons.Default.DirectionsWalk, "Compass Map"),
                    NavTabItem("sos", Icons.Default.Shield, "SOS Rescue"),
                    NavTabItem("settings", Icons.Default.Settings, "Settings")
                )

                // Automatic Permanent Login Guards
                LaunchedEffect(userProfile, currentRoute) {
                    userProfile?.let { profile ->
                        if (currentRoute == "google_login") {
                            if (profile.onboardingCompleted) {
                                navController.navigate("dashboard") {
                                    popUpTo("google_login") { inclusive = true }
                                }
                            } else {
                                navController.navigate("onboarding") {
                                    popUpTo("google_login") { inclusive = true }
                                }
                            }
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    floatingActionButton = {
                        // Floating neon speak button on all core screens so users can easily voice command
                        val isCoreRoute = currentRoute in listOf("dashboard", "vision", "navigation", "sos", "settings")
                        if (isCoreRoute) {
                            FloatingActionButton(
                                onClick = triggerSpeechCapture,
                                containerColor = PrimarySafetyYellow,
                                contentColor = Color.Black,
                                shape = CircleShape,
                                modifier = Modifier
                                    .padding(bottom = 16.dp, end = 8.dp)
                                    .testTag("global_voice_floating_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Trigger Speech Interpreter Voice Control",
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    },
                    bottomBar = {
                        // Display high-contrast Bottom navbar only on inner core frames
                        val isCoreRoute = currentRoute in listOf("dashboard", "vision", "navigation", "sos", "settings")
                        if (isCoreRoute) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Transparent)
                                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                            ) {
                                NavigationBar(
                                    containerColor = SurfaceDark.copy(alpha = 0.95f),
                                    tonalElevation = 0.dp,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .clip(RoundedCornerShape(20.dp))
                                        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), RoundedCornerShape(20.dp))
                                ) {
                                    navTabs.forEach { tab ->
                                        val isSelected = currentRoute == tab.route
                                        NavigationBarItem(
                                            selected = isSelected,
                                            onClick = {
                                                dViewModel.stopSpeaking()
                                                navController.navigate(tab.route) {
                                                    popUpTo("dashboard") { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                            icon = {
                                                Icon(
                                                    imageVector = tab.icon,
                                                    contentDescription = tab.label,
                                                    tint = if (isSelected) Color.Black else Color.White
                                                )
                                            },
                                            label = {
                                                Text(
                                                    text = tab.label,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                                    color = if (isSelected) PrimarySafetyYellow else TextSecondaryDark
                                                )
                                            },
                                            colors = NavigationBarItemDefaults.colors(
                                                indicatorColor = PrimarySafetyYellow
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "google_login", // Starts directly on requested Google Login screen
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding())
                    ) {
                        composable("google_login") {
                            GoogleLoginScreen(
                                webClientId = BuildConfig.GOOGLE_CLIENT_ID,
                                onLoginCompleted = { email, name, idToken ->
                                    dViewModel.completeGoogleLogin(email, name, idToken) { onboardingCompleted ->
                                        val destination = if (onboardingCompleted) "dashboard" else "onboarding"
                                        navController.navigate(destination) {
                                            popUpTo("google_login") { inclusive = true }
                                        }
                                    }
                                },
                                onSpeakMessage = { msg ->
                                    dViewModel.speak(msg)
                                }
                            )
                        }

                        composable("onboarding") {
                            OnboardingScreen(
                                onCompleteOnboarding = { uName, gName, gEmail, gPhone ->
                                    dViewModel.completeOnboarding(uName, gName, gEmail, gPhone)
                                    navController.navigate("dashboard") {
                                        popUpTo("onboarding") { inclusive = true }
                                    }
                                },
                                onSpeakMessage = { dViewModel.speak(it) }
                            )
                        }

                        composable("dashboard") {
                            DashboardScreen(
                                userProfile = userProfile,
                                orbState = dViewModel.orbState,
                                transcript = dViewModel.transcriptState,
                                onMicTriggered = triggerSpeechCapture,
                                onSpeechSimulated = { text ->
                                    dViewModel.processSpeachTextCommand(text)
                                },
                                onNavigateToPage = { target ->
                                    navController.navigate(target)
                                },
                                viewModel = dViewModel
                            )
                        }

                        composable("vision") {
                            VisionScreen(
                                aiDescriptionResult = dViewModel.aiDescriptionResult,
                                aiTextReaderResult = dViewModel.aiTextReaderResult,
                                isAnalyzing = dViewModel.isAnalyzing,
                                isLiveScanning = dViewModel.isLiveScanning,
                                onToggleLiveScanning = { active ->
                                    if (active) dViewModel.startLiveScanning()
                                    else dViewModel.stopLiveScanning()
                                },
                                onAnalyzeScene = { bitmap ->
                                    dViewModel.analyzeSceneSurroundings(bitmap)
                                },
                                onAnalyzeSignText = { bitmap ->
                                    dViewModel.analyzeImageForSignText(bitmap)
                                },
                                onSpeakMessage = { dViewModel.speak(it) },
                                viewModel = dViewModel
                            )
                        }

                        composable("navigation") {
                            MapNavigationScreen(
                                currentLatitude = dViewModel.currentLatitude,
                                currentLongitude = dViewModel.currentLongitude,
                                currentAddress = dViewModel.currentAddressText,
                                selectedDestination = dViewModel.selectedDestinationName,
                                activeSteps = dViewModel.activeNavigationSteps,
                                currentStepIndex = dViewModel.currentStepIndex,
                                isNavigating = dViewModel.isNavigating,
                                isCalculating = dViewModel.isCalculatingRoute,
                                onSearchAndNavigate = { dViewModel.triggerScenicNavigation(it) },
                                onNextStep = { dViewModel.nextNavigationStep() },
                                onPrevStep = { dViewModel.prevNavigationStep() },
                                onStopNavigation = { dViewModel.stopNavigation() },
                                onSpeakCurrentLocation = { dViewModel.speakCurrentLocation() },
                                viewModel = dViewModel
                            )
                        }

                        composable("sos") {
                            SosScreen(
                                guardian = guardianProfile,
                                alertsHistory = alertsHistory,
                                onTriggerSos = { dViewModel.activateEmergencySOS() },
                                onClearHistory = { dViewModel.clearHistory() }
                            )
                        }

                        composable("settings") {
                            SettingsScreen(
                                userSettings = userSettings,
                                guardian = guardianProfile,
                                groqApiKey = dViewModel.groqApiKey,
                                geminiApiKey = dViewModel.geminiApiKey,
                                onUpdateSpeechRate = { dViewModel.setCustomSpeechRate(it) },
                                onToggleVoiceFeedback = { dViewModel.toggleVoiceFeedback(it) },
                                onUpdateGuardian = { gName, gEmail, gPhone ->
                                    dViewModel.updateGuardian(gName, gEmail, gPhone)
                                },
                                onUpdateGroqApiKey = { dViewModel.saveGroqApiKey(it) },
                                onUpdateGeminiApiKey = { dViewModel.saveGeminiApiKey(it) },
                                onUpdateRuviewSettings = { enabled, url ->
                                    dViewModel.updateRuviewSettings(enabled, url)
                                },
                                onUpdateSmtpSettings = { enabled, host, port, email, pass ->
                                    dViewModel.saveSmtpSettings(enabled, host, port, email, pass)
                                },
                                onTestVoice = {
                                    dViewModel.speak("This is a verification test of the Drishti Indian vocal feedback check. System check green.")
                                },
                                currentGender = dViewModel.userGenderOverride,
                                onUpdateGender = { dViewModel.setUserGender(it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

data class NavTabItem(
    val route: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String
)
