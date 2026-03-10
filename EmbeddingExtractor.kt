package com.jesstracker.tracking

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF

/**
 * EmbeddingExtractor — convierte un recorte de imagen en un vector de 96 números.
 *
 * Usa histograma HSV dividido en 3 zonas verticales del cuerpo:
 *   - Zona alta (cabeza/torso superior): bins 0-31
 *   - Zona media (torso inferior): bins 32-63
 *   - Zona baja (piernas): bins 64-95
 *
 * Por qué HSV y no RGB:
 *   - HSV es más robusto a cambios de iluminación (gimnasios con luz inconsistente)
 *   - El canal Hue captura el color real independientemente del brillo
 *
 * Por qué 3 zonas:
 *   - En voleibol Jess puede tener jersey de un color y shorts de otro
 *   - Las zonas capturan esa distribución de colores verticalmente
 */
class EmbeddingExtractor {

    companion object {
        private const val BINS_PER_ZONE = 32       // bins de hue por zona
        private const val TOTAL_BINS = 96           // 32 * 3 zonas
        private const val HUE_RANGE = 360f
    }

    /**
     * Extrae embedding de un frame completo + bounding box.
     *
     * @param frame  El frame completo de la cámara (Bitmap)
     * @param box    Bounding box normalizado (0.0-1.0) de la persona detectada
     * @return       FloatArray de 96 valores normalizados (suma = 1.0)
     */
    fun extract(frame: Bitmap, box: RectF): FloatArray {
        val patch = cropPatch(frame, box)
        return extractFromPatch(patch)
    }

    /**
     * Extrae embedding directamente de un patch ya recortado.
     */
    fun extractFromPatch(patch: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(
            patch,
            SubjectIdentity.PATCH_WIDTH,
            SubjectIdentity.PATCH_HEIGHT,
            true
        )

        val embedding = FloatArray(TOTAL_BINS)
        val zoneHeight = resized.height / 3

        for (y in 0 until resized.height) {
            val zone = minOf(y / zoneHeight, 2) // zona 0, 1 o 2
            val zoneOffset = zone * BINS_PER_ZONE

            for (x in 0 until resized.width) {
                val pixel = resized.getPixel(x, y)
                val hue = pixelToHue(pixel)
                val saturation = pixelToSaturation(pixel)

                // Ignorar píxeles muy oscuros o desaturados (fondos, sombras)
                if (saturation > 0.15f) {
                    val bin = (hue / HUE_RANGE * BINS_PER_ZONE).toInt()
                        .coerceIn(0, BINS_PER_ZONE - 1)
                    embedding[zoneOffset + bin]++
                }
            }
        }

        return normalizeL1(embedding)
    }

    /**
     * Recorta el patch de la persona del frame completo.
     * Agrega padding vertical para capturar contexto del cuerpo completo.
     */
    fun cropPatch(frame: Bitmap, box: RectF): Bitmap {
        val frameW = frame.width.toFloat()
        val frameH = frame.height.toFloat()

        // Convertir coordenadas normalizadas a píxeles
        val left   = (box.left   * frameW).toInt().coerceIn(0, frame.width - 1)
        val top    = (box.top    * frameH).toInt().coerceIn(0, frame.height - 1)
        val right  = (box.right  * frameW).toInt().coerceIn(left + 1, frame.width)
        val bottom = (box.bottom * frameH).toInt().coerceIn(top + 1, frame.height)

        val width  = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)

        return Bitmap.createBitmap(frame, left, top, width, height)
    }

    // ─── Helpers de color ────────────────────────────────────────────────────

    private fun pixelToHue(pixel: Int): Float {
        val r = Color.red(pixel) / 255f
        val g = Color.green(pixel) / 255f
        val b = Color.blue(pixel) / 255f

        val hsv = FloatArray(3)
        Color.RGBToHSV(
            (r * 255).toInt(),
            (g * 255).toInt(),
            (b * 255).toInt(),
            hsv
        )
        return hsv[0] // hue 0-360
    }

    private fun pixelToSaturation(pixel: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)
        return hsv[1] // saturation 0-1
    }

    /**
     * Normalización L1 — divide cada bin por la suma total.
     * Hace que el histograma sea independiente del tamaño del patch.
     */
    private fun normalizeL1(array: FloatArray): FloatArray {
        val sum = array.sum()
        if (sum == 0f) return array
        return FloatArray(array.size) { array[it] / sum }
    }
}
