package com.example.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ==========================================
// GEMINI API MODELS
// ==========================================

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    @Json(name = "contents") val contents: List<Content>,
    @Json(name = "generationConfig") val generationConfig: GenerationConfig? = null,
    @Json(name = "systemInstruction") val systemInstruction: Content? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    @Json(name = "parts") val parts: List<Part>,
    @Json(name = "role") val role: String? = null
)

@JsonClass(generateAdapter = true)
data class Part(
    @Json(name = "text") val text: String? = null,
    @Json(name = "inlineData") val inlineData: InlineData? = null
)

@JsonClass(generateAdapter = true)
data class InlineData(
    @Json(name = "mimeType") val mimeType: String,
    @Json(name = "data") val data: String
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    @Json(name = "temperature") val temperature: Float? = null,
    @Json(name = "topP") val topP: Float? = null,
    @Json(name = "topK") val topK: Int? = null,
    @Json(name = "maxOutputTokens") val maxOutputTokens: Int? = null
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    @Json(name = "candidates") val candidates: List<Candidate>?
)

@JsonClass(generateAdapter = true)
data class Candidate(
    @Json(name = "content") val content: Content?
)

// ==========================================
// OPENSTREETMAP NOMINATIM GEOLOCATION MODELS
// ==========================================

@JsonClass(generateAdapter = true)
data class NominatimSearchResult(
    @Json(name = "lat") val lat: String,
    @Json(name = "lon") val lon: String,
    @Json(name = "display_name") val displayName: String
)

@JsonClass(generateAdapter = true)
data class NominatimReverseResult(
    @Json(name = "display_name") val displayName: String?
)

// ==========================================
// OSRM ROUTING / NAVIGATION MODELS
// ==========================================

@JsonClass(generateAdapter = true)
data class OsrmResponse(
    @Json(name = "routes") val routes: List<OsrmRoute>?
)

@JsonClass(generateAdapter = true)
data class OsrmRoute(
    @Json(name = "legs") val legs: List<OsrmLeg>?,
    @Json(name = "distance") val distance: Double?,
    @Json(name = "duration") val duration: Double?
)

@JsonClass(generateAdapter = true)
data class OsrmLeg(
    @Json(name = "steps") val steps: List<OsrmStep>?
)

@JsonClass(generateAdapter = true)
data class OsrmStep(
    @Json(name = "distance") val distance: Double?,
    @Json(name = "name") val name: String?,
    @Json(name = "maneuver") val maneuver: OsrmManeuver?
)

@JsonClass(generateAdapter = true)
data class OsrmManeuver(
    @Json(name = "instruction") val instruction: String?,
    @Json(name = "type") val type: String?,
    @Json(name = "modifier") val modifier: String?,
    @Json(name = "location") val location: List<Double>?
)

// ==========================================
// GROQ API MODELS (OpenAI-compatible)
// ==========================================

@JsonClass(generateAdapter = true)
data class GroqChatRequest(
    @Json(name = "model") val model: String = "llama-3.3-70b-versatile",
    @Json(name = "messages") val messages: List<GroqMessage>,
    @Json(name = "temperature") val temperature: Double = 0.7
)

@JsonClass(generateAdapter = true)
data class GroqMessage(
    @Json(name = "role") val role: String,
    @Json(name = "content") val content: String
)

@JsonClass(generateAdapter = true)
data class GroqChatResponse(
    @Json(name = "choices") val choices: List<GroqChoice>?
)

@JsonClass(generateAdapter = true)
data class GroqChoice(
    @Json(name = "message") val message: GroqMessage?
)

// ==========================================
// RUVIEW SENSING MODELS
// ==========================================

@JsonClass(generateAdapter = true)
data class RuViewSensingLatestResponse(
    @Json(name = "estimated_persons") val estimatedPersons: Int? = null,
    @Json(name = "classification") val classification: RuViewClassificationInfo? = null,
    @Json(name = "vital_signs") val vitalSigns: RuViewVitalSigns? = null,
    @Json(name = "features") val features: RuViewFeatureInfo? = null
)

@JsonClass(generateAdapter = true)
data class RuViewClassificationInfo(
    @Json(name = "motion_level") val motionLevel: String? = null,
    @Json(name = "presence") val presence: Boolean? = null,
    @Json(name = "confidence") val confidence: Double? = null
)

@JsonClass(generateAdapter = true)
data class RuViewVitalSigns(
    @Json(name = "breathing_rate_bpm") val breathingRateBpm: Double? = null,
    @Json(name = "heart_rate_bpm") val heartRateBpm: Double? = null,
    @Json(name = "breathing_confidence") val breathingConfidence: Double? = null,
    @Json(name = "heart_confidence") val heartConfidence: Double? = null
)

@JsonClass(generateAdapter = true)
data class RuViewFeatureInfo(
    @Json(name = "mean_rssi") val meanRssi: Double? = null,
    @Json(name = "variance") val variance: Double? = null,
    @Json(name = "motion_band_power") val motionBandPower: Double? = null
)

// ==========================================
// SARVAM AI TTS MODELS
// ==========================================

@JsonClass(generateAdapter = true)
data class SarvamTtsRequest(
    @Json(name = "text") val text: String,
    @Json(name = "speaker") val speaker: String = "ritu",
    @Json(name = "model") val model: String = "bulbul:v3",
    @Json(name = "target_language_code") val targetLanguageCode: String = "en-IN"
)

@JsonClass(generateAdapter = true)
data class SarvamTtsResponse(
    @Json(name = "request_id") val requestId: String?,
    @Json(name = "audios") val audios: List<String>?
)

// ==========================================
// GROQ VISION API MODELS
// ==========================================

@JsonClass(generateAdapter = true)
data class GroqVisionChatRequest(
    @Json(name = "model") val model: String = "meta-llama/llama-4-scout-17b-16e-instruct",
    @Json(name = "messages") val messages: List<GroqVisionMessage>,
    @Json(name = "temperature") val temperature: Double = 0.7
)

@JsonClass(generateAdapter = true)
data class GroqVisionMessage(
    @Json(name = "role") val role: String,
    @Json(name = "content") val content: List<GroqVisionContentPart>
)

@JsonClass(generateAdapter = true)
data class GroqVisionContentPart(
    @Json(name = "type") val type: String,
    @Json(name = "text") val text: String? = null,
    @Json(name = "image_url") val imageUrl: GroqVisionImageUrl? = null
)

@JsonClass(generateAdapter = true)
data class GroqVisionImageUrl(
    @Json(name = "url") val url: String
)
