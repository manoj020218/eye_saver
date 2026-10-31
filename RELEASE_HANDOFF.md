# Eye Saver Android release handoff

This document is the authoritative release procedure for `com.eyesaver.app`. Use this toolchain for every Google Play update unless a Play policy or security update requires a deliberate migration. Do not casually upgrade Gradle, React Native, Android Gradle Plugin, Kotlin, the JDK, SDK, NDK, or npm dependencies during a release.

## Product identity

- App name: Eye Saver
- Application ID: `com.eyesaver.app`
- First release: `versionCode 1`, `versionName 1.0.0`
- Minimum Android version: API 24 (Android 7.0)
- Target and compile API: API 36 (Android 16)
- Play artifact: Android App Bundle (`.aab`)
- Release engine: Hermes
- React Native New Architecture: enabled
- Release optimization: R8 code shrinking and resource shrinking enabled

The application ID is permanent after the first Play upload. Never change it for an update.

## Pinned build toolchain

The validated release on October 31, 2026 used:

| Component | Validated version |
| --- | --- |
| Operating system | Windows 10 build 17763 |
| PowerShell | Windows PowerShell 5.1.17763.316 |
| Node.js | 22.23.2 |
| npm | 10.9.9 |
| React | 19.1.1 |
| React Native | 0.82.1 |
| React Native Gradle plugin | 0.82.1 |
| React Native CLI used by the app | 20.0.0 |
| TypeScript | 5.9.3 |
| ESLint | 8.57.1 |
| Jest | 29.7.0 |
| `react-native-safe-area-context` | 5.10.1 |
| JDK | Eclipse Temurin 21.0.12.1 LTS, 64-bit |
| Gradle wrapper | 9.0.0 |
| Android Gradle Plugin | 8.12.0 |
| Kotlin Gradle plugin | 2.1.20 |
| Android SDK platform | android-36 |
| Android Build Tools | 36.0.0 |
| Android platform-tools / adb | 36.0.2 |
| Android NDK | 27.1.12297006 |
| CMake | 3.22.1 |
| Native ABIs | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` |

Android library source and bytecode compatibility is Java 17. The build itself runs on JDK 21.

The committed `package-lock.json`, Gradle wrapper, React Native version catalog, and Android Gradle files are the source of truth. The table records the known-good environment; do not replace the lockfile with newly resolved versions.

## Machine setup

Expected local paths on the validated Windows machine:

- Android SDK: `C:\Users\User\AppData\Local\Android\Sdk`
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`
- Upload keystore: `apps\eye-saver\android\eye-saver-upload.jks`
- Signing properties: `apps\eye-saver\android\keystore.properties`
- Local recovery copy outside this repository: `D:\IOT Device\Face + Proxymity\eye-saver-upload.jks`
- Password record outside this repository: `D:\IOT Device\Face + Proxymity\eye-saver-release-secrets.txt`

Set `JAVA_HOME` to the JDK 21 directory. Make sure the Android SDK contains platform 36, Build Tools 36.0.0, NDK 27.1.12297006, and CMake 3.22.1.

## Dependency restoration

From the repository root, restore exactly the committed dependency graph:

```powershell
npm.cmd ci
```

Do not run `npm update`, delete `package-lock.json`, or accept dependency upgrades as part of an ordinary app release. Upgrade dependencies in a separate change, then repeat all device and release verification.

## Versioning every Play update

Before every upload, edit `apps/eye-saver/android/app/build.gradle`:

1. Increase `versionCode` by at least one. Google Play rejects a code that has already been uploaded.
2. Set `versionName` to the public release number, for example `1.0.1` or `1.1.0`.
3. Update the release notes in `play-store/store-listing.md`.

Never reuse a `versionCode`, including codes used in internal or closed testing.

## Required verification

Run from the repository root:

```powershell
npm.cmd run app:typecheck
npm.cmd run lint --workspace @eye-saver/app
npm.cmd test --workspace @eye-saver/app -- --runInBand
```

Then run the release checks:

```powershell
Set-Location .\apps\eye-saver\android
.\gradlew.bat lintRelease bundleRelease --no-daemon
```

The release bundle is written to:

`apps\eye-saver\android\.build-work\app\outputs\bundle\release\app-release.aab`

The R8 mapping file is written to:

`apps\eye-saver\android\.build-work\app\outputs\mapping\release\mapping.txt`

Archive the signed `.aab` and its matching `mapping.txt` together for every release. The `.build-work` and `artifacts` directories are intentionally ignored by Git.

## Signing rules

- Never commit `eye-saver-upload.jks`, `keystore.properties`, a password, or a copied secrets file.
- Keep the existing upload key for all updates. Do not generate a new key for each release.
- Keep at least two encrypted backups of the upload keystore and the external password record in separate secure locations.
- The external recovery copy listed above is one local backup, not a substitute for a second backup on a separate encrypted device or password manager.
- Enrol the app in Google Play App Signing. The local key is the upload key; Google protects the app-signing key.
- If the upload key is lost after Play App Signing is enabled, use Play Console's upload-key reset procedure. Do not publish a second app identity.

Before upload, verify that the bundle is signed:

```powershell
& "$env:JAVA_HOME\bin\jarsigner.exe" -verify -verbose -certs `
  .\apps\eye-saver\android\.build-work\app\outputs\bundle\release\app-release.aab
```

The result must say `jar verified`; it must not say `jar is unsigned`.

## Release-manifest invariants

Every production build must retain all of the following:

- `targetSdkVersion 36` or the newer API level then required by Google Play.
- No `android.permission.INTERNET` in the merged release manifest.
- Camera foreground service declared as `camera` with `FOREGROUND_SERVICE_CAMERA`.
- Child-session, app-guard, and tamper-guard services declared as `specialUse` with accurate subtype descriptions.
- All non-public components exported as `false`.
- `android:allowBackup="false"` and `android:usesCleartextTraffic="false"`.

The debug variant intentionally has Internet access for Metro. The release variant explicitly removes it.

## Device regression test

Test the signed release-derived build on at least one API 36 device and one older supported Android device. Cover:

- Fresh install and first parent-PIN creation.
- Incorrect PIN and lockout behaviour.
- Usage access, overlay, Device Admin, battery, notification, and manufacturer AutoStart return paths.
- Starting, pausing, resuming, expiring, and parent-stopping a session.
- Selected-app overlay after expiry and unselected Phone app availability.
- Settings/installer tamper protection and the parent-unlock path.
- Screen-off timer pause, reboot recovery, and notification visibility.
- Camera denied, camera allowed, guided child-profile calibration, too-close warning, and camera stop.
- Uninstall/deactivation and a fresh reinstall.

Capture the final Play screenshots from the release UI, not from a development-only screen.

## Play Console update procedure

1. Recheck the current target-API deadline and foreground-service policy.
2. Reconfirm Data safety against the actual dependency graph and merged manifest.
3. Verify the public privacy URL: `https://eyesaver.iotsoft.in/privacy/` (source in `site/`, deployment in `site/deploy/README.md`). Update `site/privacy/index.html` before any release that changes data practices.
4. Upload the signed `.aab` to internal testing first.
5. Complete the foreground-service declarations and attach an unlisted demonstration video showing how each service starts, remains perceptible, and stops.
6. Run the Play pre-launch report and review Android vitals.
7. Promote through the required closed test when applicable, then use a staged production rollout.

See `play-store/release-checklist.md`, `play-store/data-safety.md`, and `play-store/store-listing.md` for the current console copy and declarations.

## Known environment note

The Android SDK currently contains a stale `platform-tools.backup` directory beside `platform-tools`. Gradle reports it as a duplicate package location, but it did not block the validated build. Remove or archive that backup outside the SDK during maintenance; do not change the SDK while preparing an urgent release.
