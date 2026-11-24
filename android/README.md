# android/ — FreeAgent app

Standard Android Gradle project (Kotlin, single activity). See
**../APP.md** for the design, **../KEYLESS.md** for the no-keys
doctrine, and **../HOSTING.md** for how hosting stays at $0.

## Status: v0.2 — live keyless chat + attachments picker
MainActivity is wired to the keyless public brain
(`POST https://text.pollinations.ai/openai`, no API key), with
conversation history, a thinking state, and graceful handling of the
anonymous rate limit (queue + one retry). The Attach button opens the
system document picker (images + files) with a thumbnail/name preview;
attachments are noted by name in the conversation — the model does not
read their contents yet (multimodal is a planned step, never faked).

The compiled `.apk` binary requires an Android SDK build. There is
**no Android SDK/JDK in the current dev environment** and none should
be installed on the phone — GitHub Actions builds it (below).

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
