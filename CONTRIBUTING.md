# Contributing to AadiTune

Thank you for considering contributing to AadiTune! All contributions — issue
reports, feature discussion and pull requests — are welcome. This project is
GPL-3.0 licensed; by contributing, you agree your work is licensed under the
same terms.

## Quick start

1. **Fork & clone** the repository.
2. Install **JDK 17** and the Android SDK (API 35) with build-tools 35.0.0.
   `adb`/an emulator is only needed to run the app, not to build it.
3. Build a debug APK:

   ```bash
   ./gradlew assembleFossDebug      # Firebase-free flavor (fastest)
   ./gradlew assembleFullDebug      # full flavor (needs google-services.json)
   ```

4. Code style: keep formatting consistent with Kotlin style used in the
   repository; prefer small, focused PRs.

## Pull requests

- One logical change per PR; describe *what* and *why*.
- UI changes: include before/after screenshots.
- Streaming/extractor changes: must be reversible and must not violate
  service terms (no DRM circumvention, no paid access bypass).
- Update `README.md` feature list if you add a user-visible feature.

## Reporting issues

- Search existing issues first.
- Include: app version (`Settings/About`), Android version, device model,
  precise reproduction steps and (if possible) a `adb logcat` capture.

## Don'ts

- Do **not** commit `google-services.json`, keystores, `.local.properties`
  tweaks with your SDK paths, or any private material.
- Do not open PRs that alter the `LICENSE` terms or strip upstream
  attribution.

## Review

At least one maintainer must review before merge. CI must pass
(`assembleFossRelease` artifact builds successfully) before a release.
