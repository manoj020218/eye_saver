# react-native-face-proximity

Low-power Android face-proximity events for React Native. The native side is Kotlin and uses Android's `SensorManager`; it does not run a JavaScript polling loop.

## Features

- Distance, threshold state, and Unix timestamp in every event.
- Immediate enter/exit threshold events.
- Ordinary event throttling via `intervalMs` (100–60,000 ms).
- Sensor automatically unregisters in the background by default.
- Root-level hook for a timestamped alert over the current app screen.
- No proximity-sensor Android permission required.

> Many phones—including the connected Xiaomi test handset—expose only binary values such as `0 cm` and `5 cm`. On those phones, `3 cm` still behaves as near/far; hardware cannot provide centimetre-by-centimetre distance. Inspect `isLikelyBinary` and `maxRangeCm` from `getCapabilities()`.

## Install in another React Native app

```sh
npm install "D:/IOT Device/Face + Proxymity/proxymity"
npx react-native run-android
```

After this repository is pushed, it can also be installed from GitHub:

```sh
npm install github:manoj020218/eye_saver
```

React Native 0.60+ autolinking discovers the Android library. A Metro reload alone is not enough after adding native code.

## Timestamped alert on the current screen

Mount once near the app root:

```tsx
import {useProximityAlert} from 'react-native-face-proximity';

export default function App() {
  useProximityAlert({
    thresholdCm: 5,
    intervalMs: 1000,
    cooldownMs: 5000,
    pauseInBackground: true,
    onTooClose: event => console.log(new Date(event.timestampMs)),
  });
  return <YourNavigation />;
}
```

React Native `Alert` appears above the currently visible app view. It is not a system overlay over other apps.

## Direct event API

```tsx
import {FaceProximity} from 'react-native-face-proximity';

console.log(await FaceProximity.getCapabilities());

const thresholdSub = FaceProximity.onThresholdChange(event => {
  if (event.transition === 'entered') {
    // Navigate to a camera screen, vibrate, show a custom modal, etc.
    console.log(event.distanceCm, event.timestampMs);
  }
});
const measurementSub = FaceProximity.onMeasurement(console.log);

await FaceProximity.start({
  thresholdCm: 5,
  intervalMs: 1000,
  hysteresisCm: 0.5,
  pauseInBackground: true,
});

// Cleanup
thresholdSub.remove();
measurementSub.remove();
await FaceProximity.stop();
```

`intervalMs` requests a native sensor/sample rate and limits ordinary JS event frequency. Android proximity sensors are generally `on-change`, so a stable value may not repeat every interval. Threshold crossings emit immediately so throttling cannot hide a brief safety event.

## Camera trigger

The package emits the trigger but intentionally does not take a photo silently. In `onTooClose`, navigate to your camera UI and capture using your preferred camera library after requesting `CAMERA` permission and user consent. Modern Android also restricts background camera access.

## Battery behaviour

- Keep `pauseInBackground: true` unless background sensing is essential.
- Prefer 500–1000 ms for UI feedback.
- Threshold detection stays native; JavaScript wakes only for emitted events.
- No wake lock, timer service, location, camera, or foreground service is used.

## Tested handset

- Xiaomi `23076RN4BI`
- Android 15 / API 35
- Sensor: `Virtual Proximity Sensor`, wake-up, on-change, observed values `0/5 cm`

## Support

- Android API 21+
- React Native 0.71+
- iOS is not implemented

## Eye Saver package workspace

The final parental-control app can be composed from these small React Native Android libraries:

| Package | Responsibility |
| --- | --- |
| `react-native-face-proximity` | Optional near/far warning from the hardware proximity sensor |
| `@eye-saver/child-session` | Persistent, low-power screen-on usage timer with warning/expiry timestamps |
| `@eye-saver/device-guard` | Device Admin lock, break screen, and Device Owner enforcement primitives |
| `@eye-saver/face-distance-monitor` | Calibrated CameraX/ML Kit approximate face-distance monitoring and warning overlay |
| `@eye-saver/face-verification-core` | Android Keystore-encrypted face embeddings and on-device comparison |
| `@eye-saver/parent-auth` | Keystore-backed, rate-limited parent PIN verification |

An Android-first integration app lives in [`apps/eye-saver`](apps/eye-saver). It wires parent authentication, protection readiness, configurable child sessions, expiry break UI and the optional proximity warning. The CameraX/TFLite face-embedding adapter remains the next implementation milestone.

The face core does not open the camera or identify a face by itself. The final app must add a visible CameraX/TFLite adapter that creates embeddings using one fixed model. Continuous or secret background camera capture is not part of this architecture.
