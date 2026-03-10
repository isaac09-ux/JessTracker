package com.jesstracker.tracking

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * SubjectIdentity — el "punto" visual de Jess.
 *
 * Guarda todo lo necesario para encontrarla aunque salga del frame:
 * - embedding: vector de características visuales (su "huella")
 * - lastKnownBox: última posición en pantalla
 * - lastKnownPatch: último recorte de imagen de su cuerpo
 * - confidence: qué tan seguro está el tracker de haberla encontrado
 * - framesLost: cuántos frames lleva sin ser vista
 */
data class SubjectIdentity(

    // Vector de características extraídas del patch visual de Jess.
    // Cada float representa una característica visual (color, textura, forma).
    // Usaremos histograma HSV de 96 bins: 32 hue + 32 sat + 32 val
    val embedding: FloatArray,

    // Último bounding box conocido (coordenadas normalizadas 0.0 - 1.0)
    val lastKnownBox: RectF,

    // Último recorte visual de Jess — para debugging y re-extracción si se necesita
    val lastKnownPatch: Bitmap,

    // Confianza del último match (0.0 = no hay certeza, 1.0 = certeza total)
    val confidence: Float = 1.0f,

    // Cuántos frames consecutivos lleva sin ser detectada
    val framesLost: Int = 0

) {
    companion object {

        // Umbral de similitud para considerar que una persona ES Jess
        // 0.85 = 85% de similitud mínima → ajustable según condiciones de luz
        const val SIMILARITY_THRESHOLD = 0.85f

        // Máximo de frames perdidos antes de resetear el tracker completamente
        // A 60fps → 180 frames = 3 segundos buscando antes de rendirse
        const val MAX_FRAMES_LOST = 180

        // Tamaño del patch que recortamos para calcular el embedding
        // 64x128 es estándar en re-identificación de personas (ratio 1:2)
        const val PATCH_WIDTH = 64
        const val PATCH_HEIGHT = 128
    }

    /**
     * Calcula similitud coseno entre este embedding y otro.
     * Resultado entre 0.0 (completamente diferente) y 1.0 (idéntico).
     *
     * Similitud coseno = (A · B) / (|A| * |B|)
     * Es más robusta que distancia euclidiana para comparar apariencia visual.
     */
    fun cosineSimilarity(other: FloatArray): Float {
        require(embedding.size == other.size) {
            "Embeddings de distinto tamaño: ${embedding.size} vs ${other.size}"
        }

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in embedding.indices) {
            dotProduct += embedding[i] * other[i]
            normA += embedding[i] * embedding[i]
            normB += other[i] * other[i]
        }

        // Evitar división por cero si algún vector es todo ceros
        if (normA == 0f || normB == 0f) return 0f

        return dotProduct / (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())).toFloat()
    }

    /**
     * ¿Está Jess actualmente perdida?
     */
    fun isLost(): Boolean = framesLost > 0

    /**
     * ¿Llevamos demasiado tiempo sin verla? → resetear tracker
     */
    fun isExpired(): Boolean = framesLost >= MAX_FRAMES_LOST

    /**
     * Devuelve una copia actualizada con un frame más perdido.
     * Usamos copy() para mantener inmutabilidad (patrón funcional).
     */
    fun incrementLost(): SubjectIdentity = copy(
        framesLost = framesLost + 1,
        confidence = confidence * 0.98f // confianza decae suavemente con el tiempo
    )
}
