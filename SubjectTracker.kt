package com.jesstracker.tracking

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF

/**
 * SubjectTracker — el cerebro del tracker.
 *
 * Máquina de estados que maneja todo el ciclo de vida del seguimiento:
 *
 *   IDLE ──(tap)──► TRACKING ──(pierde sujeto)──► LOST
 *     ▲                 ▲                            │
 *     │                 └────(re-id exitoso)──── RE_IDENTIFYING
 *     └─────────────────(expiró / reset)─────────────┘
 *
 * Uso:
 *   1. Llama onTap(point, detections, frame) cuando el usuario toca la pantalla
 *   2. Llama update(detections, frame) en cada frame de la cámara
 *   3. Lee state y cropBox para saber qué renderizar
 */
class SubjectTracker {

    // ─── Estado público ───────────────────────────────────────────────────────

    var state: TrackerState = TrackerState.IDLE
        private set

    // Bounding box suavizado del sujeto actual (coordenadas normalizadas 0-1)
    // null si no hay sujeto seleccionado
    var cropBox: RectF? = null
        private set

    // Identidad actual del sujeto seleccionado
    var identity: SubjectIdentity? = null
        private set

    // ─── Dependencias internas ────────────────────────────────────────────────

    private val embeddingExtractor = EmbeddingExtractor()
    private val smoothingFilter = SmoothingFilter()

    // Buffer de embeddings recientes para promediar la identidad
    // (más robusto que un solo frame)
    private val embeddingBuffer = mutableListOf<FloatArray>()
    private val EMBEDDING_BUFFER_SIZE = 5

    // ─── API pública ──────────────────────────────────────────────────────────

    /**
     * Llamar cuando el usuario toca la pantalla.
     *
     * @param tapPoint   Coordenadas normalizadas (0-1) del toque
     * @param detections Lista de bounding boxes detectados en el frame actual
     * @param frame      Frame actual de la cámara
     */
    fun onTap(tapPoint: PointF, detections: List<RectF>, frame: Bitmap) {
        val tappedBox = findTappedDetection(tapPoint, detections) ?: return

        // Extraer embedding inicial del sujeto seleccionado
        val patch = embeddingExtractor.cropPatch(frame, tappedBox)
        val embedding = embeddingExtractor.extractFromPatch(patch)

        // Inicializar buffer con el primer embedding
        embeddingBuffer.clear()
        embeddingBuffer.add(embedding)

        identity = SubjectIdentity(
            embedding = embedding,
            lastKnownBox = RectF(tappedBox),
            lastKnownPatch = patch,
            confidence = 1.0f,
            framesLost = 0
        )

        smoothingFilter.reset(tappedBox)
        cropBox = tappedBox
        state = TrackerState.TRACKING
    }

    /**
     * Llamar en cada frame de la cámara.
     *
     * @param detections Lista de bounding boxes detectados en este frame
     * @param frame      Frame actual de la cámara
     * @return           El bounding box suavizado del sujeto, o null si no hay sujeto
     */
    fun update(detections: List<RectF>, frame: Bitmap): RectF? {
        val currentIdentity = identity ?: return null

        return when (state) {
            TrackerState.IDLE -> null

            TrackerState.TRACKING -> updateTracking(currentIdentity, detections, frame)

            TrackerState.LOST -> updateLost(currentIdentity, detections, frame)

            TrackerState.RE_IDENTIFYING -> updateReIdentifying(currentIdentity, detections, frame)
        }
    }

    /**
     * Resetea el tracker completamente.
     */
    fun reset() {
        state = TrackerState.IDLE
        cropBox = null
        identity = null
        embeddingBuffer.clear()
        smoothingFilter.reset(null)
    }

    // ─── Lógica por estado ────────────────────────────────────────────────────

    /**
     * TRACKING: el sujeto es visible.
     * Usa IoU para encontrar el box más cercano al último conocido.
     */
    private fun updateTracking(
        currentIdentity: SubjectIdentity,
        detections: List<RectF>,
        frame: Bitmap
    ): RectF? {
        val bestMatch = findBestIoUMatch(currentIdentity.lastKnownBox, detections)

        if (bestMatch == null) {
            // No encontramos al sujeto → transición a LOST
            state = TrackerState.LOST
            identity = currentIdentity.incrementLost()
            return cropBox // mantener último crop conocido
        }

        // Actualizar embedding buffer con nuevo frame
        updateEmbeddingBuffer(bestMatch, frame)

        // Actualizar identidad con nueva posición
        val patch = embeddingExtractor.cropPatch(frame, bestMatch)
        identity = currentIdentity.copy(
            embedding = averageEmbedding(),
            lastKnownBox = RectF(bestMatch),
            lastKnownPatch = patch,
            confidence = 1.0f,
            framesLost = 0
        )

        // Suavizar el movimiento del crop
        val smoothed = smoothingFilter.update(bestMatch)
        cropBox = smoothed
        return smoothed
    }

    /**
     * LOST: el sujeto salió del frame.
     * Espera a que aparezcan detecciones e intenta re-identificar.
     */
    private fun updateLost(
        currentIdentity: SubjectIdentity,
        detections: List<RectF>,
        frame: Bitmap
    ): RectF? {
        // Verificar si llevamos demasiado tiempo sin verlo
        if (currentIdentity.isExpired()) {
            reset()
            return null
        }

        identity = currentIdentity.incrementLost()

        if (detections.isEmpty()) return cropBox

        // Hay detecciones → intentar re-identificar
        state = TrackerState.RE_IDENTIFYING
        return updateReIdentifying(identity!!, detections, frame)
    }

    /**
     * RE_IDENTIFYING: hay personas en frame, buscando al sujeto perdido.
     * Compara embeddings de todos los candidatos contra la identidad guardada.
     */
    private fun updateReIdentifying(
        currentIdentity: SubjectIdentity,
        detections: List<RectF>,
        frame: Bitmap
    ): RectF? {
        if (currentIdentity.isExpired()) {
            reset()
            return null
        }

        // Calcular similitud de cada detección con la identidad guardada
        val bestCandidate = detections
            .map { box ->
                val embedding = embeddingExtractor.extract(frame, box)
                val similarity = currentIdentity.cosineSimilarity(embedding)
                Pair(box, similarity)
            }
            .filter { (_, similarity) ->
                similarity >= SubjectIdentity.SIMILARITY_THRESHOLD
            }
            .maxByOrNull { (_, similarity) -> similarity }

        if (bestCandidate == null) {
            // No encontramos al sujeto todavía
            identity = currentIdentity.incrementLost()
            return cropBox
        }

        // ¡Encontrado! Volver a TRACKING
        val (foundBox, similarity) = bestCandidate
        val patch = embeddingExtractor.cropPatch(frame, foundBox)
        val newEmbedding = embeddingExtractor.extractFromPatch(patch)

        embeddingBuffer.clear()
        embeddingBuffer.add(newEmbedding)

        identity = currentIdentity.copy(
            embedding = newEmbedding,
            lastKnownBox = RectF(foundBox),
            lastKnownPatch = patch,
            confidence = similarity,
            framesLost = 0
        )

        smoothingFilter.reset(foundBox)
        cropBox = foundBox
        state = TrackerState.TRACKING

        return foundBox
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Encuentra qué bounding box fue tocado por el usuario.
     * Devuelve el box que contiene el punto del toque.
     */
    private fun findTappedDetection(tapPoint: PointF, detections: List<RectF>): RectF? {
        return detections.firstOrNull { box -> box.contains(tapPoint.x, tapPoint.y) }
    }

    /**
     * IoU — Intersection over Union.
     * Encuentra el box más similar en posición/tamaño al último conocido.
     * Threshold de 0.3 → mínimo 30% de overlap para considerarlo el mismo sujeto.
     */
    private fun findBestIoUMatch(reference: RectF, candidates: List<RectF>): RectF? {
        return candidates
            .map { box -> Pair(box, calculateIoU(reference, box)) }
            .filter { (_, iou) -> iou >= 0.3f }
            .maxByOrNull { (_, iou) -> iou }
            ?.first
    }

    /**
     * Calcula IoU entre dos bounding boxes.
     * IoU = área intersección / área unión
     */
    private fun calculateIoU(a: RectF, b: RectF): Float {
        val intersectLeft   = maxOf(a.left, b.left)
        val intersectTop    = maxOf(a.top, b.top)
        val intersectRight  = minOf(a.right, b.right)
        val intersectBottom = minOf(a.bottom, b.bottom)

        if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) return 0f

        val intersectArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val aArea = a.width() * a.height()
        val bArea = b.width() * b.height()
        val unionArea = aArea + bArea - intersectArea

        return if (unionArea <= 0f) 0f else intersectArea / unionArea
    }

    /**
     * Agrega el embedding del frame actual al buffer y mantiene el tamaño máximo.
     */
    private fun updateEmbeddingBuffer(box: RectF, frame: Bitmap) {
        val embedding = embeddingExtractor.extract(frame, box)
        embeddingBuffer.add(embedding)
        if (embeddingBuffer.size > EMBEDDING_BUFFER_SIZE) {
            embeddingBuffer.removeAt(0)
        }
    }

    /**
     * Promedia todos los embeddings del buffer.
     * Más robusto que usar un solo frame — reduce ruido de iluminación.
     */
    private fun averageEmbedding(): FloatArray {
        if (embeddingBuffer.isEmpty()) return FloatArray(96)
        val result = FloatArray(96)
        for (embedding in embeddingBuffer) {
            for (i in embedding.indices) {
                result[i] += embedding[i]
            }
        }
        return FloatArray(96) { result[it] / embeddingBuffer.size }
    }
}

/**
 * Estados posibles del tracker.
 */
enum class TrackerState {
    IDLE,             // Sin sujeto seleccionado
    TRACKING,         // Sujeto visible y siendo seguido
    LOST,             // Sujeto no visible, buscando
    RE_IDENTIFYING    // Hay detecciones, comparando embeddings
}
