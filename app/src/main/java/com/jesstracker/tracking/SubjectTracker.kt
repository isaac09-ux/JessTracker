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
        private const val EMBEDDING_WEIGHT_TRACKING = 0.82f
        private const val PROXIMITY_WEIGHT_TRACKING = 0.18f
        private const val EMBEDDING_WEIGHT_REID = 0.72f
        private const val PROXIMITY_WEIGHT_REID = 0.16f

        // Umbral de IoU para modular la penalizacion por proximidad en tracking continuo.
        private const val IOU_FAST_MATCH_THRESHOLD = 0.35f
        // Score minimo para aceptar un candidato en tracking continuo.
        private const val TRACKING_MATCH_THRESHOLD = 0.52f

        // Cuantos frames de velocidad reciente guardar para prediccion suavizada.
        private const val VELOCITY_HISTORY_SIZE = 5
    }

    // --- Estado publico ---

    var state: TrackerState = TrackerState.IDLE
        private set

    var cropBox: RectF? = null
        private set

    var identity: SubjectIdentity? = null
        private set

    // --- Dependencias internas ---

    private val embeddingExtractor = EmbeddingExtractor()
    private val smoothingFilter = SmoothingFilter()

    private val embeddingBuffer = mutableListOf<FloatArray>()
    private var anchorEmbedding: FloatArray? = null

    private var lastCenterX: Float? = null
    private var lastCenterY: Float? = null
    private var velocityX: Float = 0f
    private var velocityY: Float = 0f

    // Historial de velocidades para prediccion suavizada (evita saltos por jitter).
    private val velocityHistoryX = FloatArray(VELOCITY_HISTORY_SIZE)
    private val velocityHistoryY = FloatArray(VELOCITY_HISTORY_SIZE)
    private var velocityIndex = 0
    private var velocitySamples = 0

    // Aspect ratio del sujeto seleccionado, para filtrar candidatos de tamano muy diferente.
    private var referenceAspectRatio: Float = 1f
    private var referenceHeight: Float = 0f

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
        seedMotion(tappedBox)
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
        lastCenterX = null
        lastCenterY = null
        velocityX = 0f
        velocityY = 0f
        velocityIndex = 0
        velocitySamples = 0
        referenceAspectRatio = 1f
        referenceHeight = 0f
    }

    // --- Logica por estado ---

    private fun updateTracking(
        currentIdentity: SubjectIdentity,
        detections: List<RectF>,
        frame: Bitmap
    ): RectF? {
        val bestMatch = findBestTrackingMatch(currentIdentity, detections, frame)

        if (bestMatch == null) {
            state = TrackerState.LOST
            identity = currentIdentity.incrementLost()
            return cropBox
        }

        updateMotion(bestMatch.box)

        val patch = embeddingExtractor.cropPatch(frame, bestMatch.box)

        if (shouldUpdateEmbeddingBuffer(currentIdentity, bestMatch.embedding)) {
            addToEmbeddingBuffer(bestMatch.embedding)
        }

        // Actualizar referencia de tamano con EMA para adaptarse a cambios de distancia.
        referenceHeight = referenceHeight * 0.9f + bestMatch.box.height() * 0.1f
        referenceAspectRatio = referenceAspectRatio * 0.9f +
            (bestMatch.box.width() / bestMatch.box.height().coerceAtLeast(0.001f)) * 0.1f

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

        // Actualizar cropBox con prediccion de velocidad para que el overlay
        // siga moviéndose hacia donde iba el sujeto (en vez de congelarse).
        cropBox = predictNextBox(currentIdentity.lastKnownBox)

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

        val lastKnown = currentIdentity.lastKnownBox

        val bestCandidate = detections
            .map { box ->
                val embedding = embeddingExtractor.extract(frame, box)
                val similarity = robustSimilarity(currentIdentity, embedding)

                // Proximidad a la ultima posicion conocida (o predicha por velocidad).
                val predictedPos = predictNextBox(lastKnown)
                val proximity = 1f - normalizedCenterDistance(
                    PointF(
                        (predictedPos.left + predictedPos.right) / 2f,
                        (predictedPos.top + predictedPos.bottom) / 2f
                    ),
                    box
                ).coerceIn(0f, 1f)

                // Similitud de tamano: penalizar candidatos de tamano muy diferente.
                val sizeSimilarity = computeSizeSimilarity(box)

                val combinedScore = similarity * EMBEDDING_WEIGHT_REID +
                    proximity * PROXIMITY_WEIGHT_REID +
                    sizeSimilarity * SIZE_SIMILARITY_WEIGHT
                ReIdCandidate(box, combinedScore, embedding)
            }
            .filter { (_, score, _) ->
                score >= SubjectIdentity.SIMILARITY_THRESHOLD
            }
            .maxByOrNull { (_, score, _) -> score }

        if (bestCandidate == null) {
            identity = currentIdentity.incrementLost()
            return cropBox
        }

        val (foundBox, combinedScore, foundEmbedding) = bestCandidate

        // Reutilizamos el embedding ya calculado durante el scoring; solo re-cropeamos
        // para obtener el Bitmap que guarda SubjectIdentity (es barato vs extractFromPatch).
        val patch = embeddingExtractor.cropPatch(frame, foundBox)

        embeddingBuffer.clear()
        repeat(EMBEDDING_BUFFER_SIZE) { embeddingBuffer.add(foundEmbedding.copyOf()) }

        identity = currentIdentity.copy(
            embedding = blendedIdentityEmbedding(),
            lastKnownBox = RectF(foundBox),
            lastKnownPatch = patch,
            confidence = combinedScore,
            framesLost = 0
        )

        smoothingFilter.reset(foundBox)
        seedMotion(foundBox)
        cropBox = foundBox
        state = TrackerState.TRACKING

        return foundBox
    }

    // --- Helpers ---

    private fun computeSizeSimilarity(candidateBox: RectF): Float {
        val candidateHeight = candidateBox.height().coerceAtLeast(0.001f)
        val candidateAspect = candidateBox.width() / candidateHeight

        // Ratio entre alturas (1.0 = mismo tamano, <1.0 = diferente).
        val heightRatio = if (referenceHeight > 0f) {
            val ratio = candidateHeight / referenceHeight
            if (ratio > 1f) 1f / ratio else ratio
        } else 1f

        // Similitud de aspect ratio.
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

        val predictedBox = predictNextBox(currentIdentity.lastKnownBox)

        return candidates
            .map { box ->
                val iou = calculateIoU(predictedBox, box)
                val centerDistance = normalizedCenterDistance(predictedBox, box)
                val sizeSim = computeSizeSimilarity(box)

                // Siempre validar embedding para evitar saltos de lock entre sujetos.
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
            PointF(centerX, centerY),      // centro
            PointF(centerX, box.top),      // cabeza / parte superior
            PointF(centerX, box.bottom),   // pies / parte inferior
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

    private fun seedMotion(box: RectF) {
        lastCenterX = (box.left + box.right) / 2f
        lastCenterY = (box.top + box.bottom) / 2f
        velocityX = 0f
        velocityY = 0f
        velocityIndex = 0
        velocitySamples = 0
        velocityHistoryX.fill(0f)
        velocityHistoryY.fill(0f)
    }

    private fun updateMotion(box: RectF) {
        val centerX = (box.left + box.right) / 2f
        val centerY = (box.top + box.bottom) / 2f

        val previousX = lastCenterX ?: centerX
        val previousY = lastCenterY ?: centerY

        val dx = centerX - previousX
        val dy = centerY - previousY

        // Guardar en historial circular.
        velocityHistoryX[velocityIndex % VELOCITY_HISTORY_SIZE] = dx
        velocityHistoryY[velocityIndex % VELOCITY_HISTORY_SIZE] = dy
        velocityIndex++
        velocitySamples = minOf(velocitySamples + 1, VELOCITY_HISTORY_SIZE)

        // Velocidad suavizada = promedio del historial reciente.
        velocityX = velocityHistoryX.take(velocitySamples).average().toFloat()
        velocityY = velocityHistoryY.take(velocitySamples).average().toFloat()

        lastCenterX = centerX
        lastCenterY = centerY
    }

    private fun predictNextBox(reference: RectF): RectF {
        val predictedLeft = (reference.left + velocityX).coerceIn(0f, 1f)
        val predictedTop = (reference.top + velocityY).coerceIn(0f, 1f)
        val predictedRight = (reference.right + velocityX).coerceIn(0f, 1f)
        val predictedBottom = (reference.bottom + velocityY).coerceIn(0f, 1f)

        return RectF(predictedLeft, predictedTop, predictedRight, predictedBottom)
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
