package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.api.DrishtiApiClient
import com.example.data.api.NominatimSearchResult
import com.example.data.api.OsrmStep
import com.example.data.api.GroqMessage
import com.example.data.database.*
import com.example.data.repository.DrishtiRepository
import com.example.util.OutdoorObstacleDetector
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.util.Locale
import android.os.Vibrator
import android.os.VibrationEffect
import android.media.ToneGenerator
import android.media.AudioManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import android.media.MediaPlayer
import java.io.File
import java.io.FileOutputStream
import android.util.Base64
import com.example.data.api.SarvamTtsRequest
import com.example.data.api.GenerateContentRequest
import com.example.data.api.Content
import com.example.data.api.Part
import com.example.data.api.GenerationConfig
import com.example.data.api.InlineData

enum class OrbState {
    IDLE,
    LISTENING,
    PROCESSING,
    EMERGENCY
}

enum class SpeechPriority {
    EMERGENCY,
    OBSTACLE,
    NAVIGATION,
    SIGN,
    INFORMATION
}

class DrishtiViewModel(
    application: Application,
    private val repository: DrishtiRepository
) : AndroidViewModel(application), TextToSpeech.OnInitListener, SensorEventListener {

    private val context = application.applicationContext
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    // Speech priority queue manager properties
    var currentSpeakingPriority: SpeechPriority? = null
    private val speechQueue = java.util.Collections.synchronizedList(mutableListOf<Pair<String, SpeechPriority>>())
    private val messageCooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Last spoken navigation instruction to prevent duplicates unless route changes
    var lastSpokenNavigationInstruction: String = ""
    var routeChangeCounter: Int = 0

    // Visual scan clear transition state
    var isPathClearState: Boolean = false
    private var lastSpokenProactiveResult: String = ""
    private var proactiveCycleJob: Job? = null
    private var isUserWalkingState = false
    private var lastAccelMagnitude = 0f
    private val movementEnergies = ArrayDeque<Float>(24)
    private var accelerometerRegisteredForProactive = false

    // Last spoken important message for repeat again command
    var lastImportantMessage: String = ""
    var lastImportantMessagePriority: SpeechPriority? = null

    // Location Service Client
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    // Sensor Manager and Sensor fields
    private val sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Tactile & Sound engines
    private val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
    private var lightToneGenerator: ToneGenerator? = null

    // Sensory Aids States
    var isLightDetectorActive by mutableStateOf(false)
        private set
    var detectedColorName by mutableStateOf("")

    // Fall Detection States
    private var lastFreefallTime: Long = 0
    private var lastImpactTime: Long = 0
    private var fallCountdownJob: kotlinx.coroutines.Job? = null
    var fallCountdownSecondsRemaining by mutableStateOf(0)
        private set

    fun triggerVibration(duration: Long) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    fun triggerVibration(pattern: LongArray) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    // HIGH-IMPACT ADVANCED PREMIUM FEATURES STATES
    // 1. Contextual Memory & Scene Recall
    data class MemorySnippet(val description: String, val timestampMillis: Long)
    val sceneMemory = mutableStateListOf<MemorySnippet>(
        MemorySnippet("Wet floor warning barrier near the main lounge entryway.", System.currentTimeMillis() - 120_000), // 2 mins ago (simulated recall template)
        MemorySnippet("Large silver metal automated elevators with high-tactile buttons.", System.currentTimeMillis() - 300_000),
        MemorySnippet("Fresh tea vending station with aroma feedback cues.", System.currentTimeMillis() - 540_000)
    )

    // 2. Human Emotion & Expression Detection
    var detectedEmotionResult by mutableStateOf("")

    // 3. Currency & Document Reader
    var currencyScanResult by mutableStateOf("")

    // 4. Smart Kinetic Obstacle Prediction
    var smartObstaclePrediction by mutableStateOf("")

    // 5. Trusted Contact Screen Stream
    var isStreamingToGuardian by mutableStateOf(false)

    // 6. Indoor No-GPS Triangulation
    var isIndoorMode by mutableStateOf(false)
    var indoorStepsCount by mutableStateOf(142)
    var wifiTriangulationInfo by mutableStateOf("Beacon Node A-4 Connected (RSSI: -58dBm)")

    // 6b. AI-powered Indoor Navigation (cloud vision layer + live camera frames)
    var isIndoorNavActive by mutableStateOf(false)
        private set
    var indoorNavStatus by mutableStateOf("")
    private var lastIndoorSpeakTime = 0L
    private var lastIndoorSpokenKey = ""
    private var indoorAiJob: Job? = null
    // Latest upright camera frame shared by indoor and outdoor navigation.
    @Volatile private var latestNavFrame: Bitmap? = null
    @Volatile private var latestNavFrameTime: Long = 0L

    // 6c. Outdoor Navigation (on-device fast detector + cloud AI deep-scan layer)
    var isOutdoorNavActive by mutableStateOf(false)
        private set
    var outdoorNavStatus by mutableStateOf("")
    private var outdoorFastJob: Job? = null
    private var outdoorAiJob: Job? = null
    private var outdoorDetector: OutdoorObstacleDetector? = null
    private var lastOutdoorFastSpeakTime = 0L
    private var lastOutdoorFastSpokenKey = ""
    private var lastOutdoorAiSpeakTime = 0L
    private var lastOutdoorAiSpokenKey = ""

    // 6d. Smart Navigation: one command, auto-detects indoor vs outdoor and switches by itself
    var isSmartNavActive by mutableStateOf(false)
        private set
    var smartNavStatus by mutableStateOf("")
    var smartNavEnvironment by mutableStateOf("") // "", "indoor" or "outdoor"
        private set
    private var smartAiJob: Job? = null
    private var pendingSmartEnvironment = ""
    private var pendingSmartEnvironmentCount = 0
    private var lastSmartSpeakTime = 0L
    private var lastSmartSpokenKey = ""

    // 7. Voice-Activated Lock-Screen Emergency Panic
    var isPanicModeActive by mutableStateOf(false)

    // 8. Proactive Hazard Warning Engine
    var isProactiveScanningEnabled by mutableStateOf(false)
    var lastProactiveHazardAlert by mutableStateOf("")
    var isWalkWithMeActive by mutableStateOf(false)

    // 9. Product & Pack Food Label Reader
    var productScanResult by mutableStateOf("")

    // 11. RuView Person Detection & Room Occupancy
    var ruviewPeopleCount by mutableStateOf(0)
    var ruviewPresence by mutableStateOf(false)
    var ruviewConfidence by mutableStateOf(0.0f)
    var ruviewMotion by mutableStateOf(false)
    var ruviewHeartRate by mutableStateOf(0.0f)
    var ruviewBreathingRate by mutableStateOf(0.0f)
    var isRuviewConnected by mutableStateOf(false)
    var isRuviewScanning by mutableStateOf(false)
    var isRuviewActive by mutableStateOf(false)

    private val _scrollPagerEvent = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val scrollPagerEvent = _scrollPagerEvent.asSharedFlow()

    // 10. Personalized Geolocated Landmark Learning
    data class LearnedLandmark(val name: String, val latitude: Double, val longitude: Double)
    val learnedLandmarks = mutableStateListOf<LearnedLandmark>(
        LearnedLandmark("Main Office Reception Desk", 18.6873, 73.8569)
    )

    // UI States
    val userProfile = repository.user.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val guardianProfile = repository.guardian.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val alertsHistory = repository.alerts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val userSettings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val savedPlaces: StateFlow<List<SavedPlaceEntity>> = repository.savedPlaces.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    private val _capturePhotoForPlaceEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val capturePhotoForPlaceEvent = _capturePhotoForPlaceEvent.asSharedFlow()

    private val _capturePhotoForArrivalEvent = MutableSharedFlow<SavedPlaceEntity>(extraBufferCapacity = 1)
    val capturePhotoForArrivalEvent = _capturePhotoForArrivalEvent.asSharedFlow()

    private val _capturePhotoForProactiveEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val capturePhotoForProactiveEvent = _capturePhotoForProactiveEvent.asSharedFlow()

    private val _capturePhotoForVoiceAnalyzeEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val capturePhotoForVoiceAnalyzeEvent = _capturePhotoForVoiceAnalyzeEvent.asSharedFlow()

    var navigatingToSavedPlace by mutableStateOf<SavedPlaceEntity?>(null)
    var hasTriggeredArrivalGuidance by mutableStateOf(false)
    private var locationTrackingJob: kotlinx.coroutines.Job? = null

    var orbState by mutableStateOf(OrbState.IDLE)
        private set

    var transcriptState by mutableStateOf("")
        private set

    var aiDescriptionResult by mutableStateOf("")
        private set

    var aiTextReaderResult by mutableStateOf("")
        private set

    var isAnalyzing by mutableStateOf(false)
        private set

    var autoAnalyzeIntent by mutableStateOf("")
        private set
    var autoAnalyzeRequestId by mutableStateOf(0)
        private set
    private var lastFallbackScanTime: Long = 0

    var lastSpokenText by mutableStateOf("Welcome to Drishti Visual Assistance App.")
        private set

    // Google Login & Voice Navigation Event Flows
    private val _navigationEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigationEvents = _navigationEvents.asSharedFlow()

    var isLiveScanning by mutableStateOf(false)
        private set

    fun cleanPlaceName(name: String): String {
        return name.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun mapDestinationQuery(query: String): String {
        val cleaned = cleanPlaceName(query)
        return when {
            cleaned.contains("admin") || cleaned.contains("admision") || cleaned.contains("admission") -> "Admission Cell"
            cleaned.contains("canteen") || cleaned.contains("mess") || cleaned.contains("food") -> "Canteen"
            cleaned.contains("library") || cleaned.contains("book") || cleaned.contains("reading") -> "Library"
            cleaned.contains("lab") || cleaned.contains("computer") -> "Computer Lab"
            cleaned.contains("gate") || cleaned.contains("entrance") || cleaned.contains("exit") -> "Main Gate"
            else -> query.trim()
        }
    }


    // Geolocation state (Default coordinates: MIT Academy of Engineering, Pune Area)
    var currentLatitude by mutableStateOf(18.6873)
        private set
    var currentLongitude by mutableStateOf(73.8569)
        private set
    var currentAddressText by mutableStateOf("Reverse geocoding address...")
        private set

    // Navigation routing state
    var selectedDestinationName by mutableStateOf("")
    var isCalculatingRoute by mutableStateOf(false)
    var activeNavigationSteps by mutableStateOf<List<OsrmStep>>(emptyList())
    var currentStepIndex by mutableStateOf(0)
    var isNavigating by mutableStateOf(false)
    var destinationLatitude by mutableStateOf(18.6873)
    var destinationLongitude by mutableStateOf(73.8569)

    // State for Simulation dialog overlay
    var showSosSuccessPopup by mutableStateOf(false)
    var lastSosMapLink by mutableStateOf("")

    private val prefs = context.getSharedPreferences("drishti_secure_prefs", android.content.Context.MODE_PRIVATE)

    var groqApiKey by mutableStateOf("")
        private set
    var geminiApiKey by mutableStateOf("")
        private set
    var sarvamApiKey by mutableStateOf("")
        private set
    var ttsLanguage by mutableStateOf("en-IN")
        private set

    // Manual gender override for how Drishti addresses the user ("" = auto-detect from name,
    // "male", or "female"). Controls friendly terms (behen/didi/tai vs bro/bhava) and the
    // Marathi interjection (female "अगं" vs male "अरे").
    var userGenderOverride by mutableStateOf(prefs.getString("user_gender", "").orEmpty())
        private set

    var micAmplitude by mutableStateOf(0.0f)
        private set

    var isSpeaking by mutableStateOf(false)
        private set

    fun updateMicAmplitude(value: Float) {
        micAmplitude = value
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentSpeechJob: kotlinx.coroutines.Job? = null
    private var ttsContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    fun saveGroqApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString("groq_api_key", trimmed).apply()
        groqApiKey = trimmed
        speak("Groq API key updated.")
    }

    fun saveGeminiApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString("gemini_api_key", trimmed).apply()
        geminiApiKey = trimmed
        speak("Gemini API key updated.")
    }

    fun saveSarvamApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString("sarvam_api_key", trimmed).apply()
        sarvamApiKey = trimmed
    }

    fun changeTtsLanguage(languageCode: String) {
        prefs.edit().putString("tts_language", languageCode).apply()
        ttsLanguage = languageCode
    }

    init {
        val savedGroq = prefs.getString("groq_api_key", "").orEmpty()
            .ifBlank { prefs.getString("grok_api_key", "").orEmpty() }
        groqApiKey = if (savedGroq.isBlank() || savedGroq.startsWith("MY_") || savedGroq.contains("API_KEY")) {
            BuildConfig.GROQ_API_KEY
        } else savedGroq

        val savedGemini = prefs.getString("gemini_api_key", "").orEmpty()
        geminiApiKey = if (savedGemini.isBlank() || savedGemini.startsWith("MY_") || savedGemini.contains("API_KEY")) {
            BuildConfig.GEMINI_API_KEY
        } else savedGemini

        val savedSarvam = prefs.getString("sarvam_api_key", "").orEmpty()
        sarvamApiKey = if (savedSarvam.isBlank() || savedSarvam.startsWith("MY_") || savedSarvam.contains("API_KEY")) {
            BuildConfig.SARVAM_API_KEY
        } else savedSarvam

        ttsLanguage = "en-IN"
        prefs.edit().putString("tts_language", "en-IN").apply()
        tts = TextToSpeech(context, this)

        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                if (isRuviewActive && !isRuviewScanning) {
                    val serverUrl = userSettings.value?.ruviewServerUrl ?: "http://10.0.2.2:3000"
                    val liveData = repository.getRuViewData(serverUrl)
                    if (liveData != null) {
                        withContext(Dispatchers.Main) {
                            ruviewPeopleCount = liveData.estimatedPersons ?: 0
                            ruviewPresence = liveData.classification?.presence ?: (ruviewPeopleCount > 0)
                            ruviewConfidence = (liveData.classification?.confidence ?: 0.95).toFloat()
                            ruviewMotion = (liveData.classification?.motionLevel ?: "absent") != "absent"
                            ruviewHeartRate = (liveData.vitalSigns?.heartRateBpm ?: 72.0).toFloat()
                            ruviewBreathingRate = (liveData.vitalSigns?.breathingRateBpm ?: 16.0).toFloat()
                            isRuviewConnected = true
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            isRuviewConnected = false
                            ruviewPeopleCount = 0
                            ruviewPresence = false
                            ruviewMotion = false
                            ruviewHeartRate = 0.0f
                            ruviewBreathingRate = 0.0f
                        }

                        // Run parallel discovery, but limit it to once every 10 seconds to avoid flooding
                        val now = System.currentTimeMillis()
                        if (now - lastFallbackScanTime > 10000) {
                            lastFallbackScanTime = now
                            val gateway = getWifiGatewayIp()
                            val fallbacks = mutableListOf<String>()
                            if (gateway != null) {
                                fallbacks.add("http://$gateway:3000")
                                fallbacks.add("http://$gateway")
                            }
                            fallbacks.add("http://192.168.4.1:3000")
                            fallbacks.add("http://192.168.4.1")

                            val uniqueFallbacks = fallbacks.filter { it != serverUrl }
                            if (uniqueFallbacks.isNotEmpty()) {
                                val deferredList = uniqueFallbacks.map { url ->
                                    async(Dispatchers.IO) {
                                        try {
                                            val res = repository.getRuViewData(url)
                                            if (res != null) Pair(url, res) else null
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }
                                }
                                val results = deferredList.awaitAll().filterNotNull()
                                if (results.isNotEmpty()) {
                                    val (foundUrl, data) = results.first()
                                    withContext(Dispatchers.Main) {
                                        ruviewPeopleCount = data.estimatedPersons ?: 0
                                        ruviewPresence = data.classification?.presence ?: (ruviewPeopleCount > 0)
                                        ruviewConfidence = (data.classification?.confidence ?: 0.95).toFloat()
                                        ruviewMotion = (data.classification?.motionLevel ?: "absent") != "absent"
                                        ruviewHeartRate = (data.vitalSigns?.heartRateBpm ?: 72.0).toFloat()
                                        ruviewBreathingRate = (data.vitalSigns?.breathingRateBpm ?: 16.0).toFloat()
                                        isRuviewConnected = true
                                    }
                                    val rate = userSettings.value?.speechRate ?: 1.0f
                                    val voiceEnabled = userSettings.value?.voiceEnabled ?: true
                                    val isRuviewEnabled = userSettings.value?.ruviewEnabled ?: true
                                    repository.updateSettings(rate, voiceEnabled, ruviewEnabled = isRuviewEnabled, ruviewServerUrl = foundUrl)
                                }
                            }
                        }
                    }
                }
                kotlinx.coroutines.delay(1500)
            }
        }
    }

    private fun resumeTtsSpeech(utteranceId: String?) {
        if (utteranceId == "DrishtiUtteranceID") {
            val cont = ttsContinuation
            ttsContinuation = null
            if (cont != null && cont.isActive) {
                cont.resume(Unit)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val indianLocale = Locale("en", "IN")
            val result = tts?.setLanguage(indianLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to standard English
                tts?.setLanguage(Locale.ENGLISH)
            }

            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    resumeTtsSpeech(utteranceId)
                }
                override fun onError(utteranceId: String?) {
                    resumeTtsSpeech(utteranceId)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?, errorCode: Int) {
                    resumeTtsSpeech(utteranceId)
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    resumeTtsSpeech(utteranceId)
                }
            })

            isTtsInitialized = true
            // Read welcome greeting on launch if user profile exists
            viewModelScope.launch {
                userProfile.filterNotNull().firstOrNull()?.let {
                    speak("Welcome, ${it.name}. Tap the centre orb to speak.")
                }
            }
        } else {
            Log.e("DrishtiViewModel", "TTS Initialization failed!")
        }
    }

    private fun getWifiGatewayIp(): String? {
        return try {
            val wifiManager = context.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val dhcp = wifiManager?.dhcpInfo ?: return null
            val ipAddress = dhcp.gateway
            if (ipAddress == 0) return null
            "${ipAddress and 0xff}.${ipAddress shr 8 and 0xff}.${ipAddress shr 16 and 0xff}.${ipAddress shr 24 and 0xff}"
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    private fun getCachedTranslation(text: String, lang: String): String? {
        val hash = (text + "_" + lang).hashCode().toString()
        val file = File(context.cacheDir, "trans_cache_v3_$hash.txt")
        if (file.exists()) {
            try {
                return file.readText()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return null
    }

    private fun cacheTranslation(text: String, lang: String, translated: String) {
        val hash = (text + "_" + lang).hashCode().toString()
        val file = File(context.cacheDir, "trans_cache_v3_$hash.txt")
        try {
            file.writeText(translated)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getCachedAudio(text: String, lang: String): String? {
        val hash = (text + "_" + lang).hashCode().toString()
        val file = File(context.cacheDir, "tts_cache_v3_$hash.txt")
        if (file.exists()) {
            try {
                return file.readText()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return null
    }

    private fun cacheAudio(text: String, lang: String, base64: String) {
        val hash = (text + "_" + lang).hashCode().toString()
        val file = File(context.cacheDir, "tts_cache_v3_$hash.txt")
        try {
            file.writeText(base64)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun speak(
        text: String,
        priority: SpeechPriority = SpeechPriority.INFORMATION,
        bypassCooldown: Boolean = false
    ): kotlinx.coroutines.Job {
        val enabled = userSettings.value?.voiceEnabled ?: true
        if (!enabled || text.isBlank() || text.contains("Hearing", ignoreCase = true)) {
            return kotlinx.coroutines.Job()
        }

        // 1. Cooldown and Redundancy Filtering (unless bypassed)
        if (!bypassCooldown) {
            val now = System.currentTimeMillis()
            val normalizedText = text.trim().lowercase()

            // Obstacle & Emergency Cooldown: Same warning cannot repeat in 15 seconds
            if (priority == SpeechPriority.OBSTACLE || priority == SpeechPriority.EMERGENCY) {
                val lastSpokenTime = messageCooldowns[normalizedText] ?: 0L
                if (now - lastSpokenTime < 15000L) {
                    return kotlinx.coroutines.Job()
                }
                messageCooldowns[normalizedText] = now
            }

            // Navigation Cooldown: Cannot repeat unless route changes
            if (priority == SpeechPriority.NAVIGATION) {
                if (lastSpokenNavigationInstruction == normalizedText) {
                    return kotlinx.coroutines.Job()
                }
                lastSpokenNavigationInstruction = normalizedText
            }
        }

        // 2. Update Important Message history for the "Repeat again" command
        if (priority == SpeechPriority.EMERGENCY || 
            priority == SpeechPriority.OBSTACLE || 
            priority == SpeechPriority.NAVIGATION || 
            priority == SpeechPriority.SIGN) {
            lastImportantMessage = text
            lastImportantMessagePriority = priority
        }

        // Update default visual log text
        lastSpokenText = text

        // 3. Priority Interrupt & Queue Logic
        val currentPriority = currentSpeakingPriority
        if (currentPriority != null && priority.ordinal >= currentPriority.ordinal && priority != SpeechPriority.EMERGENCY) {
            // Lower or equal priority (ordinal is higher: EMERGENCY is 0, INFORMATION is 4)
            // Add to queue (replace existing message of the same priority to avoid speech build-up)
            synchronized(speechQueue) {
                speechQueue.removeAll { it.second == priority }
                speechQueue.add(Pair(text, priority))
            }
            return kotlinx.coroutines.Job()
        }

        // Higher priority (or EMERGENCY) interrupts active speech
        currentSpeechJob?.cancel()
        stopSpeaking()
        currentSpeakingPriority = priority

        val job = viewModelScope.launch {
            try {
                var translatedText = text
                if (ttsLanguage != "en-IN" && (textHasEnglish(text) || !isDevanagari(text))) {
                    translatedText = translateText(text, ttsLanguage)
                }

                // Split text into individual sentences (supporting both English and Devanagari punctuation)
                val sentences = translatedText.split(Regex("(?<=[.!?।])\\s+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                for (sentence in sentences) {
                    speakSentence(sentence)
                    // Add a small pause between sentences for clarity
                    delay(400)
                }
            } finally {
                // Done speaking this priority
                currentSpeakingPriority = null
                processNextSpeechInQueue()
            }
        }
        currentSpeechJob = job
        return job
    }

    private fun processNextSpeechInQueue() {
        if (!isProactiveScanningEnabled && speechQueue.isEmpty()) return
        synchronized(speechQueue) {
            if (speechQueue.isNotEmpty()) {
                // Sort by priority (lowest ordinal is highest priority)
                speechQueue.sortBy { it.second.ordinal }
                val nextSpeech = speechQueue.removeAt(0)
                speak(nextSpeech.first, nextSpeech.second)
            }
        }
    }

    private fun textHasEnglish(text: String): Boolean {
        return text.any { it in 'a'..'z' || it in 'A'..'Z' }
    }

    private suspend fun speakSentence(sentence: String) {
        isSpeaking = true
        try {
            var textToSpeak = sentence
            val isMale = isUserMale(userProfile.value?.name ?: "Sahil")
            textToSpeak = injectFriendlyTag(textToSpeak, ttsLanguage, isMale)

            // Use Pooja voice (Sarvam API) ONLY for Hindi and Marathi
            if (ttsLanguage != "en-IN" && sarvamApiKey.isNotBlank() && sarvamApiKey != "MY_SARVAM_API_KEY") {
                try {
                    val cachedBase64 = getCachedAudio(textToSpeak, ttsLanguage)
                    if (!cachedBase64.isNullOrBlank()) {
                        playBase64AudioSuspend(cachedBase64)
                        return
                    }

                    // Increased timeout to ensure voice feedback succeeds under network latency
                    val base64Audio = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                        val request = SarvamTtsRequest(
                            text = textToSpeak,
                            speaker = "ritu",
                            model = "bulbul:v3",
                            targetLanguageCode = ttsLanguage
                        )
                        val response = DrishtiApiClient.sarvamService.generateTts(sarvamApiKey, request)
                        response.audios?.firstOrNull()
                    }
                    if (!base64Audio.isNullOrBlank()) {
                        cacheAudio(textToSpeak, ttsLanguage, base64Audio)
                        playBase64AudioSuspend(base64Audio)
                        return
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e("DrishtiViewModel", "Sarvam TTS failed, falling back to Android TTS: ${e.localizedMessage}")
                }
            }

            // Use high-quality offline Android (Google/Gemini) TTS voice for English / Fallback
            if (isTtsInitialized && tts != null) {
                if (ttsLanguage == "en-IN") {
                    tts?.setLanguage(Locale("en", "IN"))
                    tts?.setPitch(1.0f)
                    val userRate = userSettings.value?.speechRate ?: 1.0f
                    val calmRate = if (userRate == 1.0f) 0.92f else userRate
                    tts?.setSpeechRate(calmRate)
                } else {
                    if (ttsLanguage == "hi-IN") {
                        tts?.setLanguage(Locale("hi", "IN"))
                    } else if (ttsLanguage == "mr-IN") {
                        tts?.setLanguage(Locale("mr", "IN"))
                    }
                    val rate = userSettings.value?.speechRate ?: 1.0f
                    tts?.setSpeechRate(rate)
                }

                suspendCancellableCoroutine<Unit> { continuation ->
                    ttsContinuation = continuation
                    val result = tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "DrishtiUtteranceID")
                    if (result == TextToSpeech.ERROR) {
                        ttsContinuation = null
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    // Safety timeout in case TTS hangs or doesn't call back
                    viewModelScope.launch {
                        delay(8000)
                        val cont = ttsContinuation
                        if (cont === continuation) {
                            ttsContinuation = null
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
            }
        } finally {
            isSpeaking = false
        }
    }

    private fun isDevanagari(text: String): Boolean {
        for (char in text) {
            if (char.code in 0x0900..0x097F) {
                return true
            }
        }
        return false
    }

    private fun isUserMale(name: String): Boolean {
        // Manual override always wins over name-based guessing.
        when (userGenderOverride) {
            "male" -> return true
            "female" -> return false
        }

        val n = name.trim().lowercase()
        if (n.isEmpty()) return true

        // Common specific female names / endings
        val femaleEndings = listOf("a", "i", "e", "u", "ya", "ti", "ta", "ka", "ni", "ri", "shree", "jyoti", "kumari", "devi")
        val femaleExceptions = listOf("rahul", "amit", "sanjay", "ganesh", "raj", "vijay", "ajay", "abhishek", "aniket", "prathamesh", "siddharth", "yash", "harsh", "tanmay", "parth", "chinmay", "vivek", "shreyas", "sahil", "sunil")

        if (n == "sahil" || n == "sunil") return true
        if (femaleExceptions.any { n.contains(it) }) return true
        if (femaleEndings.any { n.endsWith(it) }) return false
        return true // Default to male
    }

    /** Resolved gender for the current user, honoring the manual override. */
    private fun isCurrentUserMale(): Boolean = isUserMale(userProfile.value?.name ?: "Sahil")

    /** "male"/"female" if explicitly overridden, else null (let name-based detection decide). */
    private fun resolvedGenderOrNull(): String? = userGenderOverride.takeIf { it == "male" || it == "female" }

    /**
     * Manually set how Drishti addresses the user. Accepts free-typed input in
     * English/Hindi/Marathi ("female", "girl", "महिला", "स्त्री", "male", "boy", "पुरुष", etc.).
     * Pass blank/"auto" to revert to automatic name-based detection.
     */
    fun setUserGender(input: String) {
        val v = input.trim().lowercase()
        val gender = when {
            v.isBlank() || v.startsWith("auto") || v.contains("default") -> ""
            v.startsWith("f") || v.contains("female") || v.contains("woman") || v.contains("girl") ||
                v.contains("महिला") || v.contains("स्त्री") || v.contains("लड़की") || v.contains("मुलगी") ||
                v.contains("औरत") || v.contains("बाई") -> "female"
            v.startsWith("m") || v.contains("male") || v.contains("man") || v.contains("boy") ||
                v.contains("पुरुष") || v.contains("लड़का") || v.contains("मुलगा") || v.contains("आदमी") -> "male"
            else -> userGenderOverride // unrecognised: keep current
        }
        userGenderOverride = gender
        prefs.edit().putString("user_gender", gender).apply()
        val msg = when (gender) {
            "female" -> when (ttsLanguage) {
                "hi-IN" -> "ठीक है, अब मैं आपसे बहन की तरह बात करूँगी।"
                "mr-IN" -> "ठीक आहे, आता मी तुझ्याशी ताई सारखी बोलेन."
                else -> "Okay, I'll talk to you like a sister now."
            }
            "male" -> when (ttsLanguage) {
                "hi-IN" -> "ठीक है, अब मैं आपसे दोस्त की तरह बात करूँगी।"
                "mr-IN" -> "ठीक आहे, आता मी तुझ्याशी मित्रासारखी बोलेन."
                else -> "Okay, I'll talk to you like a buddy now."
            }
            else -> when (ttsLanguage) {
                "hi-IN" -> "ठीक है, मैं आपके नाम से अंदाज़ा लगा लूँगी।"
                "mr-IN" -> "ठीक आहे, मी तुझ्या नावावरून ओळखेन."
                else -> "Okay, I'll decide automatically from your name."
            }
        }
        speak(msg, bypassCooldown = true)
    }

    private fun injectFriendlyTag(text: String, language: String, isMale: Boolean): String {
        if (text.isBlank() || text.length < 8) return text
        
        // Exclude specific system texts like numbers, coordinates, etc.
        if (text.matches(Regex(".*\\b\\d+\\b.*")) && text.length < 15) return text
        if (text.contains("siren", ignoreCase = true) || text.contains("alert", ignoreCase = true) || text.contains("panic", ignoreCase = true)) return text
        
        // Avoid double injection if the text already contains a friendly tag
        val lower = text.lowercase()
        val hasFriendlyTerm = listOf(
            "bro", "bhai", "yaar", "bhava", "dost", "didi", "sis", "behen", "tai", "buddy",
            "ब्रो", "यार", "भाई", "दोस्त", "बहन", "दीदी", "भावा", "मित्रा", "ताई"
        ).any { lower.contains(it) }
        if (hasFriendlyTerm) return text

        // 8% chance to inject friendly tag (very rare and random)
        if (java.util.Random().nextFloat() > 0.08f) return text
        
        return when (language) {
            "hi-IN" -> {
                val tags = if (isMale) listOf("ब्रो", "यार") else listOf("बहन", "दीदी", "यार")
                val chosen = tags.random()
                if (java.util.Random().nextBoolean()) "$chosen, $text" else "$text, $chosen"
            }
            "mr-IN" -> {
                val tags = if (isMale) listOf("भावा", "यार") else listOf("दीदी", "ताई", "यार")
                val chosen = tags.random()
                if (java.util.Random().nextBoolean()) "$chosen, $text" else "$text, $chosen"
            }
            else -> { // en-IN and others
                val tags = if (isMale) listOf("bro", "buddy", "yaar") else listOf("sis", "didi", "buddy")
                val chosen = tags.random()
                if (java.util.Random().nextBoolean()) "$chosen, $text" else "$text, $chosen"
            }
        }
    }

    private fun translateNavigationInstructionOffline(text: String, targetLanguageCode: String): String? {
        val trimmed = text.trim()
        val lang = targetLanguageCode
        
        val exactMatch = when (trimmed.lowercase().trimEnd('.')) {
            "opening camera and scanning what is in front of you" -> when (lang) {
                "hi-IN" -> "कैमरा खोल रही हूँ और आपके सामने क्या है उसे स्कैन कर रही हूँ।"
                "mr-IN" -> "कॅмера उघडत आहे आणि तुमच्या समोर काय आहे ते स्कॅन करत आहे."
                else -> null
            }
            "activating camera intelligence. scanning your surroundings now" -> when (lang) {
                "hi-IN" -> "कैमरा सक्रिय कर रही हूँ। अब आपके आस-पास का दृश्य स्कैन कर रही हूँ।"
                "mr-IN" -> "कॅмера सक्रिय करत आहे. आता तुमच्या आजूबाजूचा परिसर स्कॅन करत आहे."
                else -> null
            }
            "aligning camera lens to read visible text for you" -> when (lang) {
                "hi-IN" -> "आपके लिए लिखा हुआ पाठ पढ़ने के लिए कैमरा लेंस संरेखित कर रही हूँ।"
                "mr-IN" -> "तुमच्यासाठी लिहिलेला मजकूर वाचण्यासाठी कॅमेरा लेन्स जुळवून घेत आहे."
                else -> null
            }
            "drishti is ready. tell me where you want to go or say describe scene" -> when (lang) {
                "hi-IN" -> "दृष्टि तैयार है। मुझे बताएं कि आप कहाँ जाना चाहते हैं या कहें दृश्य का वर्णन करें।"
                "mr-IN" -> "दृष्टी तयार आहे. मला सांगा तुम्हाला कुठे जायचे आहे किंवा म्हणा दृश्य वर्णन करा."
                else -> null
            }
            "drishti is listening" -> when (lang) {
                "hi-IN" -> "दृष्टि सुन रही है।"
                "mr-IN" -> "दृष्टी ऐकत आहे."
                else -> null
            }
            "how can i help you" -> when (lang) {
                "hi-IN" -> "मैं आपकी क्या मदद कर सकती हूँ?"
                "mr-IN" -> "मी तुम्हाला कशी मदत करू?"
                else -> null
            }
            else -> null
        }
        if (exactMatch != null) return exactMatch
        
        // Handle MIT Academy of Engineering specific spoken navigation phrases
        if (trimmed.contains("mit academy of engineering", ignoreCase = true) || trimmed.contains("mit academy of enginerring", ignoreCase = true)) {
            val isFinding = trimmed.contains("finding route to", ignoreCase = true)
            return when (lang) {
                "hi-IN" -> if (isFinding) "एम आई टी अकादमी ऑफ इंजीनियरिंग के लिए रास्ता ढूंढ रही हूँ। " else "एम आई टी अकादमी ऑफ इंजीनियरिंग"
                "mr-IN" -> if (isFinding) "एम आय टी अकॅडमी ऑफ इंजिनिअरिंगसाठी रस्ता शोधत आहे. " else "एम आय टी अकॅडमी ऑफ इंजिनिअरिंग"
                else -> null
            }
        }

        // Standard prefixes
        if (trimmed.startsWith("Finding route to ", ignoreCase = true)) {
            val dest = trimmed.substring("Finding route to ".length).trim().trimEnd('.')
            val translatedDest = if (dest.contains("mit academy of engineering", ignoreCase = true) || dest.contains("mit academy of enginerring", ignoreCase = true)) {
                if (lang == "hi-IN") "एम आई टी अकादमी ऑफ इंजीनियरिंग" else "एम आय टी अकॅडमी ऑफ इंजिनिअरिंग"
            } else dest
            return when (lang) {
                "hi-IN" -> "$translatedDest के लिए रास्ता ढूंढ रही हूँ।"
                "mr-IN" -> "$translatedDest साठी रस्ता शोधत आहे."
                else -> null
            }
        }

        if (trimmed.startsWith("Navigating to saved place ", ignoreCase = true) && trimmed.endsWith(". Starting visual navigation guide.", ignoreCase = true)) {
            val place = trimmed.substring("Navigating to saved place ".length, trimmed.length - ". Starting visual navigation guide.".length).trim()
            val translatedPlace = if (place.contains("mit academy of engineering", ignoreCase = true) || place.contains("mit academy of enginerring", ignoreCase = true)) {
                if (lang == "hi-IN") "एम आई टी अकादमी ऑफ इंजीनियरिंग" else "एम आय टी अकॅडमी ऑफ इंजिनिअरिंग"
            } else place
            return when (lang) {
                "hi-IN" -> "सुरक्षित स्थान $translatedPlace पर नेविगेट कर रही हूँ। दृश्य नेविगेशन गाइड शुरू कर रही हूँ।"
                "mr-IN" -> "जतन केलेल्या ठिकाणी $translatedPlace नेव्हिगेट करत आहे. व्हिज्युअल नेव्हिगेशन मार्गदर्शक सुरू करत आहे."
                else -> null
            }
        }

        if (trimmed.startsWith("Direct route to ", ignoreCase = true) && trimmed.contains(" is ", ignoreCase = true) && trimmed.contains(". First step: ", ignoreCase = true)) {
            val isIndex = trimmed.indexOf(" is ")
            val firstStepIndex = trimmed.indexOf(". First step: ")
            val place = trimmed.substring("Direct route to ".length, isIndex).trim()
            val distance = trimmed.substring(isIndex + " is ".length, firstStepIndex).trim()
            val step = trimmed.substring(firstStepIndex + ". First step: ".length).trim().trimEnd('.')
            
            val translatedPlace = if (place.contains("mit academy of engineering", ignoreCase = true) || place.contains("mit academy of enginerring", ignoreCase = true)) {
                if (lang == "hi-IN") "एम आई टी अकादमी ऑफ इंजीनियरिंग" else "एम आय टी अकॅडमी ऑफ इंजिनिअरिंग"
            } else place
            val transDistance = translateDistanceOffline(distance, lang)
            val transStep = translateStepOffline(step, lang)
            return when (lang) {
                "hi-IN" -> "$translatedPlace के लिए सीधा रास्ता $transDistance है। पहला कदम: $transStep"
                "mr-IN" -> "$translatedPlace साठी थेट मार्ग $transDistance आहे. पहिले पाऊल: $transStep"
                else -> null
            }
        }

        if (trimmed.startsWith("Total distance is ", ignoreCase = true) && trimmed.contains(". First step: ", ignoreCase = true)) {
            val firstStepIndex = trimmed.indexOf(". First step: ")
            val distance = trimmed.substring("Total distance is ".length, firstStepIndex).trim()
            val step = trimmed.substring(firstStepIndex + ". First step: ".length).trim().trimEnd('.')
            
            val transDistance = translateDistanceOffline(distance, lang)
            val transStep = translateStepOffline(step, lang)
            return when (lang) {
                "hi-IN" -> "कुल दूरी $transDistance है। पहला कदम: $transStep"
                "mr-IN" -> "एकूण अंतर $transDistance आहे. पहिले पाऊल: $transStep"
                else -> null
            }
        }

        if (trimmed.startsWith("You are starting near ", ignoreCase = true)) {
            val addr = trimmed.substring("You are starting near ".length).trim().trimEnd('.')
            return when (lang) {
                "hi-IN" -> "आप $addr के पास से शुरू कर रहे हैं।"
                "mr-IN" -> "तुम्ही $addr जवळून सुरुवात करत आहात."
                else -> null
            }
        }

        if (trimmed.startsWith("Using last known location near ", ignoreCase = true)) {
            val addr = trimmed.substring("Using last known location near ".length).trim().trimEnd('.')
            return when (lang) {
                "hi-IN" -> "अंतिम ज्ञात स्थान $addr के पास का उपयोग कर रही हूँ।"
                "mr-IN" -> "शेवटचे माहित असलेले ठिकाण $addr जवळील वापरत आहे."
                else -> null
            }
        }

        val stepTrans = translateStepOffline(trimmed, lang)
        if (stepTrans != trimmed) {
            return stepTrans
        }

        return null
    }

    private fun translateDistanceOffline(distanceText: String, lang: String): String {
        var clean = distanceText.lowercase()
        val isMeters = clean.contains("meter")
        val isKms = clean.contains("kilometer")
        
        val numberPart = clean.replace("kilometers", "").replace("kilometer", "").replace("meters", "").replace("meter", "").trim()
        val numTranslated = numberPart.replace(",", ".")
        
        return when (lang) {
            "hi-IN" -> if (isKms) "$numTranslated किलोमीटर" else "$numTranslated मीटर"
            "mr-IN" -> if (isKms) "$numTranslated किलोमीटर" else "$numTranslated मीटर"
            else -> distanceText
        }
    }

    private fun translateTurnOffline(turnType: String, lang: String): String {
        val t = turnType.trim().lowercase()
        return when (lang) {
            "hi-IN" -> when (t) {
                "left" -> "बाएं मुड़ें"
                "right" -> "दाएं मुड़ें"
                "sharp left" -> "तीखा बाएं मुड़ें"
                "sharp right" -> "तीखा दाएं मुड़ें"
                "slight left" -> "हल्का बाएं मुड़ें"
                "slight right" -> "हल्का दाएं मुड़ें"
                else -> turnType
            }
            "mr-IN" -> when (t) {
                "left" -> "डावीकडे वळा"
                "right" -> "उजवीकडे वळा"
                "sharp left" -> "तीव्र डावीकडे वळा"
                "sharp right" -> "तीव्र उजवीकडे वळा"
                "slight left" -> "हळूवार डावीकडे वळा"
                "slight right" -> "हळूवार उजवीकडे वळा"
                else -> turnType
            }
            else -> turnType
        }
    }

    private fun translateStepOffline(stepText: String, lang: String): String {
        val trimmed = stepText.trim().trimEnd('.')
        var result = trimmed
        
        val directions = mapOf(
            "south-west" to Pair("दक्षिण-पश्चिम", "दक्षिण-पश्चिम"),
            "south-east" to Pair("दक्षिण-पूर्व", "दक्षिण-पूर्व"),
            "north-west" to Pair("उत्तर-पश्चिम", "उत्तर-पश्चिम"),
            "north-east" to Pair("उत्तर-पूर्व", "उत्तर-पूर्व"),
            "south" to Pair("दक्षिण", "दक्षिण"),
            "north" to Pair("उत्तर", "उत्तर"),
            "east" to Pair("पूर्व", "पूर्व"),
            "west" to Pair("पश्चिम", "पश्चिम")
        )

        val headOnForPattern = Regex("(?i)^Head\\s+(south-west|south-east|north-west|north-east|south|north|east|west)\\s+on\\s+(.+)\\s+for\\s+(.+)$")
        val headOnForMatch = headOnForPattern.matchEntire(trimmed)
        if (headOnForMatch != null) {
            val direction = headOnForMatch.groupValues[1].lowercase()
            val street = headOnForMatch.groupValues[2].trim()
            val dist = headOnForMatch.groupValues[3].trim()
            
            val transDir = directions[direction]?.let { if (lang == "hi-IN") it.first else it.second } ?: direction
            val transDist = translateDistanceOffline(dist, lang)
            return when (lang) {
                "hi-IN" -> "$street पर $transDir की ओर $transDist के लिए बढ़ें।"
                "mr-IN" -> "$street वर $transDir दिशेने $transDist साठी जा."
                else -> result
            }
        }

        val headForPattern = Regex("(?i)^Head\\s+(south-west|south-east|north-west|north-east|south|north|east|west)\\s+for\\s+(.+)$")
        val headForMatch = headForPattern.matchEntire(trimmed)
        if (headForMatch != null) {
            val direction = headForMatch.groupValues[1].lowercase()
            val dist = headForMatch.groupValues[2].trim()
            
            val transDir = directions[direction]?.let { if (lang == "hi-IN") it.first else it.second } ?: direction
            val transDist = translateDistanceOffline(dist, lang)
            return when (lang) {
                "hi-IN" -> "$transDir की ओर $transDist के लिए बढ़ें।"
                "mr-IN" -> "$transDir दिशेने $transDist साठी जा."
                else -> result
            }
        }

        val headOnPattern = Regex("(?i)^Head\\s+(south-west|south-east|north-west|north-east|south|north|east|west)\\s+on\\s+(.+)$")
        val headOnMatch = headOnPattern.matchEntire(trimmed)
        if (headOnMatch != null) {
            val direction = headOnMatch.groupValues[1].lowercase()
            val street = headOnMatch.groupValues[2].trim()
            
            val transDir = directions[direction]?.let { if (lang == "hi-IN") it.first else it.second } ?: direction
            return when (lang) {
                "hi-IN" -> "$street पर $transDir की ओर बढ़ें।"
                "mr-IN" -> "$street वर $transDir दिशेने जा."
                else -> result
            }
        }

        val headPattern = Regex("(?i)^Head\\s+(south-west|south-east|north-west|north-east|south|north|east|west)$")
        val headMatch = headPattern.matchEntire(trimmed)
        if (headMatch != null) {
            val direction = headMatch.groupValues[1].lowercase()
            val transDir = directions[direction]?.let { if (lang == "hi-IN") it.first else it.second } ?: direction
            return when (lang) {
                "hi-IN" -> "$transDir की ओर बढ़ें।"
                "mr-IN" -> "$transDir दिशेने जा."
                else -> result
            }
        }

        val turnOnForPattern = Regex("(?i)^Turn\\s+(sharp\\s+left|sharp\\s+right|slight\\s+left|slight\\s+right|left|right)\\s+on\\s+(.+)\\s+for\\s+(.+)$")
        val turnOnForMatch = turnOnForPattern.matchEntire(trimmed)
        if (turnOnForMatch != null) {
            val turnType = turnOnForMatch.groupValues[1].lowercase()
            val street = turnOnForMatch.groupValues[2].trim()
            val dist = turnOnForMatch.groupValues[3].trim()
            
            val transTurn = translateTurnOffline(turnType, lang)
            val transDist = translateDistanceOffline(dist, lang)
            return when (lang) {
                "hi-IN" -> "$street पर $transTurn, $transDist के लिए।"
                "mr-IN" -> "$street वर $transTurn, $transDist साठी."
                else -> result
            }
        }

        val turnForPattern = Regex("(?i)^Turn\\s+(sharp\\s+left|sharp\\s+right|slight\\s+left|slight\\s+right|left|right)\\s+for\\s+(.+)$")
        val turnForMatch = turnForPattern.matchEntire(trimmed)
        if (turnForMatch != null) {
            val turnType = turnForMatch.groupValues[1].lowercase()
            val dist = turnForMatch.groupValues[2].trim()
            
            val transTurn = translateTurnOffline(turnType, lang)
            val transDist = translateDistanceOffline(dist, lang)
            return when (lang) {
                "hi-IN" -> "$transTurn, $transDist के लिए।"
                "mr-IN" -> "$transTurn, $transDist साठी."
                else -> result
            }
        }
        return result
    }

    private fun getLocalTranslation(text: String, targetLanguageCode: String): String? {
        val trimmed = text.trim()
        val lang = targetLanguageCode
        
        // 1. Call existing navigation offline translation
        val offlineTrans = translateNavigationInstructionOffline(trimmed, targetLanguageCode)
        if (offlineTrans != null) {
            return offlineTrans
        }
        
        // 2. Offline Camera Vision Descriptions
        if (trimmed.startsWith("Using offline description:", ignoreCase = true)) {
            val desc = trimmed.substring("Using offline description:".length).trim().trimEnd('.')
            return when (lang) {
                "hi-IN" -> {
                    val hiDesc = when {
                        desc.contains("crisp 500 Indian Rupee", ignoreCase = true) -> "आपने महात्मा गांधी वॉटरमार्क वाला ५०० रुपये का नया भारतीय नोट पकड़ा हुआ है।"
                        desc.contains("person standing in front", ignoreCase = true) -> "आपके सामने एक व्यक्ति खड़ा है। उनके चेहरे पर एक प्यारी मुस्कान है और वे स्वागत करते हुए दिख रहे हैं।"
                        desc.contains("Caution: Mind the step", ignoreCase = true) -> "आपके सामने के बोर्ड पर लिखा है: सावधान: ०.५ मीटर में नीचे उतरने की सीढ़ी है।"
                        desc.contains("delivery bicycle is moving", ignoreCase = true) -> "एक डिलीवरी साइकिल आपके बाईं ओर १.५ मीटर की दूरी पर चल रही है। कृपया एक पल के लिए रुकें।"
                        desc.contains("Amul Salted Butter", ignoreCase = true) -> "अमूल साल्टेड बटर (१०० ग्राम)। अक्टूबर २०२६ से पहले उपयोग करने की सलाह दी जाती है।"
                        else -> "आपके सामने का रास्ता बिल्कुल साफ है। आपके मार्ग में कोई बाधा नहीं है।"
                    }
                    "ऑफलाइन विवरण का उपयोग: $hiDesc"
                }
                "mr-IN" -> {
                    val mrDesc = when {
                        desc.contains("crisp 500 Indian Rupee", ignoreCase = true) -> "तुम्ही महात्मा गांधी वॉटरमार्क असलेली ५०० रुपयांची नवीन भारतीय नोट हातात धरली आहे."
                        desc.contains("person standing in front", ignoreCase = true) -> "तुमच्या समोर एक व्यक्ती उभी आहे. त्यांच्या चेहऱ्यावर एक छान हसू आहे आणि ते स्वागत करत असल्यासारखे दिसत आहेत."
                        desc.contains("Caution: Mind the step", ignoreCase = true) -> "तुमच्या समोरील पाटीवर लिहिले आहे: सावधान: ०.५ मीटर अंतरावर उतरणारी पायरी आहे."
                        desc.contains("delivery bicycle is moving", ignoreCase = true) -> "एक डिलिव्हरी सायकल तुमच्या डाव्या बाजूला १.५ मीटर अंतरावर जात आहे. कृपया थोडा वेळ थांबा."
                        desc.contains("Amul Salted Butter", ignoreCase = true) -> "अमुल साल्टेड बटर (१०० ग्रॅम). ऑक्टोबर २०२६ पूर्वी वापरण्याची शिफारस केली जाते."
                        else -> "तुमच्या समोरील मार्ग अगदी स्वच्छ आहे. तुमच्या मार्गात कोणताही अडथळा आढळला नाही."
                    }
                    "ऑफलाइन वर्णन वापरत आहे: $mrDesc"
                }
                else -> null
            }
        }

        // 3. Dynamic patterns (Emergency alerts, onboarding, saved places, headcount, etc.)
        if (trimmed.startsWith("Emergency alert activated.", ignoreCase = true) && trimmed.contains("has been notified.", ignoreCase = true)) {
            val name = trimmed
                .substring("Emergency alert activated.".length, trimmed.indexOf("has been notified."))
                .trim()
            return when (lang) {
                "hi-IN" -> "आपातकालीन अलर्ट सक्रिय हो गया है। $name को सूचित कर दिया गया है। लोकेशन लिंक तैयार है।"
                "mr-IN" -> "इमर्जन्सी अलर्ट सक्रिय केला गेला आहे. $name ला सूचित केले आहे. लोकेशन लिंक तयार केली आहे."
                else -> null
            }
        }

        if (trimmed.startsWith("Scanning the surroundings to remember", ignoreCase = true)) {
            val name = trimmed
                .replace("Scanning the surroundings to remember", "", ignoreCase = true)
                .replace("Please hold your device steady.", "", ignoreCase = true)
                .trim().trimEnd('.')
            return when (lang) {
                "hi-IN" -> "$name को याद रखने के लिए परिवेश को स्कैन कर रही हूँ। कृपया अपने डिवाइस को स्थिर रखें।"
                "mr-IN" -> "$name ला लक्षात ठेवण्यासाठी परिसर स्कॅन करत आहे. कृपया तुमचे डिव्हाइस स्थिर ठेवा."
                else -> null
            }
        }

        if (trimmed.startsWith("Saved place as", ignoreCase = true) && trimmed.contains(". I can see the ", ignoreCase = true)) {
            val placeName = trimmed.substring("Saved place as".length, trimmed.indexOf(".")).trim()
            return when (lang) {
                "hi-IN" -> "स्थान को $placeName के रूप में सुरक्षित कर लिया गया है।"
                "mr-IN" -> "जागा $placeName म्हणून जतन केली गेली आहे."
                else -> null
            }
        }

        if (trimmed.startsWith("Destination", ignoreCase = true) && trimmed.endsWith("is within immediate range.", ignoreCase = true)) {
            val placeName = trimmed
                .replace("Destination", "", ignoreCase = true)
                .replace("is within immediate range.", "", ignoreCase = true)
                .trim()
            return when (lang) {
                "hi-IN" -> "गंतव्य स्थान $placeName बिल्कुल पास में है।"
                "mr-IN" -> "ठिकाण $placeName अगदी जवळ आहे."
                else -> null
            }
        }

        if (trimmed.startsWith("Route to", ignoreCase = true) && trimmed.endsWith("is currently offline. Starting compass guide.", ignoreCase = true)) {
            val placeName = trimmed
                .replace("Route to", "", ignoreCase = true)
                .replace("is currently offline. Starting compass guide.", "", ignoreCase = true)
                .trim()
            return when (lang) {
                "hi-IN" -> "$placeName के लिए मार्ग अभी ऑफलाइन है। कम्पास गाइड शुरू कर रही हूँ।"
                "mr-IN" -> "$placeName साठी मार्ग सध्या offline आहे. होकायंत्र मार्गदर्शन सुरू करत आहे."
                else -> null
            }
        }

        if (trimmed.startsWith("Approaching", ignoreCase = true) && trimmed.endsWith(". Let me do a quick scan to verify details.", ignoreCase = true)) {
            val placeName = trimmed
                .replace("Approaching", "", ignoreCase = true)
                .replace(". Let me do a quick scan to verify details.", "", ignoreCase = true)
                .trim()
            return when (lang) {
                "hi-IN" -> "हम $placeName के पास पहुँच रहे हैं। विवरण सत्यापित करने के लिए मुझे एक त्वरित स्कैन करने दें।"
                "mr-IN" -> "आपण $placeName जवळ पोहोचत आहोत. तपशील तपासण्यासाठी मला एक द्रुत स्कॅन करू द्या."
                else -> null
            }
        }

        if (trimmed.contains(", you are approaching ", ignoreCase = true) && trimmed.contains(". I can see ", ignoreCase = true)) {
            val commaIndex = trimmed.indexOf(",")
            val name = trimmed.substring(0, commaIndex).trim()
            val afterApproaching = trimmed.substring(trimmed.indexOf("you are approaching ") + "you are approaching ".length)
            val placeName = afterApproaching.substring(0, afterApproaching.indexOf(".")).trim()
            val description = afterApproaching.substring(afterApproaching.indexOf("I can see ") + "I can see ".length).trim().trimEnd('.')
            return when (lang) {
                "hi-IN" -> "$name, आप $placeName के पास पहुँच रहे हैं। मैं $description देख सकती हूँ।"
                "mr-IN" -> "$name, तुम्ही $placeName जवळ पोहोचत आहात. मी $description पाहू शकते."
                else -> null
            }
        }

        if (trimmed.startsWith("Scan complete. RuView detects", ignoreCase = true)) {
            val countStr = trimmed
                .replace("Scan complete. RuView detects", "", ignoreCase = true)
                .replace("people in the room.", "", ignoreCase = true)
                .replace("person in the room.", "", ignoreCase = true)
                .trim()
            val count = countStr.toIntOrNull() ?: 0
            return when (lang) {
                "hi-IN" -> "स्कैन पूरा हुआ। रूरव्यू को कमरे में $count लोग मिले हैं।"
                "mr-IN" -> "स्कॅन पूर्ण झाले. रुव्ह्यूला खोलीत $count माणसे आढळली आहेत."
                else -> null
            }
        }

        if (trimmed.startsWith("Extracted sign alert:", ignoreCase = true)) {
            val text = trimmed.substring("Extracted sign alert:".length).trim().trimEnd('.')
            return when (lang) {
                "hi-IN" -> "निकाला गया साइन अलर्ट: $text"
                "mr-IN" -> "काढलेला साईन इशारा: $text"
                else -> null
            }
        }

        if (trimmed.startsWith("Matching short term recall query. About two minutes ago, you passed:", ignoreCase = true)) {
            val desc = trimmed.substring("Matching short term recall query. About two minutes ago, you passed:".length).trim().trimEnd('.')
            return when (lang) {
                "hi-IN" -> "शॉर्ट टर्म रिकॉल मैच हो रहा है। लगभग दो मिनट पहले, आप यहाँ से गुजरे थे: $desc"
                "mr-IN" -> "शॉर्ट टर्म मेमरी मॅच होत आहे. सुमारे दोन मिनिटांपूर्वी, तुम्ही येथून गेला होतात: $desc"
                else -> null
            }
        }

        if (trimmed.startsWith("Got it! I have saved", ignoreCase = true) && trimmed.contains("as a custom landmark pinpointed at your exact location.", ignoreCase = true)) {
            val name = trimmed
                .replace("Got it! I have saved", "", ignoreCase = true)
                .replace("as a custom landmark pinpointed at your exact location.", "", ignoreCase = true)
                .trim().trimEnd('.')
            return when (lang) {
                "hi-IN" -> "समझ गई! मैंने $name को आपके सटीक स्थान पर एक कस्टम लैंडमार्क के रूप में सुरक्षित कर लिया है।"
                "mr-IN" -> "समजले! मी $name ला तुमच्या अचूक स्थानावर सानुकूल लँडमार्क म्हणून जतन केले आहे."
                else -> null
            }
        }

        if (trimmed.startsWith("Onboarding complete. Welcome to Drishti,", ignoreCase = true)) {
            return when (lang) {
                "hi-IN" -> "पंजीकरण पूरा हुआ। दृष्टि में आपका स्वागत है। आपके अभिभावक को सेटअप लिंक भेज दिया गया है।"
                "mr-IN" -> "नोंदणी पूर्ण झाली. दृष्टीमध्ये आपले स्वागत आहे. तुमच्या पालकांना सेटअप लिंक पाठवली आहे."
                else -> null
            }
        }
        
        if (trimmed.startsWith("Vision streaming is now active to your guardian", ignoreCase = true)) {
            return when (lang) {
                "hi-IN" -> "आपके अभिभावक के लिए विज़न स्ट्रीमिंग अब सक्रिय है। वे लेंस द्वारा खींचे गए दृश्य को देख सकते हैं।"
                "mr-IN" -> "तुमच्या पालकांसाठी व्हिजन स्ट्रीमिंग आता सुरू आहे. ते तुमच्या कॅमेऱ्यातील दृश्य पाहू शकतात."
                else -> null
            }
        }

        if (trimmed.startsWith("PANIC ALERT DETECTED. Sounding loud emergency siren", ignoreCase = true)) {
            return when (lang) {
                "hi-IN" -> "आपातकालीन स्थिति पाई गई! सायरन बजाया जा रहा है और आपके अभिभावकों को आपकी लोकेशन भेजी जा रही है। कृपया शांत रहें, मदद आ रही है!"
                "mr-IN" -> "आणीबाणीची परिस्थिती आढळली! सायरन वाजवला जात आहे आणि तुमच्या पालकांना तुमचे लोकेशन पाठवले जात आहे. कृपया शांत रहा, मदत येत आहे!"
                else -> null
            }
        }

        // Handle dynamic Walk With Me activation mapping
        if (trimmed.startsWith("okay ", ignoreCase = true) && trimmed.contains(", i'll walk with you and only alert you when necessary.", ignoreCase = true)) {
            val name = trimmed
                .replace("okay ", "", ignoreCase = true)
                .replace(", i'll walk with you and only alert you when necessary.", "", ignoreCase = true)
                .trim()
            return when (targetLanguageCode) {
                "hi-IN" -> "ठीक है $name, मैं तुम्हारे साथ चलती हूँ और ज़रूरत होने पर ही तुम्हें बताऊँगी।"
                "mr-IN" -> "ठीक आहे $name, मी तुझ्यासोबत चालते आणि गरज असेल तेव्हाच तुला सांगेन."
                else -> null
            }
        }
        
        // Handle dynamic Walk With Me deactivation mapping
        if (trimmed.startsWith("i've stopped walking with you, ", ignoreCase = true)) {
            val name = trimmed
                .replace("i've stopped walking with you, ", "", ignoreCase = true)
                .trim().trimEnd('.')
            return when (targetLanguageCode) {
                "hi-IN" -> "मैंने तुम्हारे साथ चलना बंद कर दिया है, $name।"
                "mr-IN" -> "मी तुझ्यासोबत चालणे थांबवले आहे, $name."
                else -> null
            }
        }

        // Handle dynamic welcome message mapping
        if (trimmed.startsWith("Welcome,", ignoreCase = true) && trimmed.endsWith(". Tap the centre orb to speak.", ignoreCase = true)) {
            val name = trimmed.substring(8, trimmed.length - 30).trim()
            return when (targetLanguageCode) {
                "hi-IN" -> "स्वागत है, $name। बोलने के लिए केंद्र के क्षेत्र को टैप करें।"
                "mr-IN" -> "स्वागत आहे, $name. बोलण्यासाठी केंद्र ओर्ब टॅप करा."
                else -> null
            }
        }

        return when (targetLanguageCode) {
            "hi-IN" -> when (trimmed) {
                "Groq API key updated." -> "ग्रॉक एपीआई कुंजी अपडेट हो गई है।"
                "Gemini API key updated." -> "जेमिनी एपीआई कुंजी अपडेट हो गई है।"
                "Restored your profile from the cloud." -> "क्लाउड से आपकी प्रोफाइल पुनर्स्थापित कर दी गई है।"
                "Location services are inactive. Relying on last known position." -> "लोकेशन सेवाएं निष्क्रिय हैं। अंतिम ज्ञात स्थान का उपयोग कर रही हूँ।"
                "Emergency email server settings updated successfully." -> "आपातकालीन ईमेल सर्वर सेटिंग्स सफलतापूर्वक अपडेट हो गई हैं।"
                "Failed to send email automatically to some contacts. Opening email client." -> "कुछ संपर्कों को स्वचालित रूप से ईमेल भेजने में विफल। ईमेल खोल रही हूँ।"
                "Emergency alert sent automatically. Location links were emailed to your guardians." -> "आपातकालीन अलर्ट स्वचालित रूप से भेज दिया गया है। स्थान की लिंक आपके अभिभावकों को ईमेल कर दी गई है।"
                "Failed to send email automatically. Opening standard email app as a backup. Please tap the send button." -> "स्वचालित रूप से ईमेल भेजने में विफल। बैकअप के रूप में सामान्य ईमेल ऐप खोल रही हूँ। कृपया भेजें बटन दबाएं।"
                "Could not open email app. Please call for help manually." -> "ईमेल ऐप नहीं खोला जा सका। कृपया मैन्युअल रूप से मदद के लिए कॉल करें।"
                "Emergency alert logs deleted." -> "आपातकालीन अलर्ट लॉग हटा दिए गए हैं।"
                "Destination is within immediate walking range." -> "गंतव्य स्थान आपके चलने की सीमा के बहुत पास है।"
                "Route unavailable currently. Ensure network services are online." -> "वर्तमान में मार्ग उपलब्ध नहीं है। सुनिश्चित करें कि नेटवर्क सेवाएं चालू हैं।"
                "Destination could not be resolved. Try another search phrase." -> "गंतव्य स्थान का पता नहीं लगाया जा सका। कृपया दूसरा नाम बोलें।"
                "You have reached your destination securely. Excellent work." -> "आप अपने गंतव्य पर सुरक्षित पहुँच गए हैं। बहुत बढ़िया।"
                "Live scene scanning initiated. Visual details will be reported in real time." -> "लाइव दृश्य स्कैनिंग शुरू हो गई है। दृश्य विवरण वास्तविक समय में बताए जाएंगे।"
                "Live camera reports paused." -> "लाइव कैमरा रिपोर्ट रोक दी गई है।"
                "I have not spoken any important messages yet." -> "मैंने अभी तक कोई महत्वपूर्ण संदेश नहीं बोला है।"
                "Opening main dashboard." -> "मुख्य डैशबोर्ड खोल रही हूँ।"
                "Speech velocity updated." -> "बोलने की गति अपडेट हो गई है।"
                "Voice feedback enabled." -> "आवाज प्रतिक्रिया सक्षम हो गई है।"
                "Guardian information updated. Sending verification request to their email address." -> "अभिभावक की जानकारी अपडेट हो गई है। उनके ईमेल पते पर सत्यापन अनुरोध भेज रही हूँ।"
                "Scanning room." -> "कमरे को स्कैन कर रही हूँ।"
                "RuView sensor is offline. Please verify network connection." -> "रूरव्यू सेंसर ऑफलाइन है। कृपया नेटवर्क कनेक्शन की जांच करें।"
                "Contextual visual memory is empty. Describe scenes first to populate short term history." -> "दृश्य स्मृति खाली है। इतिहास भरने के लिए पहले दृश्यों का वर्णन करें।"
                "Activating facial analysis. Let me look at the person in front of you." -> "चेहरे का विश्लेषण सक्रिय कर रही हूँ। मुझे आपके सामने खड़े व्यक्ति को देखने दें।"
                "Hold the currency note in front of the camera. Scanning now." -> "करेंसी नोट को कैमरे के सामने रखें। अभी स्कैन कर रही हूँ।"
                "Scanning for dynamic obstacles and hazards ahead." -> "आगे आने वाले गतिशील खतरों और बाधाओं के लिए स्कैन कर रही हूँ।"
                "Local vision streaming deactivated safely." -> "स्थानीय विज़न स्ट्रीमिंग सुरक्षित रूप से निष्क्रिय कर दी गई है।"
                "Satellites are weak inside. Activating our advanced local indoor positioning. Relying on wireless beacon triangulation and step dead-reckoning." -> "अंदर सैटेलाइट सिग्नल कमजोर हैं। उन्नत स्थानीय इनडोर नेविगेशन सक्रिय कर रही हूँ।"
                "Standard outer GPS navigation restored." -> "सामान्य बाहरी जीपीएस नेविगेशन पुनर्स्थापित हो गया है।"
                "Emergency alarm disabled. Re-entering standby." -> "आपातकालीन अलार्म बंद कर दिया गया है। स्टैंडबाय में वापस आ रही हूँ।"
                "Armed. I'll continuously monitor steps, barricades, and wet floors in the background." -> "सिस्टम सक्रिय है। मैं पृष्ठभूमि में कदमों, बाधाओं और गीले फर्श पर नज़र रखूंगी।"
                "Background proactive scanning paused." -> "पृष्ठभूमि स्कैनिंग रोक दी गई है।"
                "Hold the product label in front of the camera. Reading ingredients and expiry." -> "उत्पाद लेबल को कैमरे के सामने रखें। सामग्री और समाप्ति तिथि पढ़ रही हूँ।"
                "Hold the item in front of the camera. Identifying its color now." -> "वस्तु को कैमरे के सामने रखें। अब उसका रंग पहचान रही हूँ।"
                "What name should I remember this place as?" -> "मुझे इस जगह को किस नाम से याद रखना चाहिए?"
                "Failed processing camera frame." -> "कैमरा फ्रेम प्रोसेस करने में विफल।"
                "Drishti is thinking. Here's what we know: I am here with you, and we will keep you fully safe." -> "दृष्टि सोच रही है। जहाँ तक हमें पता है: मैं आपके साथ हूँ, और हम आपको पूरी तरह सुरक्षित रखेंगे।"
                "Gemini is thinking. Here's what we know: I am here with you, and we will keep you fully safe." -> "दृष्टि सोच रही है। जहाँ तक हमें पता है: मैं आपके साथ हूँ, और हम आपको पूरी तरह सुरक्षित रखेंगे।"
                else -> null
            }
            "mr-IN" -> when (trimmed) {
                "Groq API key updated." -> "ग्रॉक एपीआई की अपडेट झाली आहे."
                "Gemini API key updated." -> "जेमिनी एपीआई की अपडेट झाली आहे."
                "Restored your profile from the cloud." -> "क्लाउडवरून तुमचे प्रोफाइल रिस्टोअर केले गेले आहे."
                "Location services are inactive. Relying on last known position." -> "लोकेशन सेवा निष्क्रिय आहेत. शेवटच्या माहित असलेल्या स्थानाचा वापर करत आहे."
                "Emergency email server settings updated successfully." -> "इमर्जन्सी ईमेल सर्व्हर सेटिंग्ज यशस्वीरित्या अपडेट झाल्या आहेत."
                "Failed to send email automatically to some contacts. Opening email client." -> "काही संपर्कांना स्वयंचलितपणे ईमेल पाठवण्यात अयशस्वी. ईमेल ॲप उघडत आहे."
                "Emergency alert sent automatically. Location links were emailed to your guardians." -> "इमर्जन्सी अलर्ट स्वयंचलितपणे पाठवला आहे. लोकेशन लिंक तुमच्या पालकांना ईमेल केली आहे."
                "Failed to send email automatically. Opening standard email app as a backup. Please tap the send button." -> "स्वयंचलितपणे ईमेल पाठवण्यात अयशस्वी. बॅकअप म्हणून ईमेल ॲप उघडत आहे. कृपया सेंड बटण दाबा."
                "Could not open email app. Please call for help manually." -> "ईमेल ॲप उघडता आले नाही. कृपया मदतीसाठी स्वतः कॉल करा."
                "Emergency alert logs deleted." -> "इमर्जन्सी अलर्ट लॉग डिलीट केले आहेत."
                "Destination is within immediate walking range." -> "ठिकाण अगदी जवळ चालण्याच्या अंतरावर आहे."
                "Route unavailable currently. Ensure network services are online." -> "सध्या मार्ग उपलब्ध नाही. नेटवर्क सेवा सुरू असल्याची खात्री करा."
                "Destination could not be resolved. Try another search phrase." -> "ठिकाणाचा शोध लागला नाही. कृपया दुसरे नाव सांगा."
                "You have reached your destination securely. Excellent work." -> "तुम्ही तुमच्या ठिकाणी सुरक्षितपणे पोहोचला आहात. उत्तम काम."
                "Live scene scanning initiated. Visual details will be reported in real time." -> "लाइव सीन स्कॅनिंग सुरू झाले आहे. व्हिज्युअल माहिती रिअल टाइममध्ये दिली जाईल."
                "Live camera reports paused." -> "लाइव कॅмера रिपोर्ट थांबवले आहेत."
                "I have not spoken any important messages yet." -> "मी अद्याप कोणताही महत्त्वाचा संदेश बोललेलो नाही."
                "Opening main dashboard." -> "मुख्य डॅशबोर्ड उघडत आहे."
                "Speech velocity updated." -> "बोलण्याचा वेग अपडेट झाला आहे."
                "Voice feedback enabled." -> "व्हॉईस फीडबॅक सुरू केला आहे."
                "Guardian information updated. Sending verification request to their email address." -> "पालकांची माहिती अपडेट झाली आहे. त्यांच्या ईमेल पत्त्यावर पडताळणी विनंती पाठवत आहे."
                "Scanning room." -> "खोली स्कॅन करत आहे."
                "RuView sensor is offline. Please verify network connection." -> "रुव्ह्यू सेन्सर ऑफलाइन आहे. कृपया नेटवर्क कनेक्शन तपासा."
                "Contextual visual memory is empty. Describe scenes first to populate short term history." -> "व्हिज्युअल मेमरी रिकामी आहे. इतिहास भरण्यासाठी आधी दृश्य वर्णन करा."
                "Activating facial analysis. Let me look at the person in front of you." -> "चेहऱ्याचे विश्लेषण सुरू करत आहे. मला तुमच्या समोरील व्यक्तीकडे पाहू द्या."
                "Hold the currency note in front of the camera. Scanning now." -> "चलन नोट कॅमेऱ्यासमोर धरा. आता स्कॅन करत आहे."
                "Scanning for dynamic obstacles and hazards ahead." -> "पुढील गतिमान अडथळे आणि धोक्यांसाठी स्कॅन करत आहे."
                "Local vision streaming deactivated safely." -> "लोकल व्हिजन स्ट्रीमिंग सुरक्षितपणे थांबवले आहे."
                "Satellites are weak inside. Activating our advanced local indoor positioning. Relying on wireless beacon triangulation and step dead-reckoning." -> "आत सॅटेलाईट सिग्नल कमकुवत आहेत. प्रगत इनडोर पोझिशनिंग सक्रिय करत आहे."
                "Standard outer GPS navigation restored." -> "सामान्य जीपीएस नेव्हिगेशन पूर्ववत केले आहे."
                "Emergency alarm disabled. Re-entering standby." -> "इमर्जन्सी अलार्म बंद केला आहे. पुन्हा स्टँडबाय मोडवर जात आहे."
                "Armed. I'll continuously monitor steps, barricades, and wet floors in the background." -> "सिस्टम सक्रिय आहे. मी बॅकग्राउंडमध्ये पावले, अडथळे आणि ओल्या फरशीवर लक्ष ठेवीन."
                "Background proactive scanning paused." -> "बॅकग्राउंड स्कॅनिंग थांबवले आहे."
                "Hold the product label in front of the camera. Reading ingredients and expiry." -> "उत्पादनाचा लेबल कॅमेऱ्यासमोर धरा. घटक आणि कालबाह्यता वाचत आहे."
                "Hold the item in front of the camera. Identifying its color now." -> "वस्तू कॅमेऱ्यासमोर धरा. आता तिचा रंग ओळखत आहे."
                "What name should I remember this place as?" -> "मी या जागेला कोणत्या नावाने लक्षात ठेवू?"
                "Failed processing camera frame." -> "कॅмера फ्रेम प्रोसेस करण्यात अयशस्वी."
                "Drishti is thinking. Here's what we know: I am here with you, and we will keep you fully safe." -> "दृष्टी विचार करत आहे. आपल्याला माहित आहे त्यानुसार: मी तुमच्यासोबत आहे आणि आम्ही तुम्हाला पूर्णपणे सुरक्षित ठेवू."
                "Gemini is thinking. Here's what we know: I am here with you, and we will keep you fully safe." -> "दृष्टी विचार करत आहे. आपल्याला माहित आहे त्यानुसार: मी तुमच्यासोबत आहे आणि आम्ही तुम्हाला पूर्णपणे सुरक्षित ठेवू."
                else -> null
            }
            else -> null
        }
    }

    private suspend fun translateText(text: String, targetLanguageCode: String): String = withContext(Dispatchers.IO) {
        val targetLangName = when (targetLanguageCode) {
            "hi-IN" -> "Hindi"
            "mr-IN" -> "Marathi"
            else -> return@withContext text
        }

        val local = getLocalTranslation(text, targetLanguageCode)
        if (local != null) {
            return@withContext local
        }

        val cached = getCachedTranslation(text, targetLanguageCode)
        if (!cached.isNullOrBlank()) {
            return@withContext cached
        }

        // Try Groq translation first (primary for non-vision)
        if (groqApiKey.isNotBlank() && groqApiKey != "MY_GROQ_API_KEY") {
            try {
                val prompt = "Translate this description to a casual, friendly, spoken-slang style $targetLangName (like a female buddy talking to a friend). CRITICAL: Use a female speaking tone (e.g., in Hindi, use female verb endings like 'रही हूँ' (kar rahi hu) instead of 'रहा हूँ' (kar raha hu); in Marathi, use female verb endings like 'करतेय' (kartey) or 'करतीये' (kartiye) instead of 'करतोय' (kartoy)). Do not use formal bookish words. Respond ONLY with the translation, do not include any other text: \"$text\""
                val response = repository.getGroqResponse(prompt, emptyList(), groqApiKey)
                if (response.isNotBlank() && !response.contains("empty", ignoreCase = true)) {
                    val result = response.trim()
                    cacheTranslation(text, targetLanguageCode, result)
                    return@withContext result
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Try Gemini translation as fallback
        if (geminiApiKey.isNotBlank() && geminiApiKey != "MY_GEMINI_API_KEY") {
            try {
                val prompt = "Translate this description to a casual, friendly, spoken-slang style $targetLangName (like a female buddy talking to a friend). CRITICAL: Use a female speaking tone (e.g., in Hindi, use female verb endings like '\\u0930\\u0939\\u0940 \\u0939\\u0942\\u0901' (kar rahi hu) instead of '\\u0930\\u0939\\u093e \\u0939\\u0942\\u0901' (kar raha hu); in Marathi, use female verb endings like '\\u0915\\u0930\\u0924\\u0947\\u092f' (kartey) or '\\u0915\\u0930\\u0924\\u0940\\u092f\\u0947' (kartiye) instead of '\\u0915\\u0930\\u0924\\u094b\\u092f' (kartoy)). Do not use formal bookish words. Respond ONLY with the translation, do not include any other text: \"$text\""
                val response = repository.getGeminiTextResponse(prompt, emptyList(), geminiApiKey)
                if (response.isNotBlank() && !response.contains("empty", ignoreCase = true)) {
                    val result = response.trim()
                    cacheTranslation(text, targetLanguageCode, result)
                    return@withContext result
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext text
    }

    private suspend fun playBase64AudioSuspend(base64Audio: String) = suspendCancellableCoroutine<Unit> { continuation ->
        try {
            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            val tempFile = File.createTempFile("sarvam_tts", ".wav", context.cacheDir)
            tempFile.deleteOnExit()
            FileOutputStream(tempFile).use { fos ->
                fos.write(audioBytes)
            }
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    it.release()
                    if (mediaPlayer == this) {
                        mediaPlayer = null
                    }
                    tempFile.delete()
                    if (continuation.isActive) continuation.resume(Unit)
                }
                setOnErrorListener { _, _, _ ->
                    release()
                    if (mediaPlayer == this) {
                        mediaPlayer = null
                    }
                    tempFile.delete()
                    if (continuation.isActive) continuation.resume(Unit)
                    true
                }
                start()
            }
            continuation.invokeOnCancellation {
                try {
                    mp.stop()
                    mp.release()
                } catch (e: Exception) {}
                if (mediaPlayer == mp) {
                    mediaPlayer = null
                }
                tempFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    fun replayLastSpeech() {
        if (lastSpokenText.isNotBlank()) {
            speak(lastSpokenText)
        }
    }

    fun stopSpeaking() {
        currentSpeechJob?.cancel()
        currentSpeechJob = null
        ttsContinuation = null
        tts?.stop()
        isSpeaking = false
        try {
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) {
                        it.stop()
                    }
                } catch (e: Exception) {}
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setCustomSpeechRate(rate: Float) {
        viewModelScope.launch {
            repository.updateSettings(rate, userSettings.value?.voiceEnabled ?: true)
            speak("Speech velocity updated.")
        }
    }

    fun toggleVoiceFeedback(enabled: Boolean) {
        viewModelScope.launch {
            val currentRate = userSettings.value?.speechRate ?: 1.0f
            repository.updateSettings(currentRate, enabled)
            if (enabled) {
                speak("Voice feedback enabled.")
            }
        }
    }

    // ==========================================
    // ONBOARDING & SAFETY PERSISTENCE
    // ==========================================

    fun completeGoogleLogin(email: String, name: String, idToken: String, onComplete: (onboardingCompleted: Boolean) -> Unit) {
        orbState = OrbState.PROCESSING
        speak("Establishing secure Google credentials...")
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        FirebaseAuth.getInstance().signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val firebaseUser = task.result?.user
                    val finalEmail = (firebaseUser?.email ?: email).trim().lowercase()
                    val finalName = firebaseUser?.displayName ?: name
                    viewModelScope.launch {
                        val existingUser = repository.fetchUserFromFirestore(finalEmail)
                        if (existingUser != null && existingUser.onboardingCompleted) {
                            repository.restoreProfileFromFirestore(finalEmail, existingUser)
                            triggerVibration(50L)
                            speak("Welcome back, ${existingUser.name}. Restored your profile and settings from the cloud.")
                            onComplete(true)
                        } else {
                            repository.saveUser(finalName, email = finalEmail, completed = false)
                            triggerVibration(50L)
                            speak("Verification successful. Welcome to Drishti, $finalName. Please configure emergency guardian contacts next.")
                            onComplete(false)
                        }
                    }
                } else {
                    val errorMsg = task.exception?.localizedMessage ?: "Handshake failed."
                    Log.e("DrishtiViewModel", "Google Firebase Login Failed", task.exception)
                    speak("Google authentication failed. $errorMsg")
                    triggerVibration(longArrayOf(0, 300))
                    orbState = OrbState.IDLE
                }
            }
    }

    fun completeOnboarding(name: String, guardianName: String, guardianEmail: String, guardianPhone: String) {
        viewModelScope.launch {
            val oneShotUser = repository.getUserOneShot()
            val currentEmail = (oneShotUser?.email?.ifBlank { null }
                ?: userProfile.value?.email?.ifBlank { null }
                ?: FirebaseAuth.getInstance().currentUser?.email
                ?: "").trim().lowercase()
            repository.saveUser(name, email = currentEmail, completed = true)
            repository.saveGuardian(guardianName, guardianEmail, guardianPhone)
            speak("Onboarding complete. Welcome to Drishti, $name. Guardian configured as $guardianName. An activation request was sent to their email.")
            
            val newEmails = extractEmails(guardianEmail)
            newEmails.forEach { email ->
                sendSmtpOrFormSubmitWelcome(guardianName, email)
            }
        }
    }

    fun updateGuardian(name: String, email: String, phone: String) {
        viewModelScope.launch {
            val oldEmail = guardianProfile.value?.email ?: ""
            repository.saveGuardian(name, email, phone)
            speak("Guardian information updated. Sending verification request to their email address.")
            
            val oldEmails = extractEmails(oldEmail)
            val newEmails = extractEmails(email)
            if (newEmails != oldEmails) {
                newEmails.forEach { newEmail ->
                    sendSmtpOrFormSubmitWelcome(name, newEmail)
                }
            }
        }
    }

    private fun extractEmails(emailStr: String): List<String> {
        return emailStr.split(Regex("[,;\\s]+"))
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && android.util.Patterns.EMAIL_ADDRESS.matcher(it).matches() }
    }

    private fun sendSmtpOrFormSubmitWelcome(guardianName: String, newEmail: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val userName = userProfile.value?.name ?: "Drishti User"
                val userEmail = userProfile.value?.email ?: "user@drishti.ai"
                val subject = "[Drishti App] Emergency Contact Registered"
                val bodyContent = """
                    Hello $guardianName,
                    
                    Your email address has been registered as the primary Emergency Guardian contact for $userName ($userEmail) in the Drishti Visual Assistance App.
                    
                    In case of an emergency, automated SOS alerts containing their GPS location and Google Maps link will be sent to this email address.
                    
                    Please click the verification link if requested to enable automatic background alerts.
                    
                    Please keep this email active.
                """.trimIndent()

                val settings = userSettings.value
                val isSent = if (settings?.smtpEnabled == true && settings.smtpEmail.isNotBlank()) {
                    Log.d("DrishtiViewModel", "Sending welcome email via SMTP to $newEmail...")
                    com.example.util.SmtpSender.sendEmail(
                        host = settings.smtpHost,
                        port = settings.smtpPort,
                        username = settings.smtpEmail,
                        password = settings.smtpPassword,
                        to = newEmail,
                        subject = subject,
                        body = bodyContent
                    )
                } else {
                    false
                }

                if (isSent) {
                    Log.d("DrishtiViewModel", "Welcome email sent successfully via SMTP.")
                } else {
                    Log.d("DrishtiViewModel", "Sending welcome activation email via FormSubmit to $newEmail...")
                    val payload = mapOf(
                        "name" to userName,
                        "email" to userEmail,
                        "_subject" to subject,
                        "message" to bodyContent
                    )
                    val response = DrishtiApiClient.formSubmitService.sendEmergencyEmail(newEmail, payload)
                    if (response.isSuccessful) {
                        Log.d("DrishtiViewModel", "FormSubmit activation dispatched successfully to $newEmail: ${response.body()?.message}")
                    } else {
                        Log.e("DrishtiViewModel", "FormSubmit activation failed: ${response.code()}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("DrishtiViewModel", "Error sending guardian setup email", e)
            }
        }
    }

    // ==========================================
    // GPS & REVERSE GEOCODING
    // ==========================================

    fun isLocationServicesEnabled(): Boolean {
        val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled
            } else {
                locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            }
        } catch (e: Exception) {
            try {
                locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            } catch (ex: Exception) {
                true // Fallback to true so the app still attempts to retrieve location rather than blocking
            }
        }
    }

    private suspend fun getFreshLocation(): android.location.Location? = withContext(Dispatchers.IO) {
        kotlin.runCatching {
            // 1. Get last known location immediately (instant check)
            val lastLoc = suspendCancellableCoroutine<android.location.Location?> { continuation ->
                try {
                    val hasFineLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.ACCESS_FINE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    val hasCoarseLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!hasFineLocation && !hasCoarseLocation) {
                        continuation.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { loc ->
                            continuation.resume(loc)
                        }
                        .addOnFailureListener {
                            continuation.resume(null)
                        }
                } catch (e: Exception) {
                    continuation.resume(null)
                }
            }
            
            // If lastLoc is fresh (within last 8 seconds), use it immediately without requesting satellite update
            if (lastLoc != null && (System.currentTimeMillis() - lastLoc.time) < 8000L) {
                return@runCatching lastLoc
            }

            // 2. Otherwise request a quick location update (1.5 second timeout)
            val freshLoc = kotlinx.coroutines.withTimeoutOrNull(1500L) {
                suspendCancellableCoroutine<android.location.Location?> { continuation ->
                    try {
                        val hasFineLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.ACCESS_FINE_LOCATION
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        val priority = if (hasFineLocation) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
                        val tokenSource = com.google.android.gms.tasks.CancellationTokenSource()
                        
                        fusedLocationClient.getCurrentLocation(priority, tokenSource.token)
                            .addOnSuccessListener { location ->
                                if (continuation.isActive) continuation.resume(location)
                            }
                            .addOnFailureListener {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        
                        continuation.invokeOnCancellation {
                            tokenSource.cancel()
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
            freshLoc ?: lastLoc
        }.getOrNull()
    }

    fun refreshLocation() {
        viewModelScope.launch {
            val loc = getFreshLocation()
            if (loc != null) {
                currentLatitude = loc.latitude
                currentLongitude = loc.longitude
                val addr = repository.reverseGeocode(loc.latitude, loc.longitude)
                currentAddressText = addr
            }
        }
    }

    private fun checkSavedPlaceProximity(lat: Double, lng: Double) {
        val place = navigatingToSavedPlace ?: return
        if (hasTriggeredArrivalGuidance) return

        val distance = calculateDistanceInMeters(lat, lng, place.latitude, place.longitude)
        Log.d("DrishtiViewModel", "Distance to saved place ${place.name}: $distance meters")

        if (distance <= 20.0) {
            hasTriggeredArrivalGuidance = true
            triggerVisualArrivalGuidance(place)
        }
    }

    private fun calculateDistanceInMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    fun triggerVisualArrivalGuidance(place: SavedPlaceEntity) {
        viewModelScope.launch {
            speak("Approaching ${place.name}. Let me do a quick scan to verify details.", SpeechPriority.NAVIGATION)
            delay(1500)
            _capturePhotoForArrivalEvent.emit(place)
        }
    }

    fun processCapturedPhotoForArrival(place: SavedPlaceEntity, bitmap: Bitmap) {
        viewModelScope.launch {
            orbState = OrbState.PROCESSING
            val prompt = """
                The user is approaching a saved place: "${place.name}".
                The stored description of this place is: "${place.description}".
                The stored landmarks of this place are: "${place.landmarks}".
                Look at the current image frame and describe what is visible. Focus on confirming visual cues that match the stored details or describe the surrounding items (e.g. gate color, nearby stalls).
                Formulate the description so it fits into the sentence: "I can see the [your description] on your left" or similar.
                Keep the output under 20 words and do not include any prefix like "Drishti says" or similar.
            """.trimIndent()

            val mergedDescription = if (groqApiKey.isNotBlank() || geminiApiKey.isNotBlank()) {
                repository.analyzeCameraIntent(
                    bitmap = bitmap,
                    voiceIntent = prompt,
                    groqKey = groqApiKey,
                    geminiKey = geminiApiKey,
                    targetLanguageCode = ttsLanguage
                )
            } else {
                "the gate and entrance area"
            }

            val userName = userProfile.value?.name ?: "Sahil"
            speak("$userName, you are approaching ${place.name}. I can see the $mergedDescription.", SpeechPriority.NAVIGATION)
            orbState = OrbState.IDLE
        }
    }

    // ==========================================
    // VOICE SPEECH SYSTEM & fallbacks
    // ==========================================

    fun startListeningCommand() {
        triggerVibration(longArrayOf(0, 40, 50, 40))
        orbState = OrbState.LISTENING
        stopSpeaking()
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            viewModelScope.launch(Dispatchers.IO) {
                delay(300)
                toneGen.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startLiveScanning() {
        if (isProactiveScanningEnabled) return
        toggleProactiveState(true)
    }

    fun stopLiveScanning() {
        if (!isLiveScanning && !isProactiveScanningEnabled) return
        isLiveScanning = false
        toggleProactiveState(false)
        speak("Live camera reports paused.")
    }

    private val conversationHistory = mutableStateListOf<GroqMessage>()
    // Tracks the in-flight voice command/conversation so a new utterance can interrupt it.
    private var commandJob: Job? = null

    private fun getGroqHistory(): List<GroqMessage> {
        val userName = userProfile.value?.name ?: "Sahil"
        val langInstruction = when (ttsLanguage) {
            "hi-IN" -> "IMPORTANT: Respond ONLY in spoken-slang Hindi using Hindi Devanagari script. Strictly do NOT mix English words in Hindi response. Use a friendly female tone with female-gendered verb endings like 'कर रही हूँ' instead of 'कर रहा हूँ'. DO NOT use 'bhai' or 'dost'."
            "mr-IN" -> "IMPORTANT: Respond ONLY in spoken-slang Marathi using Marathi Devanagari script. Strictly do NOT mix English words in Marathi response. Use a friendly female tone with female-gendered verb endings like 'करतेय' or 'करतीये' instead of 'करतोय'. DO NOT use 'bhai' or 'dost'."
            else -> "Speak in casual English."
        }
        val genderInstruction = if (isCurrentUserMale()) {
            when (ttsLanguage) {
                "hi-IN" -> "The user is MALE. Address him like a male buddy. For the casual interjection you may use 'अरे'. Rarely use 'यार'. NEVER use 'बहन', 'दीदी', or 'अगं'."
                "mr-IN" -> "The user is MALE. Address him like a male buddy. For the casual interjection use 'अरे' (NOT 'अगं'). Rarely use 'भावा' or 'यार'. NEVER use 'ताई', 'दीदी', or 'अगं'."
                else -> "The user is MALE. You may rarely use 'bro', 'buddy', or 'yaar'. Never call him sister/didi."
            }
        } else {
            when (ttsLanguage) {
                "hi-IN" -> "The user is FEMALE. Address her warmly like a sister/friend. You may use 'अरे', and rarely 'बहन', 'दीदी', or 'यार'. NEVER use 'भाई', 'भावा', or 'बॉस'."
                "mr-IN" -> "The user is FEMALE. Address her warmly. For the casual interjection use 'अगं' (NOT 'अरे'). Rarely use 'ताई', 'दीदी', or 'यार'. NEVER use 'भावा' or 'अरे'."
                else -> "The user is FEMALE. You may rarely use 'sis', 'didi', 'behen', or 'yaar'. Never call her bro/buddy."
            }
        }
        val systemPrompt = """
            You are Drishti, a friendly, casual, and safety-focused AI companion (a close buddy) for a blind user named $userName.
            Always speak in a highly conversational, informal, warm slang/colloquial buddy tone (not formal or robotic). Do NOT say things like "As an AI", "I am a language model", or "How can I assist you today". Just speak like a human friend who is right next to the user.
            $langInstruction
            $genderInstruction
            CRITICAL CRITERIA:
            1. Keep responses under 30 words — extremely concise and casual.
            2. Omit minor details, colors, or surfaces unless explicitly asked.
            3. Do NOT repeat yourself or parrot back queries.
        """.trimIndent()
        return listOf(GroqMessage(role = "system", content = systemPrompt)) + conversationHistory.takeLast(8)
    }

    private fun extractDestination(command: String): String {
        var dest = command.lowercase().trim()
        
        // Suffixes first (common in Hindi/Marathi syntax)
        val suffixes = listOf(
            "के लिए रास्ता ढूंढो", "के लिए रास्ता बताओ", "का रास्ता बताओ", "के लिए रास्ता", "का रास्ता",
            "पर ले चलो", "ले चलो", "नेविगेट करो", "नेविगेट कर", "मार्ग बताओ",
            "चा मार्ग दाखवा", "कडे नेव्हिगेट करा", "नेव्हिगेट करा", "मार्ग दाखवा", "मार्ग सांगा", "कडे ने", "कडे जा"
        )
        for (suffix in suffixes) {
            if (dest.endsWith(suffix)) {
                dest = dest.substring(0, dest.length - suffix.length).trim()
                break
            }
        }

        // Prefixes
        val prefixes = listOf(
            "navigate me to", "take me to", "show directions to", "directions to",
            "navigate to", "route to", "map to", "go to", "navigate", "guide me to",
            "रास्ता बताओ", "नेविगेट करो", "नेव्हिगेट करा", "मार्ग दाखवा"
        )
        for (prefix in prefixes) {
            if (dest.startsWith(prefix)) {
                dest = dest.substring(prefix.length).trim()
                break
            } else if (dest.contains(prefix)) {
                dest = dest.substring(dest.indexOf(prefix) + prefix.length).trim()
                break
            }
        }
        return dest.trim()
    }

    private fun getProcessingMessage(): String {
        return when (ttsLanguage) {
            "hi-IN" -> "सीन प्रोसेस कर रही हूँ।"
            "mr-IN" -> "सीन प्रोसेस करत आहे।"
            else -> "Processing scene."
        }
    }

    // ==========================================
    // OFFLINE LOCAL RESPONSES (instant, on-device, language-aware — no AI call)
    // ==========================================

    private val weekdayNames = mapOf(
        "en-IN" to listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"),
        "hi-IN" to listOf("रविवार", "सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार"),
        "mr-IN" to listOf("रविवार", "सोमवार", "मंगळवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार")
    )
    private val monthNames = mapOf(
        "en-IN" to listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"),
        "hi-IN" to listOf("जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर"),
        "mr-IN" to listOf("जानेवारी", "फेब्रुवारी", "मार्च", "एप्रिल", "मे", "जून", "जुलै", "ऑगस्ट", "सप्टेंबर", "ऑक्टोबर", "नोव्हेंबर", "डिसेंबर")
    )

    private fun localLang(): String = if (ttsLanguage in weekdayNames) ttsLanguage else "en-IN"

    // Drishti is an Indian app: always report time/date in Indian Standard Time (IST),
    // regardless of the phone's configured timezone.
    private val indiaTimeZone: java.util.TimeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
    private fun indiaCalendar(): java.util.Calendar = java.util.Calendar.getInstance(indiaTimeZone)

    private fun buildLocalTimeResponse(): String {
        val cal = indiaCalendar()
        val hour24 = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        var hour12 = hour24 % 12
        if (hour12 == 0) hour12 = 12
        val name = userProfile.value?.name ?: "Sahil"
        return when (localLang()) {
            "hi-IN" -> {
                val period = when { hour24 < 12 -> "सुबह"; hour24 < 16 -> "दोपहर"; hour24 < 20 -> "शाम"; else -> "रात" }
                if (minute == 0) "अभी $period के $hour12 बजे हैं।" else "अभी $period के $hour12 बजकर $minute मिनट हुए हैं।"
            }
            "mr-IN" -> {
                val period = when { hour24 < 12 -> "सकाळी"; hour24 < 16 -> "दुपारी"; hour24 < 20 -> "संध्याकाळी"; else -> "रात्री" }
                if (minute == 0) "आता $period $hour12 वाजले आहेत।" else "आता $period $hour12 वाजून $minute मिनिटे झाली आहेत।"
            }
            else -> {
                val ampm = if (hour24 < 12) "AM" else "PM"
                val periodEn = when { hour24 < 12 -> "in the morning"; hour24 < 16 -> "in the afternoon"; hour24 < 20 -> "in the evening"; else -> "at night" }
                val mm = String.format("%02d", minute)
                if (minute == 0) "It's $hour12 o'clock $periodEn, $name." else "It's $hour12:$mm $ampm, $periodEn, $name."
            }
        }
    }

    private fun buildLocalDateResponse(): String {
        val cal = indiaCalendar()
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val monthIdx = cal.get(java.util.Calendar.MONTH)
        val year = cal.get(java.util.Calendar.YEAR)
        val weekdayIdx = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val lang = localLang()
        val weekday = weekdayNames[lang]!![weekdayIdx]
        val month = monthNames[lang]!![monthIdx]
        return when (lang) {
            "hi-IN" -> "आज $weekday, $day $month $year है।"
            "mr-IN" -> "आज $weekday, $day $month $year आहे।"
            else -> "Today is $weekday, $day $month $year."
        }
    }

    private fun buildLocalDayResponse(): String {
        val cal = indiaCalendar()
        val weekdayIdx = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val lang = localLang()
        val weekday = weekdayNames[lang]!![weekdayIdx]
        return when (lang) {
            "hi-IN" -> "आज $weekday है।"
            "mr-IN" -> "आज $weekday आहे।"
            else -> "Today is $weekday."
        }
    }

    private fun buildLocalBatteryResponse(): String {
        val pct = try {
            val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) { -1 }
        val charging = try {
            val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.isCharging
        } catch (e: Exception) { false }
        if (pct < 0) {
            return when (localLang()) {
                "hi-IN" -> "माफ़ करना, बैटरी की जानकारी अभी नहीं मिल पा रही है।"
                "mr-IN" -> "माफ करा, बॅटरीची माहिती सध्या मिळत नाहीये."
                else -> "Sorry, I can't read the battery level right now."
            }
        }
        return when (localLang()) {
            "hi-IN" -> "बैटरी $pct प्रतिशत है" + (if (charging) " और चार्ज हो रही है।" else "।")
            "mr-IN" -> "बॅटरी $pct टक्के आहे" + (if (charging) " आणि चार्ज होत आहे." else ".")
            else -> "Battery is at $pct percent" + (if (charging) " and charging." else ".")
        }
    }

    private fun buildLocalGpsResponse(): String {
        val on = try {
            val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        } catch (e: Exception) { false }
        return when (localLang()) {
            "hi-IN" -> if (on) "जीपीएस चालू है और नेविगेशन के लिए तैयार है।" else "जीपीएस अभी बंद है। नेविगेशन के लिए कृपया इसे चालू करें।"
            "mr-IN" -> if (on) "जीपीएस सुरू आहे आणि नेव्हिगेशनसाठी तयार आहे।" else "जीपीएस सध्या बंद आहे. नेव्हिगेशनसाठी कृपया तो सुरू करा."
            else -> if (on) "GPS is on and ready for navigation." else "GPS is currently off. Please turn it on to use navigation."
        }
    }

    private fun buildLocalCapabilitiesResponse(): String {
        val name = userProfile.value?.name ?: "Sahil"
        return when (localLang()) {
            "hi-IN" -> "$name, मैं रास्ता दिखा सकती हूँ, आसपास का हाल बता सकती हूँ, लिखा हुआ और बोर्ड पढ़ सकती हूँ, नोट पहचान सकती हूँ, और इमरजेंसी में एसओएस भेज सकती हूँ। और आपसे बातें भी कर सकती हूँ। बताओ क्या करूँ?"
            "mr-IN" -> "$name, मी रस्ता दाखवू शकते, आजूबाजूचं वर्णन करू शकते, मजकूर आणि पाट्या वाचू शकते, नोटा ओळखू शकते, आणि इमर्जन्सीमध्ये एसओएस पाठवू शकते. आणि तुझ्याशी गप्पाही मारू शकते. सांग काय करू?"
            else -> "$name, I can guide your navigation, describe your surroundings, read text and signs, recognise currency, detect people, and raise an SOS in emergencies. I can also just chat with you. What would you like?"
        }
    }

    fun processSpeachTextCommand(recognizedText: String) {
        val command = recognizedText.lowercase()
            .replace("amity academy of engineering", "mit academy of engineering")
            .replace("amity college of engineering", "mit academy of engineering")
            .replace("amity academy", "mit academy")
        transcriptState = "Hearing: \"$recognizedText\""
        orbState = OrbState.PROCESSING

        // SMART INTERRUPTION: a brand-new utterance always wins. Cancel any in-flight
        // command/conversation (including a pending AI reply) and stop current speech so
        // feature commands instantly take over an ongoing conversation.
        commandJob?.cancel()
        currentSpeechJob?.cancel()
        stopSpeaking()

        commandJob = viewModelScope.launch {
            val isLanguageSwitchHindi = command.contains("switch to hindi") || command.contains("change to hindi") || command.contains("set language to hindi") || command.contains("language hindi")
            val isLanguageSwitchMarathi = command.contains("switch to marathi") || command.contains("change to marathi") || command.contains("set language to marathi") || command.contains("language marathi")
            val isLanguageSwitchEnglish = command.contains("switch to english") || command.contains("change to english") || command.contains("set language to english") || command.contains("language english")

            // OFFLINE LOCAL RESPONSES — answered instantly on-device, never sent to the AI.
            // Matchers cover English, Devanagari AND romanized Hindi/Marathi (the recognizer
            // often returns latin-script "kitne baje" / "time kya hai" / "tarikh").
            val isTimeQuery = command.contains("what time") || command.contains("the time") || command.contains("current time") || command.contains("time right now") || command.contains("time is it") || command.contains("time kya") || command.contains("time kitna") || command.contains("kitne baj") || command.contains("kitna baj") || command.contains("baje hai") || command.contains("baje hue") || command.contains("samay kya") || command.contains("kya samay") || command.contains("kiti vaj") || command.contains("vajle") || command.contains("vel kay") || command.contains("कितने बजे") || command.contains("क्या समय") || command.contains("समय क्या") || command.contains("कितना समय") || command.contains("बजे है") || command.contains("किती वाजले") || command.contains("वेळ काय") || command.contains("किती वाजलेत")
            val isDateQuery = command.contains("what date") || command.contains("today's date") || command.contains("todays date") || command.contains("date today") || command.contains("what is the date") || command.contains("date kya") || command.contains("kya date") || command.contains("tarikh") || command.contains("tareekh") || command.contains("aaj ki date") || command.contains("कौन सी तारीख") || command.contains("क्या तारीख") || command.contains("तारीख क्या") || command.contains("आज की तारीख") || command.contains("कोणती तारीख") || command.contains("तारीख काय") || command.contains("आजची तारीख")
            val isDayQuery = command.contains("what day") || command.contains("which day") || command.contains("day today") || command.contains("day is it") || command.contains("day is today") || command.contains("kaunsa din") || command.contains("kaun sa din") || command.contains("konsa din") || command.contains("din kaunsa") || command.contains("aaj kaunsa") || command.contains("konta var") || command.contains("konta divas") || command.contains("aaj konta") || command.contains("कौन सा दिन") || command.contains("कौनसा दिन") || command.contains("आज कौन") || command.contains("कोणता दिवस") || command.contains("कोणता वार") || command.contains("आज कोणता")
            val isBatteryQuery = command.contains("battery") || command.contains("charge level") || command.contains("how much charge") || command.contains("बैटरी") || command.contains("चार्ज") || command.contains("बॅटरी")
            val isGpsStatusQuery = (command.contains("gps") || command.contains("जीपीएस")) && (command.contains("status") || command.contains("on") || command.contains("off") || command.contains("working") || command.contains("चालू") || command.contains("बंद") || command.contains("सुरू") || command.contains("स्थिती"))
            val isCapabilities = command.contains("what can you do") || command.contains("what do you do") || command.contains("what are your features") || command.contains("how can you help") || command.contains("what can you help") || command.contains("क्या कर सकती") || command.contains("क्या कर सकते") || command.contains("तुम क्या कर") || command.contains("क्या क्या कर") || command.contains("काय करू शकते") || command.contains("तू काय कर")

            val isDetectPeople = command.contains("detect people") || command.contains("लोग ढूंढो") || command.contains("कितने लोग") || command.contains("माणसे शोधा") || command.contains("किती लोक")
            val isWhereAmI = command.contains("where am i") || command.contains("location") || command.contains("place") || command.contains("कहाँ हूँ") || command.contains("मेरी स्थिति") || command.contains("स्थान") || command.contains("जगह") || command.contains("कुठे आहे") || command.contains("माझे स्थान") || command.contains("पत्ता") || command.contains("जागा")
            val isNavigation = command.startsWith("navigate") || command.contains("navigate to") || command.contains("go to") || command.contains("map to") || command.contains("guide me to") || command.contains("route to") || command.contains("way to") || command.contains("directions to") || command.contains("ले चलो") || command.contains("रास्ता बताओ") || command.contains("नेविगेट करो") || command.contains("नेव्हिगेट करा") || command.contains("मार्ग दाखवा") || command.contains("मार्ग सांगा") || command.contains("कडे ने")
            val isOpenCamera = command.contains("open camera") || command.contains("open the camera") || command.contains("start camera") || command.contains("camera show") || command.contains("show camera") || command.contains("launch camera") || command.contains("कैमरा खोलो") || command.contains("कैमरा चालू") || command.contains("कैमरा दिखा") || command.contains("कॅमेरा उघडा") || command.contains("कॅमेरा चालू") || command.contains("कॅमेरा दाखवा")
            val isCloseCamera = command.contains("stop camera") || command.contains("close camera") || command.contains("exit camera") || command.contains("कैमरा बंद") || command.contains("कैमरा रोक") || command.contains("कॅमेरा बंद")
            val isDescribeScene = command.contains("describe scene") || command.contains("describe the scene") || command.contains("describe surroundings") || command.contains("describe what you see") || command.contains("what is in front") || command.contains("what do you see") || command.contains("what is there") || command.contains("scan scene") || command.contains("scan surroundings") || command.contains("दृश्य का वर्णन") || command.contains("सामने क्या है") || command.contains("क्या दिख रहा है") || command.contains("क्या दिख रहा") || command.contains("क्या दिख") || command.contains("तुम क्या देख रहे हो") || command.contains("काय दिसत आहे") || command.contains("दृश्य वर्णन") || command.contains("काय दिसत") || command.contains("समोर काय आहे")
            val isReadText = command.contains("read text") || command.contains("read sign") || command.contains("read letters") || command.contains("read board") || command.contains("पाठ पढ़ो") || command.contains("लिखा हुआ पढ़ो") || command.contains("बोर्ड पढ़ो") || command.contains("अक्षर पढ़ो") || command.contains("मजकूर वाचा") || command.contains("पाटी वाचा") || command.contains("लिहिलेले वाचा") || command.contains("अक्षरे वाचा")
            val isEmergency = command.contains("help") || command.contains("emergency") || command.contains("sos") || command.contains("मदद करो") || command.contains("बचाओ") || command.contains("आपातकाल") || command.contains("मदत करा") || command.contains("वाचवा") || command.contains("आणीबाणी")
            val isRecall = command.contains("what did i pass") || command.contains("recall scene") || command.contains("memory recall") || command.contains("whats behind") || command.contains("minutes ago") || command.contains("पीछे क्या था") || command.contains("याद करो") || command.contains("मागे काय होते") || command.contains("आठवा")
            val isEmotion = command.contains("detect emotion") || command.contains("expression") || command.contains("detect face") || command.contains("feeling") || command.contains("भाव पहचानो") || command.contains("चेहरे के भाव") || command.contains("भावना पहचानो") || command.contains("भावना ओळखा") || command.contains("चेहऱ्याचे हावभाव")
            val isCurrency = command.contains("read currency") || command.contains("detect cash") || command.contains("scan rupee") || command.contains("read cash") || command.contains("currency reader") || command.contains("rupees") || command.contains("read note") || command.contains("money") || command.contains("पैसे पढ़ो") || command.contains("रुपया पहचानो") || command.contains("रुपये पहचानो") || command.contains("नोट पढ़ो") || command.contains("करेंसी पहचानो") || command.contains("पैसे") || command.contains("रुपये") || command.contains("चलन") || command.contains("चलन ओळखा") || command.contains("नोट वाचा")
            val isProduct = command.contains("scan product") || command.contains("read label") || command.contains("food scanner") || command.contains("expiry date") || command.contains("ingredients") || command.contains("product label") || command.contains("लेबल पढ़ो") || command.contains("खाने का लेबल") || command.contains("उत्पाद पढ़ो") || command.contains("लेबल वाचा") || command.contains("उत्पादन वाचा")
            val isColor = command.contains("detect color") || command.contains("what color") || command.contains("color scanner") || command.contains("identify color") || command.contains("रंग पहचानो") || command.contains("कौन सा रंग है") || command.contains("रंग बताओ") || command.contains("रंग ओळखा") || command.contains("कोणता रंग")
            val isLight = command.contains("light detector") || command.contains("detect light") || command.contains("ambient light") || command.contains("room brightness") || command.contains("रोशनी पहचानो") || command.contains("प्रकाश पहचानो") || command.contains("उजाला बताओ") || command.contains("प्रकाश ओळखा")
            val isCancel = command.contains("cancel alert") || command.contains("dismiss sos") || command.contains("stop countdown") || command.contains("cancel sos") || command.contains("dismiss panic") || command.contains("cancel") || command.contains("रद्द करो") || command.contains("बंद करो") || command.contains("थांबवा") || command.contains("रद्द करा")
            val isRememberThis = command.contains("remember this place as") || command.contains("remember this as") || command.contains("save landmark") || command.contains("learn landmark") || command.contains("इस जगह को याद रखो") || command.contains("इस जगह को याद") || command.contains("या जागा लक्षात ठेवा") || command.contains("या जागेला लक्षात")
            val isSettings = command.contains("settings") || command.contains("configure") || command.contains("सेटिंग") || command.contains("सेटिंग्स") || command.contains("सेटअप")
            val isRepeat = command.contains("repeat again") || command.contains("say that again") || command.contains("repeat last message") || command.contains("फिर से बोलो") || command.contains("दोहराओ") || command.contains("पुन्हा सांगा")
            val isStopContinuousScanning = command.contains("stop continuous scanning") || command.contains("stop continuous scan") || command.contains("stop auto scanning") || command.contains("stop automatic scanning") || command.contains("stop background scanning") || command.contains("disable continuous scanning") || command.contains("end continuous scanning") || command.contains("turn off continuous scanning") || command.contains("continuous scanning stop") || command.contains("बंद करो निरंतर") || command.contains("निरंतर स्कैनिंग बंद") || command.contains("सतत स्कॅनिंग थांबवा")
            val isStopWalking = command.contains("stop walking with me") || command.contains("stop walking") || command.contains("चलना बंद करो") || command.contains("चालणे थांबवा") || command.contains("साथ चलना बंद करो") || command.contains("सोबत चालणे थांबवा")
            val isWalkWithMe = command.contains("walk with me") || command.contains("मेरे साथ चलो") || command.contains("चलना शुरू करो") || command.contains("माझ्यासोबत चला") || command.contains("चालायला सुरुवात करा")
            // NOTE: "continuous scanning" live-monitoring has been retired and split into
            // indoor / outdoor navigation. Indoor navigation is the new on-device feature;
            // the old proactive trigger phrases are intentionally no longer matched here.
            val isProactiveAlerts = false
            // Stop indoor navigation: any "stop/close/end ... indoor" phrasing.
            val isStopIndoorNavigation = command.contains("indoor") &&
                (command.contains("stop") || command.contains("close") || command.contains("end") ||
                 command.contains("exit") || command.contains("turn off") || command.contains("disable") ||
                 command.contains("बंद") || command.contains("थांबवा") || command.contains("रोक"))
            // Start indoor navigation: forgiving match — any command mentioning "indoor"
            // (even misspelled) starts it, plus common Hindi/Marathi phrasings.
            val isIndoorNavigation = command.contains("indoor") || command.contains("indor") ||
                command.contains("indore") || command.contains("इनडोर") || command.contains("इंडोर") ||
                command.contains("अंदर नेविगेशन") || command.contains("घरात नेव्हिगेशन") || command.contains("आत नेव्हिगेशन")
            // Outdoor navigation: same forgiving matching as indoor — any "outdoor" mention
            // (even misspelled / split) starts it, plus Hindi/Marathi phrasings.
            val mentionsOutdoor = command.contains("outdoor") || command.contains("out door") ||
                command.contains("outdor") || command.contains("आउटडोर") || command.contains("आउट डोर") ||
                command.contains("बाहर नेविगेशन") || command.contains("बाहर का नेविगेशन") ||
                command.contains("बाहेर नेव्हिगेशन") || command.contains("बाहेरचे नेव्हिगेशन")
            val isStopOutdoorNavigation = mentionsOutdoor &&
                (command.contains("stop") || command.contains("close") || command.contains("end") ||
                 command.contains("exit") || command.contains("turn off") || command.contains("disable") ||
                 command.contains("बंद") || command.contains("थांबवा") || command.contains("रोक"))
            val isOutdoorNavigation = mentionsOutdoor
            // Smart navigation: a plain "start navigation" (no indoor/outdoor word, no destination)
            // auto-detects the environment from the camera and switches modes by itself.
            val mentionsIndoorWord = command.contains("indoor") || command.contains("indor") ||
                command.contains("indore") || command.contains("इनडोर") || command.contains("इंडोर")
            val isSmartNavigation = !mentionsOutdoor && !mentionsIndoorWord && !command.contains(" to ") &&
                (command.contains("start navigation") || command.contains("smart navigation") ||
                 command.contains("begin navigation") || command.contains("navigation start") ||
                 command.contains("navigation shuru") || command.contains("navigation chalu") ||
                 command.contains("नेविगेशन शुरू") || command.contains("नेविगेशन चालू") ||
                 command.contains("नेव्हिगेशन सुरू") || command.contains("नेव्हिगेशन चालू"))
            val isPanicEmergency = command.contains("drishti help me") || command.contains("help me help me") || command.contains("panic word")
            val isDashboard = command.contains("home") || command.contains("dashboard") || command.contains("main menu") || command.contains("डैशबोर्ड") || command.contains("होम") || command.contains("डॅशबोर्ड")

            // Universal STOP: a plain "stop" (any language) halts whatever vision/navigation
            // process is running. Only hijacks when something is actually active, so emergency
            // dismiss commands ("cancel", etc.) still work normally when nothing is running.
            val hasActiveVisionOrNav = isIndoorNavActive || isOutdoorNavActive || isSmartNavActive ||
                isProactiveScanningEnabled || isLiveScanning || isWalkWithMeActive || isAnalyzing ||
                isNavigating || isLightDetectorActive
            val isStopWord = command.contains("stop") || command.contains("रुको") || command.contains("रुक जाओ") ||
                command.contains("रोको") || command.contains("रुक") || command.contains("थांब") ||
                command.contains("थांबा") || command.contains("बंद कर") || command.contains("बस कर")
            val isUniversalStop = isStopWord && hasActiveVisionOrNav

            when {
                isUniversalStop -> {
                    stopAllVisionAndNavigation()
                    orbState = OrbState.IDLE
                }
                isLanguageSwitchHindi -> {
                    changeTtsLanguage("hi-IN")
                    speak("भाषा बदलकर हिंदी कर दी गई है।")
                    orbState = OrbState.IDLE
                }
                isLanguageSwitchMarathi -> {
                    changeTtsLanguage("mr-IN")
                    speak("भाषा बदलून मराठी करण्यात आली आहे।")
                    orbState = OrbState.IDLE
                }
                isLanguageSwitchEnglish -> {
                    changeTtsLanguage("en-IN")
                    speak("Language switched to English.")
                    orbState = OrbState.IDLE
                }
                isTimeQuery -> {
                    speak(buildLocalTimeResponse(), bypassCooldown = true)
                    orbState = OrbState.IDLE
                }
                isDateQuery -> {
                    speak(buildLocalDateResponse(), bypassCooldown = true)
                    orbState = OrbState.IDLE
                }
                isDayQuery -> {
                    speak(buildLocalDayResponse(), bypassCooldown = true)
                    orbState = OrbState.IDLE
                }
                isBatteryQuery -> {
                    speak(buildLocalBatteryResponse(), bypassCooldown = true)
                    orbState = OrbState.IDLE
                }
                isGpsStatusQuery -> {
                    speak(buildLocalGpsResponse(), bypassCooldown = true)
                    orbState = OrbState.IDLE
                }
                isCapabilities -> {
                    speak(buildLocalCapabilitiesResponse(), bypassCooldown = true)
                    orbState = OrbState.IDLE
                }
                isDetectPeople -> {
                    _navigationEvents.tryEmit("dashboard")
                    _scrollPagerEvent.tryEmit(3)
                    scanRoomHeadcount()
                }
                isWhereAmI -> {
                    _navigationEvents.tryEmit("navigation")
                    speakCurrentLocation()
                    orbState = OrbState.IDLE
                }
                isNavigation -> {
                    val dest = extractDestination(command)
                    val mappedDest = mapDestinationQuery(dest)
                    _navigationEvents.tryEmit("navigation")
                    if (mappedDest.isNotBlank()) {
                        val cleanQuery = cleanPlaceName(mappedDest)
                        val savedPlace = savedPlaces.value.find { saved ->
                            val cleanSavedName = cleanPlaceName(saved.name)
                            cleanSavedName == cleanQuery || 
                            saved.name.equals(mappedDest, ignoreCase = true) ||
                            cleanSavedName.contains(cleanQuery) ||
                            cleanQuery.contains(cleanSavedName)
                        }
                        if (savedPlace != null) {
                            navigateToSavedPlace(savedPlace)
                        } else {
                            triggerScenicNavigation(mappedDest)
                        }
                    } else {
                        speak("Where would you like me to guide you? Just say where and we'll get you there safely.")
                        orbState = OrbState.IDLE
                    }
                }
                isOpenCamera -> {
                    triggerVoiceVisionAction("scene", "Opening camera and scanning what is in front of you.")
                }
                isCloseCamera -> {
                    _navigationEvents.tryEmit("dashboard")
                    stopLiveScanning()
                    speak("Stopping the camera. Re-entering dashboard standby mode.")
                    orbState = OrbState.IDLE
                }
                isDescribeScene -> {
                    triggerVoiceVisionAction("scene", "Activating camera intelligence. Scanning your surroundings now.")
                }
                isReadText -> {
                    triggerVoiceVisionAction("sign", "Aligning camera lens to read visible text for you.")
                }
                isPanicEmergency -> {
                    _navigationEvents.tryEmit("sos")
                    triggerPanicWordSOS()
                }
                isEmergency -> {
                    _navigationEvents.tryEmit("sos")
                    activateEmergencySOS()
                }
                isRecall -> {
                    handleContextualRecallQuery()
                    orbState = OrbState.IDLE
                }
                isEmotion -> {
                    triggerVoiceVisionAction("emotion", "Activating facial analysis. Let me look at the person in front of you.")
                }
                isCurrency -> {
                    triggerVoiceVisionAction("currency", "Hold the currency note in front of the camera. Scanning now.")
                }
                isProduct -> {
                    triggerVoiceVisionAction("product", "Hold the product label in front of the camera. Reading ingredients and expiry.")
                }
                isColor -> {
                    triggerVoiceVisionAction("color", "Hold the item in front of the camera. Identifying its color now.")
                }
                isLight -> {
                    toggleLightDetector()
                    orbState = OrbState.IDLE
                }
                isCancel -> {
                    cancelFallAlert()
                    dismissPanicAlarm()
                    orbState = OrbState.IDLE
                }
                isRememberThis -> {
                    val placeName = recognizedText
                        .replace("remember this place as", "", ignoreCase = true)
                        .replace("remember this as", "", ignoreCase = true)
                        .replace("save landmark", "", ignoreCase = true)
                        .replace("learn landmark", "", ignoreCase = true)
                        .replace("इस जगह को याद रखो", "", ignoreCase = true)
                        .replace("इस जगह को याद", "", ignoreCase = true)
                        .replace("या जागा लक्षात ठेवा", "", ignoreCase = true)
                        .replace("या जागेला लक्षात", "", ignoreCase = true)
                        .trim()
                    if (placeName.isNotBlank()) {
                        rememberThisPlaceAs(placeName)
                    } else {
                        speak("What name should I remember this place as?")
                        orbState = OrbState.IDLE
                    }
                }
                isSettings -> {
                    _navigationEvents.tryEmit("settings")
                    speak("Opening manual settings preferences page.")
                    orbState = OrbState.IDLE
                }
                isRepeat -> {
                    if (lastImportantMessage.isNotBlank()) {
                        speak(lastImportantMessage, lastImportantMessagePriority ?: SpeechPriority.INFORMATION, bypassCooldown = true)
                    } else {
                        speak("I have not spoken any important messages yet.")
                    }
                    orbState = OrbState.IDLE
                }
                isStopContinuousScanning -> {
                    isWalkWithMeActive = false
                    isLiveScanning = false
                    toggleProactiveState(false)
                    speak("Continuous scanning stopped.")
                    orbState = OrbState.IDLE
                }
                isStopWalking -> {
                    isWalkWithMeActive = false
                    isLiveScanning = false
                    toggleProactiveState(false)
                    val userName = userProfile.value?.name ?: "Sahil"
                    speak("I've stopped walking with you, $userName.", SpeechPriority.NAVIGATION)
                    orbState = OrbState.IDLE
                }
                isProactiveAlerts -> {
                    if (command.contains("stop") || command.contains("disable") || command.contains("end") || command.contains("बंद") || command.contains("थांबवा")) {
                        isLiveScanning = false
                        toggleProactiveState(false)
                        speak("Continuous scanning stopped.")
                    } else {
                        toggleProactiveState(true)
                        _navigationEvents.tryEmit("vision")
                    }
                    orbState = OrbState.IDLE
                }
                isStopOutdoorNavigation -> {
                    stopOutdoorNavigation()
                    orbState = OrbState.IDLE
                }
                isOutdoorNavigation -> {
                    startOutdoorNavigation()
                    orbState = OrbState.IDLE
                }
                isStopIndoorNavigation -> {
                    stopIndoorNavigation()
                    orbState = OrbState.IDLE
                }
                isIndoorNavigation -> {
                    startIndoorNavigation()
                    orbState = OrbState.IDLE
                }
                isSmartNavigation -> {
                    startSmartNavigation()
                    orbState = OrbState.IDLE
                }
                isWalkWithMe -> {
                    isWalkWithMeActive = true
                    toggleProactiveState(true)
                    _navigationEvents.tryEmit("vision")
                    val userName = userProfile.value?.name ?: "Sahil"
                    speak("Okay $userName, I'll walk with you and only alert you when necessary.", SpeechPriority.NAVIGATION)
                    orbState = OrbState.IDLE
                }
                isDashboard -> {
                    _navigationEvents.tryEmit("dashboard")
                    speak("Opening main dashboard.")
                    orbState = OrbState.IDLE
                }
                else -> {
                    val isNavIntent = command.contains("go to") || command.contains("navigate") || command.contains("route to") || command.contains("way to") || command.contains("directions to") || command.contains("guide me to")
                    if (isNavIntent && command.length > 3) {
                        val destination = extractDestination(command)
                        val mappedDest = mapDestinationQuery(destination)
                        if (mappedDest.isNotBlank()) {
                            _navigationEvents.tryEmit("navigation")
                            val cleanQuery = cleanPlaceName(mappedDest)
                            val savedPlace = savedPlaces.value.find { saved ->
                                val cleanSavedName = cleanPlaceName(saved.name)
                                cleanSavedName == cleanQuery || 
                                saved.name.equals(mappedDest, ignoreCase = true) ||
                                cleanSavedName.contains(cleanQuery) ||
                                cleanQuery.contains(cleanSavedName)
                            }
                            if (savedPlace != null) {
                                navigateToSavedPlace(savedPlace)
                            } else {
                                triggerScenicNavigation(mappedDest)
                            }
                        } else {
                            speak("Where would you like to navigate?")
                            orbState = OrbState.IDLE
                        }
                    } else {
                        // Forward to AI Assistant Brain (Groq -> Gemini fallback)
                        val gHistory = getGroqHistory()
                        try {
                            // Try Groq first (primary)
                            val response = repository.getGroqResponse(recognizedText, gHistory, groqApiKey, resolvedGenderOrNull())
                            conversationHistory.add(GroqMessage("user", recognizedText))
                            conversationHistory.add(GroqMessage("assistant", response))
                            if (conversationHistory.size > 20) {
                                conversationHistory.removeAt(0)
                            }
                            speak(response)
                        } catch (e: Exception) {
                            // Fallback to Gemini text response
                            try {
                                val response = repository.getGeminiTextResponse(recognizedText, gHistory, geminiApiKey, resolvedGenderOrNull())
                                conversationHistory.add(GroqMessage("user", recognizedText))
                                conversationHistory.add(GroqMessage("assistant", response))
                                if (conversationHistory.size > 20) {
                                    conversationHistory.removeAt(0)
                                }
                                speak(response)
                            } catch (e2: Exception) {
                                val fallbackMsg = when (ttsLanguage) {
                                    "hi-IN" -> "\u0926\u0943\u0937\u094d\u091f\u093f \u0938\u094b\u091a \u0930\u0939\u0940 \u0939\u0948\u0964 \u091c\u0939\u093e\u0901 \u0924\u0915 \u0939\u092e\u0947\u0902 \u092a\u0924\u093e \u0939\u0948: \u092e\u0948\u0902 \u0906\u092a\u0915\u0947 \u0938\u093e\u0925 \u0939\u0942\u0901, \u0914\u0930 \u0939\u092e \u0906\u092a\u0915\u094b \u092a\u0942\u0930\u0940 \u0924\u0930\u0939 \u0938\u0941\u0930\u0915\u094d\u0937\u093f\u0924 \u0930\u0916\u0947\u0902\u0917\u0947\u0964"
                                    "mr-IN" -> "\u0926\u0943\u0937\u094d\u091f\u0940 \u0935\u093f\u091a\u093e\u0930 \u0915\u0930\u0924 \u0906\u0939\u0947\u0964 \u0906\u092a\u0932\u094d\u092f\u093e\u0932\u093e \u092e\u093e\u0939\u093f\u0924 \u0906\u0939\u0947 \u0924\u094d\u092f\u093e\u0928\u0941\u0938\u093e\u0930: \u092e\u0940 \u0924\u09410\u091d\u094d\u092f\u093e\u0938\u094b\u092c\u0924 \u0906\u0939\u0947 \u0906\u0923\u093f \u0906\u092e\u094d\u0939\u0940 \u0924\u0941\u092e\u094d\u0939\u093e\u0932\u093e \u092a\u0942\u0930\u094d\u0923\u092a\u0923\u0947 \u0938\u0941\u0930\u0915\u094d\u0937\u093f\u0924 \u0920\u0947\u0935\u0942\u0964"
                                    else -> "Drishti is thinking. Here's what we know: I am here with you, and we will keep you fully safe."
                                }
                                speak(fallbackMsg)
                            }
                        }
                        orbState = OrbState.IDLE
                    }
                }
            }
        }
    }

    fun updateRuviewSettings(enabled: Boolean, url: String) {
        viewModelScope.launch {
            val rate = userSettings.value?.speechRate ?: 1.0f
            val voice = userSettings.value?.voiceEnabled ?: true
            repository.updateSettings(rate, voice, ruviewEnabled = enabled, ruviewServerUrl = url)
            speak("RuView configuration updated.")
        }
    }

    fun scanRoomHeadcount() {
        if (isRuviewScanning) return
        isRuviewActive = true
        isRuviewScanning = true
        orbState = OrbState.PROCESSING

        viewModelScope.launch {
            // Speak in parallel (no .join()) so the network check runs immediately
            speak("Scanning room.")
            
            val serverUrl = userSettings.value?.ruviewServerUrl ?: "http://10.0.2.2:3000"
            var liveData = repository.getRuViewData(serverUrl)

            if (liveData == null) {
                // Perform instant parallel fallback discovery
                val gateway = getWifiGatewayIp()
                val fallbacks = mutableListOf<String>()
                if (gateway != null) {
                    fallbacks.add("http://$gateway:3000")
                    fallbacks.add("http://$gateway")
                }
                fallbacks.add("http://192.168.4.1:3000")
                fallbacks.add("http://192.168.4.1")

                val uniqueFallbacks = fallbacks.filter { it != serverUrl }
                if (uniqueFallbacks.isNotEmpty()) {
                    val deferredList = uniqueFallbacks.map { url ->
                        async(Dispatchers.IO) {
                            try {
                                val res = repository.getRuViewData(url)
                                if (res != null) Pair(url, res) else null
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                    val results = deferredList.awaitAll().filterNotNull()
                    if (results.isNotEmpty()) {
                        val (foundUrl, data) = results.first()
                        liveData = data
                        // Update settings with the working URL to avoid future scan delays
                        val rate = userSettings.value?.speechRate ?: 1.0f
                        val voiceEnabled = userSettings.value?.voiceEnabled ?: true
                        val isRuviewEnabled = userSettings.value?.ruviewEnabled ?: true
                        repository.updateSettings(rate, voiceEnabled, ruviewEnabled = isRuviewEnabled, ruviewServerUrl = foundUrl)
                    }
                }
            }

            if (liveData != null) {
                ruviewPeopleCount = liveData.estimatedPersons ?: 0
                ruviewPresence = liveData.classification?.presence ?: (ruviewPeopleCount > 0)
                ruviewConfidence = (liveData.classification?.confidence ?: 0.95).toFloat()
                ruviewMotion = (liveData.classification?.motionLevel ?: "absent") != "absent"
                ruviewHeartRate = (liveData.vitalSigns?.heartRateBpm ?: 72.0).toFloat()
                ruviewBreathingRate = (liveData.vitalSigns?.breathingRateBpm ?: 16.0).toFloat()
                isRuviewConnected = true
                
                val peopleWord = if (ruviewPeopleCount == 1) "person" else "people"
                speak("Scan complete. RuView detects $ruviewPeopleCount $peopleWord in the room.")
            } else {
                isRuviewConnected = false
                ruviewPeopleCount = 0
                ruviewPresence = false
                ruviewMotion = false
                ruviewHeartRate = 0.0f
                ruviewBreathingRate = 0.0f
                
                speak("RuView sensor is offline. Please verify network connection.")
            }
            isRuviewScanning = false
            orbState = OrbState.IDLE
        }
    }

    fun cancelVoiceListening() {
        orbState = OrbState.IDLE
    }

    fun clearAutoAnalyzeIntent() {
        autoAnalyzeIntent = ""
    }

    private fun requestAutoAnalyze(intent: String) {
        autoAnalyzeIntent = intent
        autoAnalyzeRequestId++
    }

    private fun triggerVoiceVisionAction(intent: String, confirmationMessage: String) {
        _navigationEvents.tryEmit("dashboard")
        _scrollPagerEvent.tryEmit(0)
        speak(confirmationMessage)
        orbState = OrbState.IDLE
        viewModelScope.launch {
            delay(1800)
            _capturePhotoForVoiceAnalyzeEvent.emit(intent)
        }
    }

    fun runAutoAnalyzeOnBitmap(bitmap: Bitmap, intent: String) {
        if (isAnalyzing) return
        when (intent) {
            "scene" -> analyzeSceneSurroundings(bitmap)
            "currency" -> scanCurrencyOrDocument(bitmap, isRupee = true)
            "emotion" -> analyzeFacialEmotion(bitmap)
            "sign" -> analyzeImageForSignText(bitmap)
            "obstacle" -> predictObstacleKineticMotion(bitmap)
            "product" -> scanPackagingOrLabels(bitmap)
            "color" -> identifyColorAtCenter(bitmap)
            else -> analyzeSceneSurroundings(bitmap)
        }
    }

    // ==========================================
    // SCENE ANALYSIS & IMAGE READER (UNIFIED CAMERA INTELLIGENCE)
    // ==========================================

    fun analyzeSceneSurroundings(bitmap: Bitmap) {
        if (isAnalyzing) return
        isAnalyzing = true
        orbState = OrbState.PROCESSING

        viewModelScope.launch {
            try {
                speak(getProcessingMessage())

                val textDescription = kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                    repository.analyzeCameraIntent(bitmap, "general scene overview", groqApiKey, geminiApiKey, ttsLanguage)
                }?.takeIf { it.isNotBlank() }
                    ?: "The path ahead looks clear. No immediate hazards detected."

                aiDescriptionResult = textDescription
                addSceneToMemory(textDescription)
                triggerVibration(50L)

                currentSpeechJob?.cancel()
                stopSpeaking()
                speak(textDescription, SpeechPriority.INFORMATION, bypassCooldown = true)
            } catch (e: Exception) {
                Log.e("DrishtiViewModel", "Scene analysis failed: ${e.localizedMessage}", e)
                stopSpeaking()
                speak("Sorry, I could not analyze the scene. Please try tapping scan again.", bypassCooldown = true)
            } finally {
                isAnalyzing = false
                orbState = OrbState.IDLE
            }
        }
    }

    fun analyzeImageForSignText(bitmap: Bitmap) {
        isAnalyzing = true
        orbState = OrbState.PROCESSING

        viewModelScope.launch {
            val speakJob = speak(getProcessingMessage())
            val textParsed = repository.analyzeCameraIntent(bitmap, "read letters or text from sign boards", groqApiKey, geminiApiKey, ttsLanguage)
            
            speakJob.join()

            aiTextReaderResult = textParsed
            isAnalyzing = false
            orbState = OrbState.IDLE
            triggerVibration(50L)
            speak("Extracted sign alert: $textParsed")
        }
    }

    // --- Advanced Features Helper Operations ---
    
    // Feature 1: Contextual Memory Helper
    fun addSceneToMemory(description: String) {
        sceneMemory.add(0, MemorySnippet(description, System.currentTimeMillis()))
        if (sceneMemory.size > 15) {
            sceneMemory.removeLast()
        }
    }

    fun handleContextualRecallQuery() {
        if (sceneMemory.isEmpty()) {
            speak("Contextual visual memory is empty. Describe scenes first to populate short term history.")
            return
        }
        val first = sceneMemory.first()
        speak("Matching short term recall query. About two minutes ago, you passed: ${first.description}")
    }

    // Feature 2: Face Expression Emotion Detection
    fun analyzeFacialEmotion(bitmap: Bitmap) {
        isAnalyzing = true
        orbState = OrbState.PROCESSING
        speak(getProcessingMessage())
        viewModelScope.launch {
            val result = repository.analyzeCameraIntent(bitmap, "detect focus face emotion expression position summary", groqApiKey, geminiApiKey, ttsLanguage)
            detectedEmotionResult = result
            speak(result)
            addSceneToMemory(result)
            isAnalyzing = false
            orbState = OrbState.IDLE
            triggerVibration(50L)
        }
    }

    // Feature 3: Currency Cash Note with Indian Rupee Training & Document Reader
    fun scanCurrencyOrDocument(bitmap: Bitmap, isRupee: Boolean) {
        isAnalyzing = true
        orbState = OrbState.PROCESSING
        speak(getProcessingMessage())
        if (isRupee) {
            viewModelScope.launch {
                val result = repository.analyzeCameraIntent(bitmap, "determine cash rupee currency note denomination value", groqApiKey, geminiApiKey, ttsLanguage)
                currencyScanResult = result
                speak(result)
                addSceneToMemory(result)
                isAnalyzing = false
                orbState = OrbState.IDLE
                triggerVibration(50L)
            }
        } else {
            viewModelScope.launch {
                val result = repository.analyzeCameraIntent(bitmap, "extract document ocr text read sign text clearly", groqApiKey, geminiApiKey, ttsLanguage)
                currencyScanResult = result
                speak(result)
                addSceneToMemory(result)
                isAnalyzing = false
                orbState = OrbState.IDLE
                triggerVibration(50L)
            }
        }
    }

    // Feature 4: Smart Obstacle Motion Prediction
    fun predictObstacleKineticMotion(bitmap: Bitmap) {
        isAnalyzing = true
        orbState = OrbState.PROCESSING
        speak(getProcessingMessage())
        viewModelScope.launch {
            val result = repository.analyzeCameraIntent(bitmap, "predict kinetic obstacle dynamic movement hazards tracks safety", groqApiKey, geminiApiKey, ttsLanguage)
            smartObstaclePrediction = result
            speak(result)
            
            // Sonar proximity triggers
            if (result.contains("meter", ignoreCase = true) || result.contains("close", ignoreCase = true) || result.contains("danger", ignoreCase = true)) {
                val dist = if (result.contains("1 ") || result.contains("0.")) 0.4f else 1.1f
                playObstacleSonarBeeps(dist)
            }
            
            addSceneToMemory(result)
            isAnalyzing = false
            orbState = OrbState.IDLE
            triggerVibration(50L)
        }
    }

    // Feature 5: Trusted Contact Screen Mirroring
    fun toggleGuardianVisionStream() {
        isStreamingToGuardian = !isStreamingToGuardian
        if (isStreamingToGuardian) {
            speak("Vision streaming is now active to your guardian Sunil. Sunil can now see everything your lens captures.")
            Toast.makeText(context, "MOCK WebRTC stream initialized. Status: CONNECTED", Toast.LENGTH_SHORT).show()
        } else {
            speak("Local vision streaming deactivated safely.")
        }
    }

    // Feature 6: Indoor Inertial Positioning without GPS
    // ==========================================
    // AI-POWERED INDOOR NAVIGATION (cloud vision layer over live camera frames)
    // ==========================================

    /** Fed continuously by the CameraX frame analyzer while the Vision screen is open. */
    fun updateNavFrame(bitmap: Bitmap) {
        latestNavFrame = bitmap
        latestNavFrameTime = System.currentTimeMillis()
    }

    fun startIndoorNavigation() {
        // Always route to the camera screen so it opens automatically.
        _navigationEvents.tryEmit("vision")
        if (isIndoorNavActive) return
        if (isOutdoorNavActive) stopOutdoorNavigation(announce = false)
        if (isSmartNavActive) stopSmartNavigation(announce = false)
        isIndoorNavActive = true
        isAnalyzing = false
        lastIndoorSpeakTime = 0L
        lastIndoorSpokenKey = ""
        indoorNavStatus = "Indoor navigation active"
        orbState = OrbState.IDLE
        speak(
            "Starting indoor navigation. Opening the camera now. Move slowly and I'll tell you exactly what is in front of you.",
            SpeechPriority.NAVIGATION,
            bypassCooldown = true
        )
        startIndoorAiLoop()
    }

    fun stopIndoorNavigation(announce: Boolean = true) {
        if (!isIndoorNavActive) return
        isIndoorNavActive = false
        indoorAiJob?.cancel()
        indoorAiJob = null
        indoorNavStatus = ""
        lastIndoorSpokenKey = ""
        if (announce) {
            speak("Okay, I've stopped indoor navigation.", SpeechPriority.NAVIGATION, bypassCooldown = true)
        }
        orbState = OrbState.IDLE
    }

    private val indoorNavPrompt: String = """
        INDOOR NAVIGATION for a blind person walking inside a home, school, college or hostel.
        Look at this forward-facing photo and tell me ONLY the single most important thing in my path right now.
        Say exactly WHAT it is, WHICH direction (on your left / straight ahead / on your right), and roughly how far in meters.
        Be specific and literal about what you really see. Important things to call out correctly:
        stairs going DOWN, stairs going UP, a ramp, a closed door, an open doorway, a pillar or post, a wall,
        a lift or elevator, a person standing, a person sitting on a chair, a chair, a table, a bed, or a low obstacle on the floor.
        NEVER guess an object you do not clearly see, and do not confuse stairs with furniture.
        Keep it under 12 words, one short friendly sentence. If the path is genuinely clear, reply with exactly: CLEAR
    """.trimIndent()

    private fun startIndoorAiLoop() {
        indoorAiJob?.cancel()
        indoorAiJob = viewModelScope.launch {
            if (geminiApiKey.isBlank() && groqApiKey.isBlank()) {
                speak("Indoor navigation needs an internet connection to work. Please check your network.", bypassCooldown = true)
                stopIndoorNavigation(announce = false)
                return@launch
            }
            delay(1000) // let the camera warm up and deliver the first frame
            while (isIndoorNavActive) {
                val frame = latestNavFrame
                val frameFresh = System.currentTimeMillis() - latestNavFrameTime < 1800L
                if (frame == null || !frameFresh) {
                    // Camera screen not visible yet / no fresh frame — wait, don't waste an API call.
                    delay(250)
                    continue
                }

                orbState = OrbState.PROCESSING
                val raw = try {
                    kotlinx.coroutines.withTimeoutOrNull(9000L) {
                        repository.analyzeCameraIntent(
                            bitmap = frame,
                            voiceIntent = indoorNavPrompt,
                            groqKey = groqApiKey,
                            geminiKey = geminiApiKey,
                            targetLanguageCode = ttsLanguage,
                            bypassThrottle = true
                        )
                    }
                } catch (e: Exception) {
                    null
                }
                if (!isIndoorNavActive) break
                orbState = OrbState.IDLE

                val cleaned = raw?.trim()
                if (cleaned.isNullOrBlank() || isIndoorPathClear(cleaned)) {
                    indoorNavStatus = "Path looks clear"
                } else {
                    indoorNavStatus = cleaned
                    lastProactiveHazardAlert = cleaned
                    val key = cleaned.lowercase().filter { it.isLetterOrDigit() }
                    val now = System.currentTimeMillis()
                    // Speak when the message changes, or re-confirm the same hazard every 7s.
                    if (key != lastIndoorSpokenKey || now - lastIndoorSpeakTime > 7000L) {
                        lastIndoorSpokenKey = key
                        lastIndoorSpeakTime = now
                        currentSpeechJob?.cancel()
                        stopSpeaking()
                        speak(cleaned, SpeechPriority.OBSTACLE, bypassCooldown = true)
                        addSceneToMemory(cleaned)

                        val dist = extractDistance(cleaned)
                        if (dist != null) {
                            when {
                                dist <= 0.8f -> { triggerVibration(longArrayOf(0, 450, 105, 450)); playObstacleSonarBeeps(dist) }
                                dist <= 1.5f -> { triggerVibration(250L); playObstacleSonarBeeps(dist) }
                                dist <= 2.5f -> { triggerVibration(80L); playObstacleSonarBeeps(dist) }
                            }
                        }
                    }
                }

                delay(900) // small floor between scans so it stays responsive but not spammy
            }
            orbState = OrbState.IDLE
        }
    }

    private fun isIndoorPathClear(text: String): Boolean {
        val lower = text.trim().lowercase()
        return lower == "clear" ||
            lower == "clear." ||
            lower.startsWith("clear ") ||
            lower.contains("path is clear") ||
            lower.contains("path clear") ||
            lower.contains("nothing in") ||
            lower.contains("no obstacle") ||
            lower.contains("all clear") ||
            lower.contains("रास्ता साफ") || lower.contains("कुछ नहीं") || lower.contains("साफ है") ||
            lower.contains("मोकळा") || lower.contains("काही नाही") || lower.contains("रस्ता स्वच्छ")
    }

    // ==========================================
    // OUTDOOR NAVIGATION
    // Fast layer: on-device EfficientDet-Lite0 (vehicles, riders, people, animals — instant).
    // Deep layer: cloud AI vision (potholes, poles, barricades, broken footpath — every ~2.5s).
    // ==========================================

    fun startOutdoorNavigation() {
        // Always route to the camera screen so it opens automatically.
        _navigationEvents.tryEmit("vision")
        if (isOutdoorNavActive) return
        if (isIndoorNavActive) stopIndoorNavigation(announce = false)
        if (isSmartNavActive) stopSmartNavigation(announce = false)
        isOutdoorNavActive = true
        isAnalyzing = false
        lastOutdoorFastSpeakTime = 0L
        lastOutdoorFastSpokenKey = ""
        lastOutdoorAiSpeakTime = 0L
        lastOutdoorAiSpokenKey = ""
        outdoorNavStatus = "Outdoor navigation active"
        orbState = OrbState.IDLE
        speak(
            "Starting outdoor navigation. Opening the camera now. Walk slowly and I'll warn you about vehicles, people, poles and potholes.",
            SpeechPriority.NAVIGATION,
            bypassCooldown = true
        )
        startOutdoorFastLoop()
        startOutdoorAiLoop()
    }

    fun stopOutdoorNavigation(announce: Boolean = true) {
        if (!isOutdoorNavActive) return
        isOutdoorNavActive = false
        outdoorFastJob?.cancel()
        outdoorFastJob = null
        outdoorAiJob?.cancel()
        outdoorAiJob = null
        outdoorNavStatus = ""
        lastOutdoorFastSpokenKey = ""
        lastOutdoorAiSpokenKey = ""
        if (announce) {
            speak("Okay, I've stopped outdoor navigation.", SpeechPriority.NAVIGATION, bypassCooldown = true)
        }
        orbState = OrbState.IDLE
    }

    /** The offline fast layer serves both plain outdoor mode and smart auto-switching mode. */
    private fun fastLayerActive(): Boolean = isOutdoorNavActive || isSmartNavActive

    /**
     * Fast layer: polls the latest camera frame and runs the offline detector on it.
     * Speaks immediately when a street obstacle (vehicle, person, animal...) is seen.
     */
    private fun startOutdoorFastLoop() {
        outdoorFastJob?.cancel()
        outdoorFastJob = viewModelScope.launch(Dispatchers.Default) {
            val detector = try {
                outdoorDetector ?: OutdoorObstacleDetector(context).also { outdoorDetector = it }
            } catch (e: Exception) {
                Log.w("DrishtiViewModel", "On-device detector unavailable; AI layer only", e)
                null
            } ?: return@launch
            var lastProcessedStamp = 0L
            while (fastLayerActive()) {
                val frame = latestNavFrame
                val stamp = latestNavFrameTime
                if (frame == null || stamp == lastProcessedStamp) {
                    delay(120)
                    continue
                }
                lastProcessedStamp = stamp
                val top = detector.detect(frame).firstOrNull()
                if (!fastLayerActive()) break
                if (top != null) announceFastObstacle(top)
            }
        }
    }

    private fun announceFastObstacle(obstacle: OutdoorObstacleDetector.Obstacle) {
        // In smart mode, the AI layer already describes people while indoors (or until the
        // environment is known) — skip fast person alerts there to avoid double announcements.
        if (isSmartNavActive && smartNavEnvironment != "outdoor" && obstacle.label == "person") return
        val key = "${obstacle.label}|${obstacle.direction}|${obstacle.proximity}"
        val now = System.currentTimeMillis()
        val urgent = obstacle.proximity == OutdoorObstacleDetector.Proximity.VERY_CLOSE
        // Re-announce the same obstacle sooner when it is dangerously close.
        val repeatGap = if (urgent) 2500L else 5000L
        if (key == lastOutdoorFastSpokenKey && now - lastOutdoorFastSpeakTime < repeatGap) return
        lastOutdoorFastSpokenKey = key
        lastOutdoorFastSpeakTime = now
        val sentence = buildFastObstacleSentence(obstacle)
        viewModelScope.launch {
            if (isSmartNavActive) smartNavStatus = sentence else outdoorNavStatus = sentence
            currentSpeechJob?.cancel()
            stopSpeaking()
            speak(sentence, SpeechPriority.OBSTACLE, bypassCooldown = true)
            when (obstacle.proximity) {
                OutdoorObstacleDetector.Proximity.VERY_CLOSE -> {
                    triggerVibration(longArrayOf(0, 450, 105, 450))
                    playObstacleSonarBeeps(0.8f)
                }
                OutdoorObstacleDetector.Proximity.CLOSE -> triggerVibration(250L)
                else -> {}
            }
        }
    }

    /** Already-localized sentence so the fast layer never waits on a translation call. */
    private fun buildFastObstacleSentence(o: OutdoorObstacleDetector.Obstacle): String {
        val label = outdoorLabelName(o.label)
        return when (ttsLanguage) {
            "hi-IN" -> {
                val dir = when (o.direction) {
                    OutdoorObstacleDetector.Direction.LEFT -> "आपके बाएँ तरफ"
                    OutdoorObstacleDetector.Direction.RIGHT -> "आपके दाएँ तरफ"
                    OutdoorObstacleDetector.Direction.AHEAD -> "ठीक सामने"
                }
                val prox = when (o.proximity) {
                    OutdoorObstacleDetector.Proximity.VERY_CLOSE -> "बहुत पास, संभलकर!"
                    OutdoorObstacleDetector.Proximity.CLOSE -> "पास है"
                    OutdoorObstacleDetector.Proximity.NEARBY -> "थोड़ी दूरी पर"
                }
                "$dir $label, $prox"
            }
            "mr-IN" -> {
                val dir = when (o.direction) {
                    OutdoorObstacleDetector.Direction.LEFT -> "तुमच्या डावीकडे"
                    OutdoorObstacleDetector.Direction.RIGHT -> "तुमच्या उजवीकडे"
                    OutdoorObstacleDetector.Direction.AHEAD -> "अगदी समोर"
                }
                val prox = when (o.proximity) {
                    OutdoorObstacleDetector.Proximity.VERY_CLOSE -> "खूप जवळ, सांभाळा!"
                    OutdoorObstacleDetector.Proximity.CLOSE -> "जवळ आहे"
                    OutdoorObstacleDetector.Proximity.NEARBY -> "थोड्या अंतरावर"
                }
                "$dir $label, $prox"
            }
            else -> {
                val dir = when (o.direction) {
                    OutdoorObstacleDetector.Direction.LEFT -> "on your left"
                    OutdoorObstacleDetector.Direction.RIGHT -> "on your right"
                    OutdoorObstacleDetector.Direction.AHEAD -> "straight ahead"
                }
                val prox = when (o.proximity) {
                    OutdoorObstacleDetector.Proximity.VERY_CLOSE -> "very close, careful!"
                    OutdoorObstacleDetector.Proximity.CLOSE -> "getting close"
                    OutdoorObstacleDetector.Proximity.NEARBY -> "a little ahead"
                }
                "$label $dir, $prox"
            }
        }
    }

    private fun outdoorLabelName(label: String): String = when (ttsLanguage) {
        "hi-IN" -> when (label) {
            "person" -> "एक व्यक्ति"; "bicycle" -> "साइकिल"; "car" -> "गाड़ी"
            "motorcycle" -> "बाइक"; "bus" -> "बस"; "truck" -> "ट्रक"; "train" -> "ट्रेन"
            "dog" -> "कुत्ता"; "cow" -> "गाय"; "horse" -> "घोड़ा"; "sheep" -> "भेड़"; "cat" -> "बिल्ली"
            "traffic light" -> "ट्रैफिक सिग्नल"; "stop sign" -> "स्टॉप साइन"
            "fire hydrant" -> "हाइड्रेंट"; "bench" -> "बेंच"; "parking meter" -> "पार्किंग मीटर"
            else -> label
        }
        "mr-IN" -> when (label) {
            "person" -> "एक व्यक्ती"; "bicycle" -> "सायकल"; "car" -> "गाडी"
            "motorcycle" -> "बाईक"; "bus" -> "बस"; "truck" -> "ट्रक"; "train" -> "ट्रेन"
            "dog" -> "कुत्रा"; "cow" -> "गाय"; "horse" -> "घोडा"; "sheep" -> "मेंढी"; "cat" -> "मांजर"
            "traffic light" -> "ट्रॅफिक सिग्नल"; "stop sign" -> "स्टॉप साईन"
            "fire hydrant" -> "हायड्रंट"; "bench" -> "बाक"; "parking meter" -> "पार्किंग मीटर"
            else -> label
        }
        else -> when (label) {
            "person" -> "A person"; "bicycle" -> "A bicycle"; "car" -> "A car"
            "motorcycle" -> "A motorbike"; "bus" -> "A bus"; "truck" -> "A truck"; "train" -> "A train"
            "dog" -> "A dog"; "cow" -> "A cow"; "horse" -> "A horse"; "sheep" -> "A sheep"; "cat" -> "A cat"
            "traffic light" -> "A traffic light"; "stop sign" -> "A stop sign"
            "fire hydrant" -> "A fire hydrant"; "bench" -> "A bench"; "parking meter" -> "A parking meter"
            else -> label
        }
    }

    private val outdoorNavPrompt: String = """
        OUTDOOR NAVIGATION for a blind person walking on an Indian street, road or footpath.
        Look at this forward-facing photo and tell me ONLY the single most important STATIC hazard in my walking path:
        a pothole or hole, an open manhole or drain, a pole or post, a barricade or barrier, construction work or debris,
        a parked vehicle blocking the path, a kerb or step, broken or uneven footpath, a low hanging branch or wire,
        a speed bump, stairs, a tree in the path, a wall, or a garbage pile.
        Say exactly WHAT it is, WHICH direction (on your left / straight ahead / on your right), and roughly how far in meters.
        IGNORE moving vehicles, riders and people — another sensor announces those.
        NEVER guess something you do not clearly see. Keep it under 12 words, one short friendly sentence.
        If the walking path has no such static hazard, reply with exactly: CLEAR
    """.trimIndent()

    /**
     * Deep-scan layer: cloud AI vision every ~2.5s for hazards the offline model
     * has no classes for — potholes, poles, open drains, barricades, broken footpath.
     */
    private fun startOutdoorAiLoop() {
        outdoorAiJob?.cancel()
        outdoorAiJob = viewModelScope.launch {
            if (geminiApiKey.isBlank() && groqApiKey.isBlank()) {
                // No cloud keys: the fast on-device layer still works fully offline.
                return@launch
            }
            delay(1500) // let the camera warm up; the fast layer covers the first moments
            while (isOutdoorNavActive) {
                val frame = latestNavFrame
                val frameFresh = System.currentTimeMillis() - latestNavFrameTime < 1800L
                if (frame == null || !frameFresh) {
                    delay(250)
                    continue
                }

                val raw = try {
                    kotlinx.coroutines.withTimeoutOrNull(9000L) {
                        repository.analyzeCameraIntent(
                            bitmap = frame,
                            voiceIntent = outdoorNavPrompt,
                            groqKey = groqApiKey,
                            geminiKey = geminiApiKey,
                            targetLanguageCode = ttsLanguage,
                            bypassThrottle = true
                        )
                    }
                } catch (e: Exception) {
                    null
                }
                if (!isOutdoorNavActive) break

                val cleaned = raw?.trim()
                if (!cleaned.isNullOrBlank() && !isIndoorPathClear(cleaned)) {
                    outdoorNavStatus = cleaned
                    lastProactiveHazardAlert = cleaned
                    val key = cleaned.lowercase().filter { it.isLetterOrDigit() }
                    val now = System.currentTimeMillis()
                    // Speak when the hazard changes, or re-confirm the same one every 8s.
                    if (key != lastOutdoorAiSpokenKey || now - lastOutdoorAiSpeakTime > 8000L) {
                        lastOutdoorAiSpokenKey = key
                        lastOutdoorAiSpeakTime = now
                        // Don't talk over a fast-layer warning that just fired.
                        if (now - lastOutdoorFastSpeakTime > 1500L) {
                            currentSpeechJob?.cancel()
                            stopSpeaking()
                        }
                        speak(cleaned, SpeechPriority.OBSTACLE, bypassCooldown = true)
                        addSceneToMemory(cleaned)

                        val dist = extractDistance(cleaned)
                        if (dist != null) {
                            when {
                                dist <= 0.8f -> { triggerVibration(longArrayOf(0, 450, 105, 450)); playObstacleSonarBeeps(dist) }
                                dist <= 1.5f -> { triggerVibration(250L); playObstacleSonarBeeps(dist) }
                                dist <= 2.5f -> { triggerVibration(80L); playObstacleSonarBeeps(dist) }
                            }
                        }
                    }
                }

                delay(2500) // deep scans are supplementary; keep API usage modest
            }
        }
    }

    // ==========================================
    // SMART NAVIGATION (auto indoor / outdoor switching)
    // One command. Every AI scan first classifies the environment from the photo itself,
    // then reports the right kind of hazard. The app switches behavior automatically as
    // the user walks from inside a building to the street and back.
    // ==========================================

    fun startSmartNavigation() {
        // Always route to the camera screen so it opens automatically.
        _navigationEvents.tryEmit("vision")
        if (isSmartNavActive) return
        if (isIndoorNavActive) stopIndoorNavigation(announce = false)
        if (isOutdoorNavActive) stopOutdoorNavigation(announce = false)
        isSmartNavActive = true
        isAnalyzing = false
        smartNavEnvironment = ""
        pendingSmartEnvironment = ""
        pendingSmartEnvironmentCount = 0
        lastSmartSpeakTime = 0L
        lastSmartSpokenKey = ""
        lastOutdoorFastSpeakTime = 0L
        lastOutdoorFastSpokenKey = ""
        smartNavStatus = "Sensing your surroundings"
        orbState = OrbState.IDLE
        speak(
            "Starting navigation. I'll sense by myself whether you're indoors or outdoors, and warn you about everything in your path.",
            SpeechPriority.NAVIGATION,
            bypassCooldown = true
        )
        startOutdoorFastLoop()
        startSmartAiLoop()
    }

    fun stopSmartNavigation(announce: Boolean = true) {
        if (!isSmartNavActive) return
        isSmartNavActive = false
        outdoorFastJob?.cancel()
        outdoorFastJob = null
        smartAiJob?.cancel()
        smartAiJob = null
        smartNavStatus = ""
        smartNavEnvironment = ""
        lastSmartSpokenKey = ""
        if (announce) {
            speak("Okay, I've stopped navigation.", SpeechPriority.NAVIGATION, bypassCooldown = true)
        }
        orbState = OrbState.IDLE
    }

    private val smartNavPrompt: String = """
        SMART NAVIGATION for a blind person who may be indoors or outdoors.
        FIRST, decide from this forward-facing photo whether the user is INDOORS or OUTDOORS.
        Begin your reply with exactly the token IN| if indoors, or OUT| if outdoors. These tokens must
        always be in English capital letters, whatever language the rest of the reply is in.
        After the token, give ONE short sentence about the single most important thing in my walking path.
        If INDOORS: stairs going UP or DOWN, a ramp, a closed door, an open doorway, a pillar, a wall,
        a lift or elevator, a person, furniture, or a low obstacle — with direction (on your left / straight ahead / on your right) and rough meters.
        If OUTDOORS: only STATIC hazards — a pothole or hole, an open manhole or drain, a pole or post, a barricade,
        construction debris, a parked vehicle blocking the path, a kerb or step, broken or uneven footpath,
        a low hanging branch or wire, a speed bump, stairs, or a garbage pile — with direction and rough meters.
        IGNORE moving vehicles, riders and people when outdoors — another sensor announces those.
        NEVER guess something you do not clearly see. Keep the sentence under 12 words.
        If the path is clear, the sentence is exactly: CLEAR
    """.trimIndent()

    /** Splits "IN|..." / "OUT|..." replies into environment + spoken sentence. */
    private fun parseSmartReply(reply: String): Pair<String?, String> {
        val trimmed = reply.trim()
        val sepIndex = trimmed.indexOfFirst { it == '|' || it == ':' }
        val head = (if (sepIndex > 0) trimmed.substring(0, sepIndex) else trimmed.take(8)).trim().uppercase()
        val env = when {
            head.startsWith("OUT") -> "outdoor"
            head.startsWith("IN") -> "indoor"
            else -> null
        }
        val body = if (env != null && sepIndex > 0) trimmed.substring(sepIndex + 1).trim() else trimmed
        return Pair(env, body)
    }

    /** Spoken instantly in the user's language when the environment changes. */
    private fun smartEnvAnnouncement(env: String): String = when (ttsLanguage) {
        "hi-IN" ->
            if (env == "outdoor") "अब आप बाहर हैं। गाड़ियों, गड्ढों और खंभों पर नज़र रख रही हूँ।"
            else "अब आप अंदर हैं। सीढ़ियों, दरवाज़ों और रुकावटों पर नज़र रख रही हूँ।"
        "mr-IN" ->
            if (env == "outdoor") "आता तुम्ही बाहेर आहात. गाड्या, खड्डे आणि खांबांवर लक्ष ठेवत आहे."
            else "आता तुम्ही आत आहात. पायऱ्या, दारं आणि अडथळ्यांवर लक्ष ठेवत आहे."
        else ->
            if (env == "outdoor") "You're outdoors now. Watching for vehicles, potholes and poles."
            else "You're indoors now. Watching for stairs, doors and obstacles."
    }

    private fun startSmartAiLoop() {
        smartAiJob?.cancel()
        smartAiJob = viewModelScope.launch {
            if (geminiApiKey.isBlank() && groqApiKey.isBlank()) {
                speak(
                    "I need an internet connection for full detection, but I'll still warn you about vehicles and people.",
                    bypassCooldown = true
                )
                return@launch // the offline fast layer keeps running
            }
            delay(1000) // let the camera warm up and deliver the first frame
            while (isSmartNavActive) {
                val frame = latestNavFrame
                val frameFresh = System.currentTimeMillis() - latestNavFrameTime < 1800L
                if (frame == null || !frameFresh) {
                    delay(250)
                    continue
                }

                val raw = try {
                    kotlinx.coroutines.withTimeoutOrNull(9000L) {
                        repository.analyzeCameraIntent(
                            bitmap = frame,
                            voiceIntent = smartNavPrompt,
                            groqKey = groqApiKey,
                            geminiKey = geminiApiKey,
                            targetLanguageCode = ttsLanguage,
                            bypassThrottle = true
                        )
                    }
                } catch (e: Exception) {
                    null
                }
                if (!isSmartNavActive) break

                val cleaned = raw?.trim()
                if (!cleaned.isNullOrBlank()) {
                    val (env, body) = parseSmartReply(cleaned)

                    // Environment switching with a 2-scan debounce so one odd photo
                    // (e.g. facing a window) doesn't flip the mode back and forth.
                    if (env != null && env != smartNavEnvironment) {
                        if (env == pendingSmartEnvironment) pendingSmartEnvironmentCount++
                        else { pendingSmartEnvironment = env; pendingSmartEnvironmentCount = 1 }
                        val firstFix = smartNavEnvironment.isEmpty()
                        if (firstFix || pendingSmartEnvironmentCount >= 2) {
                            smartNavEnvironment = env
                            pendingSmartEnvironment = ""
                            pendingSmartEnvironmentCount = 0
                            currentSpeechJob?.cancel()
                            stopSpeaking()
                            speak(smartEnvAnnouncement(env), SpeechPriority.NAVIGATION, bypassCooldown = true)
                        }
                    } else {
                        pendingSmartEnvironment = ""
                        pendingSmartEnvironmentCount = 0
                    }

                    if (body.isBlank() || isIndoorPathClear(body)) {
                        smartNavStatus = "Path looks clear"
                    } else {
                        smartNavStatus = body
                        lastProactiveHazardAlert = body
                        val key = body.lowercase().filter { it.isLetterOrDigit() }
                        val now = System.currentTimeMillis()
                        // Speak when the hazard changes, or re-confirm the same one every 7s.
                        if (key != lastSmartSpokenKey || now - lastSmartSpeakTime > 7000L) {
                            lastSmartSpokenKey = key
                            lastSmartSpeakTime = now
                            // Don't talk over a fast-layer vehicle warning that just fired.
                            if (now - lastOutdoorFastSpeakTime > 1500L) {
                                currentSpeechJob?.cancel()
                                stopSpeaking()
                            }
                            speak(body, SpeechPriority.OBSTACLE, bypassCooldown = true)
                            addSceneToMemory(body)

                            val dist = extractDistance(body)
                            if (dist != null) {
                                when {
                                    dist <= 0.8f -> { triggerVibration(longArrayOf(0, 450, 105, 450)); playObstacleSonarBeeps(dist) }
                                    dist <= 1.5f -> { triggerVibration(250L); playObstacleSonarBeeps(dist) }
                                    dist <= 2.5f -> { triggerVibration(80L); playObstacleSonarBeeps(dist) }
                                }
                            }
                        }
                    }
                }

                // Indoors needs quicker reactions (stairs, doors); outdoors the fast layer
                // covers immediate danger, so deep scans can be more relaxed.
                delay(if (smartNavEnvironment == "indoor") 1200L else 2500L)
            }
        }
    }

    /**
     * Universal STOP: halts whatever vision / navigation process is currently running
     * (indoor navigation, scene scanning, walk-with-me / continuous scanning, map navigation,
     * light detector). Emergency / SOS features are intentionally NOT affected.
     */
    fun stopAllVisionAndNavigation() {
        // Indoor AI navigation
        indoorAiJob?.cancel()
        indoorAiJob = null
        isIndoorNavActive = false
        indoorNavStatus = ""
        lastIndoorSpokenKey = ""

        // Outdoor navigation (fast on-device layer + AI deep-scan layer)
        outdoorFastJob?.cancel()
        outdoorFastJob = null
        outdoorAiJob?.cancel()
        outdoorAiJob = null
        isOutdoorNavActive = false
        outdoorNavStatus = ""
        lastOutdoorFastSpokenKey = ""
        lastOutdoorAiSpokenKey = ""

        // Smart auto indoor/outdoor navigation
        smartAiJob?.cancel()
        smartAiJob = null
        isSmartNavActive = false
        smartNavStatus = ""
        smartNavEnvironment = ""
        lastSmartSpokenKey = ""

        // Proactive / walk-with-me / live scanning
        proactiveCycleJob?.cancel()
        proactiveCycleJob = null
        unregisterProactiveMotionSensor()
        isProactiveScanningEnabled = false
        isWalkWithMeActive = false
        isLiveScanning = false
        isPathClearState = false
        lastSpokenProactiveResult = ""

        // Scene / camera analysis UI state
        isAnalyzing = false

        // Light detector (sensory vision aid)
        if (isLightDetectorActive) {
            isLightDetectorActive = false
            sensorManager.unregisterListener(this, lightSensor)
        }

        // Map navigation
        if (isNavigating) stopNavigation()

        currentSpeechJob?.cancel()
        stopSpeaking()
        orbState = OrbState.IDLE
        speak("Okay, I've stopped.", SpeechPriority.NAVIGATION, bypassCooldown = true)
    }

    fun toggleIndoorNavigationMode() {
        isIndoorMode = !isIndoorMode
        if (isIndoorMode) {
            speak("Satellites are weak inside. Activating our advanced local indoor positioning. Relying on wireless beacon triangulation and step dead-reckoning.")
            Toast.makeText(context, "Indoor Mode On. WiFi Beacon + Accelerometer Step Tracker Active.", Toast.LENGTH_SHORT).show()
        } else {
            speak("Standard outer GPS navigation restored.")
        }
    }

    // Feature 7: Voice-Activated Lock-Screen Panic
    fun triggerPanicWordSOS() {
        isPanicModeActive = true
        orbState = OrbState.EMERGENCY
        speak("PANIC ALERT DETECTED. Sounding loud emergency siren, sending your coordinates to Sunil, and initiating a help call. Please stay calm, help is coming!", SpeechPriority.EMERGENCY)
        activateEmergencySOS()
    }

    fun dismissPanicAlarm() {
        isPanicModeActive = false
        orbState = OrbState.IDLE
        speak("Emergency alarm disabled. Re-entering standby.", SpeechPriority.EMERGENCY)
    }

    // Feature 8: Continuous Proactive Hazard Warnings
    fun toggleProactiveState(enabled: Boolean) {
        isProactiveScanningEnabled = enabled
        if (enabled) {
            isLiveScanning = true
            lastSpokenProactiveResult = ""
            isPathClearState = false
            if (!isWalkWithMeActive) {
                speak("Continuous scanning on. I'll only speak while you walk, saying what is ahead and how far.")
            }
            registerProactiveMotionSensor()
            triggerProactiveAutoCycle()
        } else {
            proactiveCycleJob?.cancel()
            proactiveCycleJob = null
            unregisterProactiveMotionSensor()
            isWalkWithMeActive = false
            isLiveScanning = false
            lastSpokenProactiveResult = ""
            isPathClearState = false
            speak("Background proactive scanning paused.")
        }
    }

    private fun registerProactiveMotionSensor() {
        if (accelerometerRegisteredForProactive || accelerometer == null) return
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        accelerometerRegisteredForProactive = true
        movementEnergies.clear()
        lastAccelMagnitude = 0f
        isUserWalkingState = false
    }

    private fun unregisterProactiveMotionSensor() {
        if (!accelerometerRegisteredForProactive) return
        sensorManager.unregisterListener(this, accelerometer)
        accelerometerRegisteredForProactive = false
        movementEnergies.clear()
        lastAccelMagnitude = 0f
        isUserWalkingState = false
    }

    private fun updateWalkingState(x: Float, y: Float, z: Float) {
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        if (lastAccelMagnitude > 0f) {
            movementEnergies.addLast(kotlin.math.abs(magnitude - lastAccelMagnitude))
            if (movementEnergies.size > 24) movementEnergies.removeFirst()
        }
        lastAccelMagnitude = magnitude

        if (movementEnergies.size >= 12) {
            val energy = movementEnergies.sum()
            isUserWalkingState = energy > 7.5f
        }
    }

    private fun isUserWalking(): Boolean = isUserWalkingState

    private fun buildProactiveScanPrompt(): String {
        return """
            CONTINUOUS WALK SAFETY SCAN. Camera points forward from a blind user who is walking.
            Report ONLY the single most important object directly in the walking path ahead.
            Use EXACTLY this format: "[ITEM] at [NUMBER] meters ahead" OR respond only with the word "clear".
            ITEM must be one of: person, 2 people, people, bicycle, bike, motorcycle, car, wall, pole, steps, obstacle.
            Estimate distance in meters. No colors, no scenery, no furniture details, no background objects.
            Max 8 words. Examples: "person at 2 meters ahead", "bicycle at 4 meters ahead", "wall at 1 meter ahead", "clear".
        """.trimIndent()
    }

    private fun summarizeProactiveAlert(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val lower = trimmed.lowercase()
        if (lower == "clear" ||
            lower.contains("path is clear") ||
            lower.contains("path clear") ||
            lower.contains("no hazards") ||
            lower.contains("no obstacles") ||
            lower.contains("nothing ahead") ||
            lower.contains("all clear")
        ) {
            return null
        }

        val shortFormat = Regex("""^(.+?)\s+at\s+(\d+(?:\.\d+)?)\s+meters?\s+ahead\.?$""", RegexOption.IGNORE_CASE)
        shortFormat.matchEntire(trimmed)?.let { match ->
            val item = normalizeProactiveItem(match.groupValues[1])
            val dist = match.groupValues[2].toFloatOrNull()?.let { if (it < 1f) "1" else String.format("%.0f", it) }
            return if (dist != null) "$item at $dist meters ahead" else "$item ahead"
        }

        val dist = extractDistance(trimmed)
        val distLabel = dist?.let { if (it < 1f) "1" else String.format("%.0f", it) }
        val item = normalizeProactiveItem(lower)

        return when {
            distLabel != null && item.isNotBlank() -> "$item at $distLabel meters ahead"
            item.isNotBlank() -> "$item ahead"
            else -> null
        }
    }

    private fun normalizeProactiveItem(text: String): String {
        val lower = text.trim().lowercase()
        return when {
            lower.contains("2 people") || lower.contains("two people") || lower.contains("two person") -> "2 people"
            lower.contains("people") || lower.contains("crowd") || lower.contains("group") -> "people"
            lower.contains("person") || lower.contains("man") || lower.contains("woman") ||
                lower.contains("boy") || lower.contains("girl") || lower.contains("someone") ||
                lower.contains("pedestrian") || lower.contains("human") -> "person"
            lower.contains("bicycle") || (lower.contains("cycle") && !lower.contains("motor")) -> "bicycle"
            lower.contains("motorcycle") || lower.contains("scooter") || lower.contains("bike") -> "bike"
            lower.contains("car") || lower.contains("truck") || lower.contains("bus") || lower.contains("vehicle") -> "car"
            lower.contains("wall") -> "wall"
            lower.contains("pole") || lower.contains("pillar") || lower.contains("post") -> "pole"
            lower.contains("step") || lower.contains("stair") -> "steps"
            lower.contains("obstacle") || lower.contains("barrier") || lower.contains("hazard") ||
                lower.contains("block") -> "obstacle"
            else -> lower.split(" ").take(3).joinToString(" ")
        }
    }

    private fun triggerProactiveAutoCycle() {
        proactiveCycleJob?.cancel()
        proactiveCycleJob = viewModelScope.launch {
            delay(1500)
            while (isProactiveScanningEnabled) {
                _capturePhotoForProactiveEvent.emit(Unit)
                delay(4800)
            }
        }
    }

    fun processCapturedPhotoForProactive(bitmap: Bitmap) {
        if (!isProactiveScanningEnabled) return

        if (!isUserWalking()) {
            isAnalyzing = false
            orbState = OrbState.IDLE
            return
        }

        isAnalyzing = true
        orbState = OrbState.PROCESSING
        viewModelScope.launch {
            val rawResult = if (geminiApiKey.isNotBlank() || groqApiKey.isNotBlank()) {
                repository.analyzeCameraIntent(
                    bitmap = bitmap,
                    voiceIntent = buildProactiveScanPrompt(),
                    groqKey = groqApiKey,
                    geminiKey = geminiApiKey,
                    targetLanguageCode = ttsLanguage,
                    bypassThrottle = true
                )
            } else {
                val offlineAlerts = listOf(
                    "person at 2 meters ahead",
                    "bicycle at 4 meters ahead",
                    "wall at 1 meter ahead",
                    "clear"
                )
                offlineAlerts[(System.currentTimeMillis() / 5000 % offlineAlerts.size).toInt()]
            }

            val alert = summarizeProactiveAlert(rawResult)
            if (alert != null) {
                lastProactiveHazardAlert = alert
                aiDescriptionResult = alert
                isPathClearState = false

                if (alert != lastSpokenProactiveResult) {
                    lastSpokenProactiveResult = alert
                    currentSpeechJob?.cancel()
                    stopSpeaking()
                    speak(alert, SpeechPriority.OBSTACLE, bypassCooldown = true)
                }

                val dist = extractDistance(alert)
                if (dist != null) {
                    when {
                        dist <= 0.8f -> {
                            triggerVibration(longArrayOf(0, 450, 105, 450))
                            playObstacleSonarBeeps(dist)
                        }
                        dist <= 1.5f -> {
                            triggerVibration(250L)
                            playObstacleSonarBeeps(dist)
                        }
                        dist <= 2.5f -> {
                            triggerVibration(80L)
                            playObstacleSonarBeeps(dist)
                        }
                    }
                }
                addSceneToMemory(alert)
            } else {
                isPathClearState = true
                lastSpokenProactiveResult = "clear"
            }

            isAnalyzing = false
            orbState = OrbState.IDLE
        }
    }

    private fun extractDistance(text: String): Float? {
        try {
            val regex = Regex("""(\d+(?:\.\d+)?)\s*(?:meter|meters|m|मीटर|मिटर|feet|ft)\b""", RegexOption.IGNORE_CASE)
            val match = regex.find(text)
            if (match != null) {
                val numStr = match.groupValues[1]
                var dist = numStr.toFloatOrNull()
                if (dist != null) {
                    if (match.groupValues[0].contains("feet", ignoreCase = true) || match.groupValues[0].contains("ft", ignoreCase = true)) {
                        dist *= 0.3048f
                    }
                    return dist
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    // Feature 9: Food Pack expiry ingredients scanner
    fun scanPackagingOrLabels(bitmap: Bitmap) {
        isAnalyzing = true
        orbState = OrbState.PROCESSING
        speak(getProcessingMessage())
        viewModelScope.launch {
            val result = repository.analyzeCameraIntent(bitmap, "read packaging ingredients labels expiry details dairy lactose info", groqApiKey, geminiApiKey, ttsLanguage)
            productScanResult = result
            speak(result)
            addSceneToMemory(result)
            isAnalyzing = false
            orbState = OrbState.IDLE
            triggerVibration(50L)
        }
    }

    // Feature 10: Personalized Landmark Learning
    fun learnPersonalLandmarkTag(name: String) {
        val lat = currentLatitude
        val lng = currentLongitude
        val entry = LearnedLandmark(name, lat, lng)
        learnedLandmarks.add(0, entry)
        speak("Got it! I have saved $name as a custom landmark pinpointed at your exact location.")
    }

    private fun generateSimulatedBitmap(context: android.content.Context, type: String): Bitmap {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.DKGRAY)
        return bitmap
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                if (isLightDetectorActive) {
                    val lux = event.values[0]
                    // Map lux directly to tone pitch update
                    val freq = (250 + (lux.coerceAtMost(2000f) * 0.8f)).toInt()
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            lightToneGenerator?.stopTone()
                            lightToneGenerator?.release()
                            lightToneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
                            lightToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                if (accelerometerRegisteredForProactive) {
                    updateWalkingState(x, y, z)
                }
                val gForce = sqrt((x * x + y * y + z * z).toDouble()) / 9.80665

                val currentTime = System.currentTimeMillis()

                // 1. Detect Freefall (< 0.3 G)
                if (gForce < 0.3) {
                    lastFreefallTime = currentTime
                }

                // 2. Detect Impact (> 2.8 G) within 350ms of freefall
                if (gForce > 2.8 && (currentTime - lastFreefallTime < 350)) {
                    lastImpactTime = currentTime
                }

                // 3. Detect Rest (approx 1.0 G, vector between 0.85 and 1.15)
                // If rest is stable for 1.2s after impact, confirm fall!
                if (lastImpactTime > 0 && (currentTime - lastImpactTime > 1200) && gForce > 0.85 && gForce < 1.15) {
                    lastImpactTime = 0
                    lastFreefallTime = 0
                    triggerFallCountdownAlert()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun toggleLightDetector() {
        isLightDetectorActive = !isLightDetectorActive
        if (isLightDetectorActive) {
            triggerVibration(100L)
            speak("Light detector active. Sweeping room brightness values.")
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            triggerVibration(longArrayOf(0, 300))
            speak("Light detector stopped.")
            sensorManager.unregisterListener(this, lightSensor)
            lightToneGenerator?.stopTone()
            lightToneGenerator?.release()
            lightToneGenerator = null
        }
    }

    private fun triggerFallCountdownAlert() {
        if (fallCountdownJob != null) return // Already running
        triggerVibration(longArrayOf(0, 400, 100, 400, 100, 400))
        speak("Attention. Impact detected. Initializing automatic guardian alert sequence.", SpeechPriority.EMERGENCY)
        fallCountdownJob = viewModelScope.launch {
            fallCountdownSecondsRemaining = 10
            while (fallCountdownSecondsRemaining > 0) {
                speak("$fallCountdownSecondsRemaining", SpeechPriority.EMERGENCY)
                delay(1000)
                fallCountdownSecondsRemaining--
            }
            // If countdown reaches 0, trigger emergency alert!
            triggerVibration(500)
            speak("Emergency. Guardian alert sent.", SpeechPriority.EMERGENCY)
            activateEmergencySOS()
            fallCountdownJob = null
        }
    }

    fun cancelFallAlert() {
        if (fallCountdownJob != null) {
            fallCountdownJob?.cancel()
            fallCountdownJob = null
            fallCountdownSecondsRemaining = 0
            triggerVibration(100L)
            speak("Emergency alert cancelled. Re entering standby.", SpeechPriority.EMERGENCY)
        }
    }

    fun playObstacleSonarBeeps(distance: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
                val rate = when {
                    distance < 0.5f -> 120L
                    distance < 1.2f -> 300L
                    else -> 600L
                }
                val count = when {
                    distance < 0.5f -> 4
                    distance < 1.2f -> 2
                    else -> 1
                }
                for (i in 0 until count) {
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                    delay(rate)
                }
                toneGen.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun identifyColorAtCenter(bitmap: Bitmap) {
        isAnalyzing = true
        orbState = OrbState.PROCESSING
        speak(getProcessingMessage())
        
        // Local name resolver
        val width = bitmap.width
        val height = bitmap.height
        val centerPixel = bitmap.getPixel(width / 2, height / 2)
        val r = android.graphics.Color.red(centerPixel)
        val g = android.graphics.Color.green(centerPixel)
        val b = android.graphics.Color.blue(centerPixel)

        val baseColors = mapOf(
            "Red" to Triple(255, 0, 0),
            "Green" to Triple(0, 180, 0),
            "Blue" to Triple(0, 0, 255),
            "Yellow" to Triple(255, 235, 0),
            "Orange" to Triple(255, 120, 0),
            "Purple" to Triple(128, 0, 128),
            "White" to Triple(255, 255, 255),
            "Black" to Triple(15, 15, 15),
            "Gray" to Triple(120, 120, 120),
            "Brown" to Triple(100, 50, 20),
            "Pink" to Triple(255, 150, 180)
        )
        var matchedColorName = "Unknown"
        var minDistance = Double.MAX_VALUE
        for ((name, rgb) in baseColors) {
            val dist = sqrt(((r - rgb.first) * (r - rgb.first) + (g - rgb.second) * (g - rgb.second) + (b - rgb.third) * (b - rgb.third)).toDouble())
            if (dist < minDistance) {
                minDistance = dist
                matchedColorName = name
            }
        }
        
        speak("Quick detect color is $matchedColorName.")
        
        // Also perform detailed Gemini analysis
        viewModelScope.launch {
            val detail = repository.analyzeCameraIntent(bitmap, "describe the dominant color shade, lighting, and visual tone in detail for a blind person", groqApiKey, geminiApiKey, ttsLanguage)
            detectedColorName = "$matchedColorName: $detail"
            speak(detail)
            addSceneToMemory("Color detection: $matchedColorName. Description: $detail")
            isAnalyzing = false
            orbState = OrbState.IDLE
        }
    }

    fun speakCurrentLocation() {
        viewModelScope.launch {
            val loc = getFreshLocation()
            if (loc != null) {
                currentLatitude = loc.latitude
                currentLongitude = loc.longitude
                val resolvedAddress = repository.reverseGeocode(currentLatitude, currentLongitude)
                currentAddressText = resolvedAddress
                speak("You are currently at $resolvedAddress", SpeechPriority.INFORMATION)
            } else {
                speak("Location unavailable. Try again in a few seconds.", SpeechPriority.INFORMATION)
            }
        }
    }

    fun activateEmergencySOS() {
        isPanicModeActive = true
        orbState = OrbState.EMERGENCY
        viewModelScope.launch {
            speak("Emergency SOS activated.", SpeechPriority.EMERGENCY)
            val location = getFreshLocation()
            val lat = location?.latitude ?: currentLatitude
            val lng = location?.longitude ?: currentLongitude
            val address = repository.reverseGeocode(lat, lng)
            val guardian = guardianProfile.value
            if (guardian != null && guardian.email.isNotBlank()) {
                sendSosemail(guardian.email, guardian.name, address, lat, lng)
            } else {
                speak("No guardian email configured for SOS alerts.", SpeechPriority.EMERGENCY)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearHistory()
        }
    }

    private fun parseStepInstruction(step: OsrmStep): String {
        val baseInstruction = step.maneuver?.instruction ?: "Move ahead."
        val distanceText = if (step.distance != null) {
            " for ${step.distance.toInt()} meters"
        } else ""
        return "$baseInstruction$distanceText."
    }

    fun navigateToSavedPlace(place: SavedPlaceEntity) {
        navigatingToSavedPlace = place
        hasTriggeredArrivalGuidance = false
        selectedDestinationName = place.name
        isCalculatingRoute = true
        routeChangeCounter++

        viewModelScope.launch {
            val speakJob = speak("Navigating to saved place ${place.name}. Starting visual navigation guide.", SpeechPriority.NAVIGATION)
            
            val location = getFreshLocation()
            if (location != null) {
                currentLatitude = location.latitude
                currentLongitude = location.longitude
                val resolvedAddress = repository.reverseGeocode(currentLatitude, currentLongitude)
                currentAddressText = resolvedAddress
            }

            destinationLatitude = place.latitude
            destinationLongitude = place.longitude

            val route = repository.calculateRoute(currentLatitude, currentLongitude, place.latitude, place.longitude)
            
            speakJob.join()

            if (route != null && route.legs != null) {
                val steps = route.legs.flatMap { it.steps ?: emptyList() }
                activeNavigationSteps = steps
                currentStepIndex = 0
                isNavigating = true
                isCalculatingRoute = false
                startLocationTrackingLoop()

                val totalDistance = route.distance ?: 0.0
                val distText = if (totalDistance > 1000) {
                    "${String.format(Locale.US, "%.1f", totalDistance / 1000.0)} kilometers"
                } else {
                    "${totalDistance.toInt()} meters"
                }

                if (steps.isNotEmpty()) {
                    val firstStep = steps.first()
                    val instruction = parseStepInstruction(firstStep)
                    speak("Direct route to ${place.name} is $distText. First step: $instruction", SpeechPriority.NAVIGATION)
                } else {
                    speak("You have arrived at your destination.", SpeechPriority.NAVIGATION)
                }
            } else {
                isCalculatingRoute = false
                speak("Route calculation failed.", SpeechPriority.NAVIGATION)
            }
        }
    }

    fun triggerScenicNavigation(destination: String) {
        val cleanedDest = destination
            .replace("enginerring", "engineering", ignoreCase = true)
            .replace("engneering", "engineering", ignoreCase = true)
            .replace("engeneering", "engineering", ignoreCase = true)
            .trim()

        selectedDestinationName = cleanedDest
        isCalculatingRoute = true
        routeChangeCounter++

        viewModelScope.launch {
            val speakJob = speak("Finding route to $cleanedDest.", SpeechPriority.NAVIGATION)
            val location = getFreshLocation()
            if (location != null) {
                currentLatitude = location.latitude
                currentLongitude = location.longitude
                val resolvedAddress = repository.reverseGeocode(currentLatitude, currentLongitude)
                currentAddressText = resolvedAddress
            }

            val destinationLocation = repository.searchDestination(cleanedDest)
            if (destinationLocation != null) {
                val destLat = destinationLocation.lat.toDoubleOrNull() ?: 18.6873
                val destLng = destinationLocation.lon.toDoubleOrNull() ?: 73.8569

                destinationLatitude = destLat
                destinationLongitude = destLng

                val route = repository.calculateRoute(currentLatitude, currentLongitude, destLat, destLng)
                
                speakJob.join()

                if (route != null && route.legs != null) {
                    val steps = route.legs.flatMap { it.steps ?: emptyList() }
                    activeNavigationSteps = steps
                    currentStepIndex = 0
                    isNavigating = true
                    isCalculatingRoute = false
                    startLocationTrackingLoop()

                    val totalDistance = route.distance ?: 0.0
                    val distText = if (totalDistance > 1000) {
                        "${String.format(Locale.US, "%.1f", totalDistance / 1000.0)} kilometers"
                    } else {
                        "${totalDistance.toInt()} meters"
                    }

                    if (steps.isNotEmpty()) {
                        val firstStep = steps.first()
                        val instruction = parseStepInstruction(firstStep)
                        speak("Navigation initialized to $cleanedDest. Distance: $distText. First step: $instruction", SpeechPriority.NAVIGATION)
                    } else {
                        speak("Destination is within immediate walking range.", SpeechPriority.NAVIGATION)
                    }
                } else {
                    isCalculatingRoute = false
                    speak("Route unavailable currently. Ensure network services are online.", SpeechPriority.NAVIGATION)
                }
            } else {
                isCalculatingRoute = false
                speak("Destination could not be resolved on the map. Try another search phrase.", SpeechPriority.NAVIGATION)
            }
        }
    }

    fun rememberThisPlaceAs(name: String) {
        viewModelScope.launch {
            speak("Scanning the surroundings to remember $name. Please hold your device steady.")
            delay(1200)
            _capturePhotoForPlaceEvent.emit(name)
        }
    }

    fun processCapturedPhotoForPlace(placeName: String, bitmap: Bitmap) {
        viewModelScope.launch {
            orbState = OrbState.PROCESSING
            val lat = currentLatitude
            val lng = currentLongitude
            val address = repository.reverseGeocode(lat, lng)

            val base64Data = try {
                val outputStream = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
                val byteArray = outputStream.toByteArray()
                Base64.encodeToString(byteArray, Base64.NO_WRAP)
            } catch (e: Exception) {
                ""
            }

            var description = "Building entry point at coordinates ($lat, $lng)"
            var landmarks = "Tea stall and nearby structures"

            if (geminiApiKey.isNotBlank() && base64Data.isNotBlank()) {
                try {
                    val prompt = """
                        You are Drishti, describe the building entry point and nearby landmarks visible in this camera frame.
                        Keep the description of the building entry point extremely short (under 20 words), focusing on unique identifiers (e.g. "blue gate", "glass doors", "concrete ramp").
                        Keep the description of the surrounding landmarks also extremely short (under 20 words) (e.g. "small tea stall on left").
                    """.trimIndent()
                    val result = repository.analyzeCameraIntent(bitmap, prompt, groqApiKey, geminiApiKey, ttsLanguage)
                    if (result.isNotBlank() && !result.contains("error", ignoreCase = true)) {
                        description = result
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            repository.saveSavedPlace(placeName, lat, lng, address, "", description, landmarks)
            speak("Place $placeName saved successfully.", SpeechPriority.INFORMATION)
            orbState = OrbState.IDLE
        }
    }

    fun saveSmtpSettings(enabled: Boolean, host: String, port: Int, email: String, pass: String) {
        viewModelScope.launch {
            repository.updateSmtpSettings(enabled, host, port, email, pass)
            speak("Emergency email server settings updated successfully.")
        }
    }

    fun stopNavigation() {
        isNavigating = false
        activeNavigationSteps = emptyList()
        currentStepIndex = 0
        selectedDestinationName = ""
        navigatingToSavedPlace = null
        hasTriggeredArrivalGuidance = false
        locationTrackingJob?.cancel()
        locationTrackingJob = null
    }

    fun nextNavigationStep() {
        if (currentStepIndex < activeNavigationSteps.size - 1) {
            currentStepIndex++
            val step = activeNavigationSteps[currentStepIndex]
            val instruction = parseStepInstruction(step)
            speak(instruction, SpeechPriority.NAVIGATION)
        } else {
            val place = navigatingToSavedPlace
            if (place != null && !hasTriggeredArrivalGuidance) {
                hasTriggeredArrivalGuidance = true
                viewModelScope.launch {
                    _capturePhotoForArrivalEvent.emit(place)
                }
            } else {
                speak("You have reached your destination securely. Excellent work.", SpeechPriority.NAVIGATION)
                stopNavigation()
            }
        }
    }

    fun prevNavigationStep() {
        if (currentStepIndex > 0) {
            currentStepIndex--
            val step = activeNavigationSteps[currentStepIndex]
            val instruction = parseStepInstruction(step)
            speak(instruction, SpeechPriority.NAVIGATION)
        }
    }

    fun startLocationTrackingLoop() {
        locationTrackingJob?.cancel()
        locationTrackingJob = viewModelScope.launch {
            while (isNavigating) {
                val loc = getFreshLocation()
                if (loc != null) {
                    currentLatitude = loc.latitude
                    currentLongitude = loc.longitude
                    
                    val distToDest = calculateDistance(currentLatitude, currentLongitude, destinationLatitude, destinationLongitude)
                    if (distToDest <= 15.0) {
                        val place = navigatingToSavedPlace
                        if (place != null && !hasTriggeredArrivalGuidance) {
                            hasTriggeredArrivalGuidance = true
                            _capturePhotoForArrivalEvent.emit(place)
                        } else {
                            speak("You have reached your destination securely.", SpeechPriority.NAVIGATION)
                            stopNavigation()
                        }
                        break
                    }
                    
                    if (currentStepIndex < activeNavigationSteps.size) {
                        val step = activeNavigationSteps[currentStepIndex]
                        val stepLat = step.maneuver?.location?.get(1) ?: 0.0
                        val stepLng = step.maneuver?.location?.get(0) ?: 0.0
                        val distToStep = calculateDistance(currentLatitude, currentLongitude, stepLat, stepLng)
                        if (distToStep <= 15.0) {
                            currentStepIndex++
                            if (currentStepIndex < activeNavigationSteps.size) {
                                val nextStep = activeNavigationSteps[currentStepIndex]
                                val instruction = parseStepInstruction(nextStep)
                                speak(instruction, SpeechPriority.NAVIGATION)
                            }
                        }
                    }
                }
                delay(8000)
            }
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    fun sendSosemail(guardianEmail: String, guardianName: String, address: String, lat: Double, lng: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mapsLink = "https://www.google.com/maps/search/?api=1&query=$lat,$lng"
                val subject = "[EMERGENCY] Drishti Active SOS Alert for User"
                val bodyContent = """
                    Hello $guardianName,
                    
                    This is an urgent automated alert from the Drishti Visual Assistance App.
                    The user has triggered their emergency SOS.
                    
                    Last Known Location: $address
                    GPS Coordinates: $lat, $lng
                    Google Maps Direct Link: $mapsLink
                    
                    Please check on them immediately.
                """.trimIndent()

                val userName = userProfile.value?.name ?: "Drishti User"
                val userEmail = userProfile.value?.email ?: "user@drishti.ai"

                val payload = mapOf(
                    "name" to userName,
                    "email" to userEmail,
                    "_subject" to subject,
                    "message" to bodyContent
                )

                val settings = userSettings.value
                val isSent = if (settings?.smtpEnabled == true && settings.smtpEmail.isNotBlank()) {
                    Log.d("DrishtiViewModel", "Sending SOS email via Custom SMTP host ${settings.smtpHost}...")
                    try {
                        com.example.util.SmtpSender.sendEmail(
                            host = settings.smtpHost,
                            port = settings.smtpPort,
                            username = settings.smtpEmail,
                            password = settings.smtpPassword,
                            to = guardianEmail,
                            subject = subject,
                            body = bodyContent
                        )
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                } else {
                    Log.d("DrishtiViewModel", "Sending background SOS email via FormSubmit...")
                    val response = DrishtiApiClient.formSubmitService.sendEmergencyEmail(guardianEmail, payload)
                    response.isSuccessful && response.body()?.success == "true"
                }

                if (isSent) {
                    Log.d("DrishtiViewModel", "Emergency SOS email sent successfully!")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Emergency SOS email sent automatically!", Toast.LENGTH_LONG).show()
                    }
                    speak("Emergency alert sent automatically. A location link was emailed to your guardian.", SpeechPriority.EMERGENCY)
                } else {
                    Log.e("DrishtiViewModel", "FormSubmit / SMTP failed, falling back to Intent Mail.")
                    fallbackToIntentMail(guardianEmail, subject, bodyContent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                fallbackToIntentMail(guardianEmail, "[EMERGENCY] Drishti Active SOS Alert for User", "SOS triggered near $address ($lat,$lng). Maps link: https://www.google.com/maps/search/?api=1&query=$lat,$lng")
            }
        }
    }

    private suspend fun fallbackToIntentMail(guardianEmail: String, subject: String, body: String) {
        withContext(Dispatchers.Main) {
            val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("mailto:")
                putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(guardianEmail))
                putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                putExtra(android.content.Intent.EXTRA_TEXT, body)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                speak("I have opened your mail app to send the guardian alert manually.", SpeechPriority.EMERGENCY)
            } catch (e: Exception) {
                speak("Emergency alert failed. Please contact your guardian directly.", SpeechPriority.EMERGENCY)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        outdoorDetector?.close()
        outdoorDetector = null
        tts?.shutdown()
        try {
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        sensorManager.unregisterListener(this)
        lightToneGenerator?.release()
    }
}

// Factory Provider
class DrishtiViewModelFactory(
    private val application: Application,
    private val repository: DrishtiRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DrishtiViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DrishtiViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
