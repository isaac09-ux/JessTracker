# JessTracker 🏐

> AI-powered single-subject tracking for Android. Tap a player, lock on, never lose them.

Built for volleyball coaches and athletes who want professional-grade subject tracking without expensive hardware. Works entirely on-device — no cloud, no subscription, no gimbal required.

---

## The Problem

Existing auto-framing tools (Samsung Auto Framing, etc.) are designed for video calls with 1-2 people. In a volleyball court with 6 players moving fast, they fail — erratic zoom, wrong subject, late reframing.

JessTracker solves this by letting you **tap the player you want to follow** and locking on to them using AI-based re-identification. If they leave the frame, the tracker remembers their visual "fingerprint" and re-acquires them when they return.

---

## Features

- 👆 **Tap-to-select** — touch any player on screen to lock on
- 🔁 **Re-identification** — recovers subject after leaving frame using HSV color embedding
- 📱 **Fully on-device** — runs on Samsung Galaxy S24 Ultra (and other Android devices)
- 🎯 **Dynamic crop** — output is always centered on your selected subject
- ⚡ **Real-time** — processes at 60fps with EMA smoothing for stable framing

---

## Architecture

```
CameraX (preview + capture)
    ↓
ImageAnalysis (per frame)
    ↓
MediaPipe Person Detection
    ↓
SubjectTracker (tap selection + IoU tracking)
    ↓
EmbeddingExtractor (HSV re-identification)
    ↓
Canvas overlay (dynamic crop)
    ↓
VideoCapture (saves result)
```

---

## Tech Stack

| Component | Library |
|---|---|
| Camera | CameraX |
| Person Detection | MediaPipe Tasks Vision |
| Image Processing | Android Canvas + Bitmap |
| Language | Kotlin |
| Min SDK | Android 10 (API 29) |

---

## Project Structure

```
JessTracker/
├── build.gradle                  # Project-level Gradle config
├── settings.gradle               # Module declarations
├── gradle.properties             # JVM and AndroidX config
├── gradlew                       # Gradle wrapper script
├── gradle/wrapper/
│   └── gradle-wrapper.properties
└── app/
    ├── build.gradle              # App-level dependencies
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/layout/
        │   └── activity_main.xml
        └── java/com/jesstracker/
            ├── MainActivity.kt
            ├── camera/
            │   ├── CameraManager.kt    # CameraX setup and frame pipeline
            │   └── PersonDetector.kt   # MediaPipe object detection wrapper
            ├── tracking/
            │   ├── SubjectIdentity.kt  # Visual fingerprint of the selected subject
            │   ├── EmbeddingExtractor.kt # HSV histogram embedding (96-dim vector)
            │   ├── SubjectTracker.kt   # State machine: tracking -> lost -> re-id
            │   └── SmoothingFilter.kt  # EMA filter for stable crop movement
            └── ui/
                ├── CameraPreviewView.kt # PreviewView + touch listener
                └── TrackingOverlay.kt   # Bounding box + crop visualization
```

---

## Roadmap

- [x] SubjectIdentity data structure
- [x] HSV embedding extractor
- [x] SubjectTracker state machine
- [x] EMA smoothing filter
- [x] CameraX integration
- [x] Touch-to-select UI
- [x] Dynamic crop + recording
- [ ] MobileNetV3 deep embedding (stronger re-ID)
- [ ] Multi-subject tracking
- [ ] Jump detection + auto slow-mo

---

## Use Cases

- 🏐 Volleyball technique analysis
- ⚽ Soccer player tracking
- 🏃 Athletics training footage
- 👨‍👩‍👧 Parent filming their kid at sports events

---

## Contributing

Issues and PRs are welcome. If you test this at a real training session, open an issue with your results — field data is gold for improving the re-identification algorithm.

---

## License

MIT — use it, improve it, ship it.

---

*Built by Isaac · Chihuahua, México · 2026*
