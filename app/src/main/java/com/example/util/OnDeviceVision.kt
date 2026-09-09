package com.example.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device vision, replacing cloud calls for everything that does not need a
 * vision-language model.
 *
 * Why this exists: a cloud round-trip costs a second or more and fails outright when the
 * quota is spent, which is unusable for someone waiting to be told what they are holding.
 * Every model here runs locally in ~50-200ms, works with no network, and costs nothing.
 *
 * What deliberately stays in the cloud: open-ended "describe this whole scene in
 * conversational Marathi". That needs a vision-language model, which is not something a
 * mid-range phone can run well. [labelScene] is the offline fallback for it — a list of
 * objects rather than a sentence, but always available.
 */
object OnDeviceVision {

    private const val TAG = "OnDeviceVision"

    // Recognizers are expensive to build and safe to reuse across calls.
    private val latinText by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val devanagariText by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }
    private val imageLabeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
    }
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                // Classification is what gives us the smile / eyes-open probabilities.
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    /** Bridges an ML Kit Task into a coroutine, so callers can just suspend. */
    private suspend fun <T> awaitTask(task: com.google.android.gms.tasks.Task<T>): T =
        suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }

    // ==========================================================
    // TEXT  (signs, boards, documents, labels)
    // ==========================================================

    /**
     * Reads text from the image, trying Devanagari first and falling back to Latin.
     *
     * Both scripts are tried because Indian signage mixes them constantly — a shop board is
     * as likely to be Marathi as English, and picking only one recognizer silently loses
     * half the signs a user points at. Whichever returns more text wins.
     */
    suspend fun readText(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val devanagari = runCatching { awaitTask(devanagariText.process(image)).text }
            .getOrElse {
                Log.w(TAG, "Devanagari OCR failed", it); ""
            }
        val latin = runCatching { awaitTask(latinText.process(InputImage.fromBitmap(bitmap, 0))).text }
            .getOrElse {
                Log.w(TAG, "Latin OCR failed", it); ""
            }
        return (if (devanagari.trim().length >= latin.trim().length) devanagari else latin)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ==========================================================
    // FACES  (expression)
    // ==========================================================

    data class FaceReading(
        val count: Int,
        /** 0..1, or null when the model could not classify. */
        val smilingProbability: Float?,
        val eyesOpen: Boolean?
    )

    suspend fun readFaces(bitmap: Bitmap): FaceReading {
        val faces: List<Face> = runCatching {
            awaitTask(faceDetector.process(InputImage.fromBitmap(bitmap, 0)))
        }.getOrElse {
            Log.w(TAG, "Face detection failed", it); emptyList()
        }
        if (faces.isEmpty()) return FaceReading(0, null, null)
        // The largest face is the one the user is pointing at.
        val main = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
        val eyes = listOfNotNull(main.leftEyeOpenProbability, main.rightEyeOpenProbability)
        return FaceReading(
            count = faces.size,
            smilingProbability = main.smilingProbability,
            eyesOpen = if (eyes.isEmpty()) null else eyes.average() > 0.5
        )
    }

    // ==========================================================
    // BARCODES  (products)
    // ==========================================================

    /** Raw barcode value, or null when none is visible. */
    suspend fun readBarcode(bitmap: Bitmap): String? = runCatching {
        awaitTask(barcodeScanner.process(InputImage.fromBitmap(bitmap, 0)))
            .firstOrNull()?.rawValue
    }.getOrElse {
        Log.w(TAG, "Barcode scan failed", it); null
    }

    // ==========================================================
    // SCENE LABELS  (offline fallback for scene description)
    // ==========================================================

    /** Objects visible in the frame, most confident first. Offline, no network. */
    suspend fun labelScene(bitmap: Bitmap, max: Int = 4): List<String> = runCatching {
        awaitTask(imageLabeler.process(InputImage.fromBitmap(bitmap, 0)))
            .sortedByDescending { it.confidence }
            .take(max)
            .map { it.text }
    }.getOrElse {
        Log.w(TAG, "Image labeling failed", it); emptyList()
    }

    // ==========================================================
    // COLOUR  (no ML needed)
    // ==========================================================

    /**
     * Dominant colour name at the centre of the frame.
     *
     * No model involved: this used to be a cloud vision call, which is an absurd amount of
     * latency and quota to spend on reading pixels the phone already has. Averaged over a
     * centre patch so one noisy pixel cannot decide the answer.
     */
    fun centreColorName(bitmap: Bitmap): String {
        val patch = 0.2f
        val w = bitmap.width
        val h = bitmap.height
        val x0 = ((w * (0.5f - patch / 2)).toInt()).coerceIn(0, w - 1)
        val y0 = ((h * (0.5f - patch / 2)).toInt()).coerceIn(0, h - 1)
        val x1 = ((w * (0.5f + patch / 2)).toInt()).coerceIn(x0 + 1, w)
        val y1 = ((h * (0.5f + patch / 2)).toInt()).coerceIn(y0 + 1, h)

        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        val stepX = ((x1 - x0) / 24).coerceAtLeast(1)
        val stepY = ((y1 - y0) / 24).coerceAtLeast(1)
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = bitmap.getPixel(x, y)
                r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0L) return "unknown"
        return colorName((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** Maps RGB to a small vocabulary a person would actually say out loud. */
    private fun colorName(r: Int, g: Int, b: Int): String {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val (hue, sat, value) = Triple(hsv[0], hsv[1], hsv[2])

        // Achromatic first — a low-saturation pixel has no meaningful hue.
        if (value < 0.14f) return "black"
        if (sat < 0.12f) return when {
            value > 0.85f -> "white"
            value > 0.55f -> "light grey"
            else -> "grey"
        }
        // Brown is dark low-saturation orange, and has no hue band of its own.
        if (hue < 45f && value < 0.55f && sat > 0.25f) return "brown"

        return when {
            hue < 15f || hue >= 345f -> "red"
            hue < 40f -> "orange"
            hue < 70f -> "yellow"
            hue < 165f -> "green"
            hue < 200f -> "cyan"
            hue < 255f -> "blue"
            hue < 290f -> "purple"
            hue < 345f -> "pink"
            else -> "red"
        }
    }

    /** Localised colour word for speech. */
    fun colorNameLocalised(name: String, languageCode: String): String = when (languageCode) {
        "hi-IN" -> hindiColors[name] ?: name
        "mr-IN" -> marathiColors[name] ?: name
        else -> name
    }

    private val hindiColors = mapOf(
        "black" to "काला", "white" to "सफ़ेद", "grey" to "स्लेटी", "light grey" to "हल्का स्लेटी",
        "red" to "लाल", "orange" to "नारंगी", "yellow" to "पीला", "green" to "हरा",
        "cyan" to "आसमानी", "blue" to "नीला", "purple" to "बैंगनी", "pink" to "गुलाबी",
        "brown" to "भूरा", "unknown" to "पता नहीं"
    )

    private val marathiColors = mapOf(
        "black" to "काळा", "white" to "पांढरा", "grey" to "करडा", "light grey" to "फिकट करडा",
        "red" to "लाल", "orange" to "केशरी", "yellow" to "पिवळा", "green" to "हिरवा",
        "cyan" to "आकाशी", "blue" to "निळा", "purple" to "जांभळा", "pink" to "गुलाबी",
        "brown" to "तपकिरी", "unknown" to "माहीत नाही"
    )
}
