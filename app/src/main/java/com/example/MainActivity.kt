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

/** Normal gap before reopening the mic after a session ends. */
private const val MIC_RESTART_MIN_MS = 300L

/** Ceiling for the backoff when the recogniser keeps rejecting us. */
private const val MIC_RESTART_MAX_MS = 4_000L

class MainActivity : ComponentActivity() {
    private var triggerSpeechCaptureCallback: (() -> Unit)? = null
    private var volumeDownJob: Job? = null

    /** True while we are holding the recogniser's earcon stream muted. */
    private var earconsMuted = false

    /**
     * Google's recogniser plays its own earcons on every session - open, no_input, success,
     * failure - and they are not suppressible through the SpeechRecognizer API. A latched
     * mic restarts sessions constantly, so those tones become a sound that never stops.
     *
     * Measured on device, they are emitted on STREAM_NOTIFICATION (streamType 5), so that
     * stream alone is muted for the life of a recogniser session and restored the moment it
     * is released. Drishti's own speech is on STREAM_MUSIC and is unaffected, as is the
     * start cue the user asked to keep.
     */
    private fun setEarconsMuted(muted: Boolean) {
        if (muted == earconsMuted) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(
                AudioManager.STREAM_NOTIFICATION,
                if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0
            )
            earconsMuted = muted
        } catch (e: Exception) {
            // Muting needs Do-Not-Disturb access on some builds. Losing the beep is not
            // worth losing the microphone over, so carry on either way.
            Log.w("MainActivity", "Could not ${if (muted) "mute" else "unmute"} recogniser earcons", e)
        }
    }

    override fun onPause() {
        // Never leave the user's music stream muted because we went to the background.
        setEarconsMuted(false)
        super.onPause()
    }

    /**
     * Debug-only text injection, so the command router can be exercised without a real
     * microphone. Voice is the app's only input path, which otherwise makes every routing
     * change untestable outside of someone physically speaking to the phone.
     *
     *   adb shell am broadcast -a com.drishti.DEBUG_SAY -e text "किती वाजले"
     *
     * Not registered in release builds.
     */
    private var debugSayReceiver: android.content.BroadcastReceiver? = null

    private fun registerDebugSayReceiver(onText: (String) -> Unit) {
        if (!BuildConfig.DEBUG || debugSayReceiver != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                val text = intent?.getStringExtra("text")?.trim().orEmpty()
                if (text.isNotBlank()) {
                    Log.d("MainActivity", "DEBUG_SAY injected: $text")
                    runOnUiThread { onText(text) }
                }
            }
        }
        val filter = android.content.IntentFilter("com.drishti.DEBUG_SAY")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        debugSayReceiver = receiver
    }

    override fun onDestroy() {
        setEarconsMuted(false)
        debugSayReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w("MainActivity", "debugSayReceiver already unregistered", e)
            }
        }
        debugSayReceiver = null
        super.onDestroy()
    }

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

            // Grows when the recogniser rejects us (busy / too many requests) and resets on a
            // clean result, so a failing recogniser is retried politely instead of in a loop.
            var micRestartDelayMs by remember { mutableLongStateOf(MIC_RESTART_MIN_MS) }

            // Google's recogniser rate-limits sustained use, and this app holds the mic open
            // by design. Once it has refused us we stop asking it and stay on the system
            // recogniser, which is what the fallback path ended up using anyway.
            var preferSystemRecognizer by remember { mutableStateOf(false) }

            fun startRecognizerSession(useGoogleService: Boolean) {
                if (!dViewModel.isMicOn) {
                    Log.d("MainActivity", "startRecognizerSession: ignored, mic latch is off")
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

                            dViewModel.updateMicAmplitude(0.0f)

                            // A rejection means we are asking too often: slow down before the
                            // next attempt. Silence and no-match are normal and do not.
                            if (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ||
                                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                error == SpeechRecognizer.ERROR_CLIENT
                            ) {
                                micRestartDelayMs = (micRestartDelayMs * 2).coerceAtMost(MIC_RESTART_MAX_MS)
                            }
                            if (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) {
                                preferSystemRecognizer = true
                            }

                            when {
                                useGoogleService && (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS || error == SpeechRecognizer.ERROR_CLIENT) -> {
                                    Log.w("MainActivity", "Google voice service failed with error $error, retrying with system default recognizer")
                                    (context as? android.app.Activity)?.runOnUiThread {
                                        startRecognizerSession(false)
                                    }
                                }
                                // Silence and no-match are normal in a latched mic — the user
                                // simply has not spoken yet. Reopen quietly, no toast, no
                                // dropping out of listening.
                                error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                    dViewModel.onRecognizerSessionEnded()
                                }
                                else -> {
                                    if (!dViewModel.isMicOn) {
                                        (context as? android.app.Activity)?.runOnUiThread {
                                            Toast.makeText(context, "Mic error: $message", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    dViewModel.onRecognizerSessionEnded()
                                }
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            dViewModel.updateMicAmplitude(0.0f)

                            try {
                                newRecognizer.destroy()
                            } catch (e: Exception) {}
                            if (activeRecognizer == newRecognizer) {
                                activeRecognizer = null
                            }

                            val speechText = matches?.firstOrNull()?.trim()
                            // A clean result means the recogniser is healthy again.
                            micRestartDelayMs = MIC_RESTART_MIN_MS
                            if (!speechText.isNullOrBlank()) {
                                dViewModel.stopSpeaking()
                                dViewModel.processSpeachTextCommand(speechText)
                            }
                            // Ask for a new session either way. If the reply starts speaking,
                            // the effect holds off until it finishes; if the command produced
                            // no speech at all, this is what stops the mic from stalling.
                            dViewModel.onRecognizerSessionEnded()
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            // Deliberately empty. This used to cancel TTS on any partial
                            // result to give voice barge-in, but with a latched mic the
                            // recogniser hears Drishti's own speech, so every announcement
                            // cancelled itself. Barge-in is handled by double Volume-Down and
                            // the orb tap, which stop speech instantly and reopen the mic.
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })

                    // Listen in whatever language Drishti is currently speaking — Marathi by
                    // default. This was pinned to en-IN, so Marathi speech was being decoded
                    // by an English model and came back as garbage.
                    val speechLanguage = dViewModel.ttsLanguage
                    val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, speechLanguage)
                        // Accept English too, so an English word or command still lands while
                        // Marathi stays primary.
                        putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN"))
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        // Not EXTRA_PREFER_OFFLINE: this device has no on-device Marathi
                        // model, so offline recognition fails outright with
                        // ERROR_LANGUAGE_UNAVAILABLE (12) and still plays a failure earcon.
                        // Cut the trailing silence the recogniser waits through before it
                        // hands us the text; this is dead time the user feels as lag.
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                    }

                    setEarconsMuted(true)
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

            fun releaseRecognizer() {
                setEarconsMuted(false)
                try {
                    activeRecognizer?.cancel()
                    activeRecognizer?.destroy()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                activeRecognizer = null
                dViewModel.updateMicAmplitude(0.0f)
            }

            /**
             * The mic runs only while the latch is on AND Drishti is not speaking.
             *
             * An open mic during playback hears Drishti's own voice. That fed the recogniser
             * a constant stream of speech, which cancelled the very sentence being spoken and
             * restarted the session over and over until Google returned TOO_MANY_REQUESTS —
             * heard on the device as a mic tone that never stopped and navigation that went
             * silent. Listening and speaking are now mutually exclusive; the session resumes
             * by itself the moment a sentence finishes.
             */
            LaunchedEffect(dViewModel.isMicOn, dViewModel.voiceOutputActive, dViewModel.micSessionRequest) {
                if (!dViewModel.isMicOn || dViewModel.voiceOutputActive) {
                    releaseRecognizer()
                    return@LaunchedEffect
                }
                // Back off before reopening, so a recogniser that keeps failing is not
                // hammered in a tight loop.
                delay(micRestartDelayMs)
                if (dViewModel.isMicOn && !dViewModel.voiceOutputActive) {
                    startRecognizerSession(!preferSystemRecognizer)
                }
            }

            // Active voice capture activation function - Uses background SpeechRecognizer with no popups
            val triggerSpeechCapture: () -> Unit = {
                // A fall/drop alert is cancelled by this exact gesture, and must cancel even
                // without mic permission — so handle it before the permission gate below.
                if (dViewModel.isFallAlertActive) {
                    dViewModel.cancelFallAlert()
                } else {
                    // Instantly halt/interrupt any ongoing speak outputs (fully interruptible).
                    dViewModel.stopSpeaking()

                    val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                    if (!hasMicPermission) {
                        Toast.makeText(context, "Microphone access requested for Voice Commands.", Toast.LENGTH_LONG).show()
                        permissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    } else {
                        // Toggle the latch. startMic bumps micSessionRequest, which opens the
                        // recogniser immediately.
                        dViewModel.toggleMic()
                    }
                }
            }

            triggerSpeechCaptureCallback = triggerSpeechCapture

            LaunchedEffect(Unit) {
                registerDebugSayReceiver { text ->
                    if (text == "__FALL__") {
                        // Debug-only: exercise the drop-SOS countdown without dropping the
                        // phone. adb shell am broadcast -a com.drishti.DEBUG_SAY --es text __FALL__
                        dViewModel.debugTriggerFall()
                    } else {
                        dViewModel.stopSpeaking()
                        dViewModel.processSpeachTextCommand(text)
                    }
                }
            }

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
                        // Ask the controller where we are now. This used to compare against
                        // `currentRoute`, which LaunchedEffect(Unit) captures once at first
                        // composition and never updates, so the guard was testing a route
                        // that had long since gone stale.
                        val here = navController.currentDestination?.route
                        if (route.isNotBlank() && here != route) {
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
                    NavTabItem("dashboard", Icons.Default.Mic, "मुख्य"),
                    NavTabItem("vision", Icons.Default.PhotoCamera, "कॅमेरा"),
                    NavTabItem("navigation", Icons.Default.DirectionsWalk, "नकाशा"),
                    NavTabItem("sos", Icons.Default.Shield, "मदत"),
                    NavTabItem("settings", Icons.Default.Settings, "सेटिंग")
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
                                containerColor = AccentPrimary,
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
                                    // Transparent so the glass layer below shows through;
                                    // NavigationBar would otherwise paint over it.
                                    containerColor = Color.Transparent,
                                    tonalElevation = 0.dp,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .tonalSurface(RoundedCornerShape(28.dp), strong = true)
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
                                                    color = if (isSelected) AccentPrimary else TextSecondaryDark
                                                )
                                            },
                                            colors = NavigationBarItemDefaults.colors(
                                                indicatorColor = AccentPrimary
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
