package com.jesstracker.tracking

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF

/**
 * EmbeddingExtractor — convierte un recorte de imagen en un vector de 96 numeros.
 *
 * Usa histograma HSV dividido en 3 zonas verticales del cuerpo:
 *   - Zona alta (cabeza/torso superior): bins 0-31
 *   - Zona media (torso inferior): bins 32-63
 *   - Zona baja (piernas): bins 64-95
 */
class EmbeddingExtractor {

    companion object {
        private const val BINS_PER_ZONE = 32
        private const val TOTAL_BINS = 96
        private const val HUE_RANGE = 360f
    }

    fun extract(frame: Bitmap, box: RectF): FloatArray {
        val patch = cropPatch(frame, box)
        return extractFromPatch(patch)
    }

    fun extractFromPatch(patch: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(
            patch,
            SubjectIdentity.PATCH_WIDTH,
            SubjectIdentity.PATCH_HEIGHT,
            true
        )

        val embedding = FloatArray(TOTAL_BINS)
        val zoneHeight = resized.height / 3
        val hsv = FloatArray(3)

        for (y in 0 until resized.height) {
            val zone = minOf(y / zoneHeight, 2)
            val zoneOffset = zone * BINS_PER_ZONE

            for (x in 0 until resized.width) {
                val pixel = resized.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)

                val hue = hsv[0]
                val saturation = hsv[1]

                if (saturation > 0.15f) {
                    val bin = (hue / HUE_RANGE * BINS_PER_ZONE).toInt()
                        .coerceIn(0, BINS_PER_ZONE - 1)
                    embedding[zoneOffset + bin]++
                }
            }
        }

        return normalizeL1(embedding)
    }

    fun cropPatch(frame: Bitmap, box: RectF): Bitmap {
        val frameW = frame.width.toFloat()
        val frameH = frame.height.toFloat()

        val left   = (box.left   * frameW).toInt().coerceIn(0, frame.width - 1)
        val top    = (box.top    * frameH).toInt().coerceIn(0, frame.height - 1)
        val right  = (box.right  * frameW).toInt().coerceIn(left + 1, frame.width)
        val bottom = (box.bottom * frameH).toInt().coerceIn(top + 1, frame.height)

        val width  = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)

        return Bitmap.createBitmap(frame, left, top, width, height)
    }

    private fun normalizeL1(array: FloatArray): FloatArray {
        val sum = array.sum()
        if (sum == 0f) return array
        return FloatArray(array.size) { array[it] / sum }
    }
}
