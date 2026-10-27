# @eye-saver/device-guard

Kotlin-backed parental-control primitives for React Native Android: a permission-gated full-screen break overlay, Device Admin lock, usage-access settings, and Device Owner-only package suspension/lock task.

Normal Play Store apps cannot silently become Device Owner, cannot secretly block every other app, and cannot change system grayscale. `getDeviceGuardCapabilities()` lets the final app select an honest enforcement level.

For a dedicated child phone, provision the final app as Device Owner during device setup. On a shared phone, ask the parent to explicitly grant Display over other apps and use the black break overlay. When `@eye-saver/parent-auth` is installed, the overlay verifies its rate-limited Eye Saver parent PIN through an app-private provider and ends the child session itself. This avoids launching the underlying video app into Picture-in-Picture.

The overlay blocks interaction with the app underneath; it cannot desaturate another app's pixels. Strong YouTube suspension requires Device Owner or profile-owner policy authority.
