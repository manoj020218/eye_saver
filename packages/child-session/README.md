# @eye-saver/child-session

Low-power, screen-on-aware child usage sessions for React Native Android. A foreground service persists state and schedules only warning/expiry callbacks; it does not poll every second. Screen-off time is excluded.

Start it only from a visible parent action and request Android 13+ notification permission first. Android requires the ongoing notification while the session runs.

```ts
await requestNotificationPermission();
await startChildSession({durationMs: 20 * 60_000, warningBeforeMs: 2 * 60_000});
const unsubscribe = onChildSessionEvent(event => {
  if (event.eventType === 'expired') showBreakScreen({triggeredAtMs: event.timestampMs});
});
```
