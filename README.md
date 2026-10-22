# Dheeraj Lens 🎥📺
### High-Performance Multi-Channel RTSP CCTV Viewer for Android TV & Google TV

[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Google%20TV-3DDC84?logo=android&logoColor=white)](https://developer.android.com/tv)
[![Android Media3](https://img.shields.io/badge/Media3%20ExoPlayer-v1.8.0-blue)](https://developer.android.com/media/media3)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![API Level](https://img.shields.io/badge/API-23%2B%20(Android%206.0%2B)-brightgreen)](https://developer.android.com)

**Dheeraj Lens** is a lightweight, low-latency RTSP live CCTV surveillance viewer engineered specifically for **Android TV, Google TV, and smart TV platforms**. It provides real-time multi-tile grid monitoring with D-pad navigation, independent audio monitoring, per-camera stream quality toggling (HD/SD), and hardware-backed local encryption for DVR credentials.

---

## 💡 The Real-World Engineering Problem Solved

Most Android smart TVs (powered by MediaTek, Realtek, or Amlogic SoCs) have a strict hardware limitation: **their hardware video decoder (`c2.mtk.hevc.decoder`) only supports a single active concurrent instance** or fails with `0xfffffff4 / NO_MEMORY` when multiple high-resolution H.265/HEVC feeds are rendered at the same time.

Default media players attempt to initialize the hardware decoder for every camera feed, causing all secondary tiles to crash with `DECODER_FAILED` / `CodecException`.

**Dheeraj Lens solves this architectural bottleneck** by implementing a custom `MediaCodecSelector` with `DefaultRenderersFactory`:
- Intelligently prioritizes multi-threaded software decoders (`c2.android.hevc.decoder`, `c2.android.avc.decoder`) across the TV CPU's cores.
- Bypasses hardware instance limits, enabling simultaneous 3, 4, 8, or more concurrent camera feeds on standard smart TV chipsets.
- Provides automatic HD-to-SD decoding fallback and self-healing auto-reconnect for dropped network connections.

---

## ✨ Key Features

- 📺 **Android TV / Leanback Optimized**: 100% remote D-pad navigability, sticky immersive fullscreen, and automatic `FLAG_KEEP_SCREEN_ON` for 24/7 continuous operation.
- ⚡ **Multi-Stream Concurrent Decoding**: Decode multiple simultaneous H.265/HEVC and H.264 camera streams without hardware session exhaustion.
- 🔄 **Per-Tile Dynamic Quality Toggle (HD / SD)**: Toggle between Mainstream (HD) and Substream (SD) independently on each tile.
- 🛡️ **Hardware-Backed AES-GCM Encryption**: DVR passwords and endpoints are encrypted in `AndroidKeyStore` (`AES/GCM/NoPadding`) so sensitive credentials are never stored in plaintext.
- 🔊 **Per-Camera Audio Monitoring**: One-click audio listening for any selected camera feed with automatic volume ducking on other tiles.
- 🔁 **Self-Healing Auto-Reconnect**: Automatic 5-second retry for network timeouts and transient RTSP drops.
- 🎛️ **Universal RTSP URL Templating**: Compatible with Dahua, CP Plus, Hikvision, Uniview, and any ONVIF/RTSP compliant DVR/NVR/IP camera.

---

## 🛠️ RTSP Path Templates

| DVR / Camera Brand | RTSP Path Template |
| :--- | :--- |
| **Dahua / CP Plus** | `/cam/realmonitor?channel={channel}&subtype={subtype}` |
| **Hikvision** | `/Streaming/Channels/{channel}0{subtype}/` *(channel: 1, 2... subtype: 1 for HD, 2 for SD)* |
| **Uniview** | `/unicast/c{channel}/s{subtype}/live` |
| **Generic RTSP** | `/ch{channel}/stream{subtype}` |

> *Note: `{channel}` is automatically replaced with the 1-based camera number, and `{subtype}` with `0` (HD) or `1` (SD).*

---

## 🏗️ Technical Architecture

- **Video Engine**: [AndroidX Media3 ExoPlayer 1.8.0](https://developer.android.com/media/media3) (`media3-exoplayer`, `media3-exoplayer-rtsp`, `media3-ui`).
- **Decoder Management**: Custom `MediaCodecSelector` prioritizing `c2.android.hevc.decoder` & `c2.android.avc.decoder`.
- **UI Framework**: Programmatic Android Layouts (zero XML layout overhead, ultra-fast TV cold starts, minimal garbage collector churn).
- **Security**: Android Keystore AES-GCM 128-bit IV authenticated encryption.
- **Minimum SDK**: API 23 (Android 6.0 Marshmallow).
- **Target SDK**: API 35 (Android 15).

---

## 🚀 Building & Installing

### Prerequisites
- Android Studio Ladybug / Meerkat or Command Line Tools
- JDK 17 or higher
- Android SDK with platform-tools (`adb`)

### Build Debug APK
```bash
./gradlew assembleDebug
```

### Install Directly on Android TV via ADB
1. Enable **Developer Options** and **Network Debugging** on your Android TV / Google TV.
2. Connect to the TV via ADB:
   ```bash
   adb connect <TV_IP_ADDRESS>:5555
   ```
3. Install the APK:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. Launch the application:
   ```bash
   adb shell am start -n com.example.cctvtv/.MainActivity
   ```

---

## 🔒 Privacy & Security

- All DVR connection parameters (IP, RTSP Port, Username, Password) are stored locally on the TV inside encrypted SharedPreferences.
- Cryptographic keys are protected by the hardware-backed `AndroidKeyStore`.
- The application does not collect analytics, send telemetry, or connect to any cloud server outside your local network DVR.

---

## 🤝 Contributing

Contributions, bug reports, and feature requests are welcome!
1. Fork the Project.
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`).
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`).
4. Push to the Branch (`git push origin feature/AmazingFeature`).
5. Open a Pull Request.

---

## 📄 License

Distributed under the **MIT License**. See `LICENSE` for more information.
