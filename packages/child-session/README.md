# @eye-saver/child-session

Low-power, screen-on-aware child usage sessions for React Native Android. A foreground service persists state and schedules only warning/expiry callbacks; it does not poll every second. Screen-off time is excluded.

Start it only from a visible parent action and request Android 13+ notification permission first. Android requires the ongoing notification while the session runs.

```ts
await requestNotificationPermission();
await startChildSession({
  durationMs: 20 * 60_000,
  warningBeforeMs: 2 * 60_000,
  blockOnExpire: true,
  verificationMinUsedMs: 6 * 60_000,
  verificationMaxUsedMs: 12 * 60_000,
});
const unsubscribe = onChildSessionEvent(event => {
  if (event.eventType === 'expired') showBreakScreen({triggeredAtMs: event.timestampMs});
  if (event.eventType === 'verification_required') startVisibleFaceCheckpoint();
});
```

`blockOnExpire` keeps the foreground timer alive in the expired state so an enforcement package in the same app can keep a break overlay active. The host must stop the session after successful parent authentication. It does not itself request overlay permission or silently change another app's colours.

When both verification bounds are positive, the native service chooses one unpredictable checkpoint inside that active screen-time window. Screen-off time does not advance the checkpoint. The chosen point and pending/completed timestamps are persisted across process restarts. After the camera adapter produces a real local match result, call `completeChildVerification(true)`; a failed result keeps the checkpoint pending.
