package com.jesstracker.tracking

import android.graphics.RectF

/**
 * SmoothingFilter — suaviza el movimiento del crop usando EMA adaptativo.
 *
 * EMA (Exponential Moving Average) pondera el frame actual contra
 * el historial reciente. Sin esto, el crop brincaria bruscamente
 * cada vez que MediaPipe ajusta el bounding box.
 *
 * Formula: smoothed = alpha * nuevo + (1 - alpha) * anterior
 */
class SmoothingFilter {

    companion object {
        const val FAST_ALPHA = 0.8f
        const val SLOW_ALPHA = 0.3f
        const val VELOCITY_THRESHOLD = 0.02f
    }

    private var previousBox: RectF? = null
    private var velocity: Float = 0f

    fun update(newBox: RectF): RectF {
        val prev = previousBox

        if (prev == null) {
            previousBox = RectF(newBox)
            return RectF(newBox)
        }

        val prevCenterX = (prev.left + prev.right) / 2f
        val prevCenterY = (prev.top + prev.bottom) / 2f
        val newCenterX  = (newBox.left + newBox.right) / 2f
        val newCenterY  = (newBox.top + newBox.bottom) / 2f

        velocity = Math.sqrt(
            ((newCenterX - prevCenterX) * (newCenterX - prevCenterX) +
             (newCenterY - prevCenterY) * (newCenterY - prevCenterY)).toDouble()
        ).toFloat()

        val adaptiveAlpha = if (velocity > VELOCITY_THRESHOLD) FAST_ALPHA else SLOW_ALPHA

        val smoothed = RectF(
            ema(prev.left,   newBox.left,   adaptiveAlpha),
            ema(prev.top,    newBox.top,    adaptiveAlpha),
            ema(prev.right,  newBox.right,  adaptiveAlpha),
            ema(prev.bottom, newBox.bottom, adaptiveAlpha)
        )

        previousBox = RectF(smoothed)
        return smoothed
    }

    fun reset(initialBox: RectF?) {
        previousBox = initialBox?.let { RectF(it) }
        velocity = 0f
    }

    private fun ema(previous: Float, current: Float, alpha: Float): Float {
        return alpha * current + (1f - alpha) * previous
    }

    fun currentVelocity(): Float = velocity
}
