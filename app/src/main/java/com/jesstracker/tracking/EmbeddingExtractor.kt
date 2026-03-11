package com.jesstracker.tracking

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF

/**
 * EmbeddingExtractor — convierte un recorte de imagen en un vector numerico.
 *
 * Pipeline:
 *   1) Crop de la jugadora detectada.
 *   2) Segmentacion rapida de silueta (foreground mask) para quitar fondo.
 *   3) Histograma HSV por zonas del cuerpo.
 *
 * Mejora v2: Usa Hue + Value (brillo) con 4 zonas verticales.
 * Esto permite distinguir jugadoras con la misma camiseta pero diferente
 * tono de piel, color de pelo, tipo de shorts, etc.
 *
 * Dimensiones: (HUE_BINS + VALUE_BINS) * NUM_ZONES = (24 + 16) * 4 = 160
 */
class EmbeddingExtractor {

    companion object {
        private const val HUE_BINS = 24
        private const val VALUE_BINS = 16
        private const val BINS_PER_ZONE = HUE_BINS + VALUE_BINS
        private const val NUM_ZONES = 4
        const val TOTAL_BINS = BINS_PER_ZONE * NUM_ZONES  // 160

        private const val HUE_RANGE = 360f
        private const val SATURATION_THRESHOLD = 0.12f
        private const val VALUE_THRESHOLD = 0.12f
        private const val EDGE_SUPPRESSION = 0.18f
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

        try {
            val foregroundMask = buildForegroundMask(resized)
            val embedding = FloatArray(TOTAL_BINS)
            val zoneHeight = resized.height / NUM_ZONES
            val hsv = FloatArray(3)

            for (y in 0 until resized.height) {
                val zone = minOf(y / zoneHeight, NUM_ZONES - 1)
                val zoneOffset = zone * BINS_PER_ZONE

                for (x in 0 until resized.width) {
                    if (!foregroundMask[y * resized.width + x]) continue

                    val pixel = resized.getPixel(x, y)
                    Color.colorToHSV(pixel, hsv)

                    val hue = hsv[0]
                    val saturation = hsv[1]
                    val value = hsv[2]

                    // Canal Hue: solo si hay suficiente saturacion (evita grises/blancos).
                    if (saturation > SATURATION_THRESHOLD) {
                        val hueBin = (hue / HUE_RANGE * HUE_BINS).toInt()
                            .coerceIn(0, HUE_BINS - 1)
                        embedding[zoneOffset + hueBin]++
                    }

                    // Canal Value (brillo): siempre contribuye.
                    // Distingue piel clara vs oscura, pelo negro vs rubio, etc.
                    val valueBin = (value * VALUE_BINS).toInt()
                        .coerceIn(0, VALUE_BINS - 1)
                    embedding[zoneOffset + HUE_BINS + valueBin]++
                }
            }

            return normalizeL1(embedding)
        } finally {
            if (resized !== patch) {
                resized.recycle()
            }
        }
    }

    fun cropPatch(frame: Bitmap, box: RectF): Bitmap {
        val frameW = frame.width.toFloat()
        val frameH = frame.height.toFloat()

        val left = (box.left * frameW).toInt().coerceIn(0, frame.width - 1)
        val top = (box.top * frameH).toInt().coerceIn(0, frame.height - 1)
        val right = (box.right * frameW).toInt().coerceIn(left + 1, frame.width)
        val bottom = (box.bottom * frameH).toInt().coerceIn(top + 1, frame.height)

        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)

        return Bitmap.createBitmap(frame, left, top, width, height)
    }

    private fun buildForegroundMask(patch: Bitmap): BooleanArray {
        val width = patch.width
        val height = patch.height
        val mask = BooleanArray(width * height)
        val hsv = FloatArray(3)

        for (y in 0 until height) {
            val normalizedY = y.toFloat() / height
            val expectedBodyWidth = 0.75f - normalizedY * 0.15f

            for (x in 0 until width) {
                val normalizedX = x.toFloat() / width
                val centerDistance = kotlin.math.abs(normalizedX - 0.5f)
                val nearBorders = centerDistance > expectedBodyWidth / 2f + EDGE_SUPPRESSION
                if (nearBorders) continue

                val pixel = patch.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)

                val saturation = hsv[1]
                val value = hsv[2]
                if (saturation >= SATURATION_THRESHOLD || value >= VALUE_THRESHOLD) {
                    mask[y * width + x] = true
                }
            }
        }

        return mask
    }

    private fun normalizeL1(array: FloatArray): FloatArray {
        val sum = array.sum()
        if (sum == 0f) return array
        return FloatArray(array.size) { array[it] / sum }
    }
}
