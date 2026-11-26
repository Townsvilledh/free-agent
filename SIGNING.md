# SIGNING.md — Lille's stable signing line (v0.9.2+)

## The problem this fixes

Up to and including v0.9.1, every CI build signed the APK with the
GitHub Actions runner's own debug key. Runners are ephemeral: each
run generated a **fresh** debug keystore and discarded it, so every
Lille build carried a **different certificate**. Android refuses to
install a differently-signed build over an installed app
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so every update forced a
full uninstall first — wiping Lille's app data (her task store, her
browser session, her vault) every single time.

## The fix (route: Actions secret)

One dedicated keystore now signs every build:

- RSA 4096, PKCS12, alias `lille`, validity 30 years (2026–2056).
- The keystore lives **only** as the GitHub Actions secret
  `LILLE_KEYSTORE_B64` (base64). The workflow decodes it to the
  runner's temp dir (never into the repo) and passes the passwords
  to Gradle from the companion secrets `LILLE_KEYSTORE_PASSWORD`,
  `LILLE_KEY_ALIAS`, `LILLE_KEY_PASSWORD`.
- Gradle (`android/app/build.gradle`) applies the `lille`
  signingConfig to the debug build type **whenever**
  `LILLE_KEYSTORE_FILE` is present in the environment. Without it
  (local dev, fork PRs — where secrets are unavailable), the build
  falls back to the default debug key and the workflow log says so;
  such a build will not install over a stable-line build.
- A backup of the keystore and its credentials is kept outside the
  repo on Ethuram's machine (`~/workspace/lille-signing/`, owner-only
  permissions). If the secret were ever lost, the backup restores
  the same signing identity. Keystore files are gitignored
  (`*.p12` / `*.keystore` / `*.jks`) so they cannot be committed by
  accident.

## The one last uninstall

Builds up to 0.9.1 each carried a different throwaway key, so
**one last uninstall is required** to leave the old line and install
0.9.2. That uninstall wipes the pre-0.9.2 app data one final time.
**From 0.9.2 onward, updates install in place** — same certificate
every build — and Lille's data (tasks, sessions, vault) survives
updates.

## Verify the line

Signing certificate SHA-256 fingerprint of the v0.9.2 APK (extracted
from the built artifact itself):

    8C:8E:07:6C:CC:29:88:8C:28:F2:A3:7C:EA:76:6E:3B:7F:94:FF:BF:DD:16:0C:93:86:54:44:23:AF:DA:FF:E9

(v0.9.2 APK: 17,520,889 bytes, sha256
`2e782ac13e7049e4d26b548bd9f4d4fb460c6c81ce240d8e775521e99dbf92e1`,
Actions run 45791125499. The fingerprint was extracted from the
built artifact's APK Signature Scheme v2 block and matches the
dedicated keystore's certificate exactly.)

Every future release's APK must show this same fingerprint; the
ship record for each release should state that it was checked. If a
future build ever shows a different fingerprint, it is not on the
stable line and will not install over this one.

## Trade-offs, plainly

- The signing identity is a repository secret, not a hardware key.
  Anyone with write access to this repo's Actions secrets could
  rotate it — but rotating it breaks the line (the next build would
  no longer install over the old), so rotation is a deliberate,
  announced event, never a silent one.
- A production **release** key (Play Store / formal distribution)
  remains a later, opt-in step, kept out of CI entirely. This
  keystore signs the sideloaded debug builds Ethuram installs from
  the one-tap link.
