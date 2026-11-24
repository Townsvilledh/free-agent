# ARCHITECTURE.md / SPEC

## Components
1. **Agent core loop** (`free_agent/agent.py`, mirrored on-device in the
   Android app): load memory files → build messages → route → (future:
   tool calls) → reply. v0.2 is live chat + routing in both the Python
   core and the Android app; the tool-call loop is a later milestone.
2. **Provider router** (`free_agent/router.py` + `free_agent/routes.py`):
   the **Fallback Law** (full spec in ROUTES.md). Per-capability route
   chains, tried in order: **keyless Pollinations (default, no key —
   see KEYLESS.md)** → AgentRouter → Groq (both only when the user
   supplied their OWN key) → Gemini-free / OpenRouter-:free /
   Cloudflare stubs. On 402/429/503/network error a route **cools
   down** (Retry-After when given, else its configured cooldown), is
   skipped while cooling, and is automatically re-probed after —
   switching happens inside one call, so multi-step tasks continue
   without re-sending. If **all** routes are cooling down, the router
   returns an honest **queued** result with the earliest retry time
   instead of an error. Enforces privacy routing — keyless public
   endpoints are untrusted for private content, same as the relays,
   and privacy overrides availability (a cooling trusted route queues
   a private task; it never spills to an untrusted one); writes a
   metadata-only audit trail. The Android app calls the same keyless
   endpoint directly (POST, never prompt-in-URL), mirrors the
   cooldown/auto-continue behaviour, and enforces the same policy
   on-device before any request leaves the phone. Search has its own
   live keyless chain (`tools/search_tool.py`): DuckDuckGo Instant →
   Wikipedia → Open Library.
3. **Memory** (`memory/`): `SOUL.md` persona, `USER.md` user facts,
   `MEMORY.md` durable facts — plain files, user-editable, **no
   secrets**. On the phone these sync as ordinary files (never the
   vault).
4. **Tools**: Gmail (readonly OAuth, redacted snippets — AppAuth on
   Android), Browser (Playwright persistent profile on a server host;
   **WebView / Custom Tabs on the phone**), scheduler; filesystem/shell
   tools land sandboxed in a later milestone.
5. **Scheduler — split by privacy and by layer (see HOSTING.md)**:
   - **On the phone (WorkManager)**: Gmail triage (read-only, private —
     stays on-device), daily AgentRouter check-in session check,
     provider health. Subject to Doze delays.
   - **On GitHub Actions (non-private only)**: daily AgentRouter
     check-in heartbeat (`jobs/daily_checkin.py` via
     `.github/workflows/agent-jobs.yml`, 06:17 UTC), provider health,
     public research. Public logs ⇒ nothing private ever runs here.
     If a session is dead, the job reports it; the user re-logs-in on
     the phone (the agent never handles a raw password/2FA itself).
   - **Cloudflare Workers**: cron/webhook relay between phone and
     Actions — metadata envelopes only, no content, no browser.

## Interface — APK-first
Ethuram's only working device is a Samsung Galaxy A12, and he asked
for an app. The primary interface is the **native Android app**
(`android/`, design in APP.md): chat screen, approvals screen,
settings/vault screen, and approval notifications with Approve/Reject
buttons. It is distributed as a direct APK download from GitHub
Releases — no Play Store fee. **Telegram/WhatsApp are demoted to
optional extras**, not the plan of record. The Python CLI remains the
reference core and test bed.

## Hosting: $0, layered (full detail in HOSTING.md)
- **Layer 0 — the phone is the host.** Brains are free remote APIs, so
  the A12's load is light; no local LLM. Autonomous *while the phone
  lives* — Doze can delay jobs; the phone being off pauses them. Not a
  24/7 server, and never described as one.
- **Layer 1 — GitHub Actions (public repo).** Free/unlimited runners
  for public repos; builds the APK and runs scheduled **non-private**
  jobs. Ephemeral, 6 h cap, state committed back encrypted or kept on
  the phone; schedules auto-disable after 60 days of inactivity, which
  the agent's own commits reset.
- **Layer 2 — Cloudflare Workers free.** 100k requests/day, 5 cron
  triggers, 10 ms CPU — a relay/queue only, never a brain or browser.
- **Layer 3 — OPTIONAL: Oracle Cloud Always Free.** 2 OCPU / 12 GB ARM,
  200 GB, no expiry — adds true 24/7 browser autonomy. Possible blocker:
  signup needs a credit/credit-like debit card for identity (prepaid/
  virtual often refused), and idle instances can be reclaimed. Nothing
  in v1 depends on it.

## Security mapping (rules unchanged, Android equivalents in APP.md)
Privacy routing, redaction before any LLM call, encrypted vault
(Fernet on the core / **Android Keystore + EncryptedSharedPreferences**
in the app), Gmail `gmail.readonly` first, sessions treated as
secrets, and approval gates for send/post/delete/money apply on every
layer and every surface. GitHub Actions is inside the trust boundary
only for non-private work; the vault and all private content never
leave the phone (or, later, the user's own server).

## Milestones
- [x] v0.1 router + privacy routing + vault + redaction + Gmail/browser skeletons + tests
- [x] v0.2 **Keyless brain wired into app chat** (Pollinations POST, no keys anywhere) + **attachments picker** (preview + name note; multimodal understanding still planned) + **Fallback Law** (ROUTES.md: per-capability chains, route cooldowns + automatic recovery, honest all-routes-down queueing; live for text + search) + Actions APK build live. Personal keys remain optional, vault-only.
- [ ] v0.2.5 **Attachments understanding + image generation in-app** (keyless multimodal/image endpoints), voice in/out
- [ ] v0.3 **Actions scheduled jobs live** (daily check-in, health) + **Cloudflare Workers relay**; Gmail triage live on-phone (readonly)
- [ ] v0.4 **Optional Oracle layer** (if the card check can be passed): 24/7 browser autonomy; opt-in Gmail send scope with approval UI; X posting via on-device session with approval
