# ROUTES.md — the Fallback Law

**Law (binding, decided 2026-11-24):** every capability gets a
fallback chain 4–5 deep where real free options exist, is
rate-limit aware, auto-routes, and feels seamless on the surface.
This is Lubrica's routing discipline applied to AI providers: never
depend on one route, never show a raw route error, never leave a
silent dead end.

Where fewer than 4–5 genuine free routes exist, the chain lists what
exists and marks the **gap** honestly. A provider is never invented
to pad a chain.

## How routing behaves (implemented in `free_agent/router.py`)

1. Routes are tried **in chain order**.
2. On `402 / 429 / 503` or a network error, that route **cools down**:
   its unlock time is recorded (the provider's `Retry-After` when
   given, else the route's configured cooldown) and the router moves
   to the next route **automatically, inside the same task** — a
   multi-step task continues across switches without the user
   re-sending anything (one-move composition).
3. Cooling routes are **skipped**; when a cooldown expires, the
   preferred route is tried first again (**automatic recovery** /
   health re-probe).
4. The surface is **seamless**: one answer, never a raw provider
   error. The audit log records **which route served** (provider +
   capability only — never content).
5. **No silent dead ends:** if ALL routes are cooling down, the agent
   says so plainly, gives the **earliest retry time**, and **queues
   the task** — exactly Lubrica's honest-failure rule.
6. **Privacy overrides availability:** an untrusted route NEVER
   receives private content just because it is up. A private task
   whose trusted route is cooling down queues; one with no trusted
   route at all blocks (PrivacyBlocked). See SECURITY.md.

The Android app mirrors the same behaviour for its keyless route:
cooldown on 402/429, automatic continuation of the same task after
the wait, and a plain all-routes-down message after its retries.

> **Limits change.** Every limit below is the provider's *public*
> figure as known at writing — verify against the provider before
> relying on it. The router treats limits as weather, not promises:
> whatever the route answers today is what it routes on.

## Capability chains

### 1. Text / brain — IMPLEMENTED (Python router + the app; keyless routes 1–2 since v0.12, the own-key door since v0.12.1)
| # | Route | Key | Known public limits | Status |
|---|---|---|---|---|
| 1 | Pollinations (keyless) | none | ~1 concurrent, ~45s between requests per IP; **hard cap 100 replies/day per IP** (402 FREE_LIMIT, resets 00:00 UTC — live re-confirmed 2026-11-26); on shared/carrier IPs the cap is shared with strangers; best-effort | **live** |
| 2 | AI Horde (keyless) | none (public anonymous key) | Queue-based community compute: tens of seconds to minutes per answer; community models, quality varies; raw completions only — no chat format, **no native tool calls** (since the 2026-11-26 loop-path fix it also serves complete() via the loop's fenced-JSON text protocol, catalogue rendered as text) | **live (v0.12)** — fallback of last resort: chat, and the tool-call path via the text protocol |
| 3 | Groq | own key (optional) | Per-model: Llama 3.3 70B ~1,000 req/day + 100k tokens/day; Llama 3.1 8B ~14,400 req/day + 500k tokens/day | **live (v0.12.1)** — own key only; default model `llama-3.3-70b-versatile` |
| 4 | Gemini free tier | own key (optional) | Per-model caps shown in AI Studio; change frequently | **live (v0.12.1)** — own key only; default model `gemini-flash-latest` |
| 5 | Cerebras | own key (optional) | Free tier ~1M tokens/day, ~30 req/min (provider docs) | **live (v0.12.1)** — own key only; default model `llama-3.3-70b` |
| 6 | OpenRouter `:free` | own key (optional) | ~50 requests/day on free models without credit purchase | **live (v0.12.1)** — own key only; default model `openrouter/free` |
| 7 | AgentRouter | own key (optional) | Premium models in daily batch quotas (402 when a batch is gone); DeepSeek-class effectively unlimited | **live (v0.12.1)** — own key only; default model `claude-opus-4-8` |
| 8 | Custom OpenAI-compatible endpoint | own key (optional) | The user's own server: their limits, their trust decision | **live (v0.12.1)** — own key + base URL only; user's model name or `default` |
| 9 | Cloudflare Workers AI | own token (optional) | Free tier ~10,000 neurons/day | stub — core config only, no models wired |
| — | Local Ollama | none | Your hardware | trusted **private** path only — deliberately not a public-traffic fallback |

Cooldowns in code: Pollinations 45s (or its Retry-After), AI Horde
300s, AgentRouter 1800s (daily-batch rhythm), Groq 60s, others 60s
default. In the app, every route in the walked chain — keyless
and own-key alike — also sits under the per-route health
demotion (BrainFailure: escalating rests, automatic recovery).

**Own-key routes — v0.12.1 (the "your own keys" door).** The
table above the hunt is the KEYLESS record and stays that way:
the hunt's verdicts are about what answers with no key at all.
This section is a different door, opened by Ethuram's
2026-11-26 direction — a user's OWN free-tier keys as extra
routes. Since 2026-11-27 (v1.0.2, OWN KEYS LEAD) they are
no longer fallbacks behind the keyless pair:

- **Order:** a user's own keys LEAD — Groq → Gemini →
  Cerebras → OpenRouter → AgentRouter → custom, each only
  when its key is actually saved — and the keyless pair
  (Pollinations, AI Horde, health behaviour unchanged) is
  the fallback. With no own key saved, the keyless pair
  leads exactly as before. Identical in the app (chat +
  task brain) and the Python core. The reorder never
  widens eligibility: privacy routing still bars private
  content from every untrusted route, own-key included.
- **Where keys live:** in the app, the Keystore-wrapped
  DeviceVault, entered in Settings → "Your API keys" — never
  plaintext, never in chat/task context, never logged; they
  ride the v0.11 encrypted backup inside the vault like her
  other secrets (BackupManager seals `vault.exportSecrets()`),
  never in any backup plaintext. In the core, environment
  variables only (`GROQ_API_KEY`, `GEMINI_API_KEY`,
  `CEREBRAS_API_KEY`, `OPENROUTER_API_KEY`,
  `AGENTROUTER_API_KEY`, `CUSTOM_LLM_BASE_URL` +
  `CUSTOM_LLM_API_KEY` + optional `CUSTOM_LLM_MODEL`).
- **Absent = absent:** a route with no key saved is simply
  not in the chain — no UI nagging, no error, no placeholder.
- **Settings Test:** one minimal call (GET /models) per
  provider, reporting only success/failure + the model
  count when listed — the key is never echoed anywhere.
- **Default models, and how each was chosen (2026-11-26):**
  Groq `llama-3.3-70b-versatile` and Cerebras
  `llama-3.3-70b` are the providers' documented free-tier
  production models (provider docs; their /models lists are
  key-gated, so no keyless check exists). Gemini
  `gemini-flash-latest` is Google's documented alias for the
  current Flash model (the core's long-standing default).
  OpenRouter `openrouter/free` — OpenRouter's own free-model
  auto-router — was **verified keylessly**: GET
  https://openrouter.ai/api/v1/models answered 200 with 475
  models on 2026-11-26, `openrouter/free` among them; the
  individual `:free` roster rotates, the router id does not.
  AgentRouter `claude-opus-4-8` is the core config's
  documented first model (its /models list is key-gated —
  401 keyless, checked 2026-11-26).
- **Doctrine copy stays exact:** the app works fully with
  zero keys; no key is shipped, collected, or shared; these
  are the user's own keys, optional, vault-held. Own-key
  routes are external providers — privacy routing is
  unchanged, and private content never goes to them.

**Host-side Groq bridge — 2026-11-26.** On this host, Ethuram's
own Groq key also serves Lille's host-side jobs (runner lane,
proofs, sweeps) through the launcher `python -m
free_agent.hostkeys` (`free_agent/hostkeys.py`). The launcher
obtains a short-lived *surrogate* from the custom.groq connector
exchange at runtime and spawns the job with
`GROQ_API_KEY=<surrogate>` in the child environment only; the
runtime swaps the surrogate for the real key on the approved
egress to api.groq.com. The real key is never present on this
host — not in any process, file, or log — and the child's output
is relayed through a scrubber so the surrogate cannot leak into
logs either. If the exchange yields nothing, the launcher
refuses to run. Ordering is exactly the production chain above:
keyless routes first, Groq after them; the bridge changes only
how the host supplies the key, never the order. The phone app
is untouched by this: on a phone, the same key reaches Lille
only through the user's own paste in Settings → "Your API
keys". Proof harness: `scripts/groq_proof.py` (run through the
launcher; its groq-only route pin is a labelled test-harness
selection, not production behaviour).

**The exhaustive keyless-brain hunt — v0.12 (real completion calls
from the build VM, 2026-11-26, three rounds, ~45 candidates).**
Ethuram's order: "if we can find ten use them all, if 50 use them
all, so we never face this again." Every candidate below got a
real call with the same fixed prompt. **Exactly two routes on the
internet answered keyless today, and both are wired.** The rest
are recorded with their verdicts — refused, needs-key, dead,
broken, or challenge-walled — because a hunt that only reports
its winners is how the next outage stays invisible.

**WIRED (answered keyless with a real completion):**

| Candidate | Verdict | Evidence |
|---|---|---|
| Pollinations | **WIRED — route 1** | POST answered 402 FREE_LIMIT: "Free limit reached: 100 replies a day per IP. It resets at 00:00 UTC" — this VM's shared egress IP was already over the daily cap. The route exists and is keyless (proven since v0.2); the 402 IS the per-IP limit Ethuram's phone hit. Its anonymous tier serves exactly one model (`openai` = GPT-OSS 20B); `openai-large` returned 404 "isn't on the Classic API". |
| AI Horde (stablehorde.net) | **WIRED — route 2 (fallback of last resort)** | Anonymous key `0000000000` accepted; a job completed on the second poll (~40 s) with the requested text. Slow, queue-based, community models — labelled as such in the app ("using the slow community route"). This is also the Kobold community pool — the "public Kobold instances" candidate class is this same route. |

**NEEDS A KEY (answered, but demand an account key — excluded by
the no-keys doctrine; a free key is still a key):**
llm7.io (401 "Missing API key"; catalog has zero free models) ·
api.airforce (401 "Missing Authorization"; its "free" models
still need a free account key) · Pollinations' new gateway
gen.pollinations.ai (401 "A valid API key is required") ·
OpenRouter (401) · Hugging Face Router (401) · GitHub Models
(needs a GitHub token; also unreachable from this VM — connection
dropped twice) · NVIDIA NIM (410 — the preview models probed
have been retired; the service is key-gated regardless) ·
SiliconFlow (401) · Zhipu BigModel (401, no Authorization) ·
Cohere (401 "no api key supplied") · Mistral (401) · Cerebras
(401) · Groq (401) · Gemini OpenAI-compat (400 "Missing or
invalid Authorization header") · Together (401) · Cloudflare
Workers AI (401 auth error 10000; also needs an account id) ·
free.gpt.ge (401 "no token provided") · TextSynth (403 "Missing
bearer authorization") · Puter (401 "Missing authentication
token") · DeepAI (401 "pass a valid Api-Key") · SambaNova (401) ·
Novita (403 INVALID_API_KEY) · AI21 (400 "bad or missing
authentication") · DeepSeek (401) · xAI (401 "No credentials
presented") · Perplexity (401) · OpenAI (401) · DeepInfra
(timed out; key-required by its docs) · Routeway (403 —
Cloudflare wall in front of a key-gated API).

**DEAD / GONE (no live API at the other end):**
Anyscale Endpoints (404 with its shutdown notice) · OctoAI
(connection dropped; service shut down after acquisition) ·
Lepton (connection dropped; shut down) · Hyperbolic (404 "This
inference endpoint has been decommissioned") · churchless.tech
and chatgpt-api.shn.hk (historical free GPT proxies — connection
dropped, gone) · api.g4f.dev (connection dropped — no live
hosted g4f API) · Akash chatapi (HTTP 200 serving the chat web
UI's HTML — no API answers at that path any more) · KeylessAI
worker, keylessai.thryx.workers.dev (connection dropped twice,
no response — and it is a wrapper fronting Pollinations +
ApiAirforce, so it would have shared their fate anyway).

**BROKEN (answered, but unusably):**
api.apillmfree.com, api.freeapillmkey.com, api.apisfreellm.com —
three domains, evidently ONE shared "free-ai" backend: it
refuses a benign test prompt ("Reply with exactly: ROUTE OK")
with an unrelated sexual-content policy template (403), or
times out / drops the connection. A route that refuses benign
prompts with a junk template is not a brain. NOT wired.

**CHALLENGE-WALLED (keyless for humans in a browser, not
callable by an app):**
duck.ai (DuckDuckGo AI Chat) — the status endpoint minted a
token, and the chat call still answered **418 ERR_CHALLENGE**:
its requests now require proofs minted by JavaScript running
in a real page plus interaction telemetry. Wiring it would
mean defeating a consumer service's anti-bot challenge from
inside the app — the same class of wall as X's Arkose, which
Lille parks at rather than fights (WALLS.md doctrine). NOT
wired; recorded so nobody re-tests it monthly.

**CLASSES WITH NO STABLE ENDPOINT (not individually called):**
Hugging Face Spaces demos (thousands of per-space Gradio
endpoints; they appear and vanish weekly — no stable route) ·
other consumer web-chat internals (Phind / Copilot / Gemini
web — same browser-minted-token class as duck.ai) · public
Ollama / LM-Studio instances (no registry exists) · paid relay
shops (api2d / aigc2d class — their "keys" are purchased
credit, the opposite of keyless).

**Shared-fate honesty:** several "different" services front the
same upstreams (KeylessAI wraps Pollinations; the free-ai trio
is one backend; half the keyed APIs resell the same open
models), so a long list would overstate diversity. The two
wired routes fail independently in every way that matters:
Pollinations is one company's free tier with a per-IP daily
cap; the Horde is a volunteer compute pool with a queue. Their
only shared fate is the user's own network — which is why the
offline class exists and says so.

**Per-route health (wired with the hunt):** a route that keeps
failing is demoted automatically. In the app, consecutive
failures rest a route for 2 min, then 10, then 30 (cap); the
chain skips resting routes, starts new messages at the first
healthy route, and probes a rested route automatically when
its rest expires — a success clears its record (BrainFailure,
JVM-pinned). In the core router, consecutive failures escalate
a provider's cooldown ×1/×2/×4/×8 until a success clears it.
Nobody has to notice, forgive, or reset anything by hand.

**Chat failure discipline (v0.12, app + core):** brain-call
failures are classified (device-offline / timeout-network /
402-429 honouring Retry-After / 5xx / permanent). Retryable
classes never dead-end: the sent message stays in the chat, an
honest status line names the real cause, the chain falls through
to the next route, and when every route is busy the call retries
with escalating waits (BrainPacing arithmetic) until the route
recovers. "Check your connection" copy is only used when the
device is actually offline (connectivity state is checked
first); a per-IP rate limit says the free route is busy and that
on mobile networks the limit is shared with others on the same
carrier IP. The task engine already had this discipline
(waiting_route, never fake-failed); v0.12 brings chat up to it.

### 2. Image generation — endpoint live, chain has an HONEST GAP
| # | Route | Key | Known public limits | Status |
|---|---|---|---|---|
| 1 | Pollinations image | none | Keyless; free images carry a small logo; results cached by URL → treat generated URLs as public | **live endpoint** (in-app UI is the next step) |
| 2 | Cloudflare Workers AI (image models) | own token | Free neurons/day shared with text | candidate — real, not yet integrated |
| 3 | Hugging Face Inference (image) | own token | Free-tier terms have changed repeatedly | candidate — unverified |
| — | **GAP** | | No second genuinely free + keyless image API is verified. The chain is 1 live + 2 candidates until one is proven. | |

### 3. Search — IMPLEMENTED (keyless, `free_agent/tools/search_tool.py`)
| # | Route | Key | Known public limits | Status |
|---|---|---|---|---|
| 1 | DuckDuckGo Instant Answers API | none | No published quota — be gentle. **Instant answers only, not full web results** | **live** |
| 2 | Wikipedia API | none | Wikimedia API etiquette/limits | **live** (factual lookups) |
| 3 | Open Library API | none | No published quota — be gentle | **live** (books) |
| 4 | Brave Search free tier | own key | Free tier with monthly credit (provider's terms) | candidate |
| 5 | DuckDuckGo HTML | none | Scraping: fragile, can break or block at any time | candidate — off by default |

### 4. Voice — STT (speech in)
| # | Route | Key | Notes | Status |
|---|---|---|---|---|
| 1 | **Android on-device SpeechRecognizer** | none | On the phone, offline-capable on many devices — route #1 for the app by design | planned (app milestone) |
| 2 | Groq Whisper | own key | Whisper free tier ~2,000 requests/day class limits | candidate |
| 3 | Pollinations audio | none | Audio endpoints exist in the Pollinations family | candidate — unverified |

### 5. Voice — TTS (speech out)
| # | Route | Key | Notes | Status |
|---|---|---|---|---|
| 1 | **Android on-device TextToSpeech** | none | On the phone, offline voices available — route #1 for the app by design | planned (app milestone) |
| 2 | Edge TTS | none | Keyless Edge read-aloud endpoint (unofficial; can change) | candidate |
| 3 | Pollinations audio | none | As above | candidate — unverified |

### 6. Hosting / scheduling — IMPLEMENTED as architecture (HOSTING.md)
| # | Route | Notes | Status |
|---|---|---|---|
| 1 | Phone WorkManager | Jobs while the phone lives; Doze can delay | live (design + job definitions) |
| 2 | GitHub Actions cron | Free on public repos; min interval 5 min, can be delayed; non-private jobs only | **live** (daily heartbeat workflow) |
| 3 | Cloudflare Workers cron | 5 free triggers, 10ms CPU — relay/queue only | planned |
| 4 | Oracle Always Free | True 24/7, but signup card check may block; idle reclaim rules | candidate — optional |

## One-line summary

> Preferred route first; limited route cools down and is skipped,
> not hammered; recovery is automatic; the user sees one seamless
> answer — or an honest "all routes cooling, queued, earliest retry
> HH:MM" — never a raw error and never silence.
