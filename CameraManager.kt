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
 * Dos streams simultáneos:
 *
 *   Stream 1: ImageAnalysis (720p)
 *     → MediaPipe detecta personas
 *     → SubjectTracker actualiza cropBox
 *     → TrackingOverlay renderiza el encuadre
 *
 *   Stream 2: VideoCapture (4K)
 *     → Grabación limpia sin procesamiento
 *     → El crop se aplica como overlay visual,
 *       no modifica el video grabado (full quality)
 *
 * Por qué separar los streams:
 *   MediaPipe no necesita 4K para detectar personas.
 *   Darle 720p lo hace 4x más rápido y libera el hilo
 *   de grabación para mantener 4K/60fps sin drops.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val tracker: SubjectTracker,
    private val onFrameProcessed: (cropBox: RectF?, state: String) -> Unit
) {

    // ─── CameraX components ───────────────────────────────────────────────────

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    // Executor dedicado para análisis de frames — no bloquea el hilo principal
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Detector de personas — inicializado una sola vez en el executor
    private val personDetector = PersonDetector(context)

    // ─── Estado ───────────────────────────────────────────────────────────────

    var isRecording: Boolean = false
        private set

    // ─── Setup ───────────────────────────────────────────────────────────────

    /**
     * Inicializa la cámara y enlaza todos los use cases al lifecycle.
     *
     * @param previewView  La vista donde se muestra el preview
     * @param onDetections Callback con los bounding boxes detectados en cada frame
     */
    fun setup(
        previewView: PreviewView,
        onDetections: (detections: List<RectF>, frame: Bitmap) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        // Inicializar PersonDetector en background antes de arrancar la cámara
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

        // ── Use Case 1: Preview ───────────────────────────────────────────────
        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // ── Use Case 2: ImageAnalysis (720p para MediaPipe) ───────────────────
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // STRATEGY_KEEP_ONLY_LATEST descarta frames si el análisis es lento
            // Evita que se acumule una cola y el tracker quede desincronizado
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    processFrame(imageProxy, onDetections)
                }
            }

        // ── Use Case 3: VideoCapture (4K) ─────────────────────────────────────
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.UHD,           // 4K primero
                    FallbackStrategy.higherQualityOrLowerThan(Quality.FHD) // fallback 1080p
                )
            )
            .build()

        videoCapture = VideoCapture.withOutput(recorder)

        // ── Bind al lifecycle ─────────────────────────────────────────────────
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

    // ─── Procesamiento de frames ──────────────────────────────────────────────

    /**
     * Convierte ImageProxy → Bitmap y lo pasa al tracker.
     * Corre en analysisExecutor (hilo separado).
     */
    private fun processFrame(
        imageProxy: ImageProxy,
        onDetections: (List<RectF>, Bitmap) -> Unit
    ) {
        val bitmap = imageProxy.toBitmap()
        val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees.toFloat())

        // Detectar personas con MediaPipe y pasar al tracker
        val detections = personDetector.detect(rotatedBitmap)
        onDetections(detections, rotatedBitmap)

        // Liberar el imageProxy — crítico, si no se libera CameraX se congela
        imageProxy.close()
    }

    /**
     * Rota el bitmap según la orientación del sensor.
     * El sensor trasero del S24U puede estar rotado 90° respecto a la pantalla.
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ─── Grabación ────────────────────────────────────────────────────────────

    /**
     * Inicia la grabación en 4K.
     * El video se guarda en el directorio de Movies de la app.
     *
     * @param onRecordingStarted Callback cuando la grabación arranca
     * @param onRecordingError   Callback si hay error
     */
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

    /**
     * Detiene la grabación activa.
     */
    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    /**
     * Crea el archivo de salida con timestamp para evitar colisiones de nombre.
     */
    private fun createOutputFile(): File {
        val moviesDir = context.getExternalFilesDir("Movies")
            ?: context.filesDir
        val timestamp = System.currentTimeMillis()
        return File(moviesDir, "jess_$timestamp.mp4")
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Liberar recursos cuando la Activity/Fragment se destruye.
     * Llamar desde onDestroy().
     */
    fun shutdown() {
        personDetector.close()
        analysisExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}
