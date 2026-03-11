package com.jesstracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.jesstracker.camera.LightLevel
import com.jesstracker.tracking.TrackerState

/**
 * TrackingOverlay — dibuja sobre el preview el estado visual del tracker.
 *
 * Incluye indicador de estado en la esquina superior izquierda para que
 * el usuario vea a simple vista si el tracker esta activo, buscando, o inactivo.
 */
class TrackingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val FRAME_GUIDE_HEIGHT_RATIO = 0.7f
        private const val PORTRAIT_FRAME_GUIDE_ASPECT = 9f / 16f
        private const val LANDSCAPE_FRAME_GUIDE_ASPECT = 16f / 9f
        private const val FRAME_SMOOTHING = 0.28f
        private const val LEAD_FACTOR = 0.35f

        private const val STATUS_PADDING = 16f
        private const val STATUS_PILL_RADIUS = 12f
        private const val STATUS_DOT_RADIUS = 6f
        private const val STATUS_TEXT_SIZE = 28f
        private const val STATUS_MARGIN_TOP = 80f

        private const val LIGHT_PILL_WIDTH = 260f
        private const val LIGHT_BAR_HEIGHT = 14f
    }

    // --- Estado ---

    private var trackerState: TrackerState = TrackerState.IDLE
    private var subjectBox: RectF? = null
    private var frameGuideRect: RectF? = null
    private var lastCenterX: Float? = null
    private var lastCenterY: Float? = null
    private var lastViewWidth: Int = 0
    private var lastViewHeight: Int = 0
    private var confidence: Float = 0f
    private var detectionCount: Int = 0
    private var lightLevel: LightLevel = LightLevel.NORMAL
    private var lightBrightness: Float = 0.5f

    // Detecciones crudas para mostrar en IDLE (el usuario ve a quien puede tocar).
    private var idleDetections: List<RectF> = emptyList()

    // Numero de camiseta detectado por OCR.
    private var jerseyNumber: Int? = null

    private var tapX: Float = 0f
    private var tapY: Float = 0f
    private var tapAlpha: Int = 0

    // --- Paints ---

    private val trackingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val lostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val reIdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C00")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2000FF88")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }

    private val tapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val cropGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }

    private val statusBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private val statusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = STATUS_TEXT_SIZE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(2f, 0f, 1f, Color.BLACK)
    }

    // Paint para marcadores de personas detectadas en IDLE.
    private val idleDetectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val idleDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFFFFF")
        style = Paint.Style.FILL
    }

    fun update(state: TrackerState, cropBox: RectF?) {
        trackerState = state
        subjectBox = cropBox?.let { denormalize(it) }

        if (state == TrackerState.TRACKING) {
            updateAutoFrameGuide(subjectBox)
        } else if (state == TrackerState.IDLE) {
            frameGuideRect = null
            lastCenterX = null
            lastCenterY = null
        }

        invalidate()
    }

    fun updateStatusInfo(confidence: Float, detectionCount: Int) {
        this.confidence = confidence
        this.detectionCount = detectionCount
    }

    fun updateLightInfo(level: LightLevel, brightness: Float) {
        lightLevel = level
        lightBrightness = brightness.coerceIn(0f, 1f)
    }

    /** Pasar las detecciones crudas para que se dibujen como marcadores en IDLE. */
    fun updateDetections(detections: List<RectF>) {
        idleDetections = detections
    }

    /** Actualizar el numero de camiseta leido por OCR. */
    fun updateJerseyNumber(number: Int?) {
        jerseyNumber = number
    }

    fun showTapFeedback(x: Float, y: Float) {
        tapX = x
        tapY = y
        tapAlpha = 255
        invalidate()
        postDelayed({ fadeTap() }, 50)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)

        if (lastViewWidth != width || lastViewHeight != height) {
            frameGuideRect = null
            lastCenterX = null
            lastCenterY = null
        }

        lastViewWidth = width
        lastViewHeight = height
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawTapFeedback(canvas)
        drawStatusIndicator(canvas)
        drawLightIndicator(canvas)

        // En IDLE, mostrar todas las personas detectadas como marcadores sutiles.
        if (trackerState == TrackerState.IDLE) {
            drawIdleDetections(canvas)
            return
        }

        val box = subjectBox ?: return

        when (trackerState) {
            TrackerState.IDLE -> return
            TrackerState.TRACKING -> {
                // Color del borde segun confianza: verde brillante (>80%), amarillo (<80%).
                val trackColor = if (confidence >= 0.80f) {
                    Color.parseColor("#00FF88")
                } else if (confidence >= 0.60f) {
                    Color.parseColor("#AAFF44")
                } else {
                    Color.parseColor("#FFD700")
                }
                trackingPaint.color = trackColor
                fillPaint.color = Color.argb(0x20,
                    Color.red(trackColor), Color.green(trackColor), Color.blue(trackColor))
                canvas.drawRoundRect(box, 8f, 8f, fillPaint)
                canvas.drawRoundRect(box, 8f, 8f, trackingPaint)
                drawCornerAccents(canvas, box, trackingPaint)
                drawCropGuide(canvas)
                val pct = (confidence * 100).toInt()
                val numberTag = jerseyNumber?.let { " #$it" } ?: ""
                drawStateLabel(canvas, box, "LOCK $pct%$numberTag", trackColor)
            }

            TrackerState.LOST -> {
                fillPaint.color = Color.parseColor("#20FFD700")
                canvas.drawRoundRect(box, 8f, 8f, fillPaint)
                canvas.drawRoundRect(box, 8f, 8f, lostPaint)
                drawCropGuide(canvas)
                drawStateLabel(canvas, box, "BUSCANDO...", Color.parseColor("#FFD700"))
            }

            TrackerState.RE_IDENTIFYING -> {
                fillPaint.color = Color.parseColor("#20FF8C00")
                canvas.drawRoundRect(box, 8f, 8f, fillPaint)
                canvas.drawRoundRect(box, 8f, 8f, reIdPaint)
                drawCropGuide(canvas)
                drawStateLabel(canvas, box, "RE-ID", Color.parseColor("#FF8C00"))
            }
        }
    }

    private fun drawStatusIndicator(canvas: Canvas) {
        val dotColor: Int
        val statusText: String

        when (trackerState) {
            TrackerState.IDLE -> {
                dotColor = Color.parseColor("#888888")
                statusText = if (detectionCount > 0) "$detectionCount detectados" else "LISTO"
            }
            TrackerState.TRACKING -> {
                dotColor = Color.parseColor("#00FF88")
                val pct = (confidence * 100).toInt()
                val numberTag = jerseyNumber?.let { " #$it" } ?: ""
                statusText = "LOCK $pct%$numberTag"
            }
            TrackerState.LOST -> {
                dotColor = Color.parseColor("#FFD700")
                statusText = "PERDIDO"
            }
            TrackerState.RE_IDENTIFYING -> {
                dotColor = Color.parseColor("#FF8C00")
                statusText = "BUSCANDO"
            }
        }

        statusTextPaint.textSize = STATUS_TEXT_SIZE
        val textWidth = statusTextPaint.measureText(statusText)
        val pillWidth = STATUS_DOT_RADIUS * 2 + STATUS_PADDING * 3 + textWidth
        val pillHeight = STATUS_TEXT_SIZE + STATUS_PADDING * 1.4f

        val left = STATUS_PADDING
        val top = STATUS_MARGIN_TOP
        val pillRect = RectF(left, top, left + pillWidth, top + pillHeight)

        canvas.drawRoundRect(pillRect, STATUS_PILL_RADIUS, STATUS_PILL_RADIUS, statusBgPaint)

        statusDotPaint.color = dotColor
        val dotCx = left + STATUS_PADDING + STATUS_DOT_RADIUS
        val dotCy = top + pillHeight / 2f
        canvas.drawCircle(dotCx, dotCy, STATUS_DOT_RADIUS, statusDotPaint)

        val textX = dotCx + STATUS_DOT_RADIUS + STATUS_PADDING
        val textY = top + pillHeight / 2f + STATUS_TEXT_SIZE / 3f
        canvas.drawText(statusText, textX, textY, statusTextPaint)
    }

    private fun drawLightIndicator(canvas: Canvas) {
        val label = when (lightLevel) {
            LightLevel.BRIGHT -> "LUZ ALTA"
            LightLevel.NORMAL -> "LUZ NORMAL"
            LightLevel.LOW -> "POCA LUZ"
            LightLevel.ULTRA_LOW -> "ULTRA BAJA"
        }

        val right = width - STATUS_PADDING
        val left = right - LIGHT_PILL_WIDTH
        val top = STATUS_MARGIN_TOP
        val bottom = top + STATUS_TEXT_SIZE + STATUS_PADDING * 2.0f

        val rect = RectF(left, top, right, bottom)
        canvas.drawRoundRect(rect, STATUS_PILL_RADIUS, STATUS_PILL_RADIUS, statusBgPaint)

        val dotColor = when (lightLevel) {
            LightLevel.BRIGHT -> Color.parseColor("#00E676")
            LightLevel.NORMAL -> Color.parseColor("#8BC34A")
            LightLevel.LOW -> Color.parseColor("#FFC107")
            LightLevel.ULTRA_LOW -> Color.parseColor("#FF5252")
        }

        statusDotPaint.color = dotColor
        canvas.drawCircle(left + STATUS_PADDING + STATUS_DOT_RADIUS, top + STATUS_PADDING + STATUS_DOT_RADIUS, STATUS_DOT_RADIUS, statusDotPaint)

        statusTextPaint.textSize = 24f
        val textX = left + STATUS_PADDING * 2 + STATUS_DOT_RADIUS * 2
        val textY = top + STATUS_PADDING + STATUS_TEXT_SIZE * 0.65f
        canvas.drawText(label, textX, textY, statusTextPaint)

        val barLeft = left + STATUS_PADDING
        val barRight = right - STATUS_PADDING
        val barTop = bottom - STATUS_PADDING - LIGHT_BAR_HEIGHT
        val barBottom = barTop + LIGHT_BAR_HEIGHT

        val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44FFFFFF")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 10f, 10f, barBg)

        val fillRight = barLeft + (barRight - barLeft) * lightBrightness
        val barFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dotColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(barLeft, barTop, fillRight, barBottom), 10f, 10f, barFill)
    }

    private fun updateAutoFrameGuide(box: RectF?) {
        box ?: return

        val centerX = (box.left + box.right) / 2f
        val centerY = (box.top + box.bottom) / 2f

        val prevX = lastCenterX ?: centerX
        val prevY = lastCenterY ?: centerY

        val velocityX = centerX - prevX
        val velocityY = centerY - prevY

        lastCenterX = centerX
        lastCenterY = centerY

        val predictedX = (centerX + velocityX * LEAD_FACTOR).coerceIn(0f, width.toFloat())
        val predictedY = (centerY + velocityY * LEAD_FACTOR).coerceIn(0f, height.toFloat())

        val cropHeight = height * FRAME_GUIDE_HEIGHT_RATIO
        val aspect = currentGuideAspect()
        val cropWidth = cropHeight * aspect

        val targetRect = RectF(
            predictedX - cropWidth / 2f,
            predictedY - cropHeight / 2f,
            predictedX + cropWidth / 2f,
            predictedY + cropHeight / 2f
        )

        frameGuideRect = smoothRect(frameGuideRect, clampToView(targetRect))
    }

    private fun drawCornerAccents(canvas: Canvas, box: RectF, paint: Paint) {
        val len = 20f

        canvas.drawLine(box.left, box.top, box.left + len, box.top, paint)
        canvas.drawLine(box.left, box.top, box.left, box.top + len, paint)

        canvas.drawLine(box.right - len, box.top, box.right, box.top, paint)
        canvas.drawLine(box.right, box.top, box.right, box.top + len, paint)

        canvas.drawLine(box.left, box.bottom - len, box.left, box.bottom, paint)
        canvas.drawLine(box.left, box.bottom, box.left + len, box.bottom, paint)

        canvas.drawLine(box.right, box.bottom - len, box.right, box.bottom, paint)
        canvas.drawLine(box.right - len, box.bottom, box.right, box.bottom, paint)
    }

    private fun drawCropGuide(canvas: Canvas) {
        val rect = frameGuideRect ?: subjectBox?.let { fallbackFrameGuide(it) } ?: return
        canvas.drawRect(rect, cropGuidePaint)
    }

    private fun fallbackFrameGuide(subjectBox: RectF): RectF {
        val centerX = (subjectBox.left + subjectBox.right) / 2f
        val centerY = (subjectBox.top + subjectBox.bottom) / 2f

        val cropHeight = height * FRAME_GUIDE_HEIGHT_RATIO
        val aspect = currentGuideAspect()
        val cropWidth = cropHeight * aspect

        return clampToView(
            RectF(
                centerX - cropWidth / 2f,
                centerY - cropHeight / 2f,
                centerX + cropWidth / 2f,
                centerY + cropHeight / 2f
            )
        )
    }

    private fun smoothRect(previous: RectF?, target: RectF): RectF {
        if (previous == null) return target

        return RectF(
            lerp(previous.left, target.left, FRAME_SMOOTHING),
            lerp(previous.top, target.top, FRAME_SMOOTHING),
            lerp(previous.right, target.right, FRAME_SMOOTHING),
            lerp(previous.bottom, target.bottom, FRAME_SMOOTHING)
        )
    }

    private fun clampToView(rect: RectF): RectF {
        val shiftX = when {
            rect.left < 0f -> -rect.left
            rect.right > width -> width - rect.right
            else -> 0f
        }

        val shiftY = when {
            rect.top < 0f -> -rect.top
            rect.bottom > height -> height - rect.bottom
            else -> 0f
        }

        return RectF(
            rect.left + shiftX,
            rect.top + shiftY,
            rect.right + shiftX,
            rect.bottom + shiftY
        )
    }

    private fun currentGuideAspect(): Float {
        return if (width > height) {
            LANDSCAPE_FRAME_GUIDE_ASPECT
        } else {
            PORTRAIT_FRAME_GUIDE_ASPECT
        }
    }

    private fun drawStateLabel(canvas: Canvas, box: RectF, label: String, color: Int) {
        textPaint.color = color
        textPaint.textSize = 32f
        canvas.drawText(label, box.left, box.top - 10f, textPaint)
    }

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

    private fun drawIdleDetections(canvas: Canvas) {
        for (det in idleDetections) {
            val box = denormalize(det)
            // Rectangulo sutil con esquinas redondeadas.
            canvas.drawRoundRect(box, 6f, 6f, idleDetectionPaint)
            // Punto en el centro para que se vea donde tocar.
            val cx = (box.left + box.right) / 2f
            val cy = (box.top + box.bottom) / 2f
            canvas.drawCircle(cx, cy, 5f, idleDotPaint)
        }
    }

    private fun denormalize(box: RectF): RectF {
        return RectF(
            box.left * width,
            box.top * height,
            box.right * width,
            box.bottom * height
        )
    }

    private fun lerp(from: Float, to: Float, t: Float): Float {
        return from + (to - from) * t
    }
}
