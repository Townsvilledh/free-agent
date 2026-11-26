# The X lane + browser control (v0.8)

Lille has hands on the web now: a general interactive browser, and
on top of it an X lane — including **her own X account, created by
her own run** at Ethuram's explicit direction (2026-11-25).

## The ownership rule

> "Lille should create her own X profile, not you." — Ethuram

Muse builds the tools and verifies results by state. The account
creation itself is executed by **Lille's loop** (spec mode on the
keyless brain, driving the browser tools below). If her run hits a
wall only a human can pass, her spec report says NEEDS ETHURAM with
the exact step — nobody crosses the wall for her, and a walled
route is retried through other legitimate routes (other email
providers, other handle variants, other entry points) before the
acceptance test may be recorded as failed. The test is pass/fail:
PASS = live account in her name (handle verified on x.com),
profile set (display name, bio, mascot avatar), persistent
session, feed/search returning real content, first post live with
a URL — all verified by state.

## Browser tools (free_agent/browsertools.py)

A persistent headless Chromium (the v0.6 capture engine's CDP
machinery, `tools/capture/browse.mjs`) driven by deterministic
wrappers — the model never gets raw JS or a shell:

| Tool | What it does |
|---|---|
| `browser_open` | Open an allowlisted URL; returns visible text + element refs |
| `browser_read` | Re-read the page (refs are reassigned per read) |
| `browser_click` | Click by element ref or visible text |
| `browser_type` | Type into a field by ref — **refuses password/secret fields** |
| `browser_type_secret` | Type a vault secret by KEY NAME (see below) |
| `browser_upload` | Attach a file (task sandbox or repo `assets/` only) |
| `browser_screenshot` | PNG evidence into the task sandbox |
| `browser_wait` | Wait for time or for page text |
| `vault_gen_secret` | Generate + vault-store a secret; returns ONLY the key name |
| `vault_record_account` | Record account metadata (site, username, vault key name) |

**Perception is structural** (DESIGN #2): visible text + an
element list with stable refs. Screenshots are evidence for
humans, not model input.

**Domain allowlist.** A task may only browse domains its spec
names (`Browser domains` section; plain tasks use
`FREEAGENT_BROWSER_DOMAINS`). The Sentinel checks `browser_open`;
the module re-checks the current page before every click/type, so
a click that navigates off-allowlist stops further actions.

**Persistent profile.** `~/.freeagent/browser-profile` (0700,
gitignored): sessions survive between runs. It is host-only state
— never committed, never exported, never read into a prompt.
**Where her browser lives:** on the host running her core/runner —
NOT on the phone. There is no on-device browser rung yet; the
Android app carries the version + this roadmap only.

**Secrets.** Passwords are generated host-side into Lille's
encrypted vault (`vault_gen_secret` returns only the key name) and
typed by `browser_type_secret`, which resolves the value
host-side and passes it only over the local driver pipe. The value
never enters model context, tool args, traces, or logs — a unit
test asserts a full loop trace contains no trace of it.
`browser_type` refuses secret fields twice over (last-read
snapshot + a live driver-side probe).

**Challenges.** Pages are checked after every action for
human-only walls (Arkose/funcaptcha/hCaptcha/reCAPTCHA iframes,
"verify you are human", emailed-code prompts, phone demands). A
hit returns a `CHALLENGE` observation; the loop's rule is STOP and
report it. The driver never attempts to solve or bypass one.

**Pacing.** The driver pauses human-style before clicks/typing;
the X lane rate-limits posting (≥90s between posts, verified on
the timeline). No mass actions.

**Known limitation.** This Chromium build's Local Network Access
checks block *loopback* navigation outright
(`ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS`) even with the
feature flag disabled — browsing a local dev server on 127.0.0.1
does not work from this driver today. Public http(s) sites are
unaffected (the v0.6 capture stack uses the same machinery).

## X lane tools

Thin wrappers over the browser tools, x.com allowlisted:

| Tool | What it does |
|---|---|
| `x_session_status` | Logged-in state + handle, read from the page (never cookies); persists `x_session.json` to the task record |
| `x_read_feed` | Home timeline: author, text, status URLs |
| `x_search` | Search results via the live session |
| `x_post` | Compose + post + verify on the profile timeline; returns the status URL |

**Posting gates (Sentinel, deterministic).** `x_post` is denied
unless the spec's `X lane` section authorises it: `account: own`
(Lille's own page) or `account: user` **plus** `post approved: yes`
(Ethuram's account — approval-gated, default deny). Copy rules are
enforced in the handler: no invented offers/prizes/giveaways; the
sign-off "₿itcoin or nothing" is @Bitcoinornatin's branding and is
refused on Lille's own page.

**Sweep integration.** The Thoroughness sweep gained the surface
`x (via Lille's browser session)`: with a live session it returns
real posts and the coverage note counts X as checked-via-session;
without one it is named NOT CHECKED with the reason — the gap is
printed either way (bar 9).

## Risk note

X may restrict or suspend accounts it detects as automated.
Lille's account is hers, created at Ethuram's explicit direction;
human pacing + ordinary, human-shaped use is the mitigation, not
a guarantee. If X restricts the account, that outcome is reported
as-is — never hidden, never worked around with a fresh identity
without Ethuram's word.
