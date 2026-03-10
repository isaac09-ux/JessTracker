package com.jesstracker.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

/**
 * PersonDetector — wrapper de MediaPipe Object Detection.
 *
 * Detecta personas en cada frame y devuelve sus bounding boxes
 * como coordenadas normalizadas (0.0 - 1.0).
 *
 * Por qué Object Detection y no Pose Landmarker:
 *   - Object Detection devuelve bounding boxes de personas completas
 *   - Es más rápido (~8ms vs ~15ms por frame)
 *   - Para tracking de sujeto no necesitamos el esqueleto todavía
 *   - Pose Landmarker viene en la siguiente iteración (mejora de re-ID)
 *
 * Modelo usado: efficientdet_lite0 — el más rápido de MediaPipe,
 * optimizado para móvil, corre en ~8ms en el S24U.
 */
class PersonDetector(private val context: Context) {

    companion object {
        // Modelo incluido en los assets de la app
        // Descargar de: https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite
        private const val MODEL_NAME = "efficientdet_lite0.tflite"

        // Solo nos interesan personas (clase "person" en COCO dataset = índice 0)
        private const val PERSON_LABEL = "person"

        // Confianza mínima para considerar una detección válida
        // 0.5 = 50% — balance entre falsos positivos y detecciones perdidas
        private const val MIN_CONFIDENCE = 0.5f

        // Máximo de personas a detectar por frame
        // 12 cubre un partido completo (6 + 6) con árbitros
        private const val MAX_RESULTS = 12
    }

    private var detector: ObjectDetector? = null

    // ─── Inicialización ───────────────────────────────────────────────────────

    /**
     * Inicializa el detector. Llamar una sola vez antes de procesar frames.
     * Es sincrónico — hacerlo en un hilo de background (ya lo maneja CameraManager).
     *
     * @throws Exception si el modelo no está en assets o el dispositivo no soporta el modelo
     */
    fun initialize() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_NAME)
            // Delegar a GPU si está disponible, fallback a CPU
            .useGpu()
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)   // un frame a la vez, síncrono
            .setScoreThreshold(MIN_CONFIDENCE)
            .setMaxResults(MAX_RESULTS)
            .build()

        detector = ObjectDetector.createFromOptions(context, options)
    }

    // ─── Detección ────────────────────────────────────────────────────────────

    /**
     * Detecta personas en un frame.
     *
     * @param frame Bitmap del frame actual (ya rotado correctamente)
     * @return Lista de bounding boxes normalizados (0-1) de personas detectadas
     */
    fun detect(frame: Bitmap): List<RectF> {
        val det = detector ?: return emptyList()

        // Convertir Bitmap a MPImage (formato de MediaPipe)
        val mpImage = BitmapImageBuilder(frame).build()

        val result: ObjectDetectorResult = det.detect(mpImage)

        return result.detections()
            .filter { detection ->
                // Filtrar solo personas con confianza suficiente
                detection.categories().any { category ->
                    category.categoryName() == PERSON_LABEL &&
                    category.score() >= MIN_CONFIDENCE
                }
            }
            .mapNotNull { detection ->
                // Convertir bounding box a coordenadas normalizadas
                val boundingBox = detection.boundingBox()
                normalizeBox(boundingBox, frame.width, frame.height)
            }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Convierte coordenadas absolutas (píxeles) a normalizadas (0.0 - 1.0).
     * Filtra boxes inválidos (fuera de frame o demasiado pequeños).
     */
    private fun normalizeBox(box: android.graphics.Rect, frameW: Int, frameH: Int): RectF? {
        val left   = box.left.toFloat()   / frameW
        val top    = box.top.toFloat()    / frameH
        val right  = box.right.toFloat()  / frameW
        val bottom = box.bottom.toFloat() / frameH

        // Descartar boxes que estén completamente fuera del frame
        if (left >= 1f || top >= 1f || right <= 0f || bottom <= 0f) return null

        // Descartar boxes demasiado pequeños (ruido del detector)
        // Mínimo 3% del frame de alto — una persona muy lejana en cancha
        val height = bottom - top
        val width  = right - left
        if (height < 0.03f || width < 0.01f) return null

        return RectF(
            left.coerceIn(0f, 1f),
            top.coerceIn(0f, 1f),
            right.coerceIn(0f, 1f),
            bottom.coerceIn(0f, 1f)
        )
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Liberar recursos del modelo. Llamar desde CameraManager.shutdown().
     */
    fun close() {
        detector?.close()
        detector = null
    }
}
