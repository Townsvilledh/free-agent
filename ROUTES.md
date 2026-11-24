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

### 1. Text / brain — IMPLEMENTED (Python router; app uses route 1)
| # | Route | Key | Known public limits | Status |
|---|---|---|---|---|
| 1 | Pollinations (keyless) | none | ~1 concurrent, ~45s between requests per IP; 402 + Retry-After beyond; best-effort | **live** |
| 2 | AgentRouter | own key (optional) | Premium models in daily batch quotas (402 when a batch is gone); DeepSeek-class effectively unlimited | stub — enabled only with the user's own key |
| 3 | Groq | own key (optional) | Per-model: Llama 3.3 70B ~1,000 req/day + 100k tokens/day; Llama 3.1 8B ~14,400 req/day + 500k tokens/day | stub — own key only |
| 4 | Gemini free tier | own key (optional) | Per-model caps shown in AI Studio; change frequently | stub |
| 5 | OpenRouter `:free` | own key (optional) | ~50 requests/day on free models without credit purchase | stub |
| 6 | Cloudflare Workers AI | own token (optional) | Free tier ~10,000 neurons/day | stub |
| — | Local Ollama | none | Your hardware | trusted **private** path only — deliberately not a public-traffic fallback |

Cooldowns in code: Pollinations 45s (or its Retry-After), AgentRouter
1800s (daily-batch rhythm), Groq 60s, others 60s default.

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
