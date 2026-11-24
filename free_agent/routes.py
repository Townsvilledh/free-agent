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
        ("agentrouter", "stub", "own-key"),
        ("groq", "stub", "own-key"),
        ("gemini-free", "stub", "own-key"),
        ("openrouter-free", "stub", "own-key"),
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
    "keyless-pollinations": "Anonymous: ~1 concurrent request, ~45s between requests per IP; 402 + Retry-After beyond that. Best-effort.",
    "agentrouter": "Premium models released in daily batch quotas (402 when a batch is exhausted); DeepSeek-class routes effectively unlimited.",
    "groq": "Per-model free caps, e.g. Llama 3.3 70B ~1,000 req/day + 100k tokens/day; Llama 3.1 8B ~14,400 req/day + 500k tokens/day.",
    "gemini-free": "Free-tier per-model request/day caps shown in AI Studio; change frequently.",
    "openrouter-free": ":free models ~50 requests/day without credit purchase.",
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
