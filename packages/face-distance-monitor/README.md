# @eye-saver/face-distance-monitor

Android-only React Native module that estimates viewing distance from front-camera face landmarks. It uses CameraX and the bundled ML Kit face detector, processes frames on-device, and does not save images.

Distance is approximate. The parent must first hold the same phone at a measured distance from one forward-facing face. The module stores the median inter-eye pixel distance from that calibration, then estimates later distance using the ratio between the calibrated and current inter-eye measurements.

```ts
const permission = await requestFaceDistanceCameraPermission();
await startFaceDistanceCalibration({profileId: 'child-1', knownDistanceCm: 35});

const unsubscribe = onFaceDistanceEvent(event => {
  if (event.eventType === 'calibration_complete') {
    startFaceDistanceMonitor({
      profileId: 'child-1',
      thresholdCm: 25,
      clearThresholdCm: 30,
      intervalMs: 1500,
    });
  }
});
```

Three consecutive near samples trigger a non-touchable warning banner over other apps. Two readings beyond the clear threshold dismiss it. Head-angle checks and median smoothing reduce false alerts.

The camera foreground service must be started while Eye Saver is visible. Android shows its camera privacy indicator and an ongoing notification. The service releases the camera while the screen is off and stops when the child session is paused, expires, or ends. Other apps may temporarily take camera ownership.

This package detects a face but does not identify whose face it is. Child identity requires a separate face-embedding model integrated with `@eye-saver/face-verification-core`.
