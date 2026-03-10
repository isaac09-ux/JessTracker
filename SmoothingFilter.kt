package com.jesstracker.tracking

import android.graphics.RectF

/**
 * SmoothingFilter — suaviza el movimiento del crop usando EMA.
 *
 * EMA (Exponential Moving Average) pondera el frame actual contra
 * el historial reciente. Sin esto, el crop brincaría bruscamente
 * cada vez que MediaPipe ajusta el bounding box por un pixel.
 *
 * Formula: smoothed = alpha * nuevo + (1 - alpha) * anterior
 *
 * Alpha controla qué tan "reactivo" vs "suave" es el seguimiento:
 *   - Alpha alto (0.8-0.9) → muy reactivo, sigue rápido pero puede vibrar
 *   - Alpha bajo (0.1-0.3) → muy suave, pero puede quedarse atrás en movimientos bruscos
 *   - Alpha 0.5 → balance para deportes (recomendado para voleibol)
 */
class SmoothingFilter(
    private var alpha: Float = DEFAULT_ALPHA
) {

    companion object {
        const val DEFAULT_ALPHA = 0.5f

        // Alpha rápido para cuando el sujeto se mueve a alta velocidad
        const val FAST_ALPHA = 0.8f

        // Alpha lento para movimientos suaves (posición estática, recepción)
        const val SLOW_ALPHA = 0.3f

        // Umbral de velocidad para cambiar entre alpha rápido/lento
        // En coordenadas normalizadas por frame
        const val VELOCITY_THRESHOLD = 0.02f
    }

    private var previousBox: RectF? = null
    private var velocity: Float = 0f

    /**
     * Aplica el filtro EMA al nuevo bounding box.
     * Ajusta alpha automáticamente según velocidad del sujeto.
     *
     * @param newBox Bounding box crudo de MediaPipe (coordenadas normalizadas)
     * @return       Bounding box suavizado
     */
    fun update(newBox: RectF): RectF {
        val prev = previousBox

        if (prev == null) {
            previousBox = RectF(newBox)
            return RectF(newBox)
        }

        // Calcular velocidad como distancia del centro entre frames
        val prevCenterX = (prev.left + prev.right) / 2f
        val prevCenterY = (prev.top + prev.bottom) / 2f
        val newCenterX  = (newBox.left + newBox.right) / 2f
        val newCenterY  = (newBox.top + newBox.bottom) / 2f

        velocity = Math.sqrt(
            ((newCenterX - prevCenterX) * (newCenterX - prevCenterX) +
             (newCenterY - prevCenterY) * (newCenterY - prevCenterY)).toDouble()
        ).toFloat()

        // Adaptar alpha según velocidad
        val adaptiveAlpha = if (velocity > VELOCITY_THRESHOLD) FAST_ALPHA else SLOW_ALPHA

        // Aplicar EMA a cada coordenada del box
        val smoothed = RectF(
            ema(prev.left,   newBox.left,   adaptiveAlpha),
            ema(prev.top,    newBox.top,    adaptiveAlpha),
            ema(prev.right,  newBox.right,  adaptiveAlpha),
            ema(prev.bottom, newBox.bottom, adaptiveAlpha)
        )

        previousBox = RectF(smoothed)
        return smoothed
    }

    /**
     * Resetea el filtro con una posición inicial.
     * Llamar cuando el sujeto es seleccionado o re-identificado.
     */
    fun reset(initialBox: RectF?) {
        previousBox = initialBox?.let { RectF(it) }
        velocity = 0f
    }

    /**
     * Formula EMA para un solo valor.
     */
    private fun ema(previous: Float, current: Float, alpha: Float): Float {
        return alpha * current + (1f - alpha) * previous
    }

    /**
     * Velocidad actual del sujeto en coordenadas normalizadas/frame.
     * Útil para futuras features como auto-slowmo en saltos.
     */
    fun currentVelocity(): Float = velocity
}
