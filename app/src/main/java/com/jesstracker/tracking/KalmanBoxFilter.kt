package com.jesstracker.tracking

import android.graphics.RectF

/**
 * KalmanBoxFilter — filtro de Kalman para predecir posicion y velocidad
 * de un bounding box en coordenadas normalizadas.
 *
 * Estado: [cx, cy, w, h, vx, vy, vw, vh] — 8 dimensiones.
 *   cx, cy = centro del box
 *   w, h   = ancho y alto
 *   vx, vy = velocidad del centro
 *   vw, vh = cambio de tamaño (por acercamiento/alejamiento)
 *
 * Modelo: movimiento con velocidad constante + ruido de proceso.
 *
 * Ventajas sobre promedio de velocidad:
 *   - Pondera mediciones ruidosas segun su incertidumbre.
 *   - Predice posicion futura con modelo fisico (no solo ultimo delta).
 *   - Se adapta: si las mediciones son estables, confia mas en ellas.
 *     Si son ruidosas, confia mas en la prediccion.
 */
class KalmanBoxFilter {

    companion object {
        private const val STATE_DIM = 8
        private const val MEAS_DIM = 4

        // Ruido de proceso (Q): que tanto confiamos en el modelo de velocidad constante.
        // Valores mas altos = el filtro se adapta mas rapido a cambios de direccion.
        private const val PROCESS_NOISE_POS = 0.005f     // posicion
        private const val PROCESS_NOISE_VEL = 0.02f      // velocidad

        // Ruido de medicion (R): que tanto confiamos en las detecciones del modelo.
        // Valores mas altos = suaviza mas (ignora jitter del detector).
        private const val MEASUREMENT_NOISE = 0.008f
    }

    // Estado: [cx, cy, w, h, vx, vy, vw, vh]
    private val x = FloatArray(STATE_DIM)

    // Covarianza del error (matriz diagonal simplificada para eficiencia).
    // P[i] = varianza del estado i.
    private val p = FloatArray(STATE_DIM)

    // Ruido de proceso (diagonal).
    private val q = FloatArray(STATE_DIM) { i ->
        if (i < MEAS_DIM) PROCESS_NOISE_POS else PROCESS_NOISE_VEL
    }

    // Ruido de medicion (diagonal).
    private val r = FloatArray(MEAS_DIM) { MEASUREMENT_NOISE }

    private var initialized = false

    /** Inicializar con el primer bounding box observado. */
    fun initialize(box: RectF) {
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f
        val w = box.width()
        val h = box.height()

        x[0] = cx; x[1] = cy; x[2] = w; x[3] = h
        x[4] = 0f; x[5] = 0f; x[6] = 0f; x[7] = 0f  // velocidad inicial = 0

        // Incertidumbre inicial: alta en velocidad, baja en posicion.
        for (i in 0 until MEAS_DIM) p[i] = 0.01f        // posicion conocida
        for (i in MEAS_DIM until STATE_DIM) p[i] = 0.1f  // velocidad desconocida

        initialized = true
    }

    /**
     * Paso de prediccion: avanza el estado un frame usando el modelo de velocidad.
     * Llamar ANTES de recibir la nueva medicion.
     */
    fun predict(): RectF {
        if (!initialized) return RectF()

        // x_new = F * x  (modelo de velocidad constante: pos += vel)
        x[0] += x[4]  // cx += vx
        x[1] += x[5]  // cy += vy
        x[2] += x[6]  // w  += vw
        x[3] += x[7]  // h  += vh

        // P_new = P + Q
        for (i in 0 until STATE_DIM) {
            p[i] += q[i]
        }

        return stateToBox()
    }

    /**
     * Paso de correccion: incorpora una nueva medicion (bounding box detectado).
     * Llamar DESPUES de predict().
     */
    fun correct(measurement: RectF): RectF {
        if (!initialized) {
            initialize(measurement)
            return measurement
        }

        val z = floatArrayOf(
            (measurement.left + measurement.right) / 2f,
            (measurement.top + measurement.bottom) / 2f,
            measurement.width(),
            measurement.height()
        )

        // Kalman gain: K = P / (P + R) — version diagonal simplificada.
        // Innovacion: y = z - H*x (residuo entre medicion y prediccion).
        for (i in 0 until MEAS_DIM) {
            val k = p[i] / (p[i] + r[i])  // ganancia de Kalman
            val innovation = z[i] - x[i]   // error de prediccion

            x[i] += k * innovation           // corregir estado
            p[i] *= (1f - k)                 // actualizar covarianza

            // Actualizar velocidad implicitamente: la velocidad se corrige
            // por la misma innovacion escalada.
            x[i + MEAS_DIM] += k * 0.5f * innovation
        }

        return stateToBox()
    }

    /** Obtener el box predicho sin avanzar el estado. */
    fun predictedBox(): RectF {
        if (!initialized) return RectF()

        val cx = x[0] + x[4]
        val cy = x[1] + x[5]
        val w = (x[2] + x[6]).coerceAtLeast(0.01f)
        val h = (x[3] + x[7]).coerceAtLeast(0.01f)

        return RectF(
            (cx - w / 2f).coerceIn(0f, 1f),
            (cy - h / 2f).coerceIn(0f, 1f),
            (cx + w / 2f).coerceIn(0f, 1f),
            (cy + h / 2f).coerceIn(0f, 1f)
        )
    }

    /** Velocidad actual estimada (magnitud normalizada). */
    fun speed(): Float {
        val vx = x[4]
        val vy = x[5]
        return kotlin.math.sqrt(vx * vx + vy * vy)
    }

    fun reset() {
        x.fill(0f)
        p.fill(0f)
        initialized = false
    }

    fun isInitialized(): Boolean = initialized

    private fun stateToBox(): RectF {
        val cx = x[0]
        val cy = x[1]
        val w = x[2].coerceAtLeast(0.01f)
        val h = x[3].coerceAtLeast(0.01f)

        return RectF(
            (cx - w / 2f).coerceIn(0f, 1f),
            (cy - h / 2f).coerceIn(0f, 1f),
            (cx + w / 2f).coerceIn(0f, 1f),
            (cy + h / 2f).coerceIn(0f, 1f)
        )
    }
}
