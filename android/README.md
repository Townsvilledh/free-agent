# android/ — FreeAgent app scaffold

Standard Android Gradle project (Kotlin, single activity). See
**../APP.md** for the design and **../HOSTING.md** for how hosting
stays at $0.

## Status: scaffold, not a compiled APK
This tree is **source-complete structure** — manifest, Gradle files,
MainActivity chat stub, resources. The compiled `.apk` binary requires
an Android SDK build. There is **no Android SDK/JDK in the current dev
environment** and none should be installed on the phone.

## Chosen build path: GitHub Actions (free)
`.github/workflows/build-apk.yml` (repo root) builds the debug APK on
an `ubuntu-latest` public-repo runner — JDK 17 and the Android SDK are
preinstalled there — and uploads `app-debug.apk` as a workflow
artifact. Download it from the Actions run (or a later Release) and
install it on the Galaxy A12. No SDK is needed on the phone or here.

A local build, on any machine that does have the SDK, is the usual:
`gradle assembleDebug` (Gradle 8.7, JDK 17) inside this directory.

## Security notes (must survive every future edit)
- Keys/tokens only in Keystore-backed EncryptedSharedPreferences.
- `android:allowBackup="false"` — the vault never leaves via backup.
- Gmail OAuth is AppAuth + `gmail.readonly`; browser work is
  WebView/Custom Tabs on-device (no Playwright on the phone).
