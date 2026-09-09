package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * Fast on-device detector behind the navigation fast layer, indoors and outdoors.
 *
 * Runs Google's EfficientDet-Lite0 (COCO classes) fully offline on each camera frame and
 * reports obstacles with a direction and proximity estimate. Pass [OUTDOOR_LABELS] for
 * street hazards (vehicles, riders, people, animals) or [INDOOR_LABELS] for furniture and
 * people you would walk into.
 *
 * Everything COCO has no class for — potholes, poles and barricades outdoors; stairs,
 * doors, ramps and lifts indoors — is covered by the cloud AI deep-scan layer that runs
 * alongside this detector. The point of this class is that a blind user hears the common
 * cases in ~100ms instead of waiting on a network round-trip.
 *
 * [detect] is synchronous (~50-100ms); call it from a background thread only.
 */
class ObstacleDetector(context: Context) {

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
            // 0.40 let single weak guesses through - a white door read as "refrigerator"
            // and was spoken to the user. Raised so only confident detections survive;
            // DetectionStabilizer then requires the label to persist across frames.
            .setScoreThreshold(0.55f)
            .build()
        detector = ObjectDetector.createFromOptions(context, options)
    }

    /**
     * Most dangerous obstacle first; empty list when nothing relevant is visible.
     *
     * [labels] selects which COCO classes count as an obstacle, so the same model serves
     * the street (vehicles, riders, animals) and indoors (furniture you would walk into).
     */
    fun detect(bitmap: Bitmap, labels: Set<String> = OUTDOOR_LABELS): List<Obstacle> {
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
            if (label !in labels) return@mapNotNull null
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
        // Lowered from 0.12 so obstacles register while they are still some distance away,
        // giving a blind walker warning BEFORE they are on top of the hazard rather than
        // when it already fills the frame. The danger-only label filter keeps the extra
        // small detections from becoming noise.
        private const val MIN_HEIGHT_FRACTION = 0.07f

        /**
         * The only classes live navigation announces: things that can actually hurt a
         * blind walker — moving traffic, people, animals, bikes. Benign furniture and
         * décor (bench, potted plant, tv, poster-like signage) is deliberately excluded,
         * because calling out everything in view is noise that buries the real danger.
         * The one-time full scan still describes everything; this set is nav-only.
         */
        val DANGER_LABELS = setOf(
            "person", "bicycle", "car", "motorcycle", "bus", "truck", "train",
            "dog", "cow", "horse"
        )

        /** COCO classes that matter on an Indian street or footpath. */
        val OUTDOOR_LABELS = setOf(
            "person", "bicycle", "car", "motorcycle", "bus", "truck", "train",
            "dog", "cow", "horse", "sheep", "cat",
            "traffic light", "stop sign", "fire hydrant", "bench", "parking meter"
        )

        /**
         * COCO classes worth announcing indoors — things you walk into or trip over.
         *
         * Note what COCO cannot see: stairs, doors, ramps and lifts have no class here,
         * and those are the most dangerous indoor features. The cloud vision layer stays
         * responsible for them; this set exists so furniture and people are announced
         * instantly instead of waiting on a network round-trip.
         */
        val INDOOR_LABELS = setOf(
            "person", "chair", "couch", "bed", "dining table", "toilet",
            "potted plant", "tv", "refrigerator", "oven", "sink", "microwave",
            "suitcase", "backpack", "bicycle", "dog", "cat"
        )
    }
}

/**
 * Suppresses one-frame detection flicker.
 *
 * The detector is a per-frame classifier with no memory: it re-guesses every frame, and a
 * single bad guess used to be announced immediately - which is how a user standing in a
 * room with no fridge was told there was a refrigerator very close to them. Requiring a
 * label to appear in several of the recent frames turns a momentary misfire into silence,
 * while a real object - which persists - still gets through in a fraction of a second.
 *
 * Not learning. The model does not improve with use; this only filters its output.
 */
class DetectionStabilizer(
    private val requiredHits: Int = 3,
    private val windowSize: Int = 5
) {
    private val recent = ArrayDeque<Set<String>>()

    /** Feed one frame's detections; returns only those seen often enough to trust. */
    fun confirm(detections: List<ObstacleDetector.Obstacle>): List<ObstacleDetector.Obstacle> {
        recent.addLast(detections.map { it.label }.toSet())
        while (recent.size > windowSize) recent.removeFirst()

        return detections.filter { obstacle ->
            val hits = recent.count { obstacle.label in it }
            // Something about to be walked into is reported on fewer confirmations - the
            // cost of a late warning is higher than the cost of an occasional wrong one.
            val needed = if (obstacle.proximity == ObstacleDetector.Proximity.VERY_CLOSE) {
                (requiredHits - 1).coerceAtLeast(2)
            } else {
                requiredHits
            }
            hits >= needed
        }
    }

    fun reset() = recent.clear()
}
