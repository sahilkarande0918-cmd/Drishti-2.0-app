package com.example.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

// ==========================================
// API SERVICES
// ==========================================

interface GeminiApiService {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

interface GroqApiService {
    @POST("v1/chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authorization: String,
        @Body request: GroqChatRequest
    ): GroqChatResponse

    @POST("v1/chat/completions")
    suspend fun chatCompletionsVision(
        @Header("Authorization") authorization: String,
        @Body request: GroqVisionChatRequest
    ): GroqChatResponse
}

interface SarvamApiService {
    @POST("text-to-speech")
    suspend fun generateTts(
        @Header("api-subscription-key") subscriptionKey: String,
        @Body request: SarvamTtsRequest
    ): SarvamTtsResponse
}

interface NominatimService {
    @Headers("User-Agent: DrishtiAccessibilityApp/1.0 (sahilkarande0918@gmail.com)")
    @GET("https://nominatim.openstreetmap.org/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 1,
        @Query("accept-language") acceptLanguage: String? = null
    ): List<NominatimSearchResult>

    @Headers("User-Agent: DrishtiAccessibilityApp/1.0 (sahilkarande0918@gmail.com)")
    @GET("https://nominatim.openstreetmap.org/reverse")
    suspend fun reverseGeocode(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("format") format: String = "json",
        @Query("accept-language") acceptLanguage: String? = null
    ): NominatimReverseResult
}

interface OsrmService {
    @GET("https://router.project-osrm.org/route/v1/foot/{coordinates}")
    suspend fun getRoute(
        @Path("coordinates") coordinates: String, // format: "lon,lat;lon,lat"
        @Query("steps") steps: Boolean = true,
        @Query("overview") overview: String = "full",
        @Query("geometries") geometries: String = "polyline"
    ): OsrmResponse
}

// ==========================================
// RETROFIT CLIENT
// ==========================================

object DrishtiApiClient {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    // Logging interceptor for debugging network communication
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    val geminiService: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }

    val nominatimService: NominatimService by lazy {
        Retrofit.Builder()
            .baseUrl("https://nominatim.openstreetmap.org/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(NominatimService::class.java)
    }

    val osrmService: OsrmService by lazy {
        Retrofit.Builder()
            .baseUrl("https://router.project-osrm.org/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OsrmService::class.java)
    }

    val groqService: GroqApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.groq.com/openai/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GroqApiService::class.java)
    }

    val sarvamService: SarvamApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.sarvam.ai/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SarvamApiService::class.java)
    }

    val ruviewService: RuViewService by lazy {
        val ruviewClient = okHttpClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("http://127.0.0.1/") // Overridden by @Url
            .client(ruviewClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(RuViewService::class.java)
    }

    val formSubmitService: FormSubmitService by lazy {
        Retrofit.Builder()
            .baseUrl("https://formsubmit.co/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(FormSubmitService::class.java)
    }
}

interface RuViewService {
    @GET
    suspend fun getSensingLatest(@Url url: String): RuViewSensingLatestResponse
}

interface FormSubmitService {
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Referer: https://drishti.ai",
        "Origin: https://drishti.ai"
    )
    @POST("ajax/{email}")
    suspend fun sendEmergencyEmail(
        @Path("email") email: String,
        @Body payload: Map<String, String>
    ): retrofit2.Response<FormSubmitResponse>
}

data class FormSubmitResponse(
    val success: String,
    val message: String
)
