package com.example.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Lightweight CameraX analyzer that grabs the latest preview frame, rotates it upright,
 * downscales it, and hands it off via [onFrame].
 *
 * Indoor navigation identification is done by the cloud AI vision layer (Gemini / Groq)
 * because it can recognise the things that actually matter indoors — stairs going up vs
 * down, pillars, lifts, doors, a person sitting on a chair, etc. — which a small fixed
 * on-device object model cannot. This analyzer only provides fresh frames for that layer,
 * throttled so we don't waste work between AI scans.
 *
 * The ImageAnalysis use case must be configured with OUTPUT_IMAGE_FORMAT_RGBA_8888 so the
 * single plane can be copied straight into an ARGB bitmap.
 */
class IndoorFrameAnalyzer(
    private val onFrame: (Bitmap) -> Unit
) : ImageAnalysis.Analyzer {

    private var reusableBitmap: Bitmap? = null
    private var lastEmitTime = 0L

    override fun analyze(image: ImageProxy) {
        try {
            // Throttle: one usable frame every ~300ms is plenty for the AI layer.
            val now = SystemClock.uptimeMillis()
            if (now - lastEmitTime < MIN_FRAME_INTERVAL_MS) {
                image.close()
                return
            }
            lastEmitTime = now

            val buffer = reusableBitmap?.takeIf { it.width == image.width && it.height == image.height }
                ?: Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    .also { reusableBitmap = it }

            val rotation = image.imageInfo.rotationDegrees.toFloat()
            image.use { buffer.copyPixelsFromBuffer(it.planes[0].buffer) }

            // Rotate upright and downscale so left/right is correct and uploads stay fast.
            val matrix = Matrix().apply {
                if (rotation != 0f) postRotate(rotation)
                val longest = maxOf(buffer.width, buffer.height).toFloat()
                if (longest > TARGET_LONGEST_SIDE) {
                    val scale = TARGET_LONGEST_SIDE / longest
                    postScale(scale, scale)
                }
            }
            val processed = Bitmap.createBitmap(buffer, 0, 0, buffer.width, buffer.height, matrix, true)
            onFrame(processed)
        } catch (e: Exception) {
            image.close()
        }
    }

    companion object {
        private const val MIN_FRAME_INTERVAL_MS = 300L
        private const val TARGET_LONGEST_SIDE = 640f
    }
}
