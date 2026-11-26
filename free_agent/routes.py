"""Route tables — the FALLBACK LAW, as data.

Every capability gets an ordered fallback chain, 4-5 deep where real
free options exist. Where fewer genuinely exist, the chain lists what
exists and ROUTES.md marks the gap — a provider is never invented to
pad a chain.

Status per route:
  live       — implemented and exercised by code in this repo
  stub       — wired in config, disabled until the user adds their OWN
               optional key (never shipped, never shared — KEYLESS.md)
  candidate  — real service, not yet integrated/verified here
  planned    — design target (e.g. on-device Android capability)

Privacy law overrides availability: a route marked
trusted_for_private=False NEVER receives private content just because
it is up. Private work with no trusted route queues/blocks honestly.
"""

# capability -> ordered list of (route name, status, key requirement)
ROUTE_TABLES = {
    "text": [
        ("keyless-pollinations", "live", "none"),
        # v0.12: second genuinely-keyless route, live-verified
        # 2026-11-26. Slow community queue — fallback of last
        # resort. It has no NATIVE tool-call support; since the
        # 2026-11-26 loop-path fix it also serves complete()
        # via the loop's fenced-JSON text protocol, with the
        # tool catalogue rendered as text (router.py). The
        # v0.12 EXHAUSTIVE HUNT live-tested ~45 keyless
        # candidates (three rounds of real completion calls);
        # these two are the only ones that answered keyless.
        # Every other verdict — refused / needs-key / dead /
        # broken / challenge-walled — is recorded in ROUTES.md.
        ("ai-horde", "live", "none"),
        # Own-key routes (v0.12.1): live code in BOTH the core
        # (keys from environment variables only) and the app
        # (keys from the user's encrypted vault, Settings ->
        # "Your API keys"). "stub" here means what it always
        # meant — disabled until the user adds their OWN key;
        # absent key = absent route. Walk order after the
        # keyless pair: groq -> gemini -> cerebras ->
        # openrouter -> agentrouter -> custom.
        ("groq", "stub", "own-key"),
        ("gemini-free", "stub", "own-key"),
        ("cerebras", "stub", "own-key"),
        ("openrouter-free", "stub", "own-key"),
        ("agentrouter", "stub", "own-key"),
        ("custom-openai", "stub", "own-key"),
        ("cloudflare-workers-ai", "stub", "own-key"),
        # local-ollama is deliberately NOT in the public text chain:
        # it is the trusted PRIVATE path (privacy routing), not a
        # fallback for public traffic.
    ],
    "image": [
        ("pollinations-image", "live-endpoint", "none"),
        # GAP (honest): no second genuinely free + keyless image API
        # has been verified. Candidates below are NOT live routes.
        ("cloudflare-workers-ai-image", "candidate", "own-key"),
        ("huggingface-inference-image", "candidate", "own-key"),
    ],
    "search": [
        ("duckduckgo-instant", "live", "none"),
        ("wikipedia-api", "live", "none"),
        ("openlibrary-api", "live", "none"),
        ("brave-search-free", "candidate", "own-key"),
        ("duckduckgo-html", "candidate", "none"),  # fragile scraping; off by default
    ],
    "voice_stt": [
        ("android-ondevice-speech", "planned", "none"),  # route #1 for the app
        ("groq-whisper", "candidate", "own-key"),
        ("pollinations-audio", "candidate", "none"),     # unverified
    ],
    "voice_tts": [
        ("android-ondevice-tts", "planned", "none"),     # route #1 for the app
        ("edge-tts", "candidate", "none"),               # keyless Edge read-aloud endpoint (unofficial)
        ("pollinations-audio", "candidate", "none"),     # unverified
    ],
    "hosting": [
        ("phone-workmanager", "live", "none"),
        ("github-actions-cron", "live", "none"),
        ("cloudflare-workers-cron", "planned", "none"),
        ("oracle-always-free", "candidate", "account"),  # optional, card check may block
    ],
}

# Known PUBLIC rate limits (see ROUTES.md; limits change — verify).
RATE_LIMIT_NOTES = {
    "keyless-pollinations": "Anonymous: ~1 concurrent request, ~45s between requests per IP, and a hard cap of 100 replies/day per IP (402 FREE_LIMIT, resets 00:00 UTC — re-confirmed live 2026-11-26). On shared/carrier IPs the cap is shared with strangers. Best-effort.",
    "ai-horde": "Anonymous key 0000000000 (the service's own public key). Queue-based: tens of seconds to minutes per answer; community models, quality varies; raw completions only — no chat format, no tool calls. Live-verified keyless 2026-11-26.",
    "agentrouter": "Premium models released in daily batch quotas (402 when a batch is exhausted); DeepSeek-class routes effectively unlimited.",
    "groq": "Per-model free caps, e.g. Llama 3.3 70B ~1,000 req/day + 100k tokens/day; Llama 3.1 8B ~14,400 req/day + 500k tokens/day.",
    "gemini-free": "Free-tier per-model request/day caps shown in AI Studio; change frequently.",
    "cerebras": "Free tier ~1M tokens/day, ~30 requests/min (provider docs); Llama 3.3 70B is the documented flagship.",
    "openrouter-free": ":free models ~50 requests/day without credit purchase; the openrouter/free auto-router id is the default (keyless-verified present 2026-11-26).",
    "custom-openai": "The user's own OpenAI-compatible endpoint: their server, their limits, their trust decision.",
    "cloudflare-workers-ai": "Free tier ~10,000 neurons/day.",
    "pollinations-image": "Keyless; free images carry a small logo; results cached by URL (treat as public).",
    "duckduckgo-instant": "Keyless Instant Answers API; returns instant answers only, not full web results; be gentle (no published quota).",
    "wikipedia-api": "Keyless; standard Wikimedia API etiquette / rate limits apply.",
    "openlibrary-api": "Keyless; be gentle (no published quota for casual use).",
}

def chain(capability):
    """Ordered route names for a capability (all statuses)."""
    return [name for name, _status, _key in ROUTE_TABLES[capability]]

def live_chain(capability):
    """Ordered route names that are actually live in code today."""
    return [name for name, status, _key in ROUTE_TABLES[capability]
            if status in ("live", "live-endpoint")]
