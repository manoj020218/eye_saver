# @eye-saver/device-guard

Kotlin-backed parental-control primitives for React Native Android: Device Admin lock, usage-access settings, a full-screen break UI, and Device Owner-only package suspension/lock task.

Normal Play Store apps cannot silently become Device Owner, cannot secretly block every other app, and cannot change system grayscale. `getDeviceGuardCapabilities()` lets the final app select an honest enforcement level.

For a dedicated child phone, provision the final app as Device Owner during device setup. On a shared phone, use the visible break screen or Device Admin lock and require a parent PIN before calling `dismissBreakScreen()`.
