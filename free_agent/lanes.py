"""Browser lane selection (v0.9 — WALLS.md build-next #1).

Lille's browser runs on one of two lanes:

- **host** — the persistent-profile Chromium on the host running
  her core (v0.8). Headless and fast; fine for sites that accept
  it. Its weakness is class A in WALLS.md: fingerprint / bot
  detection reads the datacentre browser and the datacentre
  network, and walls like Luma's Turnstile refuse it outright.
- **device** — the WebView inside the Lille app on Ethuram's
  phone (v0.9). A real device browser: real fingerprint, real
  history, real mobile network — the signals those checks pass.
  It is also the only lane where a human-only wall can be handed
  to Ethuram *inside her session* (PARKED_AT_WALL, WALLS.md #3):
  the check appears on his screen, he taps once, she resumes.

A task/spec names its lane — `host`, `device`, or `auto`
(default). `auto` means: device-first when any target domain is
fingerprint-sensitive (the WALLS.md class-A list below), host
otherwise. An explicit lane is always honoured — the spec author
may know something the list doesn't.

The decision is deterministic and recorded (spec report +
device-task package + trace): a lane choice is evidence, not
narration. This module is pure logic — no browser is touched
here — so the policy is unit-testable off-device and identical
on both lanes' doorsteps.
"""

LANES = ("host", "device", "auto")
DEFAULT_LANE = "auto"

# Fingerprint-sensitive domains (WALLS.md class A), each mapped
# to the wall that put it on the list. Suffix-matched, like the
# browser allowlist: an entry covers its subdomains.
FINGERPRINT_SENSITIVE = {
    "x.com": "W3 — X signup presents Arkose to the host browser; "
             "X trusts a real device session more",
    "twitter.com": "W3 — X's legacy domain, same trust model as x.com",
    "luma.com": "W1 — Cloudflare Turnstile refused the automated "
                "browser 3x on two forms; the phone passed silently",
    "lu.ma": "W1 — Luma's short domain, same Turnstile wall",
    "tiktok.com": "WALLS.md TikTok section (anticipated) — signup "
                  "leans on puzzle CAPTCHAs and app-first trust; "
                  "first session should be the real device",
    "duckduckgo.com": "W24 — DDG HTML answered HTTP 202 (bot "
                      "challenge) to keyless/host requests; the "
                      "record calls the wall fingerprint-sensitive",
    "dorahacks.io": "W24 — DoraHacks public API errored/bot-walled "
                    "from the host; a real browser session is the "
                    "recorded fix",
    "guerrillamail.com": "W13 — a real reCAPTCHA was presented to "
                         "the host browser during Lille's own "
                         "signup run (observed, not attempted)",
}


def parse_lane(value):
    """Normalise a lane name from a spec/task ('Device', ' device ')
    to 'host' | 'device' | 'auto', or None if it names no lane."""
    v = str(value or "").strip().lower()
    return v if v in LANES else None


def _host_of(domain):
    d = str(domain or "").strip().lower()
    d = d.split("://", 1)[-1].split("/", 1)[0].split(":", 1)[0]
    return d[4:] if d.startswith("www.") else d


def sensitive_match(domain):
    """(listed_domain, wall_reason) if `domain` is on the
    fingerprint-sensitive list (exact or subdomain), else None."""
    host = _host_of(domain)
    if not host:
        return None
    for listed, reason in FINGERPRINT_SENSITIVE.items():
        if host == listed or host.endswith("." + listed):
            return listed, reason
    return None


def choose_lane(requested, domains):
    """Decide the lane for a task/spec.

    requested: 'host' | 'device' | 'auto' | None (None/garbage
    falls back to auto — the spec parser validates separately).
    domains: the task's browser allowlist (may be empty).

    Returns {"requested", "lane", "reason"} — the reason is the
    evidence line recorded in the trace/report.
    """
    req = parse_lane(requested) or DEFAULT_LANE
    doms = [d for d in (domains or []) if str(d).strip()]
    if req in ("host", "device"):
        return {"requested": req, "lane": req,
                "reason": f"lane '{req}' named explicitly by the "
                          "spec/task — honoured as written"}
    for d in doms:
        m = sensitive_match(d)
        if m:
            listed, why = m
            return {"requested": req, "lane": "device",
                    "reason": f"auto: {listed} is fingerprint-"
                              f"sensitive ({why}) — device lane "
                              "first (real phone browser/network; "
                              "a human wall there is reachable by "
                              "Ethuram in her session)"}
    if doms:
        return {"requested": req, "lane": "host",
                "reason": "auto: no fingerprint-sensitive domain "
                          "in the allowlist — host lane (headless "
                          "Chromium is accepted there and faster)"}
    return {"requested": req, "lane": "host",
            "reason": "auto: no browser domains named — host lane "
                      "by default"}
