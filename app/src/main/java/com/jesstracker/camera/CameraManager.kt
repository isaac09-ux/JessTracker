package com.jesstracker.camera

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.camera2.CaptureRequest
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.BatteryManager
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.jesstracker.tracking.TrackerState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CameraManager — conecta CameraX con el SubjectTracker.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TAG = "CameraManager"

        private const val PORTRAIT_TARGET_SUBJECT_HEIGHT = 0.66f
        private const val LANDSCAPE_TARGET_SUBJECT_HEIGHT = 0.52f
        private const val MIN_ZOOM = 1.0f
        private const val MAX_ZOOM = 4.0f
        // Smoothing mas bajo = transiciones mas suaves tipo Samsung.
        private const val BASE_ZOOM_SMOOTHING = 0.12f
        private const val ZOOM_RESET_SMOOTHING = 0.06f

        // Umbrales para adaptar framing segun movimiento real del dispositivo.
        private const val LOW_HAND_MOTION = 0.18f
        private const val HIGH_HAND_MOTION = 0.9f

        // Seguimiento de enfoque / metering para acercarnos al "auto framing" tipo Samsung.
        private const val METERING_UPDATE_INTERVAL_MS = 350L
        private const val MAX_CENTER_BIAS = 0.10f

        // Throttling adaptativo segun bateria.
        private const val BATTERY_CHECK_INTERVAL_MS = 30_000L
        private const val LOW_BATTERY_THRESHOLD = 20
        private const val MEDIUM_BATTERY_THRESHOLD = 40
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var previewUseCase: Preview? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null
    private var activeRecording: Recording? = null
    private var previewViewRef: PreviewView? = null
    private var detectionsCallbackRef: ((List<RectF>, Bitmap) -> Unit)? = null

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val personDetector = PersonDetector(context)
    private val deviceMotionEstimator = DeviceMotionEstimator(context)

    private var currentZoomRatio: Float = MIN_ZOOM
    private var targetRotation: Int = Surface.ROTATION_0
    private var lastMeteringUpdateAtMs: Long = 0L

    // Throttling: saltar N frames entre detecciones para ahorrar bateria.
    private var frameSkipInterval: Int = 0
    private var frameCounter: Int = 0
    private var lastBatteryCheckMs: Long = 0L

    var isRecording: Boolean = false
        private set

    fun setup(
        previewView: PreviewView,
        onDetections: (detections: List<RectF>, frame: Bitmap) -> Unit
    ) {
        previewViewRef = previewView
        detectionsCallbackRef = onDetections
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        analysisExecutor.execute { personDetector.initialize() }
        deviceMotionEstimator.start()

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases(previewView, onDetections)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindUseCases(
        previewView: PreviewView,
        onDetections: (List<RectF>, Bitmap) -> Unit
    ) {
        val provider = cameraProvider ?: return

        val previewBuilder = Preview.Builder()
            .setTargetRotation(targetRotation)
        val previewInterop = Camera2Interop.Extender(previewBuilder)
        previewInterop.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
        )
        previewInterop.setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
        )

        val preview = previewBuilder
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        previewUseCase = preview

        val analysisBuilder = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setTargetRotation(targetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)

        val analysisInterop = Camera2Interop.Extender(analysisBuilder)
        analysisInterop.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
        )

        val imageAnalysis = analysisBuilder
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    processFrame(imageProxy, onDetections)
                }
            }
        imageAnalysisUseCase = imageAnalysis

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.UHD,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
                )
            )
            .build()

        videoCapture = VideoCapture.withOutput(recorder).also {
            it.targetRotation = targetRotation
        }

        try {
            provider.unbindAll()
            activeCamera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
                videoCapture
            )
            applyZoom(currentZoomRatio)
            enableDeviceStabilization()
        } catch (e: Exception) {
            Log.e(TAG, "Error binding use cases", e)
        }
    }

    private fun processFrame(
        imageProxy: ImageProxy,
        onDetections: (List<RectF>, Bitmap) -> Unit
    ) {
        try {
            // Throttling adaptativo: saltar frames cuando bateria baja.
            updateThrottling()
            frameCounter++
            if (frameSkipInterval > 0 && frameCounter % (frameSkipInterval + 1) != 0) {
                return
            }

            val bitmap = imageProxy.toBitmap()
            val degrees = imageProxy.imageInfo.rotationDegrees.toFloat()
            val rotatedBitmap = rotateBitmap(bitmap, degrees)

            // Reciclar el bitmap original si se creo uno rotado nuevo.
            if (rotatedBitmap !== bitmap) {
                bitmap.recycle()
            }

            val detections = personDetector.detect(rotatedBitmap)
            onDetections(detections, rotatedBitmap)
        } finally {
            imageProxy.close()
        }
    }

    private fun updateThrottling() {
        val now = System.currentTimeMillis()
        if (now - lastBatteryCheckMs < BATTERY_CHECK_INTERVAL_MS) return
        lastBatteryCheckMs = now

        val batteryLevel = getBatteryLevel()
        frameSkipInterval = when {
            batteryLevel <= LOW_BATTERY_THRESHOLD -> 2   // Procesar 1 de cada 3 frames
            batteryLevel <= MEDIUM_BATTERY_THRESHOLD -> 1 // Procesar 1 de cada 2 frames
            else -> 0                                     // Procesar todos los frames
        }

        if (frameSkipInterval > 0) {
            Log.i(TAG, "Bateria $batteryLevel%, throttling: skip $frameSkipInterval frames")
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 100
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun setTargetRotation(rotation: Int) {
        if (targetRotation == rotation) return

        if (isRecording) {
            Log.w(TAG, "Ignoring rotation change during recording")
            return
        }

        targetRotation = rotation

        val previewView = previewViewRef
        val callback = detectionsCallbackRef
        if (previewView == null || callback == null) {
            previewUseCase?.targetRotation = rotation
            imageAnalysisUseCase?.targetRotation = rotation
            videoCapture?.targetRotation = rotation
            return
        }

        bindUseCases(previewView, callback)
    }

    fun updateAutoFraming(
        state: TrackerState,
        trackedBox: RectF?,
        detections: List<RectF>,
        viewWidth: Int,
        viewHeight: Int
    ) {
        // --- Tracking activo: zoom enfocado SOLO en la jugadora seleccionada ---
        if (state == TrackerState.TRACKING && trackedBox != null) {
            val isLandscape = viewWidth > viewHeight
            val targetSubjectSize = if (isLandscape) {
                LANDSCAPE_TARGET_SUBJECT_HEIGHT
            } else {
                PORTRAIT_TARGET_SUBJECT_HEIGHT
            }

            val subjectHeight = (trackedBox.bottom - trackedBox.top).coerceAtLeast(0.01f)
            val centerX = (trackedBox.left + trackedBox.right) * 0.5f
            val centerY = (trackedBox.top + trackedBox.bottom) * 0.5f

            // Unica compensacion: si la jugadora esta muy al borde, zoom out suave
            // para no cortarla. NO compensar crowd ni contexto — el zoom es SOLO para ella.
            val distanceToNearestEdge = minOf(centerX, 1f - centerX, centerY, 1f - centerY)
            val edgeCompensation = if (distanceToNearestEdge < 0.12f) 0.92f else 1f

            val targetZoom = (targetSubjectSize * edgeCompensation / subjectHeight)
                .coerceIn(MIN_ZOOM, MAX_ZOOM)

            // Smoothing adaptativo al movimiento de mano, pero mas suave que antes.
            val handMotion = deviceMotionEstimator.angularSpeed()
            val motionFactor = when {
                handMotion <= LOW_HAND_MOTION -> 1.0f
                handMotion >= HIGH_HAND_MOTION -> 0.5f
                else -> 1.0f - ((handMotion - LOW_HAND_MOTION) / (HIGH_HAND_MOTION - LOW_HAND_MOTION)) * 0.5f
            }

            val adaptiveSmoothing = (BASE_ZOOM_SMOOTHING * motionFactor).coerceIn(0.04f, 0.16f)
            val smoothedZoom = currentZoomRatio + (targetZoom - currentZoomRatio) * adaptiveSmoothing
            applyZoom(smoothedZoom)
            updateMeteringPoint(centerX, centerY, handMotion)
            return
        }

        // --- LOST o RE_IDENTIFYING: mantener el zoom actual ---
        // No hacer zoom-out cuando pierde al sujeto temporalmente.
        // Esto da una experiencia tipo Samsung donde el encuadre se mantiene estable
        // incluso si el tracker la pierde por 1-2 segundos.
        if (state == TrackerState.LOST || state == TrackerState.RE_IDENTIFYING) {
            // Mantener el metering en el ultimo punto conocido.
            return
        }

        // --- IDLE: zoom-out suave al nivel base ---
        val relaxedZoom = currentZoomRatio + (MIN_ZOOM - currentZoomRatio) * ZOOM_RESET_SMOOTHING
        applyZoom(relaxedZoom)
    }

    private fun updateMeteringPoint(centerX: Float, centerY: Float, handMotion: Float) {
        val previewView = previewViewRef ?: return
        val camera = activeCamera ?: return

        val now = System.currentTimeMillis()
        if (now - lastMeteringUpdateAtMs < METERING_UPDATE_INTERVAL_MS) return

        // Si la mano se mueve mucho, evitamos micro-ajustes agresivos para que no oscile.
        val damping = if (handMotion > HIGH_HAND_MOTION) 0.5f else 1f
        val compensatedX = (centerX + (0.5f - centerX) * MAX_CENTER_BIAS * damping).coerceIn(0.05f, 0.95f)
        val compensatedY = (centerY + (0.5f - centerY) * MAX_CENTER_BIAS * damping).coerceIn(0.05f, 0.95f)

        // meteringPointFactory.createPoint() espera coordenadas en pixeles del view.
        val pixelX = compensatedX * previewView.width
        val pixelY = compensatedY * previewView.height

        val point = previewView.meteringPointFactory.createPoint(pixelX, pixelY)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(2, TimeUnit.SECONDS)
            .build()

        camera.cameraControl.startFocusAndMetering(action)
        lastMeteringUpdateAtMs = now
    }

    private fun applyZoom(zoomRatio: Float) {
        val clamped = zoomRatio.coerceIn(MIN_ZOOM, MAX_ZOOM)
        currentZoomRatio = clamped
        activeCamera?.cameraControl?.setZoomRatio(clamped)
    }

    private fun enableDeviceStabilization() {
        val camera = activeCamera ?: return
        try {
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                )
                .setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
                .build()

            Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options)
        } catch (e: Exception) {
            // Algunos dispositivos no soportan ambos modos a la vez.
            Log.w(TAG, "Stabilization modes not fully supported: ${e.message}")
        }
    }

    fun startRecording(
        onRecordingStarted: () -> Unit = {},
        onRecordingSaved: (String) -> Unit = {},
        onRecordingError: (String) -> Unit = {}
    ) {
        val capture = videoCapture ?: run {
            onRecordingError("VideoCapture no inicializado")
            return
        }

        val outputOptions = createGalleryOutputOptions()

        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        onRecordingStarted()
                    }

                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        if (event.hasError()) {
                            onRecordingError("Error al grabar: ${event.error}")
                        } else {
                            val uri = event.outputResults.outputUri
                            scanRecordedVideo(uri)
                            onRecordingSaved(uri.toString())
                        }
                    }
                }
            }
    }

    private fun scanRecordedVideo(uri: Uri) {
        val path = if (uri.scheme == "file") uri.path else null
        val target = path ?: uri.toString()

        MediaScannerConnection.scanFile(
            context,
            arrayOf(target),
            arrayOf("video/mp4"),
            null
        )
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    private fun createGalleryOutputOptions(): MediaStoreOutputOptions {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "jess_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/JessTracker")
        }

        return MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .build()
    }

    fun shutdown() {
        personDetector.close()
        deviceMotionEstimator.stop()
        analysisExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}
