# Eye Saver 1.0.0 release checklist

## Decisions that must be final before the first Play upload

- Confirm the permanent application ID. The current ID is `com.eyesaver.app`; it cannot be changed after the first production identity is established without publishing a new app.
- Confirm the Play developer account identity and public developer contact details.
- Generate and securely back up the upload key using `scripts/create-android-upload-key.ps1`. Never commit the `.jks` or `keystore.properties` files.
- Deploy `site/` to the VPS (see `site/deploy/README.md`) and verify `https://eyesaver.iotsoft.in/privacy/` opens publicly over HTTPS.

## Play Console setup

- Create the app and complete App content, target audience, content rating, ads, Data safety, privacy policy, and app access forms.
- Use **parents/adults (18+)** as the intended account user. Do not market the app directly to children.
- Declare every camera and special-use foreground-service purpose exactly as documented in `data-safety.md`.
- Record an unlisted reviewer video that demonstrates starting and stopping the child session, app guard, tamper guard, and optional camera monitoring; attach it to the foreground-service declaration.
- Add reviewer instructions and a temporary four-digit parent PIN. Explain: create/unlock PIN → Setup → allow the four required settings → Home → start 1-minute test.
- Upload `assets/app-icon-512.png` and `assets/feature-graphic-1024x500.png`.
- Capture at least two phone screenshots from the final release UI: Home, Quick setup, Apps, and Viewing distance are recommended.

## Build and verification

- `npm run lint --workspace @eye-saver/app`
- `npm test --workspace @eye-saver/app -- --runInBand`
- `cd apps/eye-saver/android && ./gradlew lintRelease bundleRelease`
- Confirm the merged release manifest targets API 36 and does not contain `android.permission.INTERNET`.
- Confirm the signed bundle is produced in the temporary build output shown by Gradle.
- Run Play Console pre-launch report on multiple Android versions and screen sizes.
- Test fresh install, PIN creation, all permission return paths, overlay above YouTube, session expiry, calls, reboot recovery, camera denial, camera monitoring, and uninstall/deactivation.

## Rollout

- Start with internal testing.
- If the developer account is a personal account created after November 13, 2023, complete the required closed test with at least 12 opted-in testers for 14 continuous days.
- Promote to a small production percentage first, monitor Android vitals and policy feedback, then expand.
