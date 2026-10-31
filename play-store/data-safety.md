# Google Play Data safety worksheet

This worksheet describes the current **production release build**. Reconfirm every answer after adding any SDK or network feature.

## Recommended declarations

- Does the app collect or share required user data types? **No.** Data is processed locally and is not transmitted off the device.
- Is all user data encrypted in transit? **Not applicable; the production app does not transmit user data.**
- Can users request deletion? **Not applicable to server data; no account or server copy exists.** Explain that local data can be deleted by clearing app storage or uninstalling.
- Independent security review: **No**, unless one is completed before submission.

## Local-only processing to disclose in the privacy policy

| Data or access | Purpose | Storage | Leaves device? |
| --- | --- | --- | --- |
| Parent PIN derivative | App functionality and security | Encrypted private storage | No |
| Session timing and timestamps | App functionality | Private app storage | No |
| Parent-selected package names | App functionality | Private app storage | No |
| Current foreground package | App enforcement | Processed during protection | No |
| Optional camera frames | Approximate distance analysis | Memory only; not saved | No |
| Optional numeric face template | Future local verification | Encrypted private storage | No |

## Permission declarations that need Play Console explanations

- Camera and camera foreground service: parent-started, optional, on-device distance estimation with visible indicator and notification.
- Special-use foreground services: parent-started active-screen timer, selected-app enforcement, and tamper protection; each is user-visible and stops when the parent ends the session.
- Usage access: detect the current foreground app so only parent-selected apps and protected system screens are covered.
- Display over other apps: show the break or too-close warning over the app being used.
- Request ignore battery optimizations: maintain a time-sensitive parent-started safety/wellbeing session; the app also provides an ongoing notification and an explicit stop path.
- Device Admin: optional anti-removal measure; no wipe policy and no device-password policy are requested.

## Recheck before every release

- Inspect the merged release manifest.
- Inspect all third-party SDKs and transitive dependencies.
- Confirm the release build still has no Internet permission.
- Make the Play Console answers match the published privacy policy exactly.
