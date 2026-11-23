# SECURITY.md — FreeAgent threat model & rules

Security is the top requirement for this project. The design goal: at least
as safe as a managed assistant, and safer on logins/sessions because
everything runs on a host the user controls.

## Threat model
| Threat | Mitigation |
|---|---|
| Key/token leaks via chat, code, git, logs | Secrets only in the encrypted vault; `.gitignore` covers `.env`, vault, tokens, browser profiles, logs; audit logs are metadata-only |
| A free provider retaining/reading private mail | **Privacy routing**: private content never goes to AgentRouter (community relay, unknown retention) or Groq by default; only trusted providers (local model) or a redacted local summary path |
| Over-broad Google access | Gmail OAuth starts with `gmail.readonly` only; send scope is a separate opt-in (`FREEAGENT_GMAIL_SEND=1`) plus per-send approval |
| Stolen session cookies (= stolen accounts) | Browser profile lives only on the host, dir 0700, never exported/committed/synced; treat it like a password file |
| Agent acting on its own (sending, posting, paying) | Approval gates: send/post/delete/money always require explicit user approval by default |
| One-time codes being captured | Codes are redacted before any LLM call, never logged, never stored; 2FA is user-approved per use |
| Prompt/content logging by us | Prompt logging OFF by default; no telemetry, no analytics, anywhere |

## Vault
- Backend: Fernet (`cryptography`). Vault file + key file are `0600`.
- If `cryptography` is unavailable the vault **refuses** to store secrets
  rather than falling back to plaintext. (An explicitly-named insecure stub
  exists only for unit tests on machines without the library.)
- Keys enter the vault via a local setup command on the host — never via
  chat, never via a message to any assistant, never in a file that syncs.

## Gmail
- Read-only scope first; listing/search/metadata only in v0.1.
- Mail is fetched to the host; an LLM receives at most a 500-char
  **redacted snippet** (`redact.snippet`). No full mailbox dumps, ever.
- Verification codes in mail are redacted by pattern before any model call.

## Logins (X/Twitter, GitHub, Google, …)
- Login happens **in the host browser** (Playwright persistent profile),
  by the user, once. The agent reuses the session; it does not collect or
  store raw passwords. Password entry, when unavoidable, goes through the
  vault flow on the host — the agent never sees password text in chat/logs.
- Session cookies are secrets: profile dir is host-only and gitignored.
- Any 2FA step pauses the task and asks the user; codes are single-use,
  user-approved, never stored.
- This is safer than pasting passwords into third-party apps: nothing
  leaves the host except the site's own normal login traffic.

## Privacy routing (config: `FREEAGENT_PRIVACY_MODE=strict`, default)
1. Content is categorised (`public`, `private:gmail`, `private:account`, …).
2. Private categories skip every provider with `trusted_for_private=False`.
3. If no trusted provider is enabled, the task blocks with an explanation —
   it does not silently downgrade.
4. A per-task explicit user override is the only exception; overrides are
   recorded in the metadata audit (provider + category, never content).

## Residual risks (honest)
- The host itself is the crown jewel: anyone with host access + the vault
  key has the accounts. Keep the host patched, disk encrypted where the
  provider allows, and the vault key out of backups that sync publicly.
- Free providers' own policies can change; re-check retention terms when
  enabling a new provider for anything sensitive.
- A local 8B model is weaker than frontier models — privacy for mail
  triage is traded against some capability, by design.
