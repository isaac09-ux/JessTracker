package com.jesstracker.ui

import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.camera.view.PreviewView

/**
 * CameraPreviewView — el preview de la cámara con detección de toque.
 *
 * Extiende PreviewView de CameraX para agregar:
 *   - Touch listener que convierte coordenadas de pantalla → normalizadas (0-1)
 *   - Callback onSubjectSelected cuando el usuario toca una persona
 *
 * Por qué coordenadas normalizadas:
 *   La pantalla puede ser 1080x2400px pero el frame de análisis es 720x1280px.
 *   Normalizar (0.0-1.0) hace que las coordenadas funcionen en cualquier resolución.
 */
class CameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : PreviewView(context, attrs) {

    // Callback que dispara SubjectTracker.onTap()
    var onSubjectSelected: ((normalizedPoint: PointF) -> Unit)? = null

    // Feedback visual — callback para mostrar animación de "tap" en la UI
    var onTapFeedback: ((screenX: Float, screenY: Float) -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)

        val screenX = event.x
        val screenY = event.y

        // Convertir coordenadas de pantalla a normalizadas (0.0 - 1.0)
        val normalizedX = screenX / width.toFloat()
        val normalizedY = screenY / height.toFloat()

        // Disparar feedback visual inmediatamente (se siente más responsivo)
        onTapFeedback?.invoke(screenX, screenY)

        // Notificar al tracker
        onSubjectSelected?.invoke(PointF(normalizedX, normalizedY))

        return true
    }
}
