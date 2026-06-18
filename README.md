# Drishti 2.0 App

Drishti 2.0 is an assistive Android application designed to aid visually impaired users in navigating their surroundings.

## Run Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set your API keys (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file if building with default signing configs: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device

