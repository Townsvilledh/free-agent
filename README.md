# Lille

![Lille mascot](assets/lille-mascot-jacket.webp)

**Lille** is an open-source, **completely-free-stack** autonomous agent for
one user (Ethuram), built 2026-11-23. It is **not** Muse, not affiliated
with Meta, and not an exact clone of anything. It aims at the same *jobs*:
chat with long-term memory, browse the web, use tools, read Gmail
(read-only first), run scheduled jobs, and act from a phone. **APK-first**:
the product is an Android app for Ethuram's Galaxy A12 (design in APP.md),
built for free by GitHub Actions and installed from a direct APK download
— no Play Store fee.

> **Names:** the product is **Lille**. The GitHub repo stays
> `free-agent`, and the Python package / Android applicationId keep the
> `freeagent` name — renaming those would break the build and the
> download links for no user benefit.

## The free stack
| Layer | Choice | Cost |
|---|---|---|
| Brain (default) | **Keyless public API** — Pollinations (`POST https://text.pollinations.ai/openai`) | $0 — **no key, no signup, nothing collected (see KEYLESS.md)** |
| Brain (optional upgrade) | Your OWN AgentRouter free-credit key (`https://agentrouter.org/v1`) and/or Groq free-tier key — added by you, in the app's vault only | $0 within free limits; never required, never shipped, never shared |
| Brain (more fallbacks) | Gemini free, OpenRouter `:free`, Cloudflare Workers AI (config stubs) | $0 within free limits |
| Brain (private content) | Trusted path only (local model where available) — never community relays by default | $0 |
| App | Native Android (Kotlin) in `android/` — chat, approvals, Keystore vault | $0 |
| APK build | GitHub Actions on the public repo (`.github/workflows/build-apk.yml`) | $0 |
| Hosting | **$0 layered stack — see HOSTING.md**: phone is the host (Layer 0) + GitHub Actions scheduled non-private jobs (Layer 1) + Cloudflare Workers relay (Layer 2) | $0 |
| Hosting (optional upgrade) | Oracle Cloud Always Free (Layer 3) — only if its signup card check can be passed | $0 |
| Memory / scheduler / tools | This repo (Python core, MIT) + WorkManager on-device | $0 |

## Honest limits
- The keyless anonymous tier is **slow by design**: ~1 request at a time,
  roughly 45s between requests per IP, `402` + `Retry-After` beyond that,
  best-effort with no uptime guarantee. The app queues/retries and the
  router falls through — but it will never feel like a paid tier.
- Keyed free tiers are **capped and changeable**: AgentRouter releases
  premium-model quota in daily batches (a `402` means the batch is gone
  until the next one); Groq free has per-model daily token caps. The
  router falls back automatically, but no free stack is unlimited.
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
Read **SECURITY.md** before adding any key or account. Short version: **no
API key is needed at all** (KEYLESS.md); any personal key you choose to add
lives only in the encrypted vault on your device, Gmail starts read-only,
private content is **not** routed to public/keyless endpoints or community
relays by default, and sending/posting/deleting/money always needs your
approval.

## Run (Python core)
```bash
pip install -r requirements.txt
python -m free_agent --demo     # no keys, no network — proves the wiring
python -m free_agent            # live chat via the keyless brain — still no keys
python -m free_agent --task "Create a Python program that ..." --pace 50
                                # v0.3 tool-call loop: Lille plans, runs tools
                                # in a sandbox, verifies, finishes (LOOP.md)
python -m free_agent --tasks    # v0.4: durable tasks — checkpointed after
python -m free_agent --resume <id>   # every step, resumable in a new process,
python -m free_agent --pause <id>    # waiting (not failing) when the network
python -m free_agent --cancel <id>   # or a free route drops (OFFLINE.md)
python -m free_agent --commit "…" --check "actions-run:owner/repo:123" --every 5
                                # v0.7: register a commitment — a result Lille
                                # must surface when it lands (PROACTIVITY.md)
python -m free_agent --enqueue "…"   # queue a task; nobody starts it —
python -m free_agent --proactive-run # a proactive pass picks it up, works the
                                # due checks, and writes reports unprompted
python -m free_agent --sweep "…"     # v0.7: a THOROUGH sweep — many keyless
                                # surfaces, several phrasings, dedup vs the
                                # known-items record, mandatory coverage note
python -m unittest discover tests -v
```

## Muse-twin roadmap — capability status (honest, updated per release)
Lille's scope is "everything Muse has that the app needs — only the
style/face differs". Status as of **v0.8**:

| Muse capability | Lille status |
|---|---|
| Chat with an AI brain | **Built (v0.2)** — keyless public brain wired into the app + Python core |
| Attachments — pictures & files picker | **Built (v0.2)** — picker + image thumbnail preview; attachment noted by name in the conversation |
| Attachment understanding (model actually reads the image/file) | Planned — multimodal keyless models are the next step; v0.2 does **not** fake it |
| Image generation | In progress — keyless endpoint chosen (`image.pollinations.ai`, see KEYLESS.md); in-app UI lands next |
| Voice in / voice out | Planned |
| Web search | **Built (v0.2)** — keyless chain: DuckDuckGo Instant → Wikipedia → Open Library (`tools/search_tool.py`); full web results are a candidate, honestly marked in ROUTES.md |
| Web browsing / browser tasks | **Built in core (v0.8)** — a persistent-profile Chromium driven by deterministic tools (open/read/click/type/upload/screenshot/wait) with structural perception (visible text + element refs), a per-task domain allowlist, vault-typed secrets that never enter model context, and CHALLENGE stop-and-report on human-only walls (XLANE.md). On top sits the X lane (session/feed/search/post, Sentinel posting gates). Lille's OWN X account is being created by her own spec run: the run stands at NEEDS ETHURAM — she drove the real signup to valid submits and met X's Arkose human-verification wall, with everything pre-staged (live-tests/2026-11-25-v08-proof.md). The browser lives on the host running her core/runner, NOT on the phone — no on-device browser yet. Known limitation: this Chromium build blocks loopback navigation (local dev servers) outright |
| Fallback routing (every capability, 4–5 routes deep, cooldowns, honest queueing) | **Built (v0.2, text + search)** — the Fallback Law, see ROUTES.md; image/voice chains defined with honest gaps |
| Gmail access | In progress — readonly-first OAuth skeleton built; live connection is a later milestone |
| Long-term memory (SOUL/USER/MEMORY files) | Built (core) — loaded into the system prompt; on-device sync planned |
| Scheduled / autonomous jobs | **Built (v0.7)** — the Proactivity Law as machinery: a commitments register (pending results with machine-checkable landing conditions + deadlines), proactive passes (due checks, idle pickup of queued tasks, waiting-task resumes) writing DONE / LANDED / BLOCKED reports to an outbox unprompted, on two lanes — on-device WorkManager periodic passes and the scheduled Actions lane `lille-proactive.yml` (non-private only; state on the `lille-state` branch). The app's Tasks screen shows the "While you were away" reports + the commitments register (PROACTIVITY.md) |
| Approval gates (send/post/delete/money) | Built (core policy) — Android approvals UI planned |
| Autonomous tool use (plan → act → observe → verified finish) | **Built in Python core (v0.3)** — tool-call loop with native + text-fallback tool calls, deterministic Sentinel, sandboxed code/file tools, live-proven end-to-end (`live-tests/2026-11-24-v03-proof.md`, LOOP.md) |
| Background / offline tasks (works while the user is away) | **Built (v0.4)** — durable checkpointed task engine in core (`--task/--resume/--tasks/--pause/--cancel`; waiting_network/waiting_route instead of fake failures) AND in the app (Tasks screen, WorkManager foreground workers, boot recovery, `needs_runner` spec export for code tasks). Runner lane for fully-offline completion: `lille-task.yml` on Actions, non-private only (OFFLINE.md, `live-tests/2026-11-24-v04-proof.md`) |
| Files on device / workspace tools | Built in core (v0.3, sandboxed task dirs); on-device note files inside the app sandbox (v0.4 task runner) |
| Repo work (clone / read / edit / test / diff / commit / push) | **Built in core (v0.5)** — deterministic git wrappers, never a raw shell: github-only clone, auto-detected `run_tests`, secret-scan commit block (pattern name reported, value never), push gated to spec-authorised branches only |
| Spec-following (spec pack in → shipped-with-proof report out) | **Built in core (v0.5)** — `--spec spec.md [--repo …]`; criteria graded deterministically from trace + git evidence; report is SHIPPED / BLOCKED / NEEDS ETHURAM with the `Cost: $0 — keyless routes only` line on every report (BENCHMARK.md bar 1). Core + runner-lane for now; not in the app yet |
| Video production (demo/explainer videos) | **Built in core (v0.6)** — code-driven only, NO generative video models: scripted Chromium capture of live web apps (`capture_walkthrough`), PIL cards/captions (`render_card`), free-TTS fallback chain (`narrate`: edge-tts → Google Translate TTS → local flite/espeak, honest failure), ffmpeg assembly + ffprobe-verified finish (`assemble_video`/`verify_video`). Core + runner-lane for now; not in the app yet (VIDEO.md, `live-tests/2026-11-25-v06-proof.md`) |
| Optional personal free keys (own AgentRouter/Groq) | Built (core config) — enabled only when the user adds their own key to the vault |

## Get the app (APK)
Push to the public repo → **Actions → build-apk** builds the debug APK for
free and attaches it as a workflow artifact → download and install it on the
A12. No Android SDK is needed on the phone or on any local machine. The
`android/` scaffold is source-complete structure; the compiled `.apk` binary
is produced by that workflow (see android/README.md).

## Layout
- `DESIGN.md` — evidence-based design principles (phone-as-computer, structural perception, macros, the non-LLM sentinel, quota transparency, verify-by-state)
- `assets/` — Lille mascot (jacket / cap / working poses)
- `KEYLESS.md` — the no-keys doctrine: keyless endpoints, their real limits, why no shared key is ever shipped
- `ROUTES.md` — the Fallback Law: per-capability route chains (4–5 deep), public rate limits, cooldown/recovery/queue behaviour
- `free_agent/routes.py` — the route tables as data (live / stub / candidate / planned, per capability)
- `APP.md` — Android app design (Kotlin, Keystore vault, approvals, WorkManager)
- `HOSTING.md` — the $0 layered hosting stack and its honest caveats
- `LOOP.md` — the v0.3 tool-call loop: tool protocol, the tool set, Sentinel rules, and the sandbox's honest limits
- `OFFLINE.md` — the v0.4 background/offline task modes (A: user away, B: network drops, C: fully-offline runner lane) and the honest Android limits
- `free_agent/loop.py` — plan-act-observe loop; `free_agent/tasktools.py` — tools + Sentinel; `free_agent/sandbox.py` — confined execution
- `free_agent/taskstore.py` — durable task engine (v0.4): persistent store, per-step checkpoints, kill/resume, waiting-state classification
- `android/` — Android app (v0.4; live keyless chat + attachments picker + durable background Tasks screen; built by Actions)
- `.github/workflows/build-apk.yml` — free APK build + Python/Android tests on Actions
- `.github/workflows/lille-task.yml` — runner lane: one non-private durable task per dispatch, artifact `lille-task-<id>` (OFFLINE.md mode C)
- `.github/workflows/agent-jobs.yml` — scheduled non-private jobs on Actions
- `jobs/daily_checkin.py` — placeholder non-private check-in job (no real logins)
- `free_agent/router.py` — provider fallback + privacy routing + metadata-only audit
- `free_agent/vault.py` — Fernet-encrypted secrets vault (0600); Android equivalent: Keystore + EncryptedSharedPreferences
- `free_agent/redact.py` — strips emails, codes, cards, keys before any LLM sees text
- `free_agent/tools/gmail_tool.py` — least-privilege Gmail (readonly first; AppAuth on Android)
- `free_agent/tools/browser_tool.py` — Playwright, server-host profile (phone uses WebView/Custom Tabs instead)
- `free_agent/scheduler.py` — job definitions (incl. optional AgentRouter check-in for users who added their own key)
- `memory/` — SOUL.md / USER.md / MEMORY.md equivalents (no secrets, ever)
