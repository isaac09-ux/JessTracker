package com.jesstracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.jesstracker.camera.CameraManager
import com.jesstracker.tracking.SubjectTracker
import com.jesstracker.tracking.TrackerState
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
    private lateinit var txtHint: TextView

    // --- Core modules ---

    private val tracker = SubjectTracker()
    private lateinit var cameraManager: CameraManager

    // --- Estado compartido para touch (ultimo frame analizado) ---
    // Accedidos desde analysis thread (escritura) y UI thread (lectura en tap).
    // Usamos lock para evitar race conditions y reciclar bitmaps correctamente.

    private val frameLock = Object()
    private var latestDetections: List<RectF> = emptyList()
    private var latestFrame: Bitmap? = null

    // --- Double-tap para deseleccionar ---
    private var lastTapTimeMs: Long = 0L
    private companion object {
        const val DOUBLE_TAP_THRESHOLD_MS = 350L
    }

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

        enterImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        touchOverlay = findViewById(R.id.touchOverlay)
        trackingOverlay = findViewById(R.id.trackingOverlay)
        txtHint = findViewById(R.id.txtHint)

        setupTouchListener()
        setupRecordButton()
        checkPermissions()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::cameraManager.isInitialized) {
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            cameraManager.setTargetRotation(rotation)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraManager.isInitialized) {
            cameraManager.shutdown()
        }
        synchronized(frameLock) {
            latestFrame?.recycle()
            latestFrame = null
        }
    }

    // --- Immersive fullscreen ---

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
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

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        cameraManager.setTargetRotation(rotation)

        cameraManager.setup(
            previewView = previewView,
            onDetections = { detections, frame ->
                synchronized(frameLock) {
                    val oldFrame = latestFrame
                    latestDetections = detections
                    latestFrame = frame
                    if (oldFrame != null && oldFrame !== frame && !oldFrame.isRecycled) {
                        oldFrame.recycle()
                    }
                }

                val cropBox = tracker.update(detections, frame)

                runOnUiThread {
                    val conf = tracker.identity?.confidence ?: 0f
                    trackingOverlay.updateStatusInfo(conf, detections.size)
                    trackingOverlay.update(tracker.state, cropBox)
                    updateHintVisibility(tracker.state)
                    cameraManager.updateAutoFraming(
                        state = tracker.state,
                        trackedBox = cropBox,
                        detections = detections,
                        viewWidth = previewView.width,
                        viewHeight = previewView.height
                    )
                }
            }
        )
    }

    private fun updateHintVisibility(state: TrackerState) {
        txtHint.visibility = if (state == TrackerState.IDLE) View.VISIBLE else View.GONE
    }

    @Suppress("ClickableViewAccessibility")
    private fun setupTouchListener() {
        touchOverlay.setOnTouchListener { v, event ->
            if (event.action != MotionEvent.ACTION_DOWN) return@setOnTouchListener false

            val now = System.currentTimeMillis()
            val screenX = event.x
            val screenY = event.y

            trackingOverlay.showTapFeedback(screenX, screenY)

            // Double-tap para deseleccionar: si esta trackeando y toca dos veces rapido, reset.
            if (tracker.state != TrackerState.IDLE && now - lastTapTimeMs < DOUBLE_TAP_THRESHOLD_MS) {
                tracker.reset()
                trackingOverlay.update(tracker.state, null)
                updateHintVisibility(tracker.state)
                lastTapTimeMs = 0L
                return@setOnTouchListener true
            }
            lastTapTimeMs = now

            val normalizedX = screenX / v.width.toFloat()
            val normalizedY = screenY / v.height.toFloat()

            // Snapshot del frame y detecciones bajo lock para evitar race con analysis thread.
            val (frame, detections) = synchronized(frameLock) {
                Pair(latestFrame, latestDetections)
            }

            if (frame != null && !frame.isRecycled && detections.isNotEmpty()) {
                tracker.onTap(PointF(normalizedX, normalizedY), detections, frame)
                val cropBox = tracker.cropBox
                trackingOverlay.update(tracker.state, cropBox)
                updateHintVisibility(tracker.state)
                cameraManager.updateAutoFraming(
                    state = tracker.state,
                    trackedBox = cropBox,
                    detections = detections,
                    viewWidth = previewView.width,
                    viewHeight = previewView.height
                )
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
                            Toast.makeText(this, "Video guardado en galeria: $uri", Toast.LENGTH_LONG).show()
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
