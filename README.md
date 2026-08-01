# 🎬 Minify

Minify is a high-performance Android media compression and editing app. It delivers professional-grade video and photo compression with precise size targeting, a modern Material 3 interface, and integrated editing tools.

---

## 🚀 Key Features

### 📹 Video Compression & Editing
* **Smart Compression**: Compress videos to a specific target size (MB) with accurate bitrate calculation.
* **Hardware Acceleration**: Leverages `MediaCodec` and `Media3 Transformer` for fast, efficient processing.
* **Codec Support**: Choose between **H.264 (AVC)**, **H.265 (HEVC)**, and **AV1** for optimal quality/size trade-offs.
* **Native Editor**: Integrated tools for **trimming** and **cropping** videos before compression.
* **Real-time Progress**: Detailed compression feedback with background service support.

### 🖼️ Photo Compression & Editing
* **Target Size Compression**: Reduce image file sizes while maintaining visual quality.
* **Format Support**: Handles **JPEG**, **PNG**, and **WebP** formats.
* **Integrated Photo Editor**: Apply filters, adjust geometry, and enhance images before saving or compressing.
* **Batch-ready Pipeline**: Architecture designed for high-performance image processing.

### 📱 Modern User Experience
* **Material Design 3**: A beautiful, adaptive UI with **Dynamic Accent Colors**.
* **Comparison Tool**: Side-by-side Before/After comparison for both videos and photos to verify quality.
* **Privacy Focused**: Processes all media locally on your device.
* **Ad-Supported**: Non-intrusive native ads integration.

---

## 🛠️ Tech Stack

* **Language**: [Kotlin](https://kotlinlang.org/)
* **UI Framework**: [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3)
* **Media Processing**: [Media3 Transformer](https://developer.android.com/guide/topics/media/transformer) & [ExoPlayer](https://developer.android.com/guide/topics/media/exoplayer)
* **Storage & Preferences**: [Jetpack DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
* **Background Tasks**: [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
* **Architecture**: MVVM with hoisted state for seamless tab switching.
* **Testing**:
    * **JUnit 5** & **jqwik** (Property-based testing)
    * **Robolectric** for local JVM tests
    * **Mockito** for mocking

---

## 📥 Getting Started

1. Download the latest APK from the [Releases](https://github.com/RiyanBharadwaj/Minify/releases) section.
2. Grant the necessary media permissions.
3. Select a video or photo, set your target size, and hit Compress!

---

## ⚠️ Current Status

**Stable Release — V7.0**
* Optimized for Android 9.0 (API 28) and above.
* Target SDK 35 (Android 15).

---

## 🤝 Contributing

Contributions are welcome! Whether it's reporting a bug, suggesting a feature, or submitting a pull request, your help is appreciated.

---

## 📄 License

This project is licensed under the **MIT License**.

---

## 👨‍💻 Author

Developed by **Riyan Bharadwaj**

---

## ⭐ Support

If you find Minify useful, please consider giving the project a star ⭐ on GitHub. It helps more than you think!
