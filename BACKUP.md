# BACKUP.md — Lille's autonomous, seamless backup (v0.11)

**The promise (Ethuram's words): "back ups should be autonomous and
seamless even with switching to a different device."** You can
uninstall Lille on one phone, install it on another, sign in with
your Fluid name, and continue from where you left off — chats,
tasks, settings, identity, and the vault. Nobody sets a schedule,
exports a file by hand, or manages a key file. This document is
the design, including its honest limits.

## The key is your Fluid wallet's signature

The backup is one encrypted blob. The encryption key comes from
your wallet, not from a password you must invent and guard:

- Lille asks your connected Fluid wallet to `personal_sign` one
  fixed, versioned message (`BackupCrypto.SIGN_MESSAGE`,
  "Lille backup key v1"). `personal_sign` is the ONLY method the
  Lille↔Fluid session can ever request — it cannot move funds.
- The key is `SHA-256(signature bytes)` — a 256-bit AES key.
  The signature and the key are **never logged, never persisted,
  never sent anywhere**. The key lives in memory only while
  backup/restore operations run (`BackupKeyHolder`); the sealed
  blob is the only thing that is ever stored.
- **Determinism check at setup:** setup signs the message TWICE
  and compares the derived keys. Signature mode turns on only if
  they are identical (Fluid's signing is deterministic — and
  Lille verifies that at runtime, on your wallet, every setup,
  instead of assuming it).
- **Passphrase fallback:** if a wallet's signatures ever differ,
  setup offers passphrase mode instead — same blob format, key
  from PBKDF2-HMAC-SHA256 (210,000 iterations, random 16-byte
  salt). The passphrase is the ONLY way back into those backups;
  the app says so, in a confirmation dialog, before it is set.

The blob itself is AES-256-GCM (random 12-byte IV, 128-bit tag):
a wrong key or a single flipped byte fails decryption outright —
there is no partial restore of a tampered file.

## What's inside

One payload (version 1) holding, byte-for-byte where they are
files: **all chats** (the v0.11 multi-chat store), **the task
store**, **the proactive store**, **app settings/preferences**,
**the identity record** (your Fluid username + address), and
**the vault contents** — decrypted from the on-device vault only
at the moment of sealing, and present only inside the encrypted
payload. The vault is what makes Lille's own X password (and any
secret you stored) recoverable on a new phone.

**Browser/WebView sessions are OPT-IN, default OFF**, behind
their own switch and warning copy: cookies for x.com,
twitter.com and tiktok.com only, exported via CookieManager.
A session cookie is a login — treat the backup accordingly.

## The two layers

**Layer 1 — a folder on your phone (automatic).** One-time
system folder grant (suggested: Documents/Lille). Lille writes
the sealed blob there on every change (debounced through a
dirty flag; also on leaving the chat screen, plus a 12-hour
WorkManager safety net). The folder lives outside app data, so
the copy **survives uninstalling Lille on the same phone**.

**Layer 2 — your own Google Drive (default-on after one
consent).** The same blob, synced to the `appDataFolder` of
YOUR Google account — a hidden per-app folder no other app can
read. The consent is presented as part of backup setup, not
buried in settings, with this copy:

> "Your backup is a locked file only your Fluid wallet can
> open. Google stores the lock-box, never the key."

What Google can see: that a file named `lille-backup.lillebak`
exists, its size, and when it changed. What Google cannot see:
anything inside it. There is no Lille server in this design —
your Drive, your folder, your wallet.

**Status (v0.11.0): shipped DISABLED-BY-CONFIG.** The full
code path (Google sign-in, appDataFolder find/download/
create/update) is in the app behind
`BuildConfig.DRIVE_ENABLED`, which was false until the OAuth
client below existed. In v0.11.0 the app said so plainly on
the backup screen; folder + file backups worked fully.

**Status (v0.11.1, 2026-11-26): Drive layer ENABLED.** The
console steps below were completed in the owner's own Google
account (project `lille-515912`, Drive API on, Android OAuth
client registered for `org.freeagent.app` + the stable
signing cert's SHA-1), the repo variable
`LILLE_DRIVE_ENABLED=true` is set, and the shipped v0.11.1
build carries `DRIVE_ENABLED=true`. No client ID value is
wired into the app: on Android, Google matches the sign-in by
package name + signing-certificate fingerprint, and the app
requests only the `drive.appdata` scope — no server auth
code, no ID token, no secret anywhere.

**What "Testing" means for users today.** The project's
OAuth consent screen is in Google's **Testing** status. In
plain words: Drive sync works only for Google accounts the
owner has added as **test users** on the consent screen (the
owner is being added); everyone else's Drive sign-in is
refused by Google until the consent screen is **published**.
Publishing is a separate step, and its outcome will be
reported separately — until then, a non-test user's backup
still works fully via the folder layer and file export;
Drive is simply not offered to them by Google.

**Correction (2026-11-26): the consent screen is now
published — status "In production."** The paragraph above
was written before the publish step completed. Later the
same day, the consent screen for project `lille-515912` was
published (homepage and PRIVACY.md URLs supplied), so Drive
sync is no longer limited to test users — any user can
consent. No verification has been submitted, so the app
remains **unverified**: two caveats stand until verification
is submitted and approved — a **100-user cap**, and a
possible **"unverified app" warning** shown by Google
during consent.

### Turning Drive on — the exact console steps (for Muse/owner)

1. Go to <https://console.cloud.google.com> and create (or
   select) a project — any name, e.g. "Lille".
2. **APIs & Services → Library**: enable **Google Drive API**.
3. **APIs & Services → OAuth consent screen**: External (or
   Internal if a Workspace org is used), app name "Lille",
   support email = the owner's. Scopes: none need adding here —
   the app requests `drive.appdata` at sign-in.
4. **APIs & Services → Credentials → Create credentials →
   OAuth client ID → Android**:
   - Package name: `org.freeagent.app`
   - **SHA-1 certificate fingerprint** of the STABLE Lille
     signing certificate (SIGNING.md). The signing cert's
     SHA-256 is
     `8C:8E:07:6C:CC:29:88:8C:28:F2:A3:7C:EA:76:6E:3B:7F:94:FF:BF:DD:16:0C:93:86:54:44:23:AF:DA:FF:E9`;
     its SHA-1 (computed from the v0.11.0 APK's signing block)
     is recorded in the v0.11 ship note
     (`live-tests/2026-11-26-v011-note.md`) — use that value.
   No client secret is needed or used (Android OAuth clients
   have none); nothing goes in the repo or the app.
5. Set the GitHub **repo variable** `LILLE_DRIVE_ENABLED` to
   `true` (Settings → Secrets and variables → Actions →
   Variables) and rebuild. The workflow passes it into
   `BuildConfig.DRIVE_ENABLED`. No code change is needed.

## Restore — seamless, and bound to the wallet

On a fresh install / first login with no local chats, Lille
checks the folder locations and Drive (when configured) for a
backup and offers it. Restoring asks for exactly one signature
(or the passphrase), decrypts, and **bind-checks** the payload:
the backup carries the username + address that made it, and it
restores only into that same Fluid identity. A backup made by
a different wallet **cannot be opened by yours at all** — in
signature mode your wallet derives a different key, so
decryption itself fails; the bind check is the second lock,
and the app names the backup's owner when it refuses.

A manual **Restore from backup** lives in Settings → Backup &
restore (from a file, or from Drive), plus manual **file
export/import** as the escape hatch for anyone who wants a
copy in their own hands.

## The forgotten-wallet truth

**No Fluid wallet = no restore, ever.** If you lose the wallet
(the seed phrase behind your Fluid name), no one — not Lille,
not Google, not the developer — can derive your backup key or
open your backups. That is the same rule your money already
lives by, and it is stated in the app, not just here. In
passphrase mode the equivalent truth holds for the passphrase.

## Known limits (honest)

- The key is memory-only by design, so automatic writes happen
  while Lille's process is alive with an unlocked key (the
  normal case: writes are change-driven and immediate). After
  a cold start, one signature (or passphrase entry) re-unlocks
  writing; until then changes are marked dirty, not lost, and
  the backup screen says the key is locked.
- Layer 1 survives uninstall, not a lost/wiped phone — that is
  exactly what Layer 2 is for.
- Restoring replaces the chats/tasks/vault on the new phone
  with the backup's. Your live Fluid connection is untouched.
