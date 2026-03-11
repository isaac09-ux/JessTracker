package com.jesstracker.camera

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Analisis de luminosidad por frame.
 */
class LightAnalyzer {

    companion object {
        private const val SAMPLE_GRID = 24
        private const val BRIGHTNESS_SMOOTHING = 0.18f
    }

    private var smoothedBrightness: Float = 0.5f

    fun analyze(frame: Bitmap): LightInfo {
        val measured = estimateBrightness(frame)
        smoothedBrightness += (measured - smoothedBrightness) * BRIGHTNESS_SMOOTHING
        val level = LightLevel.fromBrightness(smoothedBrightness)
        return LightInfo(level, smoothedBrightness)
    }

    private fun estimateBrightness(bitmap: Bitmap): Float {
        val stepX = (bitmap.width / SAMPLE_GRID).coerceAtLeast(1)
        val stepY = (bitmap.height / SAMPLE_GRID).coerceAtLeast(1)

        var sumLuma = 0f
        var count = 0
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val luma = (0.2126f * Color.red(pixel) +
                    0.7152f * Color.green(pixel) +
                    0.0722f * Color.blue(pixel)) / 255f
                sumLuma += luma
                count++
            }
        }

        return if (count == 0) 0.5f else (sumLuma / count).coerceIn(0f, 1f)
    }
}

enum class LightLevel {
    BRIGHT,
    NORMAL,
    LOW,
    ULTRA_LOW;

    companion object {
        fun fromBrightness(brightness: Float): LightLevel {
            return when {
                brightness < 0.16f -> ULTRA_LOW
                brightness < 0.30f -> LOW
                brightness < 0.55f -> NORMAL
                else -> BRIGHT
            }
        }
    }
}

data class LightInfo(
    val level: LightLevel,
    val brightness: Float
)
