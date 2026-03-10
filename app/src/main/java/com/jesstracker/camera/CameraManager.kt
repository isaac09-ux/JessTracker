package com.jesstracker.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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

/**
 * CameraManager — conecta CameraX con el SubjectTracker.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TARGET_SUBJECT_HEIGHT = 0.66f
        private const val MIN_ZOOM = 1.0f
        private const val MAX_ZOOM = 4.0f
        private const val ZOOM_SMOOTHING = 0.34f
        private const val ZOOM_RESET_SMOOTHING = 0.15f
    }

    // --- CameraX components ---

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val personDetector = PersonDetector(context)

    // --- Estado ---

    private var currentZoomRatio: Float = MIN_ZOOM

    var isRecording: Boolean = false
        private set

    // --- Setup ---

    fun setup(
        previewView: PreviewView,
        onDetections: (detections: List<RectF>, frame: Bitmap) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        analysisExecutor.execute { personDetector.initialize() }

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

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(960, 540))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    processFrame(imageProxy, onDetections)
                }
            }

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.UHD,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
                )
            )
            .build()

        videoCapture = VideoCapture.withOutput(recorder)

        try {
            provider.unbindAll()
            activeCamera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
                videoCapture
            )
            currentZoomRatio = MIN_ZOOM
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Procesamiento de frames ---

    private fun processFrame(
        imageProxy: ImageProxy,
        onDetections: (List<RectF>, Bitmap) -> Unit
    ) {
        try {
            val bitmap = imageProxy.toBitmap()
            val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees.toFloat())

            val detections = personDetector.detect(rotatedBitmap)
            onDetections(detections, rotatedBitmap)
        } finally {
            imageProxy.close()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // --- Auto framing (zoom en sujeto seleccionado) ---

    fun updateAutoFraming(state: TrackerState, trackedBox: RectF?) {
        if (state == TrackerState.TRACKING && trackedBox != null) {
            val subjectHeight = (trackedBox.bottom - trackedBox.top).coerceAtLeast(0.01f)
            val targetZoom = (TARGET_SUBJECT_HEIGHT / subjectHeight).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val smoothedZoom = currentZoomRatio + (targetZoom - currentZoomRatio) * ZOOM_SMOOTHING
            applyZoom(smoothedZoom)
            return
        }

        val relaxedZoom = currentZoomRatio + (MIN_ZOOM - currentZoomRatio) * ZOOM_RESET_SMOOTHING
        applyZoom(relaxedZoom)
    }

    private fun applyZoom(zoomRatio: Float) {
        val clamped = zoomRatio.coerceIn(MIN_ZOOM, MAX_ZOOM)
        currentZoomRatio = clamped
        activeCamera?.cameraControl?.setZoomRatio(clamped)
    }

    // --- Grabacion ---

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
                            onRecordingSaved(uri.toString())
                        }
                    }
                }
            }
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

    // --- Lifecycle ---

    fun shutdown() {
        personDetector.close()
        analysisExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}
