# Eye Saver Android app

React Native 0.82 Android app that composes the local Eye Saver native modules.

## Implemented flow

- First-run Android Keystore-backed parent PIN setup and rate-limited unlock.
- Protection readiness for Device Admin, Usage Access and Device Owner capability.
- Parent-selected 1/20/30/45-minute child sessions.
- Screen-on-aware timer, persistent notification, warning and expiry timestamps.
- Full-screen break UI and parent-PIN exit.
- Optional post-expiry restriction for parent-selected apps, leaving unselected apps such as Phone available.
- Optional foreground-only hardware proximity warning.
- Calibrated CameraX/ML Kit face-distance warning with a native cross-app banner.
- Encrypted face-template readiness check.

The CameraX/TFLite face-embedding adapter is intentionally the next milestone. The app never performs secret background camera capture.

## Run on Android

From the repository root:

```sh
npm install
npm run app:typecheck
npm run app:android
```

For a direct Gradle build:

```sh
cd apps/eye-saver/android
gradlew.bat assembleDebug -PreactNativeArchitectures=arm64-v8a
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

Device Owner features require provisioning on a dedicated/test device during setup. Normal shared-phone installation supports the visible break screen and optional Device Admin lock, but cannot silently suspend arbitrary apps.
