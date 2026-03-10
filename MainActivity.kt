package com.jesstracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jesstracker.camera.CameraManager
import com.jesstracker.tracking.SubjectTracker
import com.jesstracker.tracking.TrackerState
import com.jesstracker.ui.CameraPreviewView
import com.jesstracker.ui.TrackingOverlay

/**
 * MainActivity — conecta todos los módulos.
 *
 * Flujo:
 *   1. Solicitar permisos (cámara + micrófono + storage)
 *   2. Inicializar CameraManager con CameraPreviewView
 *   3. CameraManager → frame → SubjectTracker.update()
 *   4. SubjectTracker → cropBox → TrackingOverlay.update()
 *   5. Usuario toca CameraPreviewView → SubjectTracker.onTap()
 *   6. Botón REC → CameraManager.startRecording() / stopRecording()
 */
class MainActivity : AppCompatActivity() {

    // ─── Views ────────────────────────────────────────────────────────────────

    private lateinit var previewView: CameraPreviewView
    private lateinit var trackingOverlay: TrackingOverlay

    // ─── Core modules ─────────────────────────────────────────────────────────

    private val tracker = SubjectTracker()
    private lateinit var cameraManager: CameraManager

    // ─── Permisos ─────────────────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) startCamera()
        else Toast.makeText(this, "Se necesitan permisos de cámara", Toast.LENGTH_LONG).show()
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        trackingOverlay = findViewById(R.id.trackingOverlay)

        setupTouchListener()
        setupRecordButton()
        checkPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.shutdown()
    }

    // ─── Setup ───────────────────────────────────────────────────────────────

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
            lifecycleOwner = this,
            tracker = tracker,
            onFrameProcessed = { cropBox, state ->
                // Actualizar overlay en el hilo principal
                runOnUiThread {
                    trackingOverlay.update(
                        TrackerState.valueOf(state),
                        cropBox
                    )
                }
            }
        )

        cameraManager.setup(
            previewView = previewView,
            onDetections = { detections, frame ->
                // SubjectTracker corre en el hilo de análisis (no bloquea UI)
                val cropBox = tracker.update(detections, frame)
                val state = tracker.state.name

                runOnUiThread {
                    trackingOverlay.update(tracker.state, cropBox)
                }
            }
        )
    }

    private fun setupTouchListener() {
        previewView.onSubjectSelected = { normalizedPoint: PointF ->
            // Necesitamos las detecciones actuales para saber qué tocó el usuario
            // Por ahora registramos el tap — PersonDetector lo completará
            Toast.makeText(this, "Sujeto seleccionado", Toast.LENGTH_SHORT).show()
        }

        previewView.onTapFeedback = { x, y ->
            trackingOverlay.showTapFeedback(x, y)
        }
    }

    private fun setupRecordButton() {
        findViewById<android.widget.Button>(R.id.btnRecord).setOnClickListener {
            if (cameraManager.isRecording) {
                cameraManager.stopRecording()
                (it as android.widget.Button).text = "● REC"
            } else {
                cameraManager.startRecording(
                    onRecordingStarted = {
                        (it as android.widget.Button).text = "■ STOP"
                        Toast.makeText(this, "Grabando en 4K...", Toast.LENGTH_SHORT).show()
                    },
                    onRecordingError = { error ->
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }
}
