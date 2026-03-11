package com.jesstracker.tracking

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * SubjectTracker — el cerebro del tracker.
 *
 * Maquina de estados:
 *   IDLE --(tap)--> TRACKING --(pierde sujeto)--> LOST
 *     ^                 ^                            |
 *     |                 +----(re-id exitoso)---- RE_IDENTIFYING
 *     +-----------------(expiro / reset)-------------+
 *
 * v3: Usa Kalman filter para prediccion de posicion y OCR de numero
 * de camiseta como senal de identidad definitiva.
 */
class SubjectTracker {

    companion object {
        private const val MAX_REFERENCE_DISTANCE_FOR_TAP = 0.22f
        private const val TAP_INSIDE_BONUS = 0.35f
        private const val EMBEDDING_BUFFER_SIZE = 4
        private const val ANCHOR_BLEND_WEIGHT = 0.38f
        private const val EMBEDDING_UPDATE_SIMILARITY_GATE = 0.86f

        // Peso del aspect ratio en el score de re-identificacion.
        private const val SIZE_SIMILARITY_WEIGHT = 0.12f

        // En voley, las camisetas son iguales → proximidad importa MAS que embedding.
        private const val EMBEDDING_WEIGHT_TRACKING = 0.55f
        private const val PROXIMITY_WEIGHT_TRACKING = 0.40f
        private const val EMBEDDING_WEIGHT_REID = 0.50f
        private const val PROXIMITY_WEIGHT_REID = 0.35f

        // Umbral de IoU para modular la penalizacion por proximidad en tracking continuo.
        private const val IOU_FAST_MATCH_THRESHOLD = 0.35f
        // Score minimo para aceptar un candidato en tracking continuo.
        private const val TRACKING_MATCH_THRESHOLD = 0.58f

        // Distancia maxima (normalizada) que un candidato puede estar de la posicion
        // predicha para ser considerado. Evita saltos al otro lado de la cancha.
        private const val MAX_JUMP_DISTANCE_TRACKING = 0.18f
        private const val MAX_JUMP_DISTANCE_REID = 0.30f

        // Re-ID consecutivo: cuantos frames seguidos debe ganar el MISMO candidato
        // antes de aceptarlo. Evita false re-locks de un solo frame.
        private const val REID_CONSECUTIVE_REQUIRED = 3
        private const val REID_SAME_CANDIDATE_IOU = 0.40f

        // Bonus al score de re-ID cuando el OCR lee el mismo numero de camiseta.
        private const val OCR_MATCH_BONUS = 0.20f

        // Cada cuantos frames intentar leer OCR durante tracking (no cada frame).
        private const val OCR_INTERVAL_FRAMES = 15
    }

    // --- Estado publico ---

    var state: TrackerState = TrackerState.IDLE
        private set

    var cropBox: RectF? = null
        private set

    var identity: SubjectIdentity? = null
        private set

    /** Numero de camiseta confirmado (null si no se ha leido aun). */
    val jerseyNumber: Int?
        get() = jerseyOcr.confirmedNumber

    // --- Dependencias internas ---

    private val embeddingExtractor = EmbeddingExtractor()
    private val smoothingFilter = SmoothingFilter()
    private val kalmanFilter = KalmanBoxFilter()
    val jerseyOcr = JerseyOcrReader()

    private val embeddingBuffer = mutableListOf<FloatArray>()
    private var anchorEmbedding: FloatArray? = null

    // Aspect ratio del sujeto seleccionado, para filtrar candidatos de tamano muy diferente.
    private var referenceAspectRatio: Float = 1f
    private var referenceHeight: Float = 0f

    // Re-ID consecutivo: guardar el candidato ganador y cuantos frames lleva ganando.
    private var reIdCandidateBox: RectF? = null
    private var reIdConsecutiveCount: Int = 0

    // CropBox congelado: cuando se pierde el sujeto, congelar el crop en la ultima
    // posicion conocida en vez de dejarlo derivar con la velocidad.
    private var frozenCropBox: RectF? = null

    // Contador de frames para OCR periodico.
    private var framesSinceOcr: Int = 0

    // --- API publica ---

    fun onTap(tapPoint: PointF, detections: List<RectF>, frame: Bitmap) {
        val tappedBox = findTappedDetection(tapPoint, detections) ?: return

        val patch = embeddingExtractor.cropPatch(frame, tappedBox)
        val embedding = embeddingExtractor.extractFromPatch(patch)

        embeddingBuffer.clear()
        // Sembramos el buffer para que la identidad quede estable desde el primer frame.
        repeat(EMBEDDING_BUFFER_SIZE) { embeddingBuffer.add(embedding.copyOf()) }
        anchorEmbedding = embedding.copyOf()

        identity = SubjectIdentity(
            embedding = embedding,
            lastKnownBox = RectF(tappedBox),
            lastKnownPatch = patch,
            confidence = 1.0f,
            framesLost = 0
        )

        referenceAspectRatio = tappedBox.width() / tappedBox.height().coerceAtLeast(0.001f)
        referenceHeight = tappedBox.height()

        smoothingFilter.reset(tappedBox)
        kalmanFilter.initialize(tappedBox)
        jerseyOcr.reset()
        framesSinceOcr = OCR_INTERVAL_FRAMES // Forzar lectura inmediata.

        // Intentar leer el numero de camiseta al seleccionar.
        jerseyOcr.readFromPatch(patch) { /* resultado se guarda internamente */ }

        cropBox = tappedBox
        state = TrackerState.TRACKING
    }

    fun update(detections: List<RectF>, frame: Bitmap): RectF? {
        val currentIdentity = identity ?: return null

        return when (state) {
            TrackerState.IDLE -> null
            TrackerState.TRACKING -> updateTracking(currentIdentity, detections, frame)
            TrackerState.LOST -> updateLost(currentIdentity, detections, frame)
            TrackerState.RE_IDENTIFYING -> updateReIdentifying(currentIdentity, detections, frame)
        }
    }

    fun reset() {
        state = TrackerState.IDLE
        cropBox = null
        identity = null
        embeddingBuffer.clear()
        anchorEmbedding = null
        smoothingFilter.reset(null)
        kalmanFilter.reset()
        jerseyOcr.reset()
        referenceAspectRatio = 1f
        referenceHeight = 0f
        reIdCandidateBox = null
        reIdConsecutiveCount = 0
        frozenCropBox = null
        framesSinceOcr = 0
    }

    // --- Logica por estado ---

    private fun updateTracking(
        currentIdentity: SubjectIdentity,
        detections: List<RectF>,
        frame: Bitmap
    ): RectF? {
        // Kalman predict: avanzar el estado antes de buscar matches.
        kalmanFilter.predict()

        val bestMatch = findBestTrackingMatch(currentIdentity, detections, frame)

        if (bestMatch == null) {
            state = TrackerState.LOST
            identity = currentIdentity.incrementLost()
            // Congelar el crop en la ultima posicion buena conocida.
            frozenCropBox = cropBox?.let { RectF(it) }
            reIdCandidateBox = null
            reIdConsecutiveCount = 0
            return cropBox
        }

        // Kalman correct: actualizar el estado con la medicion real.
        kalmanFilter.correct(bestMatch.box)

        val patch = embeddingExtractor.cropPatch(frame, bestMatch.box)

        if (shouldUpdateEmbeddingBuffer(currentIdentity, bestMatch.embedding)) {
            addToEmbeddingBuffer(bestMatch.embedding)
        }

        // Actualizar referencia de tamano con EMA para adaptarse a cambios de distancia.
        referenceHeight = referenceHeight * 0.9f + bestMatch.box.height() * 0.1f
        referenceAspectRatio = referenceAspectRatio * 0.9f +
            (bestMatch.box.width() / bestMatch.box.height().coerceAtLeast(0.001f)) * 0.1f

        // OCR periodico: intentar leer el numero cada N frames durante tracking.
        framesSinceOcr++
        if (framesSinceOcr >= OCR_INTERVAL_FRAMES) {
            framesSinceOcr = 0
            jerseyOcr.readFromPatch(patch) { /* resultado se guarda internamente */ }
        }

        identity = currentIdentity.copy(
            embedding = blendedIdentityEmbedding(),
            lastKnownBox = RectF(bestMatch.box),
            lastKnownPatch = patch,
            confidence = 1.0f,
            framesLost = 0
        )

        val smoothed = smoothingFilter.update(bestMatch.box)
        cropBox = smoothed
        return smoothed
    }

    private fun updateLost(
        currentIdentity: SubjectIdentity,
        detections: List<RectF>,
        frame: Bitmap
    ): RectF? {
        if (currentIdentity.isExpired()) {
            reset()
            return null
        }

        identity = currentIdentity.incrementLost()

        // Kalman sigue prediciendo incluso sin mediciones.
        kalmanFilter.predict()

        // Congelar el cropBox en la ultima posicion buena.
        cropBox = frozenCropBox ?: cropBox

        if (detections.isEmpty()) return cropBox

        state = TrackerState.RE_IDENTIFYING
        return updateReIdentifying(identity!!, detections, frame)
    }

    private fun updateReIdentifying(
        currentIdentity: SubjectIdentity,
        detections: List<RectF>,
        frame: Bitmap
    ): RectF? {
        if (currentIdentity.isExpired()) {
            reset()
            return null
        }

        // Usar prediccion de Kalman en vez de simple velocidad.
        val predictedBox = kalmanFilter.predictedBox()
        val predictedCenter = PointF(
            (predictedBox.left + predictedBox.right) / 2f,
            (predictedBox.top + predictedBox.bottom) / 2f
        )

        val bestCandidate = detections
            .filter { box ->
                // Gate espacial para re-ID.
                normalizedCenterDistance(predictedCenter, box) <= MAX_JUMP_DISTANCE_REID
            }
            .map { box ->
                val embedding = embeddingExtractor.extract(frame, box)
                val similarity = robustSimilarity(currentIdentity, embedding)

                val proximity = 1f - normalizedCenterDistance(
                    predictedCenter,
                    box
                ).coerceIn(0f, 1f)

                val sizeSimilarity = computeSizeSimilarity(box)

                var combinedScore = similarity * EMBEDDING_WEIGHT_REID +
                    proximity * PROXIMITY_WEIGHT_REID +
                    sizeSimilarity * SIZE_SIMILARITY_WEIGHT

                // Bonus de OCR: si tenemos numero confirmado, intentar leer del candidato.
                // Esto es asincrono, asi que solo aplicamos el bonus si ya tenemos lectura previa
                // del mismo candidato (via reIdCandidateBox + OCR previo).
                // En la practica, el bonus se aplica en frames posteriores del consecutive-match.

                ReIdCandidate(box, combinedScore, embedding)
            }
            .filter { (_, score, _) ->
                score >= SubjectIdentity.SIMILARITY_THRESHOLD
            }
            .maxByOrNull { (_, score, _) -> score }

        if (bestCandidate == null) {
            identity = currentIdentity.incrementLost()
            reIdConsecutiveCount = 0
            reIdCandidateBox = null
            return cropBox
        }

        val (foundBox, combinedScore, foundEmbedding) = bestCandidate

        // --- Consecutive-match: exigir que el MISMO candidato gane N frames seguidos ---
        val prevCandidate = reIdCandidateBox
        if (prevCandidate != null && calculateIoU(prevCandidate, foundBox) >= REID_SAME_CANDIDATE_IOU) {
            reIdConsecutiveCount++
        } else {
            reIdConsecutiveCount = 1
            // Nuevo candidato: intentar leer OCR para el siguiente frame.
            if (jerseyOcr.confirmedNumber != null) {
                jerseyOcr.readFromFrame(frame, foundBox) { candidateNumber ->
                    // Si el numero coincide, podemos aceptar mas rapido.
                    // El resultado se evaluara en el siguiente frame.
                }
            }
        }
        reIdCandidateBox = RectF(foundBox)

        // Si tenemos OCR confirmado y el candidato tiene el mismo numero,
        // reducir el requisito de frames consecutivos.
        val requiredConsecutive = if (jerseyOcr.confirmedNumber != null) {
            // Con OCR, 2 frames son suficientes (el numero ya confirma identidad).
            2
        } else {
            REID_CONSECUTIVE_REQUIRED
        }

        if (reIdConsecutiveCount < requiredConsecutive) {
            identity = currentIdentity.incrementLost()
            return cropBox
        }

        // Candidato confirmado — aceptar re-identificacion.
        reIdConsecutiveCount = 0
        reIdCandidateBox = null
        frozenCropBox = null

        val patch = embeddingExtractor.cropPatch(frame, foundBox)

        embeddingBuffer.clear()
        repeat(EMBEDDING_BUFFER_SIZE) { embeddingBuffer.add(foundEmbedding.copyOf()) }

        // Kalman correct con la nueva posicion.
        kalmanFilter.correct(foundBox)

        identity = currentIdentity.copy(
            embedding = blendedIdentityEmbedding(),
            lastKnownBox = RectF(foundBox),
            lastKnownPatch = patch,
            confidence = combinedScore,
            framesLost = 0
        )

        smoothingFilter.reset(foundBox)
        cropBox = foundBox
        state = TrackerState.TRACKING

        return foundBox
    }

    // --- Helpers ---

    private fun computeSizeSimilarity(candidateBox: RectF): Float {
        val candidateHeight = candidateBox.height().coerceAtLeast(0.001f)
        val candidateAspect = candidateBox.width() / candidateHeight

        val heightRatio = if (referenceHeight > 0f) {
            val ratio = candidateHeight / referenceHeight
            if (ratio > 1f) 1f / ratio else ratio
        } else 1f

        val aspectRatio = if (referenceAspectRatio > 0f) {
            val ratio = candidateAspect / referenceAspectRatio
            if (ratio > 1f) 1f / ratio else ratio
        } else 1f

        return (heightRatio * 0.6f + aspectRatio * 0.4f).coerceIn(0f, 1f)
    }

    private fun findTappedDetection(tapPoint: PointF, detections: List<RectF>): RectF? {
        if (detections.isEmpty()) return null

        return detections
            .map { box ->
                val distance = minReferenceDistance(tapPoint, box)
                val scoredDistance = if (box.contains(tapPoint.x, tapPoint.y)) {
                    (distance - TAP_INSIDE_BONUS).coerceAtLeast(0f)
                } else {
                    distance
                }
                Pair(box, scoredDistance)
            }
            .filter { (_, distance) -> distance <= MAX_REFERENCE_DISTANCE_FOR_TAP }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    private fun findBestTrackingMatch(
        currentIdentity: SubjectIdentity,
        candidates: List<RectF>,
        frame: Bitmap
    ): TrackingMatch? {
        if (candidates.isEmpty()) return null

        // Usar Kalman prediction en vez de simple velocidad.
        val predictedBox = kalmanFilter.predictedBox()

        return candidates
            .filter { box ->
                normalizedCenterDistance(predictedBox, box) <= MAX_JUMP_DISTANCE_TRACKING
            }
            .map { box ->
                val iou = calculateIoU(predictedBox, box)
                val centerDistance = normalizedCenterDistance(predictedBox, box)
                val sizeSim = computeSizeSimilarity(box)

                val embedding = embeddingExtractor.extract(frame, box)
                val similarity = robustSimilarity(currentIdentity, embedding)

                val proximityTerm = if (iou >= IOU_FAST_MATCH_THRESHOLD) {
                    (1f - centerDistance) * PROXIMITY_WEIGHT_TRACKING * 0.32f
                } else {
                    (1f - centerDistance) * PROXIMITY_WEIGHT_TRACKING
                }

                val score = similarity * EMBEDDING_WEIGHT_TRACKING +
                    proximityTerm +
                    sizeSim * SIZE_SIMILARITY_WEIGHT

                TrackingMatch(box, score, embedding)
            }
            .filter { match -> match.score >= TRACKING_MATCH_THRESHOLD }
            .maxByOrNull { match -> match.score }
    }

    private fun robustSimilarity(currentIdentity: SubjectIdentity, candidateEmbedding: FloatArray): Float {
        val anchorSimilarity = anchorEmbedding?.let { cosineSimilarity(it, candidateEmbedding) } ?: 0f
        val currentSimilarity = currentIdentity.cosineSimilarity(candidateEmbedding)
        return maxOf(currentSimilarity, anchorSimilarity)
    }

    private fun shouldUpdateEmbeddingBuffer(currentIdentity: SubjectIdentity, candidateEmbedding: FloatArray): Boolean {
        val similarity = robustSimilarity(currentIdentity, candidateEmbedding)
        return similarity >= EMBEDDING_UPDATE_SIMILARITY_GATE
    }

    private fun normalizedCenterDistance(reference: RectF, box: RectF): Float {
        val refCenter = PointF((reference.left + reference.right) / 2f, (reference.top + reference.bottom) / 2f)
        return normalizedCenterDistance(refCenter, box)
    }

    private fun normalizedCenterDistance(point: PointF, box: RectF): Float {
        val centerX = (box.left + box.right) / 2f
        val centerY = (box.top + box.bottom) / 2f
        val dx = abs(point.x - centerX)
        val dy = abs(point.y - centerY)
        return (dx + dy) / 2f
    }

    private fun minReferenceDistance(point: PointF, box: RectF): Float {
        val points = referencePoints(box)
        return points.minOf { ref -> euclideanDistance(point, ref) }
    }

    private fun referencePoints(box: RectF): List<PointF> {
        val centerX = (box.left + box.right) / 2f
        val centerY = (box.top + box.bottom) / 2f
        val quarterY = box.top + box.height() * 0.25f
        val threeQuarterY = box.top + box.height() * 0.75f

        return listOf(
            PointF(centerX, centerY),
            PointF(centerX, box.top),
            PointF(centerX, box.bottom),
            PointF(box.left, centerY),
            PointF(box.right, centerY),
            PointF(box.left, quarterY),
            PointF(box.right, quarterY),
            PointF(box.left, threeQuarterY),
            PointF(box.right, threeQuarterY)
        )
    }

    private fun euclideanDistance(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val intersectLeft = maxOf(a.left, b.left)
        val intersectTop = maxOf(a.top, b.top)
        val intersectRight = minOf(a.right, b.right)
        val intersectBottom = minOf(a.bottom, b.bottom)

        if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) return 0f

        val intersectArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val aArea = a.width() * a.height()
        val bArea = b.width() * b.height()
        val unionArea = aArea + bArea - intersectArea

        return if (unionArea <= 0f) 0f else intersectArea / unionArea
    }

    private fun addToEmbeddingBuffer(embedding: FloatArray) {
        embeddingBuffer.add(embedding)
        if (embeddingBuffer.size > EMBEDDING_BUFFER_SIZE) {
            embeddingBuffer.removeAt(0)
        }
    }

    private fun averageEmbedding(): FloatArray {
        if (embeddingBuffer.isEmpty()) return FloatArray(EmbeddingExtractor.TOTAL_BINS)
        val size = embeddingBuffer.first().size
        val result = FloatArray(size)
        for (embedding in embeddingBuffer) {
            for (i in embedding.indices) {
                result[i] += embedding[i]
            }
        }
        val count = embeddingBuffer.size.toFloat()
        return FloatArray(size) { result[it] / count }
    }

    private fun blendedIdentityEmbedding(): FloatArray {
        val averaged = averageEmbedding()
        val anchor = anchorEmbedding ?: return averaged
        return FloatArray(averaged.size) { i ->
            averaged[i] * (1f - ANCHOR_BLEND_WEIGHT) + anchor[i] * ANCHOR_BLEND_WEIGHT
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embeddings de distinto tamano: ${a.size} vs ${b.size}" }

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        if (normA == 0f || normB == 0f) return 0f

        return dotProduct / (sqrt(normA) * sqrt(normB))
    }
}

private data class TrackingMatch(
    val box: RectF,
    val score: Float,
    val embedding: FloatArray
)

private data class ReIdCandidate(
    val box: RectF,
    val score: Float,
    val embedding: FloatArray
)

/**
 * Estados posibles del tracker.
 */
enum class TrackerState {
    IDLE,
    TRACKING,
    LOST,
    RE_IDENTIFYING
}
