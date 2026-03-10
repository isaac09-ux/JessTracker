package com.jesstracker.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.jesstracker.tracking.SubjectTracker
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraManager — conecta CameraX con el SubjectTracker.
 *
 * Dos streams simultaneos:
 *
 *   Stream 1: ImageAnalysis (720p)
 *     -> MediaPipe detecta personas
 *     -> SubjectTracker actualiza cropBox
 *     -> TrackingOverlay renderiza el encuadre
 *
 *   Stream 2: VideoCapture (4K)
 *     -> Grabacion limpia sin procesamiento
 *     -> El crop se aplica como overlay visual,
 *       no modifica el video grabado (full quality)
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val tracker: SubjectTracker
) {

    // --- CameraX components ---

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val personDetector = PersonDetector(context)

    // --- Estado ---

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

        // Use Case 1: Preview
        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // Use Case 2: ImageAnalysis (720p para MediaPipe)
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    processFrame(imageProxy, onDetections)
                }
            }

        // Use Case 3: VideoCapture (4K)
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.UHD,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
                )
            )
            .build()

        videoCapture = VideoCapture.withOutput(recorder)

        // Bind al lifecycle
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
                videoCapture
            )
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

    // --- Grabacion ---

    fun startRecording(
        onRecordingStarted: () -> Unit = {},
        onRecordingError: (String) -> Unit = {}
    ) {
        val capture = videoCapture ?: run {
            onRecordingError("VideoCapture no inicializado")
            return
        }

        val outputFile = createOutputFile()
        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
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
                        }
                    }
                }
            }
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    private fun createOutputFile(): File {
        val moviesDir = context.getExternalFilesDir("Movies")
            ?: context.filesDir
        val timestamp = System.currentTimeMillis()
        return File(moviesDir, "jess_$timestamp.mp4")
    }

    // --- Lifecycle ---

    fun shutdown() {
        personDetector.close()
        analysisExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}
