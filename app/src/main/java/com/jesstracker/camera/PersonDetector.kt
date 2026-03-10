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
import android.util.Log
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
        private const val TAG = "PersonDetector"
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

    @Volatile
    var isInitialized: Boolean = false
        private set

    var initializationError: String? = null
        private set

    // --- Inicializacion ---

    fun initialize() {
        // Intentar GPU primero; si falla, caer a CPU.
        try {
            detector = createDetector(Delegate.GPU)
            isInitialized = true
            Log.i(TAG, "Inicializado con GPU")
            return
        } catch (e: Exception) {
            Log.w(TAG, "GPU no disponible, intentando CPU: ${e.message}")
        }

        try {
            detector = createDetector(Delegate.CPU)
            isInitialized = true
            Log.i(TAG, "Inicializado con CPU")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo inicializar el detector: ${e.message}", e)
            initializationError = e.message
            isInitialized = false
        }
    }

    private fun createDetector(delegate: Delegate): ObjectDetector {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_NAME)
            .setDelegate(delegate)
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(MIN_CONFIDENCE)
            .setMaxResults(MAX_RESULTS)
            .build()

        return ObjectDetector.createFromOptions(context, options)
    }

    // --- Deteccion ---

    fun detect(frame: Bitmap): List<RectF> {
        val det = detector ?: return emptyList()

        return try {
            val mpImage = BitmapImageBuilder(frame).build()
            val result: ObjectDetectorResult = det.detect(mpImage)

            result.detections()
                .filter { detection ->
                    detection.categories().any { category ->
                        val label = category.categoryName().orEmpty().lowercase(Locale.US)
                        label in TRACKABLE_LABELS && category.score() >= MIN_CONFIDENCE
                    }
                }
                .mapNotNull { detection ->
                    normalizeBox(detection.boundingBox(), frame.width, frame.height)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error en deteccion: ${e.message}")
            emptyList()
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
