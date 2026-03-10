package com.jesstracker.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.util.Locale

/**
 * PersonDetector — wrapper de MediaPipe Object Detection.
 *
 * Detecta personas y vehiculos en cada frame y devuelve sus bounding boxes
 * como coordenadas normalizadas (0.0 - 1.0).
 *
 * Modelo usado: efficientdet_lite0 — el mas rapido de MediaPipe,
 * optimizado para movil, corre en ~8ms en el S24U.
 */
class PersonDetector(private val context: Context) {

    companion object {
        private const val MODEL_NAME = "efficientdet_lite0.tflite"
        private const val MIN_CONFIDENCE = 0.32f
        private const val MAX_RESULTS = 14

        // Permite detectar jugadores/objetos lejanos (gradas / fondo de cancha).
        private const val MIN_NORMALIZED_HEIGHT = 0.012f
        private const val MIN_NORMALIZED_WIDTH = 0.004f

        private val TRACKABLE_LABELS = setOf(
            "person",
            "car",
            "truck",
            "bus",
            "motorcycle",
            "bicycle"
        )
    }

    private var detector: ObjectDetector? = null

    // --- Inicializacion ---

    fun initialize() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_NAME)
            .setDelegate(Delegate.GPU)
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(MIN_CONFIDENCE)
            .setMaxResults(MAX_RESULTS)
            .build()

        detector = ObjectDetector.createFromOptions(context, options)
    }

    // --- Deteccion ---

    fun detect(frame: Bitmap): List<RectF> {
        val det = detector ?: return emptyList()

        val mpImage = BitmapImageBuilder(frame).build()
        val result: ObjectDetectorResult = det.detect(mpImage)

        return result.detections()
            .filter { detection ->
                detection.categories().any { category ->
                    val label = category.categoryName().orEmpty().lowercase(Locale.US)
                    label in TRACKABLE_LABELS && category.score() >= MIN_CONFIDENCE
                }
            }
            .mapNotNull { detection ->
                normalizeBox(detection.boundingBox(), frame.width, frame.height)
            }
    }

    // --- Helpers ---

    private fun normalizeBox(box: android.graphics.Rect, frameW: Int, frameH: Int): RectF? {
        val left = box.left.toFloat() / frameW
        val top = box.top.toFloat() / frameH
        val right = box.right.toFloat() / frameW
        val bottom = box.bottom.toFloat() / frameH

        if (left >= 1f || top >= 1f || right <= 0f || bottom <= 0f) return null

        val height = bottom - top
        val width = right - left
        if (height < MIN_NORMALIZED_HEIGHT || width < MIN_NORMALIZED_WIDTH) return null

        return RectF(
            left.coerceIn(0f, 1f),
            top.coerceIn(0f, 1f),
            right.coerceIn(0f, 1f),
            bottom.coerceIn(0f, 1f)
        )
    }

    // --- Lifecycle ---

    fun close() {
        detector?.close()
        detector = null
    }
}
