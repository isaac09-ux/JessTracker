# MediaPipe — no ofuscar modelos ni clases internas de TFLite
-keep class com.google.mediapipe.** { *; }
-keep class org.tensorflow.lite.** { *; }

# CameraX
-keep class androidx.camera.** { *; }
