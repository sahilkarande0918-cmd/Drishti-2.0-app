package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * Fast on-device detector for the OUTDOOR navigation fast layer.
 *
 * Runs Google's EfficientDet-Lite0 (COCO classes) fully offline on each camera frame and
 * reports only street-relevant obstacles — vehicles, riders, people, animals, signs — with
 * a direction and proximity estimate. Static hazards COCO cannot represent (potholes,
 * poles, barricades, broken footpath) are covered by the cloud AI deep-scan layer that
 * runs alongside this detector.
 *
 * [detect] is synchronous (~50-100ms); call it from a background thread only.
 */
class OutdoorObstacleDetector(context: Context) {

    enum class Direction { LEFT, AHEAD, RIGHT }

    /** Buckets derived from how much of the frame height the object fills. */
    enum class Proximity { VERY_CLOSE, CLOSE, NEARBY }

    data class Obstacle(
        val label: String,
        val direction: Direction,
        val proximity: Proximity,
        val score: Float,
        val heightFraction: Float
    )

    private val detector: ObjectDetector

    init {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(5)
            .setScoreThreshold(0.40f)
            .build()
        detector = ObjectDetector.createFromOptions(context, options)
    }

    /** Most dangerous obstacle first; empty list when nothing street-relevant is visible. */
    fun detect(bitmap: Bitmap): List<Obstacle> {
        val result = try {
            detector.detect(BitmapImageBuilder(bitmap).build())
        } catch (e: Exception) {
            Log.w(TAG, "On-device detection failed for a frame", e)
            return emptyList()
        }
        val frameWidth = bitmap.width.toFloat()
        val frameHeight = bitmap.height.toFloat()
        return result.detections().mapNotNull { detection ->
            val category = detection.categories().firstOrNull() ?: return@mapNotNull null
            val label = category.categoryName().lowercase()
            if (label !in OUTDOOR_LABELS) return@mapNotNull null
            val box = detection.boundingBox()
            val heightFraction = (box.height() / frameHeight).coerceIn(0f, 1f)
            // Tiny boxes are distant objects — noise for someone walking.
            if (heightFraction < MIN_HEIGHT_FRACTION) return@mapNotNull null
            val centerX = (box.left + box.right) / 2f / frameWidth
            Obstacle(
                label = label,
                direction = when {
                    centerX < 0.36f -> Direction.LEFT
                    centerX > 0.64f -> Direction.RIGHT
                    else -> Direction.AHEAD
                },
                proximity = when {
                    heightFraction > 0.55f -> Proximity.VERY_CLOSE
                    heightFraction > 0.32f -> Proximity.CLOSE
                    else -> Proximity.NEARBY
                },
                score = category.score(),
                heightFraction = heightFraction
            )
        }.sortedByDescending { it.heightFraction * classWeight(it.label) }
    }

    /** Vehicles outrank people/animals outrank street furniture at the same apparent size. */
    private fun classWeight(label: String): Float = when (label) {
        "car", "truck", "bus", "train", "motorcycle" -> 1.5f
        "bicycle", "person", "dog", "cow", "horse" -> 1.2f
        else -> 1.0f
    }

    fun close() {
        try {
            detector.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "OutdoorDetector"
        private const val MODEL_ASSET = "efficientdet_lite0.tflite"
        private const val MIN_HEIGHT_FRACTION = 0.12f

        /** COCO classes that matter on an Indian street or footpath. */
        val OUTDOOR_LABELS = setOf(
            "person", "bicycle", "car", "motorcycle", "bus", "truck", "train",
            "dog", "cow", "horse", "sheep", "cat",
            "traffic light", "stop sign", "fire hydrant", "bench", "parking meter"
        )
    }
}
