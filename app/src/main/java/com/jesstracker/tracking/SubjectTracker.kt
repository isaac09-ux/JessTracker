package com.jesstracker.tracking

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF

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
    private val EMBEDDING_BUFFER_SIZE = 5

    // --- API publica ---

    fun onTap(tapPoint: PointF, detections: List<RectF>, frame: Bitmap) {
        val tappedBox = findTappedDetection(tapPoint, detections) ?: return

        val patch = embeddingExtractor.cropPatch(frame, tappedBox)
        val embedding = embeddingExtractor.extractFromPatch(patch)

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
    }

    // --- Logica por estado ---

    private fun updateTracking(
        currentIdentity: SubjectIdentity,
        detections: List<RectF>,
        frame: Bitmap
    ): RectF? {
        val bestMatch = findBestIoUMatch(currentIdentity.lastKnownBox, detections)

        if (bestMatch == null) {
            state = TrackerState.LOST
            identity = currentIdentity.incrementLost()
            return cropBox
        }

        updateEmbeddingBuffer(bestMatch, frame)

        val patch = embeddingExtractor.cropPatch(frame, bestMatch)
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
            identity = currentIdentity.incrementLost()
            return cropBox
        }

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

    // --- Helpers ---

    private fun findTappedDetection(tapPoint: PointF, detections: List<RectF>): RectF? {
        return detections.firstOrNull { box -> box.contains(tapPoint.x, tapPoint.y) }
    }

    private fun findBestIoUMatch(reference: RectF, candidates: List<RectF>): RectF? {
        return candidates
            .map { box -> Pair(box, calculateIoU(reference, box)) }
            .filter { (_, iou) -> iou >= 0.3f }
            .maxByOrNull { (_, iou) -> iou }
            ?.first
    }

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

    private fun updateEmbeddingBuffer(box: RectF, frame: Bitmap) {
        val embedding = embeddingExtractor.extract(frame, box)
        embeddingBuffer.add(embedding)
        if (embeddingBuffer.size > EMBEDDING_BUFFER_SIZE) {
            embeddingBuffer.removeAt(0)
        }
    }

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
    IDLE,
    TRACKING,
    LOST,
    RE_IDENTIFYING
}
