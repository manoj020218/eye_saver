# @eye-saver/face-distance-monitor

Android-only React Native module that estimates viewing distance from front-camera face landmarks. It uses CameraX and the bundled ML Kit face detector, processes frames on-device, and does not save images.

Distance is approximate. Calibration opens a dedicated parent-assisted live camera. The parent keeps the child about one metre from the same phone while the child looks at a pulsing target. The guide checks that exactly one forward-facing face is fully visible and automatically collects four stable inter-eye readings. Frames are analysed in memory; no calibration photo or video is saved.

```ts
const permission = await requestFaceDistanceCameraPermission();
await startFaceDistanceCalibration({profileId: 'child-1', knownDistanceCm: 100, sampleCount: 4});

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

The live guide gives move-closer, move-farther, centring and head-angle feedback. Once alignment is stable, four readings are captured automatically in roughly two seconds. An unstable set restarts automatically without overwriting the previous profile, and the parent can cancel or redo calibration at any time.

Three consecutive near samples trigger a non-touchable warning banner over other apps. Two readings beyond the clear threshold dismiss it. Head-angle checks and median smoothing reduce false alerts.

The camera foreground service must be started while Eye Saver is visible. Android shows its camera privacy indicator and an ongoing notification. The service releases the camera while the screen is off and stops when the child session is paused, expires, or ends. Other apps may temporarily take camera ownership.

This package detects a face but does not identify whose face it is. Child identity requires a separate face-embedding model integrated with `@eye-saver/face-verification-core`.
