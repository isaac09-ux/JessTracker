package com.jesstracker.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.jesstracker.tracking.TrackerState

/**
 * TrackingOverlay — dibuja sobre el preview el estado visual del tracker.
 *
 * Renderiza:
 *   - Bounding box del sujeto seleccionado (color según estado)
 *   - Indicador de estado (TRACKING / LOST / RE-IDENTIFYING)
 *   - Animación de tap cuando el usuario selecciona un sujeto
 *   - Crop guide — el encuadre final que tendrá el video
 */
class TrackingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ─── Estado ───────────────────────────────────────────────────────────────

    private var trackerState: TrackerState = TrackerState.IDLE
    private var subjectBox: RectF? = null
    private var tapX: Float = 0f
    private var tapY: Float = 0f
    private var tapAlpha: Int = 0  // 0 = invisible, 255 = visible

    // ─── Paints ───────────────────────────────────────────────────────────────

    // Box verde → TRACKING activo
    private val trackingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // Box amarillo → LOST, buscando
    private val lostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f) // línea punteada
    }

    // Box naranja → RE_IDENTIFYING
    private val reIdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C00")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
    }

    // Relleno semitransparente del box
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2000FF88")
        style = Paint.Style.FILL
    }

    // Texto de estado
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }

    // Animación de tap
    private val tapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // Crop guide — borde del encuadre final del video
    private val cropGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }

    // ─── API pública ──────────────────────────────────────────────────────────

    /**
     * Actualiza el estado visual del overlay.
     * Llamar desde el hilo principal en cada frame procesado.
     */
    fun update(state: TrackerState, cropBox: RectF?) {
        trackerState = state
        subjectBox = cropBox?.let { denormalize(it) }
        invalidate() // solicita redibujado
    }

    /**
     * Muestra animación de tap en las coordenadas dadas.
     */
    fun showTapFeedback(x: Float, y: Float) {
        tapX = x
        tapY = y
        tapAlpha = 255
        invalidate()
        // Fade out gradual
        postDelayed({ fadeTap() }, 50)
    }

    // ─── Dibujo ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawTapFeedback(canvas)

        val box = subjectBox ?: return

        when (trackerState) {
            TrackerState.IDLE -> return

            TrackerState.TRACKING -> {
                fillPaint.color = Color.parseColor("#2000FF88")
                canvas.drawRoundRect(box, 8f, 8f, fillPaint)
                canvas.drawRoundRect(box, 8f, 8f, trackingPaint)
                drawCornerAccents(canvas, box, trackingPaint)
                drawCropGuide(canvas, box)
                drawStateLabel(canvas, box, "● TRACKING", Color.parseColor("#00FF88"))
            }

            TrackerState.LOST -> {
                fillPaint.color = Color.parseColor("#20FFD700")
                canvas.drawRoundRect(box, 8f, 8f, fillPaint)
                canvas.drawRoundRect(box, 8f, 8f, lostPaint)
                drawStateLabel(canvas, box, "◎ BUSCANDO...", Color.parseColor("#FFD700"))
            }

            TrackerState.RE_IDENTIFYING -> {
                fillPaint.color = Color.parseColor("#20FF8C00")
                canvas.drawRoundRect(box, 8f, 8f, fillPaint)
                canvas.drawRoundRect(box, 8f, 8f, reIdPaint)
                drawStateLabel(canvas, box, "⟳ RE-ID", Color.parseColor("#FF8C00"))
            }
        }
    }

    /**
     * Dibuja acentos en las esquinas del box — estética de viewfinder profesional.
     */
    private fun drawCornerAccents(canvas: Canvas, box: RectF, paint: Paint) {
        val len = 20f

        // Esquina superior izquierda
        canvas.drawLine(box.left, box.top, box.left + len, box.top, paint)
        canvas.drawLine(box.left, box.top, box.left, box.top + len, paint)

        // Esquina superior derecha
        canvas.drawLine(box.right - len, box.top, box.right, box.top, paint)
        canvas.drawLine(box.right, box.top, box.right, box.top + len, paint)

        // Esquina inferior izquierda
        canvas.drawLine(box.left, box.bottom - len, box.left, box.bottom, paint)
        canvas.drawLine(box.left, box.bottom, box.left + len, box.bottom, paint)

        // Esquina inferior derecha
        canvas.drawLine(box.right, box.bottom - len, box.right, box.bottom, paint)
        canvas.drawLine(box.right - len, box.bottom, box.right, box.bottom, paint)
    }

    /**
     * Dibuja el encuadre final del video — guía visual de qué quedará grabado.
     * Ratio 9:16 centrado en el sujeto.
     */
    private fun drawCropGuide(canvas: Canvas, subjectBox: RectF) {
        val centerX = (subjectBox.left + subjectBox.right) / 2f
        val centerY = (subjectBox.top + subjectBox.bottom) / 2f

        val cropHeight = height * 0.7f
        val cropWidth = cropHeight * (9f / 16f)

        val cropRect = RectF(
            centerX - cropWidth / 2f,
            centerY - cropHeight / 2f,
            centerX + cropWidth / 2f,
            centerY + cropHeight / 2f
        )

        canvas.drawRect(cropRect, cropGuidePaint)
    }

    /**
     * Label de estado encima del bounding box.
     */
    private fun drawStateLabel(canvas: Canvas, box: RectF, label: String, color: Int) {
        textPaint.color = color
        textPaint.textSize = 32f
        canvas.drawText(label, box.left, box.top - 10f, textPaint)
    }

    /**
     * Animación de círculo en el punto de toque.
     */
    private fun drawTapFeedback(canvas: Canvas) {
        if (tapAlpha <= 0) return
        tapPaint.alpha = tapAlpha
        canvas.drawCircle(tapX, tapY, 40f, tapPaint)
        canvas.drawCircle(tapX, tapY, 15f, tapPaint)
    }

    private fun fadeTap() {
        tapAlpha -= 40
        if (tapAlpha > 0) {
            invalidate()
            postDelayed({ fadeTap() }, 30)
        }
    }

    /**
     * Convierte coordenadas normalizadas (0-1) a coordenadas de pantalla (píxeles).
     */
    private fun denormalize(box: RectF): RectF {
        return RectF(
            box.left   * width,
            box.top    * height,
            box.right  * width,
            box.bottom * height
        )
    }
}
