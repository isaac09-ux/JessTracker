package com.jesstracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.jesstracker.camera.CameraManager
import com.jesstracker.tracking.SubjectTracker
import com.jesstracker.ui.TrackingOverlay

/**
 * MainActivity — conecta todos los modulos.
 *
 * Flujo:
 *   1. Solicitar permisos (camara + microfono)
 *   2. Inicializar CameraManager con CameraPreviewView
 *   3. CameraManager -> frame -> SubjectTracker.update()
 *   4. SubjectTracker -> cropBox -> TrackingOverlay.update()
 *   5. Usuario toca CameraPreviewView -> SubjectTracker.onTap()
 *   6. Boton REC -> CameraManager.startRecording() / stopRecording()
 */
class MainActivity : AppCompatActivity() {

    // --- Views ---

    private lateinit var previewView: PreviewView
    private lateinit var touchOverlay: View
    private lateinit var trackingOverlay: TrackingOverlay

    // --- Core modules ---

    private val tracker = SubjectTracker()
    private lateinit var cameraManager: CameraManager

    // --- Estado compartido para touch (ultimo frame analizado) ---

    @Volatile private var latestDetections: List<RectF> = emptyList()
    @Volatile private var latestFrame: Bitmap? = null

    // --- Permisos ---

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) startCamera()
        else Toast.makeText(this, "Se necesitan permisos de camara", Toast.LENGTH_LONG).show()
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    // --- Lifecycle ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        touchOverlay = findViewById(R.id.touchOverlay)
        trackingOverlay = findViewById(R.id.trackingOverlay)

        setupTouchListener()
        setupRecordButton()
        checkPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraManager.isInitialized) {
            cameraManager.shutdown()
        }
    }

    // --- Setup ---

    private fun checkPermissions() {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) startCamera()
        else permissionLauncher.launch(requiredPermissions)
    }

    private fun startCamera() {
        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this
        )

        cameraManager.setup(
            previewView = previewView,
            onDetections = { detections, frame ->
                latestDetections = detections
                latestFrame = frame

                val cropBox = tracker.update(detections, frame)

                runOnUiThread {
                    trackingOverlay.update(tracker.state, cropBox)
                    cameraManager.updateAutoFraming(tracker.state, cropBox)
                }
            }
        )
    }

    @Suppress("ClickableViewAccessibility")
    private fun setupTouchListener() {
        touchOverlay.setOnTouchListener { v, event ->
            if (event.action != MotionEvent.ACTION_DOWN) return@setOnTouchListener false

            val screenX = event.x
            val screenY = event.y
            val normalizedX = screenX / v.width.toFloat()
            val normalizedY = screenY / v.height.toFloat()

            trackingOverlay.showTapFeedback(screenX, screenY)

            val frame = latestFrame
            val detections = latestDetections
            if (frame != null && detections.isNotEmpty()) {
                tracker.onTap(PointF(normalizedX, normalizedY), detections, frame)
                val cropBox = tracker.cropBox
                trackingOverlay.update(tracker.state, cropBox)
                cameraManager.updateAutoFraming(tracker.state, cropBox)
            }

            true
        }
    }

    private fun setupRecordButton() {
        val btnRecord = findViewById<Button>(R.id.btnRecord)
        btnRecord.setOnClickListener {
            if (cameraManager.isRecording) {
                cameraManager.stopRecording()
                btnRecord.text = "\u25CF REC"
            } else {
                cameraManager.startRecording(
                    onRecordingStarted = {
                        runOnUiThread {
                            btnRecord.text = "\u25A0 STOP"
                            Toast.makeText(this, "Grabando en 4K...", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRecordingSaved = { uri ->
                        runOnUiThread {
                            btnRecord.text = "\u25CF REC"
                            Toast.makeText(this, "Video guardado en galería: $uri", Toast.LENGTH_LONG).show()
                        }
                    },
                    onRecordingError = { error ->
                        runOnUiThread {
                            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }
    }
}
