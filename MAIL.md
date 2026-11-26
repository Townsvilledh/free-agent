# MAIL.md — Gmail + Calendar in Lille's core (v0.13)

Lille can now reach Gmail and Google Calendar herself from the
Python core's task engine — the gap behind "read the invite from
my Gmail" becoming a list of asks is closed by building, per
Ethuram's law: she handles tasks end to end.

Tools (loop allowlist, `free_agent/mailtools.py`):

| Tool | What it does | Gate |
|---|---|---|
| `gmail_search` | Search the connected mailbox; returns id/date/from/subject/snippet per message, redacted | Read-only scope |
| `gmail_read` | Read one message: headers + redacted ≤500-char body snippet + host-extracted plain links | Read-only scope |
| `gmail_send` | Send from the connected account | **Disabled by default** — three gates, below |
| `calendar_list` | Upcoming events on the primary calendar (window in days) | Read-only scope |
| `calendar_get` | One event, incl. a redacted description snippet | Read-only scope |
| `calendar_create` | Create an event | **Disabled by default** — three gates, below |

Spec mode picks the tools up automatically when a spec mentions
Gmail / inbox / email / calendar, and the task text carries the
workflow + honesty rules.

## One-time setup (the only step that is the user's)

On the host:

```bash
pip install google-api-python-client google-auth-oauthlib   # optional deps
python3 -m free_agent --google-connect
```

`--google-connect` needs an OAuth client JSON (type **Desktop
app**, from Google Cloud Console with the Gmail API and Google
Calendar API enabled) saved as `~/.freeagent/client_secrets.json`
(or pointed to by `FREEAGENT_GOOGLE_CLIENT_SECRETS`). If the file
is missing, the command prints exactly those steps. The consent
opens in the user's own browser on the host; the resulting token
is stored **only** in Lille's encrypted vault
(`~/.freeagent/vault.enc`, key name `google_oauth_token`).

- Default consent = **read-only scopes only**:
  `gmail.readonly` + `calendar.readonly`.
- `--google-send` additionally requests `gmail.send`;
  `--google-calendar-write` additionally requests
  `calendar.events`. Asking for a scope at connect time does NOT
  enable the write by itself (see gates).
- `python3 -m free_agent --google-status` reports connected /
  not connected with scope **names only**.
- `python3 -m free_agent --google-disconnect` deletes the token
  from the vault.

## Security shape

- **Token**: vault-only. Never in chat, code, git, logs, task
  traces or args summaries (summaries carry message/event ids
  and sizes only; a send summary is recipient-domain + char
  counts). Expired tokens refresh against Google and the
  refreshed JSON goes straight back into the vault.
- **Redaction before the model**: everything the tools return
  passes through `redact.py` first. One-time codes, card
  numbers, email addresses and phone numbers are stripped host-
  side. Mail bodies reach the model only as `redact.snippet()`
  (≤ 500 chars) — never a full mailbox dump.
- **Links**: extracted host-side from the *already-redacted*
  body. Query strings and fragments are stripped; URLs with
  credential cues (reset / token / verify / password / …) are
  dropped entirely. A plain invite link — the registration-mail
  case this was built for — survives and reaches the task.
- **Writes are gated three times**, and every gate is the
  user's/host's decision, never the model's:
  1. Host config flag: `FREEAGENT_GMAIL_SEND=1` /
     `FREEAGENT_CALENDAR_WRITE=1` (both default OFF).
  2. A per-action approval via a real host approval hook
     (`approvals.require_approval`); the Sentinel refuses the
     default allow-all hook for these tools outright.
  3. The connected token must actually carry the write scope.
- **Not connected is honest**: with no token (or no google
  libraries, or an unrefreshable token), the read tools return
  `GMAIL_NOT_CONNECTED` / `CALENDAR_NOT_CONNECTED` naming the
  single setup step above. Lille reports that step — she never
  asks the user to fetch or paste mail contents as a workaround,
  and the loop's system prompt + spec task text both say so.
- **Privacy note**: mail/calendar content is private data. What
  reaches a model is only the redacted snippet/link shape above;
  hosts should still treat mail tasks as private tasks (the
  router's privacy routing governs which brain may serve them).

## What is NOT built (honest remainder)

- **No live proof yet**: the connect flow has never been run
  against a real Google account — everything above is unit-proven
  with fake services (tests/test_mailtools.py). The first real
  `--google-connect` + a live search/read is the remaining proof.
- **Android app**: no Gmail/Calendar tool in the app. On the
  device lane, mail is read via her browser session (the user
  signs in themselves in her WebView; she never sees the
  password) — that path is unchanged.
- No label/draft/attachment handling, no multi-calendar support
  (primary only), no send scheduling. Deliberately out of scope
  until the read path is live-proven.
