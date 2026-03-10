package com.jesstracker.tracking

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.sqrt

/**
 * SubjectIdentity — la huella visual del sujeto seleccionado.
 *
 * Guarda todo lo necesario para re-identificar al sujeto:
 * - embedding: vector de caracteristicas visuales (histograma HSV 96-dim)
 * - lastKnownBox: ultima posicion en pantalla
 * - lastKnownPatch: ultimo recorte de imagen
 * - confidence: que tan seguro esta el tracker
 * - framesLost: cuantos frames lleva sin ser visto
 *
 * Nota: No es data class porque FloatArray y Bitmap no implementan
 * equals/hashCode por contenido. Usamos copy() manual.
 */
class SubjectIdentity(
    val embedding: FloatArray,
    val lastKnownBox: RectF,
    val lastKnownPatch: Bitmap,
    val confidence: Float = 1.0f,
    val framesLost: Int = 0
) {
    companion object {
        const val SIMILARITY_THRESHOLD = 0.85f
        const val MAX_FRAMES_LOST = 180
        const val PATCH_WIDTH = 64
        const val PATCH_HEIGHT = 128
    }

    fun cosineSimilarity(other: FloatArray): Float {
        require(embedding.size == other.size) {
            "Embeddings de distinto tamano: ${embedding.size} vs ${other.size}"
        }

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in embedding.indices) {
            dotProduct += embedding[i] * other[i]
            normA += embedding[i] * embedding[i]
            normB += other[i] * other[i]
        }

        if (normA == 0f || normB == 0f) return 0f

        return dotProduct / (sqrt(normA) * sqrt(normB))
    }

    fun isLost(): Boolean = framesLost > 0

    fun isExpired(): Boolean = framesLost >= MAX_FRAMES_LOST

    fun incrementLost(): SubjectIdentity = copy(
        framesLost = framesLost + 1,
        confidence = confidence * 0.98f
    )

    fun copy(
        embedding: FloatArray = this.embedding,
        lastKnownBox: RectF = this.lastKnownBox,
        lastKnownPatch: Bitmap = this.lastKnownPatch,
        confidence: Float = this.confidence,
        framesLost: Int = this.framesLost
    ): SubjectIdentity = SubjectIdentity(
        embedding = embedding,
        lastKnownBox = lastKnownBox,
        lastKnownPatch = lastKnownPatch,
        confidence = confidence,
        framesLost = framesLost
    )
}
