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
import com.jesstracker.tracking.TrackerState

/**
 * TrackingOverlay — dibuja sobre el preview el estado visual del tracker.
 */
class TrackingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val FRAME_GUIDE_HEIGHT_RATIO = 0.7f
        private const val FRAME_GUIDE_ASPECT = 9f / 16f
        private const val FRAME_SMOOTHING = 0.28f
        private const val LEAD_FACTOR = 0.35f
    }

    // --- Estado ---

    private var trackerState: TrackerState = TrackerState.IDLE
    private var subjectBox: RectF? = null
    private var frameGuideRect: RectF? = null
    private var lastCenterX: Float? = null
    private var lastCenterY: Float? = null

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

    fun showTapFeedback(x: Float, y: Float) {
        tapX = x
        tapY = y
        tapAlpha = 255
        invalidate()
        postDelayed({ fadeTap() }, 50)
    }

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
                drawCropGuide(canvas)
                drawStateLabel(canvas, box, "TRACKING", Color.parseColor("#00FF88"))
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
        val cropWidth = cropHeight * FRAME_GUIDE_ASPECT

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
        val cropWidth = cropHeight * FRAME_GUIDE_ASPECT

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
