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
**Where her browser lives:** two places, one per lane — see
"Two lanes" below. The host lane's browser lives on the host
running her core/runner; since v0.9 there is also a device lane
whose browser lives in the app on the phone.

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

## Two lanes (v0.9) — and lane selection

The v0.8 run proved the shape of the remaining problem: her loop
reached X's signup submit from the host browser, and X presented
Arkose there — in a session Ethuram's finger cannot reach
(WALLS.md W1/W3). The device lane is the answer:

- **Host lane** (v0.8): the persistent-profile Chromium above.
- **Device lane** (v0.9): a WebView inside the Lille app on
  Ethuram's phone, driven by `BrowserSession` with perception by
  `BrowserPerception` — the SAME tool contract
  (browser_open/read/click/type/type_secret/upload/screenshot/
  wait), the SAME Sentinel rules (per-task domain allowlist,
  SECRET-field refusal twice over — last-read snapshot + live
  probe, secrets typed only by vault key name, CHALLENGE
  stop-and-report, human pacing), and the SAME X lane tools
  (x_session_status / x_read_feed / x_search / x_post with the
  same posting gates and copy rules). Structural perception
  only: DOM via evaluateJavascript, elements with stable refs —
  no vision. The WebView's own storage is her on-device session
  profile: cookies and site storage persist across app restarts
  inside the app sandbox and never leave it. Secrets live in a
  Keystore-wrapped encrypted store (`DeviceVault`):
  `vault_gen_secret` generates on-device and returns only the
  key name; the value crosses from the vault into the page field
  and nowhere else.
- **Lane selection** (`free_agent/lanes.py`): a task/spec names
  `host | device | auto` (specs: a `Lane` section; default auto).
  Auto = device-first for fingerprint-sensitive domains — the
  WALLS.md class-A list (x.com, luma.com, tiktok.com,
  duckduckgo.com, dorahacks.io, guerrillamail.com, …) — host
  otherwise. An explicit lane is honoured as written. The choice
  + reason is recorded in the spec report and the device-task
  package. A device-lane spec is NOT run by the host core: it is
  packaged (`device_task.json`) and reported NEEDS ETHURAM with
  the one step (start the in-app task) — the host never fakes
  the device lane, and the app likewise exports host-lane tasks
  instead of running them on the wrong lane.

**Differences that remain (honest):** the device driver has no
CDP network-error feed, so observations carry no NET ERRORS
section; uploads go through the page's file chooser (a staged
file answers it) rather than DOM file-setting; typing uses the
native value setter + input events (or insertText for
contenteditable) rather than CDP key events.

## PARKED_AT_WALL — the handoff (v0.9, WALLS.md build-next #3)

When a device-lane run stops at an OBSERVED human-only wall
(the model's finish — or the step budget — arrives while the
last browser observation was a CHALLENGE), the task parks in
the first-class state `parked_at_wall` with the wall recorded:
domain, kind, detail, a screenshot, and the ONE named step.
The app surfaces it as a **"Lille needs you"** card (Tasks
screen section + a notification). Tapping it opens
`BrowserActivity`: her parked WebView session full-screen —
the exact page, form state and all, nothing reloaded — where
Ethuram passes the check *in her session*. **"Done — hand back
to Lille"** moves the task back to running and she resumes
from her checkpoint alone; "Close (stay parked)" changes
nothing. A parked task is never auto-resumed by timers or
reboots — only the handback resumes it. Host-lane tasks park
the same way in the core task store (state + wall record; the
core's spec grading reports NEEDS ETHURAM as in v0.8) minus
the reachable WebView.

## The X retry, packaged (v0.9)

The v0.8 X signup spec ships in the app as a ready task —
**"Lille: create my X account"** on the Tasks screen
(`XAccountTask`): the same identity kit (handle preference
list, bio, mascot avatar from the bundled asset), the same
hard rules the host runs taught (mailbox discipline, one
patient submit, the legal-link trap, form discipline), lane =
device. Her X password is generated on-device into DeviceVault
as `x_password` and typed only via browser_type_secret. One
tap starts her; if Arkose appears she parks and the "Lille
needs you" card is the handoff; after his tap she resumes and
finishes alone. The acceptance criteria are unchanged and are
graded from the task record's evidence (verified handle,
first-post URL, feed/search evidence) in the in-app spec
report (Report / Share on the Tasks screen).

**Status, plainly: the device lane's live proof is PENDING
ETHURAM'S RUN.** Everything provable off the phone is proven
(unit suites, APK build, wiring — live-tests/2026-11-26-v09-proof.md);
the lane's first live exercise is Ethuram starting that task,
and the acceptance test's verdict comes from that run's graded
report — not from anything claimed here.

## Hers alone — the account belongs to Lille, the button to the owner (v0.10.1)

The X account is **Lille's own** — her page, for promoting
herself and Fluid. It is created once, by her loop, from
**Ethuram's phone**, and it is created and managed from his
instance only. It was never a feature owed to every install:
from v0.10.1, distributed copies carry **no creation path at
all**.

The mechanism is the v0.10 Fluid login identity. The
"Lille: create my X account" button on the Tasks screen is
rendered only when the app's *verified* Fluid login name is
an owner identity (`OwnerGate.OWNER_USERNAMES` — one
clearly-marked constant set in code, today just `ethuram`,
compared case-insensitively through the login gate's own
name normalisation). No verified login, or any other
username → the button is simply absent (not greyed, not
mentioned) and the task cannot be created from the UI. The
gate is pinned by JVM tests (`OwnerGateTest`: owner incl.
case variants → visible; other name → hidden; no login →
hidden).

Why not ship it to everyone: every distributed copy would
race for the same handles (only one can ever win), the
winning account would live in a stranger's vault and session
outside Ethuram's control, and a crowd of identical signups
could draw X's spam/bot scrutiny onto the Lille identity
itself. Management and posting tasks for her account
likewise run from the owner's instance — her page is run
from the one place her creator actually is.

**The identity kit lives in build secrets, not in the
repo.** The kit (handle preference list, bio, date of
birth, mailbox ladder) is her personal identity, so from
v0.10.1 it is not carried in the public source at all — not
in `XAccountTask`, not in the spec. It is injected at build
time: the workflow passes the repo's Actions secrets
(`LILLE_X_KIT_HANDLES`, `LILLE_X_KIT_BIO`, `LILLE_X_KIT_DOB`,
`LILLE_X_KIT_MAILBOXES`) through the build environment into
Gradle, which writes them into the app's `BuildConfig`
(`XIdentityKit.fromBuildConfig`) — the same mechanism as
the stable signing key. The packaged task interpolates the
kit into its task text at creation. Consequences, honestly:
the official build carries the kit, still behind the owner
gate above; a build from plain source (local dev, a fork)
is **hollow** — the kit fields are empty, and creating her
task records it in a FAILED "owner configuration missing"
state that never runs, instead of falling back to any
baked-in defaults. Tests use fixture kits injected the same
way (`XIdentityKit.fromRaw`), never the real values. The
v0.8 spec in `specs/` was redacted to match (dated note in
the file); the kit values do remain in this repo's *git
history* up to the v0.10.1 commit — removing them from
history would be a separate, deliberate rewrite.

**The counterpart, for everyone: "Connect my X account."**
The same release adds a packaged Tasks-screen task that is
deliberately NOT owner-gated (`XConnectTask`). It connects
the *user's own* X account: Lille opens X's login flow in
her WebView session, then parks via a planned user handoff
(`browser_handoff` — honoured only on tasks whose record
carries the `userHandoff` flag, so it is a packaged task's
design, never a model improvisation; wall parks still
require an observed challenge). The user types their own
credentials into the parked session themselves — Lille
never sees, types, asks for, or stores their password —
and on handback she verifies with `x_session_status` and
reports "Connected as @handle" from the recorded evidence,
or an honest not-connected state. The session persists in
her WebView storage like any lane session. Posting from a
connected user account stays approval-gated (`account:
user` + `post approved: yes`), unchanged.

## Brain pacing on the device lane (v0.9.1)

The first live run (2026-11-26) exposed a stall: the task sat
at steps=3 in `waiting_route` across repeated checks, her
browser parked on the mailbox homepage. Diagnosis (verified in
`TaskRunner.kt`): the step loop fired brain calls back-to-back
with **no pacing between successful calls** — browser actions
paced themselves, brain calls did not — and the keyless
anonymous tier tolerates roughly one call per ~45 s per IP
(the host loop only works with `--pace 50`). She tripped
402/429 within her first steps, and the old recovery waited
only Retry-After-or-45 s, so each wake-up could re-trip the
tier instantly: 429 → wait → 429, steps frozen.

The fix (`BrainPacing.kt`, pure + JVM-pinned, wired into the
runner):

- **50 s minimum interval** between a task's brain calls,
  enforced from `lastBrainCallAt` persisted in the task
  record — it survives process death and worker retries. A
  call that would come early becomes a `waiting_route` pacing
  wait for the remainder, stated plainly in the task note.
- **Escalating cooldown** on 402/429: 60 s × 2^(consecutive
  rate limits), a larger Retry-After wins, capped at 15 min.
  The streak counter (`consecutiveRateLimits`, also persisted)
  resets on the first HTTP 200.
- **Compatibility:** both fields are additive. A pre-0.9.1
  record lacks them; they default to 0 ("never called",
  "no streak"), so the in-flight task on Ethuram's phone
  resumes from its existing checkpoint (steps=3, her WebView
  session untouched) after an in-place update. Pinned by
  `BrainPacingTest.oldShapeRecordDefaultsCleanly`.

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
