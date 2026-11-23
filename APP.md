# APP.md — FreeAgent Android app (APK-first)

The primary interface is a native Android app, distributed as a direct
APK download from the GitHub repo's Releases — open source, **no Play
Store fee** ($25 developer account avoided entirely). Telegram/WhatsApp
interfaces are demoted to optional extras.

## Target device
- Samsung Galaxy A12: Helio P35, 3–4 GB RAM, Android 10/11.
- **minSdk 29 (Android 10)**, targetSdk 34. Keep the app light: no local
  LLM, no bundled models; brains are the free remote APIs (AgentRouter
  → Groq fallback, per the router/privacy rules in SECURITY.md).

## Design (Kotlin, single-activity to start)
- **Chat screen** — the main surface: conversation with the agent,
  streaming replies, tool-activity indicators.
- **Approvals screen** — pending actions (send email, post, delete,
  money) with full detail and Approve / Reject. Nothing in these
  categories executes without a tap here or on the matching
  notification.
- **Settings / Vault screen** — unlock the vault, add/remove provider
  keys, connect Gmail, toggle privacy mode and foreground-service mode.
- **Notifications as approvals** — Android notifications with
  **Approve / Reject action buttons** for approval-gated actions, so
  Ethuram can clear a gate without opening the app.

## Security on Android (mapping of SECURITY.md rules)
| Server/vault rule | Android equivalent |
|---|---|
| Encrypted vault (Fernet, 0600) | **Android Keystore**-backed encryption: Jetpack Security **EncryptedSharedPreferences** (AES-256, keys in hardware-backed Keystore where available). API keys + OAuth tokens live only there. |
| Keys never in chat/logs/git | Keys are typed once into Settings, stored encrypted, and **never leave the device except to their own provider** (the AgentRouter key goes only to agentrouter.org, etc.). No key is ever written to logs, analytics (none), or backups (vault excluded from Auto Backup). |
| Gmail least privilege | OAuth via **AppAuth for Android**, scope **`gmail.readonly`** only. Send scope is a separate, later, opt-in grant — same rule as the Python core. |
| Privacy routing | Same policy, enforced on-device before any request: private content is not sent to AgentRouter/Groq; redaction runs on-device first (port of `redact.py`); overrides are per-task and explicit. |
| Browser sessions are secrets | No Playwright on the phone. Browser tasks run in **WebView / Custom Tabs on-device**; the WebView profile/cookies stay in the app's private storage, never exported or synced. Posting etc. still passes the approval gate. |
| Approval gates | Notification + Approvals-screen gates for send/post/delete/money. No standing auto-approval in v1. |
| One-time codes | Never stored, never sent to an LLM, redacted on-device; 2FA steps pause and ask the user, exactly as in SECURITY.md. |

## Background work
- **WorkManager** periodic jobs: daily AgentRouter check-in reminder/
  session check, Gmail triage (read-only), provider health check.
  WorkManager respects Doze — jobs may be delayed, never promised
  on-the-minute (see HOSTING.md, Layer 0).
- **Foreground service** (opt-in, visible notification) for long tasks
  the user starts, so Android does not kill them mid-run.

## Signing & distribution
- Release APKs are signed with **Ethuram's own keystore**, which is
  itself a secret: it is kept in the vault (encrypted storage), never
  in the repo, never in CI logs. CI receives it only as a GitHub
  Secret if/when release signing is automated; debug builds use the
  standard debug key.
- Distribution: GitHub Releases on the public repo → direct APK
  download → install on the A12 (allow install from browser/files).

## Build path — no SDK needed on the phone or on this machine
There is **no Android SDK/JDK in the current dev environment**, and
installing one on the A12 is not viable. The chosen build path is
**GitHub Actions**: `.github/workflows/build-apk.yml` builds the debug
APK on a free public-repo runner (Java + Android SDK preinstalled)
and uploads it as a workflow artifact / release asset. The scaffold in
`android/` is source-complete structure; the compiled `.apk` binary
comes from that workflow.
