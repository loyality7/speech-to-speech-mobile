# Speech-to-Speech Mobile SDK: Privacy, Permissions & License Compliance Guide

This document outlines the privacy design, Android runtime permissions, Play Store policy compliance, and open-source software licensing breakdown for the `speech-to-speech-mobile` SDK.

---

## 1. Privacy Architecture (100% On-Device)

The SDK operates **100% locally on-device**. No voice audio, microphone recordings, transcription text, or LLM token streams are ever transmitted to remote servers.

- **Zero Cloud Dependencies**: All pipeline stages (VAD, ASR, LLM, TTS) execute inside native C++/JNI bindings using ONNX runtime and `llama.cpp`.
- **Zero Telemetry / Analytics**: The SDK contains no analytics trackers, diagnostic telemetry SDKs, or background network calls during inference.
- **Model Download Integrity**: Models are fetched directly over HTTPS from verified HuggingFace / public model registries during initial setup and validated against SHA-256 checksums before loading.

---

## 2. Android Runtime Permissions

To integrate the SDK into an Android app, the host app requires the following permissions declared in its `AndroidManifest.xml`:

| Permission | Category | Purpose | Mandatory? |
| :--- | :--- | :--- | :---: |
| `android.permission.RECORD_AUDIO` | Dangerous / Runtime | Required for microphone input frame capture during voice sessions. | **Yes** |
| `android.permission.FOREGROUND_SERVICE` | Normal | Required to maintain background audio processing during voice calls. | Optional (Service mode) |
| `android.permission.FOREGROUND_SERVICE_MICROPHONE` | Android 14+ (API 34+) | Required for background microphone access in Android 14 targetSdk. | Optional (Service mode) |
| `android.permission.POST_NOTIFICATIONS` | Runtime (API 33+) | Display ongoing foreground service status and model download progress. | Optional |

### Google Play Store Data Safety Declaration
When publishing an application using this SDK to the Google Play Store, complete the **Data Safety Form** as follows:

- **Data Collected**: *No user data collected.*
- **Data Shared**: *No user data shared with third parties.*
- **Data Encrypted in Transit**: *N/A (No user data transmitted).*
- **Data Deletion Request**: *N/A (All conversation history resides locally in RAM/app private cache).*

---

## 3. Open Source Licensing & Compliance Audit

The SDK core is published under the **Apache License 2.0**. Native runtime dependencies are audited for license compatibility:

| Component | Library / Engine | License | Compliance Notes |
| :--- | :--- | :--- | :--- |
| **SDK Core** | `com.s2s.mobile` | Apache 2.0 | Permissive commercial redistribution. |
| **LLM Runtime** | `llama.cpp` / `Llamatik` | MIT | Permissive MIT open-source license. |
| **Speech Pipeline** | `sherpa-onnx` | Apache 2.0 | Permissive ONNX inference engine. |
| **VAD Backend** | `Silero VAD v5` / `TEN VAD` | MIT / Apache 2.0 | Permissive neural VAD models. |
| **Acoustic TTS** | `Kokoro` / `Piper` ONNX | Apache 2.0 / MIT | ONNX neural voice bundles. |

> [!IMPORTANT]
> **GPL-3.0 Compliance Audit Note (`Issue #27`)**:
> Legacy TTS backends (e.g., standalone `espeak-ng` binaries) under GPL-3.0 are **excluded** from the production SDK runtime bundle. The SDK exclusively uses pre-compiled ONNX neural synthesis graph models (`sherpa-onnx`) operating under Apache 2.0 / MIT licenses, ensuring full compliance for commercial closed-source applications without GPL copyleft infection.

---

## 4. Android 14 Target SDK 34 Compliance

When targeting Android 14 (`targetSdk 34`), declare the service type explicitly in your `AndroidManifest.xml` if using background voice sessions:

```xml
<service
    android:name="com.s2s.mobile.audio.VoiceSessionService"
    android:foregroundServiceType="microphone"
    android:exported="false" />
```
