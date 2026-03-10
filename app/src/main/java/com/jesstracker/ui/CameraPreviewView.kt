package com.jesstracker.ui

import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.camera.view.PreviewView

/**
 * CameraPreviewView — preview de la camara con deteccion de toque.
 *
 * Extiende PreviewView de CameraX para agregar:
 *   - Touch listener que convierte coordenadas de pantalla a normalizadas (0-1)
 *   - Callback onSubjectSelected cuando el usuario toca una persona
 */
class CameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : PreviewView(context, attrs) {

    var onSubjectSelected: ((normalizedPoint: PointF) -> Unit)? = null
    var onTapFeedback: ((screenX: Float, screenY: Float) -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)

        val screenX = event.x
        val screenY = event.y

        val normalizedX = screenX / width.toFloat()
        val normalizedY = screenY / height.toFloat()

        onTapFeedback?.invoke(screenX, screenY)
        onSubjectSelected?.invoke(PointF(normalizedX, normalizedY))

        return true
    }
}
