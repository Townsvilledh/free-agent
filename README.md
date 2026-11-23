# FreeAgent

An open-source, **completely-free-stack** autonomous agent for one user (Ethuram),
built 2026-11-23. Working name only — it is **not** Muse, not affiliated with
Meta, and not an exact clone of anything. It aims at the same *jobs*: chat with
long-term memory, browse the web, use tools, read Gmail (read-only first), run
scheduled jobs, and act from a phone. **APK-first**: the product is an Android
app for Ethuram's Galaxy A12 (design in APP.md), built for free by GitHub
Actions and installed from a direct APK download — no Play Store fee.

## The free stack
| Layer | Choice | Cost |
|---|---|---|
| Brain (primary) | AgentRouter free credit (OpenAI-compatible `https://agentrouter.org/v1`) | $0 — signup + daily check-in credit |
| Brain (fallback) | Groq free tier (`https://api.groq.com/openai/v1`) | $0 within free limits |
| Brain (more fallbacks) | Gemini free, OpenRouter `:free`, Cloudflare Workers AI (config stubs) | $0 within free limits |
| Brain (private content) | Trusted path only (local model where available) — never community relays by default | $0 |
| App | Native Android (Kotlin) in `android/` — chat, approvals, Keystore vault | $0 |
| APK build | GitHub Actions on the public repo (`.github/workflows/build-apk.yml`) | $0 |
| Hosting | **$0 layered stack — see HOSTING.md**: phone is the host (Layer 0) + GitHub Actions scheduled non-private jobs (Layer 1) + Cloudflare Workers relay (Layer 2) | $0 |
| Hosting (optional upgrade) | Oracle Cloud Always Free (Layer 3) — only if its signup card check can be passed | $0 |
| Memory / scheduler / tools | This repo (Python core, MIT) + WorkManager on-device | $0 |

## Honest limits
- Free tiers are **capped and changeable**: AgentRouter releases premium-model
  quota in daily batches (a `402` means the batch is gone until the next one);
  Groq free has per-model daily token caps. The router falls back automatically,
  but no free stack is unlimited.
- Model quality varies by provider/model; this will not match a frontier
  product on every hard task.
- Free providers can shrink or shut down. The design is multi-provider from
  day one so no single loss kills the agent.
- Phone hosting is **autonomous while the phone lives**, not a 24/7 server:
  Android Doze can delay background jobs, and jobs pause when the phone is
  off. True 24/7 browser autonomy is the optional Oracle layer (HOSTING.md
  Layer 3) — its signup card check may block it, and v1 does not depend on it.
- GitHub Actions runs only **non-private** jobs: public-repo workflow logs
  are public, so Gmail/private content never runs there.

## Security first
Read **SECURITY.md** before adding any key or account. Short version: secrets
live only in an encrypted vault, Gmail starts read-only, private content is
**not** routed to community relays by default, and sending/posting/deleting/
money always needs your approval.

## Run (Python core)
```bash
pip install -r requirements.txt
python -m free_agent --demo     # no keys, no network — proves the wiring
python -m unittest discover tests -v
```

## Get the app (APK)
Push to the public repo → **Actions → build-apk** builds the debug APK for
free and attaches it as a workflow artifact → download and install it on the
A12. No Android SDK is needed on the phone or on any local machine. The
`android/` scaffold is source-complete structure; the compiled `.apk` binary
is produced by that workflow (see android/README.md).

## Layout
- `APP.md` — Android app design (Kotlin, Keystore vault, approvals, WorkManager)
- `HOSTING.md` — the $0 layered hosting stack and its honest caveats
- `android/` — Android Gradle scaffold (chat UI stub; built by Actions)
- `.github/workflows/build-apk.yml` — free APK build + Python tests on Actions
- `.github/workflows/agent-jobs.yml` — scheduled non-private jobs on Actions
- `jobs/daily_checkin.py` — placeholder non-private check-in job (no real logins)
- `free_agent/router.py` — provider fallback + privacy routing + metadata-only audit
- `free_agent/vault.py` — Fernet-encrypted secrets vault (0600); Android equivalent: Keystore + EncryptedSharedPreferences
- `free_agent/redact.py` — strips emails, codes, cards, keys before any LLM sees text
- `free_agent/tools/gmail_tool.py` — least-privilege Gmail (readonly first; AppAuth on Android)
- `free_agent/tools/browser_tool.py` — Playwright, server-host profile (phone uses WebView/Custom Tabs instead)
- `free_agent/scheduler.py` — job definitions incl. daily AgentRouter check-in
- `memory/` — SOUL.md / USER.md / MEMORY.md equivalents (no secrets, ever)
