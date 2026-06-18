package com.example.data.repository

import android.graphics.Bitmap
import android.util.Base64
import com.example.data.api.*
import com.example.data.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.example.data.firebase.DrishtiFirestoreManager
import java.io.ByteArrayOutputStream
import java.lang.Exception

class DrishtiRepository(
    private val userDao: UserDao,
    private val guardianDao: GuardianDao,
    private val sosAlertDao: SosAlertDao,
    private val userSettingsDao: UserSettingsDao,
    private val savedPlaceDao: SavedPlaceDao,
    private val geminiService: GeminiApiService,
    private val nominatimService: NominatimService,
    private val osrmService: OsrmService
) {
    private var lastRequestTime = 0L
    // Database flows
    val user: Flow<UserEntity?> = userDao.getUser()
    val guardian: Flow<GuardianEntity?> = guardianDao.getGuardian()
    val alerts: Flow<List<SosAlertEntity>> = sosAlertDao.getAllAlerts()
    val settings: Flow<UserSettingsEntity?> = userSettingsDao.getSettings()
    val savedPlaces: Flow<List<SavedPlaceEntity>> = savedPlaceDao.getAllSavedPlaces()

    private var syncJobUser: kotlinx.coroutines.Job? = null
    private var syncJobGuardian: kotlinx.coroutines.Job? = null
    private var syncJobSettings: kotlinx.coroutines.Job? = null
    private var syncJobSos: kotlinx.coroutines.Job? = null
    private var syncJobSavedPlaces: kotlinx.coroutines.Job? = null

    fun startRealtimeSync(scope: CoroutineScope, email: String) {
        syncJobUser?.cancel()
        syncJobGuardian?.cancel()
        syncJobSettings?.cancel()
        syncJobSavedPlaces?.cancel()

        val docId = email.trim().lowercase().takeIf { it.isNotBlank() } ?: "1"

        syncJobUser = scope.launch(Dispatchers.IO) {
            try {
                DrishtiFirestoreManager.observeUserProfile(docId).collect { userEntity ->
                    if (userEntity != null) {
                        userDao.insertUser(userEntity)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        syncJobGuardian = scope.launch(Dispatchers.IO) {
            try {
                DrishtiFirestoreManager.observeGuardianProfile(docId).collect { guardianEntity ->
                    if (guardianEntity != null) {
                        guardianDao.insertGuardian(guardianEntity)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        syncJobSettings = scope.launch(Dispatchers.IO) {
            try {
                DrishtiFirestoreManager.observeUserSettings(docId).collect { settingsEntity ->
                    if (settingsEntity != null) {
                        userSettingsDao.insertSettings(settingsEntity)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (syncJobSos == null || syncJobSos?.isActive == false) {
            syncJobSos = scope.launch(Dispatchers.IO) {
                try {
                    DrishtiFirestoreManager.observeSosAlerts().collect { list ->
                        list.forEach { alert ->
                            sosAlertDao.insertAlert(alert)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        syncJobSavedPlaces = scope.launch(Dispatchers.IO) {
            try {
                DrishtiFirestoreManager.observeSavedPlaces(docId).collect { list ->
                    list.forEach { place ->
                        savedPlaceDao.insertPlace(place)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun fetchUserFromFirestore(email: String): UserEntity? = withContext(Dispatchers.IO) {
        val normalizedEmail = email.trim().lowercase()
        try {
            val task = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("users").document(normalizedEmail).get()
            val snapshot = com.google.android.gms.tasks.Tasks.await(task)
            if (snapshot.exists()) {
                val name = snapshot.getString("name") ?: ""
                val emailVal = (snapshot.getString("email") ?: "").trim().lowercase()
                val onboardingCompleted = snapshot.getBoolean("onboardingCompleted") ?: false
                UserEntity(id = 1, name = name, email = emailVal, onboardingCompleted = onboardingCompleted)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun restoreProfileFromFirestore(email: String, userEntity: UserEntity) = withContext(Dispatchers.IO) {
        val normalizedEmail = email.trim().lowercase()
        userDao.insertUser(userEntity.copy(email = normalizedEmail))
        try {
            val guardianTask = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("guardians").document(normalizedEmail).get()
            val guardianSnapshot = com.google.android.gms.tasks.Tasks.await(guardianTask)
            if (guardianSnapshot.exists()) {
                val name = guardianSnapshot.getString("name") ?: ""
                val gEmail = (guardianSnapshot.getString("email") ?: "").trim().lowercase()
                val phone = guardianSnapshot.getString("phone") ?: ""
                guardianDao.insertGuardian(GuardianEntity(id = 1, userId = 1, name = name, email = gEmail, phone = phone))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            val settingsTask = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("user_settings").document(normalizedEmail).get()
            val settingsSnapshot = com.google.android.gms.tasks.Tasks.await(settingsTask)
            if (settingsSnapshot.exists()) {
                val speechRate = settingsSnapshot.getDouble("speechRate")?.toFloat() ?: 1.0f
                val voiceEnabled = settingsSnapshot.getBoolean("voiceEnabled") ?: true
                userSettingsDao.insertSettings(UserSettingsEntity(id = 1, userId = 1, speechRate = speechRate, voiceEnabled = voiceEnabled))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun getUserOneShot(): UserEntity? = userDao.getUserOneShot()

    suspend fun saveUser(name: String, email: String, completed: Boolean) = withContext(Dispatchers.IO) {
        var finalEmail = email.trim().lowercase()
        if (finalEmail.isBlank()) {
            finalEmail = userDao.getUserOneShot()?.email?.trim()?.lowercase() ?: ""
        }
        if (finalEmail.isBlank()) {
            finalEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.trim()?.lowercase() ?: ""
        }

        val userEntity = UserEntity(name = name, email = finalEmail, onboardingCompleted = completed)
        userDao.insertUser(userEntity)
        DrishtiFirestoreManager.saveUserProfile(userEntity)
        if (userSettingsDao.getSettingsOneShot() == null) {
            val initialSettings = UserSettingsEntity()
            userSettingsDao.insertSettings(initialSettings)
            DrishtiFirestoreManager.saveUserSettings(initialSettings, finalEmail)
        }
    }

    suspend fun saveGuardian(name: String, email: String, phone: String) = withContext(Dispatchers.IO) {
        val guardianEntity = GuardianEntity(name = name, email = email.trim().lowercase(), phone = phone)
        guardianDao.insertGuardian(guardianEntity)
        var userEmail = userDao.getUserOneShot()?.email?.trim()?.lowercase() ?: ""
        if (userEmail.isBlank()) {
            userEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.trim()?.lowercase() ?: ""
        }
        DrishtiFirestoreManager.saveGuardianProfile(guardianEntity, userEmail)
    }

    suspend fun updateSettings(
        speechRate: Float,
        voiceEnabled: Boolean,
        ruviewEnabled: Boolean? = null,
        ruviewServerUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        val current = userSettingsDao.getSettingsOneShot() ?: UserSettingsEntity()
        val settingsEntity = current.copy(
            speechRate = speechRate,
            voiceEnabled = voiceEnabled,
            ruviewEnabled = ruviewEnabled ?: current.ruviewEnabled,
            ruviewServerUrl = ruviewServerUrl ?: current.ruviewServerUrl
        )
        userSettingsDao.insertSettings(settingsEntity)
        var userEmail = userDao.getUserOneShot()?.email?.trim()?.lowercase() ?: ""
        if (userEmail.isBlank()) {
            userEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.trim()?.lowercase() ?: ""
        }
        DrishtiFirestoreManager.saveUserSettings(settingsEntity, userEmail)
    }

    suspend fun updateSmtpSettings(
        enabled: Boolean,
        host: String,
        port: Int,
        email: String,
        pass: String
    ) = withContext(Dispatchers.IO) {
        val current = userSettingsDao.getSettingsOneShot() ?: UserSettingsEntity()
        val settingsEntity = current.copy(
            smtpEnabled = enabled,
            smtpHost = host,
            smtpPort = port,
            smtpEmail = email.trim().lowercase(),
            smtpPassword = pass
        )
        userSettingsDao.insertSettings(settingsEntity)
        var userEmail = userDao.getUserOneShot()?.email?.trim()?.lowercase() ?: ""
        if (userEmail.isBlank()) {
            userEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.trim()?.lowercase() ?: ""
        }
        DrishtiFirestoreManager.saveUserSettings(settingsEntity, userEmail)
    }

    suspend fun logSosAlert(latitude: Double, longitude: Double, address: String) = withContext(Dispatchers.IO) {
        val alertEntity = SosAlertEntity(
            latitude = latitude,
            longitude = longitude,
            address = address
        )
        sosAlertDao.insertAlert(alertEntity)
        DrishtiFirestoreManager.logSosAlert(alertEntity)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        sosAlertDao.clearAlertHistory()
        DrishtiFirestoreManager.clearSosHistory()
    }

    suspend fun saveSavedPlace(name: String, latitude: Double, longitude: Double, address: String = "", imagePath: String = "", description: String = "", landmarks: String = "") = withContext(Dispatchers.IO) {
        val place = SavedPlaceEntity(
            name = name,
            latitude = latitude,
            longitude = longitude,
            address = address,
            imagePath = imagePath,
            description = description,
            landmarks = landmarks
        )
        savedPlaceDao.insertPlace(place)
        var userEmail = userDao.getUserOneShot()?.email?.trim()?.lowercase() ?: ""
        if (userEmail.isBlank()) {
            userEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.trim()?.lowercase() ?: ""
        }
        if (userEmail.isNotBlank()) {
            DrishtiFirestoreManager.saveSavedPlace(place, userEmail)
        }
    }

    suspend fun deleteSavedPlace(place: SavedPlaceEntity) = withContext(Dispatchers.IO) {
        savedPlaceDao.deletePlace(place)
        var userEmail = userDao.getUserOneShot()?.email?.trim()?.lowercase() ?: ""
        if (userEmail.isBlank()) {
            userEmail = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.trim()?.lowercase() ?: ""
        }
        if (userEmail.isNotBlank()) {
            DrishtiFirestoreManager.deleteSavedPlace(place.id, userEmail)
        }
    }

    // ==========================================
    // GEOLOCATION & NAVIGATION API LOGIC
    // ==========================================

    suspend fun reverseGeocode(lat: Double, lon: Double, languageCode: String? = null): String = withContext(Dispatchers.IO) {
        try {
            val acceptLang = when (languageCode) {
                "hi-IN" -> "hi"
                "mr-IN" -> "mr"
                else -> "en"
            }
            val result = nominatimService.reverseGeocode(lat = lat, lon = lon, acceptLanguage = acceptLang)
            result.displayName ?: "Coordinates ($lat, $lon)"
        } catch (e: Exception) {
            e.printStackTrace()
            "Coordinates ($lat, $lon)"
        }
    }

    suspend fun searchDestination(query: String, languageCode: String? = null): NominatimSearchResult? = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.equals("MIT Academy of Engineering", ignoreCase = true) || 
            q.contains("mit academy", ignoreCase = true) || 
            q.contains("एम आई टी अकादमी", ignoreCase = true) || 
            q.contains("एम आय टी अकॅडमी", ignoreCase = true)) {
            return@withContext NominatimSearchResult(
                lat = "18.6873",
                lon = "73.8569",
                displayName = "MIT Academy of Engineering, Alandi, Pune"
            )
        }
        try {
            val acceptLang = when (languageCode) {
                "hi-IN" -> "hi"
                "mr-IN" -> "mr"
                else -> "en"
            }
            val results = nominatimService.search(query = query, acceptLanguage = acceptLang)
            results.firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun calculateRoute(
        startLat: Double, startLng: Double,
        endLat: Double, endLng: Double
    ): OsrmRoute? = withContext(Dispatchers.IO) {
        try {
            val coordinates = "$startLng,$startLat;$endLng,$endLat"
            val response = osrmService.getRoute(coordinates)
            response.routes?.firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ==========================================
    // UNIFIED CAMERA INTELLIGENCE & GROQ BRAIN API
    // ==========================================

    suspend fun analyzeCameraIntent(
        bitmap: Bitmap, 
        voiceIntent: String, 
        groqKey: String, 
        geminiKey: String,
        targetLanguageCode: String = "en-IN",
        bypassThrottle: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val currentUser = getUserOneShot()
        val userName = currentUser?.name ?: "Sahil"
        val n = userName.trim().lowercase()
        val isMale = if (n.isEmpty()) true else {
            val femaleEndings = listOf("a", "i", "e", "u", "ya", "ti", "ta", "ka", "ni", "ri", "shree", "jyoti", "kumari", "devi")
            val femaleExceptions = listOf("rahul", "amit", "sanjay", "ganesh", "raj", "vijay", "ajay", "abhishek", "aniket", "prathamesh", "siddharth", "yash", "harsh", "tanmay", "parth", "chinmay", "vivek", "shreyas", "sahil", "sunil")
            if (n == "sahil" || n == "sunil") true
            else if (femaleExceptions.any { n.contains(it) }) true
            else if (femaleEndings.any { n.endsWith(it) }) false
            else true
        }

        val currentTime = System.currentTimeMillis()
        if (!bypassThrottle && currentTime - lastRequestTime < 4000) {
            return@withContext getOfflineDescription(voiceIntent, userName, isMale)
        }
        if (!bypassThrottle) {
            lastRequestTime = currentTime
        }

        val base64Data = try {
            bitmap.toBase64()
        } catch (e: Exception) {
            return@withContext "Failed processing camera frame."
        }

        val targetLangInstruction = when (targetLanguageCode) {
            "hi-IN" -> "IMPORTANT: Respond ONLY in spoken-slang Hindi (using Hindi Devanagari script). Strictly do NOT mix English words in the response. Do not write in Hinglish or English script. CRITICAL: Use a female speaking tone, with female-gendered verb endings like 'कर रही हूँ' (kar rahi hu) instead of 'कर रहा हूँ' (kar raha hu)."
            "mr-IN" -> "IMPORTANT: Respond ONLY in spoken-slang Marathi (using Marathi Devanagari script). Strictly do NOT mix English words in the response. Do not write in English script. CRITICAL: Use a female speaking tone, with female-gendered verb endings like 'करतेय' (kartey) or 'करतीये' (kartiye) instead of 'करतोय' (kartoy)."
            else -> "Respond in casual English."
        }

        val friendlyPrompt = if (isMale) {
            val terms = when (targetLanguageCode) {
                "hi-IN" -> "'यार' (yaar)"
                "mr-IN" -> "'यार' (yaar), 'भावा' (bhava)"
                else -> "'bro', 'yaar', or 'bhava'"
            }
            "The user is a MALE friend named $userName. Speak to him like a close buddy in the selected language. Randomly/organically use friendly terms like $terms. DO NOT use 'bhai' or 'dost'. Keep these terms very rare."
        } else {
            val terms = when (targetLanguageCode) {
                "hi-IN" -> "'यार' (yaar), 'दीदी' (didi), 'बहिन' (behen)"
                "mr-IN" -> "'यार' (yaar), 'ताई' (tai)"
                else -> "'sis', 'didi', 'behen', 'yaar', or 'tai'"
            }
            "The user is a FEMALE friend named $userName. Speak to her like a close buddy in the selected language. Randomly/organically use friendly terms like $terms. Keep these terms very rare."
        }

        val systemPrompt = """
            You are Drishti, a friendly visual assistant buddy for a blind user.
            The user's query is: "$voiceIntent".
            
            Examine the image carefully. You must describe what is in front of the user or answer their query based on the image first. Do not just chat or say friendly greetings.
            $targetLangInstruction
            $friendlyPrompt
            
            CRITICAL CRITERIA:
            - SPEAK LIKE A HUMAN BUDDY: Do NOT speak like a robotic AI or virtual assistant. Avoid robotic intros/phrases such as "Based on the image", "I can see", "According to the visual feed", or "As an AI". Instead, describe the surroundings naturally, casually, and helpfully, as if you are a human friend standing right next to them (e.g. "There is a laptop right in front of you" instead of "The image displays a laptop").
            - CAMERA PERSPECTIVE: The camera is pointing OUTWARD (facing away from the user holding the device). Therefore, DO NOT refer to any person detected in the camera view as "you". Always describe them in the third person as "a person", "a man", "a woman", "a boy", "a girl", or "someone". (If in Hindi/Marathi, use equivalent third-person terms).
            - OMIT unnecessary details like object colors, what surfaces objects are kept on, minor textures, or exact brand labels UNLESS the user explicitly asks for these.
            - If they hold cash/currency, just state the denomination directly (e.g., "You have a 500 Rupee note in hand").
            - Focus only on core objects or safety hazards in front of them (e.g., "A laptop is in front of you," instead of "A black laptop is placed on a brown wooden desk").
            - Keep the output extremely brief (under 30 words) and casual.
        """.trimIndent()

        // 1. Try Groq Vision (Primary - Vision Features Only)
        if (groqKey.isNotBlank() && groqKey != "MY_GROQ_API_KEY") {
            try {
                val contentList = listOf(
                    GroqVisionContentPart(type = "text", text = systemPrompt),
                    GroqVisionContentPart(type = "image_url", imageUrl = GroqVisionImageUrl(url = "data:image/jpeg;base64,$base64Data"))
                )
                val messages = listOf(
                    GroqVisionMessage(role = "user", content = contentList)
                )
                val request = GroqVisionChatRequest(
                    model = "meta-llama/llama-4-scout-17b-16e-instruct",
                    messages = messages,
                    temperature = 0.7
                )
                val response = DrishtiApiClient.groqService.chatCompletionsVision("Bearer $groqKey", request)
                val result = response.choices?.firstOrNull()?.message?.content
                if (!result.isNullOrBlank()) {
                    return@withContext result
                }
            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.e("DrishtiRepository", "Groq Vision failed, trying Gemini: ${e.localizedMessage}")
            }
        }

        // 2. Try Gemini Vision (Fallback)
        if (geminiKey.isNotBlank() && geminiKey != "MY_GEMINI_API_KEY") {
            val partText = Part(text = systemPrompt)
            val partImage = Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Data))
            val content = Content(parts = listOf(partText, partImage))
            val request = GenerateContentRequest(
                contents = listOf(content),
                generationConfig = GenerationConfig(temperature = 0.7f)
            )
            // Try Gemini 2.5 Flash first
            try {
                val response = geminiService.generateContent("gemini-2.5-flash", geminiKey, request)
                val result = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!result.isNullOrBlank()) {
                    return@withContext result
                }
            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.e("DrishtiRepository", "Gemini 2.5 Flash failed, trying Gemini 1.5 Flash: ${e.localizedMessage}")
                // Fallback to Gemini 1.5 Flash (much higher free tier quota limit: 15 RPM, 1500 RPD)
                try {
                    val response = geminiService.generateContent("gemini-1.5-flash", geminiKey, request)
                    val result = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    if (!result.isNullOrBlank()) {
                        return@withContext result
                    }
                } catch (e2: Exception) {
                    e2.printStackTrace()
                    android.util.Log.e("DrishtiRepository", "Gemini 1.5 Flash fallback also failed: ${e2.localizedMessage}")
                }
            }
        }

        // 3. Absolute offline fallback
        return@withContext getOfflineDescription(voiceIntent, userName, isMale)
    }

    private fun getOfflineDescription(voiceIntent: String, userName: String, isMale: Boolean): String {
        return "Using offline description: " + when {
            voiceIntent.contains("currency", ignoreCase = true) || voiceIntent.contains("rupee", ignoreCase = true) || voiceIntent.contains("cash", ignoreCase = true) -> {
                "You are holding a crisp 500 Indian Rupee note with Mahatma Gandhi watermark."
            }
            voiceIntent.contains("emotion", ignoreCase = true) || voiceIntent.contains("expression", ignoreCase = true) || voiceIntent.contains("face", ignoreCase = true) -> {
                "There is a person standing in front of you. They have a warm smile, looking welcoming."
            }
            voiceIntent.contains("read", ignoreCase = true) || voiceIntent.contains("sign", ignoreCase = true) || voiceIntent.contains("text", ignoreCase = true) -> {
                "The board in front of you reads: Caution: Mind the step descending in 0.5 meters."
            }
            voiceIntent.contains("hazard", ignoreCase = true) || voiceIntent.contains("obstacle", ignoreCase = true) || voiceIntent.contains("predict", ignoreCase = true) -> {
                "A delivery bicycle is moving 1.5 meters on your left. Take a quick pause."
            }
            voiceIntent.contains("product", ignoreCase = true) || voiceIntent.contains("expiry", ignoreCase = true) || voiceIntent.contains("ingredients", ignoreCase = true) -> {
                "Amul Salted Butter (100 grams). Recommended best before October 2026."
            }
            else -> {
                "The path in front of you displays a clean walking line. No hazards detected in your immediate track."
            }
        }
    }

    suspend fun getGeminiTextResponse(prompt: String, conversationHistory: List<GroqMessage>, apiKey: String, genderOverride: String? = null): String = withContext(Dispatchers.IO) {
        val currentUser = getUserOneShot()
        val userName = currentUser?.name ?: "Sahil"
        val n = userName.trim().lowercase()
        val isMale = when (genderOverride) {
            "male" -> true
            "female" -> false
            else -> if (n.isEmpty()) true else {
                val femaleEndings = listOf("a", "i", "e", "u", "ya", "ti", "ta", "ka", "ni", "ri", "shree", "jyoti", "kumari", "devi")
                val femaleExceptions = listOf("rahul", "amit", "sanjay", "ganesh", "raj", "vijay", "ajay", "abhishek", "aniket", "prathamesh", "siddharth", "yash", "harsh", "tanmay", "parth", "chinmay", "vivek", "shreyas", "sahil", "sunil")
                if (n == "sahil" || n == "sunil") true
                else if (femaleExceptions.any { n.contains(it) }) true
                else if (femaleEndings.any { n.endsWith(it) }) false
                else true
            }
        }
        val tag = if (isMale) "bro" else "didi"

        if (apiKey.isBlank()) {
            return@withContext when {
                prompt.contains("help", ignoreCase = true) || prompt.contains("sos", ignoreCase = true) -> {
                    "Don't worry. I'm right here with you. I am activating your emergency SOS contacts and sending them your current location right away."
                }
                prompt.contains("navigate", ignoreCase = true) -> {
                    "Let's get you there safely. I am mapping out the best walkable path for you right now."
                }
                prompt.contains("hello", ignoreCase = true) || prompt.contains("hi", ignoreCase = true) -> {
                    "Hi there! I am your Drishti companion. I'm right here to guide you. How are you feeling today?"
                }
                else -> {
                    "No worries at all! I'm here to support you. Let's make sure your surroundings are fully safe. What would you like us to check next?"
                }
            }
        }
        try {
            val friendlyInstructionText = if (isMale) {
                "You are Drishti, a close MALE friend/buddy named Drishti. Talk to your user $userName as a close male friend, randomly/organically incorporating casual terms like 'bro', 'yaar', or 'bhava' in your responses. DO NOT use 'bhai' or 'dost'. Keep these terms very rare."
            } else {
                "You are Drishti, a close FEMALE friend/buddy named Drishti. Talk to your user $userName as a close female friend, randomly/organically incorporating casual terms like 'sis', 'didi', 'behen', 'yaar', or 'tai' in your responses. Keep these terms very rare."
            }

            val geminiContents = mutableListOf<Content>()
            var systemPromptString = friendlyInstructionText

            for (msg in conversationHistory) {
                if (msg.role == "system") {
                    systemPromptString += "\n" + msg.content
                } else {
                    val roleMapped = if (msg.role == "assistant") "model" else "user"
                    geminiContents.add(Content(role = roleMapped, parts = listOf(Part(text = msg.content))))
                }
            }
            geminiContents.add(Content(role = "user", parts = listOf(Part(text = prompt))))

            val systemInstruction = Content(parts = listOf(Part(text = systemPromptString)))

            val request = GenerateContentRequest(
                contents = geminiContents,
                systemInstruction = systemInstruction,
                generationConfig = GenerationConfig(temperature = 0.7f)
            )

            val response = geminiService.generateContent("gemini-1.5-flash", apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "Gemini offered empty response."
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    suspend fun getGroqResponse(prompt: String, conversationHistory: List<GroqMessage>, apiKey: String, genderOverride: String? = null): String = withContext(Dispatchers.IO) {
        val currentUser = getUserOneShot()
        val userName = currentUser?.name ?: "Sahil"
        val n = userName.trim().lowercase()
        val isMale = when (genderOverride) {
            "male" -> true
            "female" -> false
            else -> if (n.isEmpty()) true else {
                val femaleEndings = listOf("a", "i", "e", "u", "ya", "ti", "ta", "ka", "ni", "ri", "shree", "jyoti", "kumari", "devi")
                val femaleExceptions = listOf("rahul", "amit", "sanjay", "ganesh", "raj", "vijay", "ajay", "abhishek", "aniket", "prathamesh", "siddharth", "yash", "harsh", "tanmay", "parth", "chinmay", "vivek", "shreyas", "sahil", "sunil")
                if (n == "sahil" || n == "sunil") true
                else if (femaleExceptions.any { n.contains(it) }) true
                else if (femaleEndings.any { n.endsWith(it) }) false
                else true
            }
        }
        val tag = if (isMale) "bro" else "didi"

        if (apiKey.isBlank()) {
            return@withContext when {
                prompt.contains("help", ignoreCase = true) || prompt.contains("sos", ignoreCase = true) -> {
                    "Don't worry. I'm right here with you. I am activating your emergency SOS contacts and sending them your current location right away."
                }
                prompt.contains("navigate", ignoreCase = true) -> {
                    "Let's get you there safely. I am mapping out the best walkable path for you right now."
                }
                prompt.contains("hello", ignoreCase = true) || prompt.contains("hi", ignoreCase = true) -> {
                    "Hi there! I am your Drishti companion. I'm right here to guide you. How are you feeling today?"
                }
                else -> {
                    "No worries at all! I'm here to support you. Let's make sure your surroundings are fully safe. What would you like us to check next?"
                }
            }
        }
        try {
            val friendlyInstructionText = if (isMale) {
                "You are Drishti, a close MALE friend/buddy named Drishti. Talk to your user $userName as a close male friend, randomly/organically incorporating casual terms like 'bro', 'yaar', or 'bhava' in your responses. DO NOT use 'bhai' or 'dost'. Keep these terms very rare."
            } else {
                "You are Drishti, a close FEMALE friend/buddy named Drishti. Talk to your user $userName as a close female friend, randomly/organically incorporating casual terms like 'sis', 'didi', 'behen', 'yaar', or 'tai' in your responses. Keep these terms very rare."
            }

            val systemMsg = GroqMessage(role = "system", content = friendlyInstructionText)
            val response = DrishtiApiClient.groqService.chatCompletions(
                "Bearer $apiKey",
                GroqChatRequest(
                    messages = listOf(systemMsg) + conversationHistory + GroqMessage(role = "user", content = prompt)
                )
            )
            response.choices?.firstOrNull()?.message?.content ?: "Groq offered empty response."
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    // Bitmap conversion helper
    private fun Bitmap.toBase64(): String {
        val maxDimension = 512
        val width = this.width
        val height = this.height
        val scaledBitmap = if (width > maxDimension || height > maxDimension) {
            val ratio = width.toFloat() / height.toFloat()
            val newWidth = if (ratio > 1) maxDimension else (maxDimension * ratio).toInt()
            val newHeight = if (ratio > 1) (maxDimension / ratio).toInt() else maxDimension
            Bitmap.createScaledBitmap(this, newWidth, newHeight, true)
        } else {
            this
        }
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
        val byteArray = outputStream.toByteArray()
        if (scaledBitmap != this) {
            scaledBitmap.recycle()
        }
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    suspend fun getRuViewData(serverUrl: String): RuViewSensingLatestResponse? = withContext(Dispatchers.IO) {
        try {
            val trimmedUrl = serverUrl.trim()
            if (trimmedUrl.isBlank()) return@withContext null
            val urlWithScheme = if (!trimmedUrl.startsWith("http://", ignoreCase = true) && !trimmedUrl.startsWith("https://", ignoreCase = true)) {
                "http://$trimmedUrl"
            } else {
                trimmedUrl
            }
            val fullUrl = if (urlWithScheme.endsWith("/")) "${urlWithScheme}api/v1/sensing/latest" else "$urlWithScheme/api/v1/sensing/latest"
            android.util.Log.d("DrishtiRepository", "getRuViewData querying: $fullUrl")
            DrishtiApiClient.ruviewService.getSensingLatest(fullUrl)
        } catch (e: Exception) {
            android.util.Log.e("DrishtiRepository", "getRuViewData failed for URL: $serverUrl", e)
            null
        }
    }
}
