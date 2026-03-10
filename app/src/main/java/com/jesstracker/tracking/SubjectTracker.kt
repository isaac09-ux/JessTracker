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

    private var lastCenterX: Float? = null
    private var lastCenterY: Float? = null
    private var velocityX: Float = 0f
    private var velocityY: Float = 0f

    // --- API publica ---

    fun onTap(tapPoint: PointF, detections: List<RectF>, frame: Bitmap) {
        val tappedBox = findTappedDetection(tapPoint, detections) ?: return

        val patch = embeddingExtractor.cropPatch(frame, tappedBox)
        val embedding = embeddingExtractor.extractFromPatch(patch)

        embeddingBuffer.clear()
        // Sembramos el buffer para que la identidad quede estable desde el primer frame.
        repeat(EMBEDDING_BUFFER_SIZE) { embeddingBuffer.add(embedding.copyOf()) }

        identity = SubjectIdentity(
            embedding = embedding,
            lastKnownBox = RectF(tappedBox),
            lastKnownPatch = patch,
            confidence = 1.0f,
            framesLost = 0
        )

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
        smoothingFilter.reset(null)
        lastCenterX = null
        lastCenterY = null
        velocityX = 0f
        velocityY = 0f
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

        updateMotion(bestMatch)

        // Extraer embedding y patch una sola vez (antes se hacia doble).
        val patch = embeddingExtractor.cropPatch(frame, bestMatch)
        val embedding = embeddingExtractor.extractFromPatch(patch)
        addToEmbeddingBuffer(embedding)

        identity = currentIdentity.copy(
            embedding = averageEmbedding(),
            lastKnownBox = RectF(bestMatch),
            lastKnownPatch = patch,
            confidence = 1.0f,
            framesLost = 0
        )

        val smoothed = smoothingFilter.update(bestMatch)
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
                val similarity = currentIdentity.cosineSimilarity(embedding)

                // Usar proximidad a la ultima posicion conocida como factor secundario.
                // Un jugador que desaparecio probablemente reaparezca cerca de donde estaba.
                val proximity = 1f - normalizedCenterDistance(
                    PointF(
                        (lastKnown.left + lastKnown.right) / 2f,
                        (lastKnown.top + lastKnown.bottom) / 2f
                    ),
                    box
                ).coerceIn(0f, 1f)

                val combinedScore = similarity * 0.80f + proximity * 0.20f
                Triple(box, combinedScore, embedding)
            }
            .filter { (_, score, _) ->
                score >= SubjectIdentity.SIMILARITY_THRESHOLD
            }
            .maxByOrNull { (_, score, _) -> score }

        if (bestCandidate == null) {
            identity = currentIdentity.incrementLost()
            return cropBox
        }

        val (foundBox, combinedScore, _) = bestCandidate

        // Extraer embedding limpio del patch final (no reusar el de comparacion
        // porque este viene del resize a PATCH_WIDTH/HEIGHT).
        val patch = embeddingExtractor.cropPatch(frame, foundBox)
        val newEmbedding = embeddingExtractor.extractFromPatch(patch)

        embeddingBuffer.clear()
        repeat(EMBEDDING_BUFFER_SIZE) { embeddingBuffer.add(newEmbedding.copyOf()) }

        identity = currentIdentity.copy(
            embedding = newEmbedding,
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
    ): RectF? {
        if (candidates.isEmpty()) return null

        val predictedBox = predictNextBox(currentIdentity.lastKnownBox)

        return candidates
            .map { box ->
                val iou = calculateIoU(predictedBox, box)
                val centerDistance = normalizedCenterDistance(predictedBox, box)

                val score = if (iou >= 0.10f) {
                    iou * 0.9f + (1f - centerDistance) * 0.1f
                } else {
                    val embedding = embeddingExtractor.extract(frame, box)
                    val similarity = currentIdentity.cosineSimilarity(embedding)
                    similarity * 0.75f + (1f - centerDistance) * 0.25f
                }

                Pair(box, score)
            }
            .filter { (_, score) -> score >= 0.37f }
            .maxByOrNull { (_, score) -> score }
            ?.first
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
    }

    private fun updateMotion(box: RectF) {
        val centerX = (box.left + box.right) / 2f
        val centerY = (box.top + box.bottom) / 2f

        val previousX = lastCenterX ?: centerX
        val previousY = lastCenterY ?: centerY

        velocityX = centerX - previousX
        velocityY = centerY - previousY

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
}

/**
 * Estados posibles del tracker.
 */
enum class TrackerState {
    IDLE,
    TRACKING,
    LOST,
    RE_IDENTIFYING
}
