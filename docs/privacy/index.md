---
title: Eye Saver Privacy Policy
permalink: /privacy/
---

# Eye Saver Privacy Policy

**Effective date:** October 29, 2026  
**App:** Eye Saver for Android  
**Operator:** Eye Saver developer

Eye Saver is designed to work locally on the Android phone. The production app has no advertising, analytics, account system, or cloud service, and does not transmit personal data to the developer or third parties.

## Information processed on the device

Eye Saver may process the following information only on the phone:

- A parent PIN. The PIN is transformed using a salted key-derivation function and the result is encrypted with Android Keystore; the plain PIN is not stored.
- Child-session settings and events, including selected duration, timer state, start time, remaining active-screen time, and the time a warning or break was triggered.
- The list of apps a parent selects for protection and the identity of the app currently visible while an active session is being enforced.
- Optional front-camera frames for approximate face-to-phone distance. Frames are analysed in memory, are not saved as photos or video, and are not transmitted.
- Optional face-template data if a future face-verification feature is explicitly enrolled. Templates are numerical embeddings encrypted with Android Keystore; raw enrollment photos are not stored.
- Device capability and permission status needed to show whether protection is ready.

This on-device processing is used only to provide parent-configured screen-time, break-overlay, anti-tamper, and optional viewing-distance features.

## Android permissions and special access

- **Camera:** optional, used only when a parent starts calibration or enables distance monitoring. Android displays its camera privacy indicator and an ongoing notification during monitoring.
- **Usage access:** used during protection to determine which app is currently in the foreground so a parent-selected app, Settings, or an installer can be covered. Eye Saver does not create or upload a browsing-history profile.
- **Display over other apps:** used to place the parent-configured warning or break screen above another app.
- **Notifications and foreground services:** used to make active background protection visible and controllable.
- **Device Admin:** optional best-effort protection against casual removal. Eye Saver does not erase the device or change the device unlock credential.
- **Run after restart and battery exemption:** used to restore an active parent-started session reliably after a normal reboot and to prevent Android from prematurely stopping it.

All optional access can be withdrawn from Android Settings. Withdrawing access may disable the related protection feature.

## Data sharing and sale

Eye Saver does not sell, share, or transmit personal information. The production build does not include advertising, analytics, social-login, or crash-reporting SDKs.

## Storage, security, and retention

Settings and session state remain in the app's private Android storage. Sensitive PIN and face-template values are encrypted using keys protected by Android Keystore. Data is retained until it is replaced, the app's storage is cleared, or Eye Saver is uninstalled. Because there is no account or server, the developer has no remote copy to retrieve or delete.

## Children

Eye Saver is configured and controlled by a parent or legal guardian. It is not a social service, does not show ads, and does not knowingly collect or transmit information from children. A parent should explain active camera-distance monitoring to the child and use it only with appropriate consent.

## Changes

If the app later introduces a network service, analytics, advertising, or another material data practice, this policy and the Google Play Data safety declaration will be updated before that feature is released.

## Contact

For privacy questions or deletion assistance, open a support request at [github.com/manoj020218/eye_saver/issues](https://github.com/manoj020218/eye_saver/issues). Do not include a PIN, face image, or other sensitive information in a public issue.
