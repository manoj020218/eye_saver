# @eye-saver/device-guard

Kotlin-backed parental-control primitives for React Native Android: a permission-gated full-screen break overlay, Device Admin lock, usage-access settings, and Device Owner-only package suspension/lock task.

Normal Play Store apps cannot silently become Device Owner, cannot secretly block every other app, and cannot change system grayscale. `getDeviceGuardCapabilities()` lets the final app select an honest enforcement level.

For a dedicated child phone, provision the final app as Device Owner during device setup. On a shared phone, ask the parent to explicitly grant Display over other apps and use the black break overlay. When `@eye-saver/parent-auth` is installed, the overlay verifies its rate-limited Eye Saver parent PIN through an app-private provider and ends the child session itself. This avoids launching the underlying video app into Picture-in-Picture.

The overlay blocks interaction with the app underneath; it cannot desaturate another app's pixels. Strong YouTube suspension requires Device Owner or profile-owner policy authority.

## Selective app guard

The shared-phone fallback can monitor the foreground package with explicitly granted Usage access and show an overlay only for parent-selected launchable apps:

```ts
const apps = await getLaunchableApps();
await setRestrictedPackages(['com.google.android.youtube', 'com.android.vending']);
await startSelectiveGuard(1500);
```

The selection is stored natively. In the Eye Saver app, selected apps remain usable while screen time is available; the child-session event receiver starts the monitor only when the timer expires. Starting, resuming, pausing, or ending a session stops the monitor and clears its overlay. A longer interval reduces polling and battery use but makes the post-expiry overlay appear later.

This is best-effort on a shared device: system UI can appear above app overlays, Picture-in-Picture behavior varies by video app, and a user can revoke Usage/overlay access or uninstall the app. Device Owner mode should use `setPackagesSuspended()` and uninstall restrictions for tamper-resistant control. Keep the dialer unselected if calls must remain available.

## Shared-phone tamper guard

`setTamperGuardEnabled(true)` enables a separate low-frequency foreground monitor while the child timer is active. It protects Android Settings/App info, common package installers, Play Store and common manufacturer stores with the same native parent-PIN overlay. Calls and ordinary unselected apps stay available. The monitor polls every two seconds, stops when the session pauses or expires, and hands enforcement to the selective post-expiry guard.

Removing the React Native activity from Recents does not stop the native foreground timer or tamper guard. This is still not equivalent to Device Owner: Android does not let an ordinary app disable the system Force Stop control, and a force-stopped package cannot receive its boot receiver until the user launches it again.

## Guided settings links

The package exposes direct, parent-initiated Android settings actions for overlay, Usage access, Device Admin, battery-optimization exemption, and manufacturer AutoStart pages. `openAutoStartSettings()` currently recognizes common Xiaomi, Huawei, Oppo/OnePlus, and Vivo components and falls back to Eye Saver's app-details page. Capability state can be refreshed when the host activity resumes so the UI immediately marks completed settings as ready.
