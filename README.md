# 👁️ Drishti 2.0

Drishti 2.0 is an advanced, AI-powered assistive Android application designed to empower visually impaired and blind users with greater independence. By leveraging on-device machine learning and cutting-edge vision APIs, Drishti acts as a real-time digital companion for navigating indoor and outdoor environments safely.

---

## 🚀 Key Features

*   **🎙️ Immersive Voice Dashboard**: Complete screen-reader-free conversational interface designed from scratch, supporting hands-free activation.
*   **📷 Real-Time Surroundings Analysis**:
    *   **On-Device Object Detection**: Employs Google MediaPipe Task Vision model (`efficientdet_lite0`) running offline locally to detect nearby obstacles, blocks, and items.
    *   **Visual Question Answering (VQA)**: Asks questions about the current camera feed using multimodal AI models (Gemini / Grok / OpenRouter integration).
*   **🗺️ Smart Navigation**:
    *   **Outdoor Pathfinding**: Integrates OSRM (Open Source Routing Machine) routing with real-time location updates.
    *   **Indoor Assistance**: Dedicated indoor navigation helper utilities.
    *   **Geocoding**: Accurate location descriptions using Nominatim reverse-geocoding engines.
*   **🚨 Multi-Channel SOS Alerting**: Sends instant emergency messages containing GPS coordinates via Firebase Firestore real-time database synchronization and SMTP-based automated email alerting.
*   **🔒 Secure Google Authentication**: Integrated with Google Sign-in and Firebase Auth.
*   **⚙️ Custom Voice Settings**: Adjustable TTS/speech speeds, voice selections, and API key management panel.

---

## 🛠️ Technology Stack

*   **Language**: Kotlin
*   **Framework**: Jetpack Compose (Modern Declarative UI)
*   **Database**: Room DB (Offline storage & preferences) + Firebase Firestore (Cloud persistence & SOS alerts)
*   **Authentication**: Google Identity Client & Firebase Auth
*   **Networking**: Retrofit 2 & OkHttp (for API connectivity)
*   **Machine Learning**: Google MediaPipe Task Vision (`Tasks Vision` SDK for object tracking)
*   **Parser & Serializer**: Moshi (JSON mapping)
*   **Local Secret Configuration**: Secrets Gradle Plugin (supporting `.env` variables safely)

---

## ⚙️ How to Setup and Run

### 📋 Prerequisites
*   [Android Studio](https://developer.android.com/studio) (Ladybug or newer recommended)
*   Android SDK version 24 (Android 7.0) or higher

### 🛠️ Configuration Steps

1.  **Clone the Repository** and open the project in Android Studio.
2.  **Restore Local Secrets**:
    *   Create a file named `.env` in the root folder of the project.
    *   Add your API key credentials like below (refer to `.env.example`):
        ```env
        GEMINI_API_KEY=your_gemini_api_key_here
        GROK_API_KEY=your_grok_api_key_here
        OPENROUTER_API_KEY=your_openrouter_api_key_here
        SARVAM_API_KEY=your_sarvam_api_key_here
        ```
3.  **Firebase Settings**: Add your `google-services.json` inside the `app/` directory if connecting to a custom Firebase database.
4.  **Signing Configuration**:
    *   By default, the debug build expects a debug keystore configurations. You can tweak `signingConfigs` in `app/build.gradle.kts` if you wish to build using custom signing properties.
5.  **Build and Run**: Compile and deploy the application on an emulator or standard Android device.

---

## 🧪 Testing

The codebase implements comprehensive testing modules to ensure stability and UI consistency:
*   **Unit Tests**: Local business logic checking using standard JUnit & Kotlin Coroutines Test framework.
*   **Robolectric & Roborazzi**: Custom Roborazzi integration for automated visual screenshot tests (`GreetingScreenshotTest`).
