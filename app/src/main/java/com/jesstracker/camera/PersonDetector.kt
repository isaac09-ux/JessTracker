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
        private const val BASE_CONFIDENCE = 0.45f
        private const val LOW_LIGHT_CONFIDENCE = 0.30f
        private const val ULTRA_LOW_LIGHT_CONFIDENCE = 0.18f
        private const val MAX_RESULTS = 12

        // Permite detectar jugadores lejanos (fondo de cancha).
        private const val MIN_NORMALIZED_HEIGHT = 0.020f
        private const val MIN_NORMALIZED_WIDTH = 0.008f

        // Solo personas — evita locks accidentales en objetos (red, postes, bancas).
        private val TRACKABLE_LABELS = setOf(
            "person"
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
        // Forzar GPU para mantener latencia minima en tracking en tiempo real.
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_NAME)
                .setDelegate(Delegate.GPU)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(ULTRA_LOW_LIGHT_CONFIDENCE)
                .setMaxResults(MAX_RESULTS)
                .build()

            detector = ObjectDetector.createFromOptions(context, options)
            isInitialized = true
            Log.i(TAG, "Inicializado con GPU")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo inicializar el detector con GPU: ${e.message}", e)
            initializationError = e.message
            isInitialized = false
        }
    }

    // --- Deteccion ---

    fun detect(frame: Bitmap, lightInfo: LightInfo): List<RectF> {
        val det = detector ?: return emptyList()

        return try {
            val mpImage = BitmapImageBuilder(frame).build()
            val result: ObjectDetectorResult = det.detect(mpImage)

            val adaptiveConfidence = when (lightInfo.level) {
                LightLevel.BRIGHT, LightLevel.NORMAL -> BASE_CONFIDENCE
                LightLevel.LOW -> LOW_LIGHT_CONFIDENCE
                LightLevel.ULTRA_LOW -> ULTRA_LOW_LIGHT_CONFIDENCE
            }

            result.detections()
                .filter { detection ->
                    detection.categories().any { category ->
                        val label = category.categoryName().orEmpty().lowercase(Locale.US)
                        label in TRACKABLE_LABELS && category.score() >= adaptiveConfidence
                    }
                }
                .mapNotNull { detection ->
                    normalizeBox(detection.boundingBox(), frame.width, frame.height, lightInfo.level)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error en deteccion: ${e.message}")
            emptyList()
        }
    }

    // --- Helpers ---

    private fun normalizeBox(box: RectF, frameW: Int, frameH: Int, lightLevel: LightLevel): RectF? {
        val left = box.left / frameW
        val top = box.top / frameH
        val right = box.right / frameW
        val bottom = box.bottom / frameH

        if (left >= 1f || top >= 1f || right <= 0f || bottom <= 0f) return null

        val minHeight = when (lightLevel) {
            LightLevel.BRIGHT, LightLevel.NORMAL -> MIN_NORMALIZED_HEIGHT
            LightLevel.LOW -> MIN_NORMALIZED_HEIGHT * 0.85f
            LightLevel.ULTRA_LOW -> MIN_NORMALIZED_HEIGHT * 0.65f
        }
        val minWidth = when (lightLevel) {
            LightLevel.BRIGHT, LightLevel.NORMAL -> MIN_NORMALIZED_WIDTH
            LightLevel.LOW -> MIN_NORMALIZED_WIDTH * 0.85f
            LightLevel.ULTRA_LOW -> MIN_NORMALIZED_WIDTH * 0.65f
        }

        val height = bottom - top
        val width = right - left
        if (height < minHeight || width < minWidth) return null

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
