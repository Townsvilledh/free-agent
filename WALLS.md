# WALLS.md — the Walls Register

*Ordered by Ethuram, 2026-11-25 18:47 WAT: "take stock of all [the walls]…
every wall you've met we should find a solution for Lille."*

Every wall Muse or Lille has actually hit, what caused it, and Lille's
answer — **BUILT** (mechanism exists, verified in code/docs), **TO BUILD**
(concrete design, not yet built), or **HUMAN-HANDOFF** (genuinely
Ethuram's alone; the solution is the protocol, never a bypass).

Root-cause classes: **A** browser fingerprint / bot detection ·
**B** missing session / sign-in wall · **C** rate limit / quota ·
**D** geo restriction · **E** credential / billing state ·
**F** platform policy (human-only by design) · **G** infra limit ·
**H** model behaviour · **I** provider outage / silent failure.

Priority = how often the wall blocks real work.

---

## P1 — Walls that block work every week

### W1. Cloudflare Turnstile "Verify you are human" refuses the automated browser — class A
- **Evidence:** Luma, twice: White Circle Halo application
  (luma.com/ykoj0t48) — 3 attempts, "Verification failed" every time,
  plus a 4th unresponsive (memory/2026-11-25.md:790, :891); Stellar
  Builders Showcase registration (luma.com/tcqpuj3j) — same, 3 attempts
  (memory/2026-11-25.md:875–879, pattern recorded there: "Luma forms
  Muse fills for him should be expected to end at a Turnstile
  checkbox only he can clear"). LocalCoinSwap registration —
  Turnstile failed 2026-11-07 (including Ethuram's own takeover tap
  *inside that same browser*), failed again on retry
  (memory/bank/reflections.md:1376, :2411). An old dashboard's
  Turnstile failed for automated AND manual attempts in the cloud
  browser — conclusion recorded: "this browser's network is refused
  outright" (memory/2026-11-09.md:79, :186). Counter-evidence:
  Ethuram's own phone Chrome passed the Luma check silently
  (2026-11-25, this chat) — the wall reads the *browser/network*,
  not the human.
- **Lille solution — TO BUILD (the big one): on-device browser lane.**
  Lille's host is Ethuram's real phone: a real device browser/WebView
  with real history, cookies, carrier/residential network and a
  genuine device fingerprint — the exact signals Turnstile passes.
  Her browser roadmap must therefore be two-lane: (1) host Chromium
  (v0.8, built) for sites that accept it; (2) an **on-device lane**
  (app-driven WebView / Chrome on the A12, human-paced, her existing
  structural perception + Sentinel gates on top) for
  fingerprint-walled sites. Pre-flight: probe the wall class first
  (see W2's diagnosis rule) and pick the lane *before* burning
  attempts. Fallback if even the device lane is challenged:
  HUMAN-HANDOFF (W-protocol below) — form filled, session parked,
  one tap named.

### W2. Google sign-in 2-Step / number-match loop — class B+F
- **Evidence:** Devvit/Reddit signup, 2026-11-25 — three successive
  Google prompts (numbers 81, then 17, then 81 again), each browser
  restart re-triggering the challenge; Ethuram stressed ("you keep
  stressing me"); route abandoned (memory/2026-11-25.md:892).
  Artefact sign-in stuck at 2-Step 2026-11-15 — the step was his
  phone prompt or an SMS Google would only send after his full
  number was typed (memory/2026-11-15.md:1472). Learn AR / Devpost
  Google 2-Step tap still pending (standing record). Counter-case:
  Tactiq Google sign-in, same day as the Devvit loop, completed with
  **no** 2-Step prompt at all (memory/2026-11-25.md:1898) — the wall
  is per-session/per-profile, not per-account.
- **Lille solution — TO BUILD: session-first + attempt budget.**
  (1) One persistent profile per identity, logged in once, reused
  forever — a session that already holds Google cookies rarely
  re-challenges. (2) The diagnose-before-retry rule (custom skill
  `diagnose_platform_block_before_retry`) as loop law: after **two**
  failures of the same shape, stop, change one driver (profile /
  network / device lane), never fire a third prompt at the human.
  (3) If a number-match is genuinely required: HUMAN-HANDOFF — one
  prompt, one tap, everything else pre-staged; a looped prompt is a
  defect, not persistence.

### W3. The X cluster: no keyless route in, three different doors each walled — classes A+B+C+E
- **Evidence:** (a) Keyless scraping dead — sweep #1: X via public
  Nitter timed out / returned no tweet content; recorded NOT CHECKED
  in every coverage note (live-tests/2026-11-25-v07-sweep-report-1.md:47;
  BENCHMARK.md:207). (b) Official API — OAuth works (`me` succeeds)
  but every other read and posting returns **HTTP 402** at $0
  pay-per-use credits; 402 is a billing state, not a credential
  failure (custom-connectors/custom.x/CONNECTOR.md:36). (c) Website
  sign-in — X rejected Muse's browser sign-in for @jeetethuram
  ("Something went wrong" / "Confirm your account" loop); task
  closed 2026-11-08 with X activity staying on Ethuram's side
  (memory/2026-11-08.md:497). A temporary X login limit also hit a
  website sign-in attempt on 2026-11-14 (chat record). (d) Signup
  itself: in Lille's own v0.8 acceptance run, email route 1
  (GuerrillaMail) presented a real reCAPTCHA, observed by her tool
  and not attempted; Arkose/FunCaptcha on X signup anticipated
  (live-tests/2026-11-25-v08-proof.md, attempt log 2).
- **Lille solution — BUILT (v0.8, in tree):** persistent-profile
  browser lane with `x_session_status` / `x_read_feed` / `x_search` /
  `x_post`, challenge detection that stops and reports, vault-typed
  secrets, sweep X surface backed by her session when one exists
  (XLANE.md). **TO BUILD:** the on-device lane (W1) as the stronger
  X lane — X trusts a real device session more than a host browser;
  and route-ladder discipline for signup dependencies (multiple
  email providers, W13). **HUMAN-HANDOFF:** Arkose/CAPTCHA on her
  own signup if every email/entry route reaches one. The 402 API
  door stays shut by the $0 law — not a wall to fix, a price to
  refuse; the browser lane is the free door.

### W4. Sign-in walls in front of shared content — class B
- **Evidence:** Tactiq transcript share, 2026-11-25 — share link
  lands on a sign-in page (Google / magic link / SSO); browser
  attempt stopped at the wall; resolved the same day when Ethuram
  chose Google sign-in, which passed cleanly
  (memory/2026-11-25.md:1876–1877, :1898).
- **Lille solution — TO BUILD: connector-before-browser check.**
  Before driving any browser at a share link, check whether a
  connector/API/export route exists (Gmail copy, official API,
  owner export). If sign-in is the only door, it happens **once**
  in her persistent profile (session-first, W2) — never as a fresh
  login per task.

### W5. Keyless brain limits: Pollinations anonymous tier — class C
- **Evidence:** ~1 concurrent request, ~45 s between requests per
  IP, 402 + Retry-After beyond that; best-effort, no uptime
  guarantee (ROUTES.md:51). The v0.3–v0.8 proof runs all pace
  against this ceiling.
- **Lille solution — BUILT:** Fallback Law routing (routes.py /
  ROUTES.md): 402/429/503 → cooldown with Retry-After honoured,
  automatic fall-through to the next route, honest queued state
  when all routes are down; private content never spills to
  untrusted routes.

### W6. Keyless endpoint payload ceiling (HTTP 413) — class C/G
- **Evidence:** v0.8 browser-basics proof, attempts 3–5: runs died
  at step 2–8 with HTTP 413; measured empirically — the anonymous
  endpoint accepts a 12,081-byte body, rejects 16,102 bytes; a
  simulated step-2 request was 16,291 bytes (full 32-tool schema
  set alone is 13.2 KB) (live-tests/2026-11-25-v08-proof.md).
- **Lille solution — BUILT (v0.8):** payload discipline — per-spec
  tool-schema subsetting (13.2 KB → 5.1 KB), loop history budget
  with deterministic compaction of older observations, per-
  observation cap. Generalised lesson for every future tool:
  schemas and observations are a budget, spent deliberately.

### W7. Free-model phrasing refusals (GPT-OSS 20B) — class H
- **Evidence:** live battery 2026-11-24, task 7a — Ethuram's exact
  prompt ("can you create an autonomous profitable agent") returned
  the verbatim refusal "I'm sorry, but I can't help with that."
  Score 0/5; the same need rephrased as a concrete paper-trading
  coding task scored 5/5. Recorded weakness: "refusal hair-trigger
  on money phrasing… phrasing-driven" (live-tests/2026-11-24-battery.md:146–221).
- **Lille solution — BUILT (v0.2.1):** refusal recovery
  (free_agent/recovery.py) — a short refusal triggers ONE automatic
  reframe into concrete task form + retry, prefixed honestly;
  harmful-category requests are never reframed; a second refusal
  surfaces with a suggested manual rephrase.

### W8. The model claims a wall it never met — class H
- **Evidence:** v0.8 X acceptance attempt 1 — Lille finished at
  step 2 claiming a CAPTCHA, with **zero tool calls** in the trace;
  the grader initially believed the summary
  (live-tests/2026-11-25-v08-proof.md, attempt log 1).
- **Lille solution — BUILT (v0.8):** a wall grades NEEDS ETHURAM
  only from an *observed* `CHALLENGE` tool result (`_wall_signal`,
  evidence-only); the finish guard rejects any finish on a browser
  spec before a browser tool has run, and any wall claim with no
  CHALLENGE in the trace. Walls are observed, never narrated —
  this register follows the same rule.

---

## P2 — Walls that block a lane at a time

### W9. Binance HTTP 451 from this VM's egress — class D
- **Evidence:** DripBot staging send refused repeatedly 2026-11-10/
  11: the signing runtime's two-source price check depended on
  api.binance.com, which returns 451 "restricted location" from
  this egress (curl- and node-verified; CoinGecko answered fine)
  (memory/2026-11-10.md:149 and AGENTS.md, "Binance 451 lesson").
- **Lille solution — TO BUILD: never single-source a verification.**
  Any check she performs (price, status, balance) draws on ≥2
  independent sources with automatic source-switching; a geo-block
  on one source is a routing event, not a task failure. (The
  DripBot root cause was repaired Muse-side per the AGENTS.md
  rule — a repeated failure is a repair order; Lille inherits the
  rule, not the bug.)

### W10. Video generation 403 — Nigeria — class D/F
- **Evidence:** "video generation is NOT available for this account
  in Nigeria — a lasting regional restriction (403), not an
  outage; do not retry" (memory/2026-11-24.md:181).
- **Lille solution — BUILT:** substitution, not circumvention —
  v0.6 code-driven video (scripted capture + rendered cards + free
  TTS chain + ffmpeg, ffprobe-verified) covers demo/explainer
  needs at $0 (VIDEO.md). Generative video stays on the
  never-retry list.

### W11. edge-tts TLS failure through the egress proxy — class G
- **Evidence:** edge-tts installed, but its synthesis websocket
  fails TLS verification through the host's egress proxy
  (SSLCertVerificationError); the chain falls through
  (live-tests/2026-11-25-v06-proof.md:93–94; VIDEO.md:47).
- **Lille solution — BUILT:** the TTS fallback chain itself —
  Google Translate TTS served all 9 scenes of the proof video and
  the route used is named in the report; honest captions-only
  failure if every route fails. **TO BUILD (minor):** retry
  edge-tts first whenever her host is the phone (no egress proxy
  in the way) — route order should be host-aware.

### W12. GitHub device-flow OAuth dies through the proxy (EOF) — class G
- **Evidence:** `gh auth login --web` token-polling POST dies with
  EOF through the egress proxy though curl to the same endpoint
  returns 200; "don't retry more than once" (AGENTS.md).
- **Lille solution — BUILT (host machinery she inherits):** git
  over SSH via ssh.github.com:443 tunnelled through the proxy
  (socat wrapper + ssh config, AGENTS.md); her own git tools
  (v0.5) never use the device flow — clone/push ride the working
  route, and push stays Sentinel-gated to spec-authorised branches.

### W13. Disposable-email walls under her own signups — class A/F
- **Evidence:** v0.8 acceptance attempt 2 — GuerrillaMail presented
  a real reCAPTCHA (observed, not attempted); she adapted to route
  2 (maildrop.cc) before a machinery failure stopped the run
  (live-tests/2026-11-25-v08-proof.md). Standing risk, named in the
  v0.8 spec: X/TikTok signups may also *reject* disposable domains
  outright even when the mailbox itself opens.
- **Lille solution — BUILT (partial):** an email route ladder in
  the spec (guerrillamail → maildrop → emailnator) with
  route-switching instead of retries. **TO BUILD:** a mailbox she
  *owns* — a free-tier mailbox on a domain she controls, readable
  by her tools — so signup email stops depending on disposable
  providers at all. **HUMAN-HANDOFF:** if a verification code is
  sent somewhere only Ethuram can read.

### W14. SMS / phone codes: never arrive, or arrive only to him — class F/B
- **Evidence:** Mt Pelerin web signup demanded an SMS code that
  never arrived, 2026-11-07 — solved by *switching routes*: the
  Bridge Wallet app issued the same access via email
  (memory/2026-11-07.md:577, :673). CDP signup 2026-11-19: his
  only inputs were two one-time SMS codes (values never recorded)
  (memory/2026-11-20.md:188).
- **Lille solution — HUMAN-HANDOFF + route-switch first:** before
  asking him for a code, exhaust alternate doors (app route, email
  route, authenticator). When a code to his phone is truly the
  door: one ask, code used once, never stored/logged/sent to a
  model (already her security law).

### W15. Personal-appearance requirements — class F
- **Evidence:** Cognizant Agentic Web Challenge vetted ENTER-ONLY-IF
  then scrapped by Ethuram 2026-11-24: the mandatory demo video
  had to include the entrant personally explaining the project,
  not AI-generated (memory/2026-11-24.md:2077, :2175).
- **Lille solution — BUILT:** specmode refuses personal-appearance
  specs *before any work runs* and reports NEEDS ETHURAM (v0.6).
  A Lille video never substitutes for him and never passes as him.

### W16. KYC / identity-verification walls — class F
- **Evidence:** standing pattern across lanes — identity steps are
  the recurring "only he can pass" item (grant/opportunity records
  throughout memory/2026-11-*.md); his rules make payout-KYC his
  own deliberate act (short-term rule, 2026-11-25).
- **Lille solution — HUMAN-HANDOFF, by design:** she prepares
  everything up to the identity step, names the single step, and
  never holds, types, or transmits his identity documents.

---

## P3 — Infra and provider walls (rarer, but they kill runs silently)

### W17. Egress-proxy burst penalty + relay slot bug — class C/G
- **Evidence:** 2026-11-22 balance census: ~260 requests in ~60 s
  tripped the proxy's penalty window (19 CONNECTs refused, queue
  backed to 129) even under a ~12-tunnel cap; and the cap itself
  failed under burst because it was checked at CONNECT *arrival*,
  not at dequeue (AGENTS.md, capped-relay entry).
- **Lille solution — TO BUILD: a request-shaping layer for all her
  outbound work** — per-host pacing, batching instead of bursts,
  concurrency slots reserved at dequeue time, and backoff that
  treats a penalty window as a waiting state (v0.4 pattern), never
  as a reason to hammer.

### W18. /tmp is a 512 MB tmpfs — and logs die with the host — class G
- **Evidence:** 2026-11-09 — the name-claims processor's sync died
  with /tmp 97–100% full of leftover browser profiles (AGENTS.md).
  2026-11-25 — v0.8 acceptance attempt 3 was killed by a host
  restart ~12 min in; the in-process run *and its /tmp log* were
  lost; only her on-disk browser profile survived
  (live-tests/2026-11-25-v08-proof.md, attempt log 3).
- **Lille solution — BUILT (partial):** durable task store with
  per-step checkpoints outside /tmp (v0.4) and her persistent
  browser profile on disk. **TO BUILD:** run logs default outside
  /tmp, and a self-pruning rule in her harness — every run deletes
  its own scratch (profiles, captures) on exit, the AGENTS.md rule
  as code.

### W19. Samsung battery optimisation / Doze vs background work — class G
- **Evidence:** OFFLINE.md:79–84 — Samsung battery optimisation can
  still kill background work; Doze and app standby can delay
  WorkManager retries. On-device survival is provable only on
  Ethuram's A12 (v0.4/v0.7 proof notes).
- **Lille solution — BUILT (partial):** WorkManager foreground
  workers, boot recovery, checkpoint/resume so a killed task is a
  paused task, never a lost one (v0.4). **TO BUILD:** a first-run
  flow that asks for the battery-optimisation exemption with the
  reason stated plainly, and a "while you were away" report that
  says honestly if a pass was Doze-delayed.

### W20. Netlify free-plan credits cap (300/cycle) — class C/E
- **Evidence:** Nov 7–8 publish cadence burned the Townsvilledh
  team's 300 credits; every later deploy "Skipped due to account
  credit usage exceeded" with no error on the site itself — the
  symptom is a frozen deploy stamp while other hosts advance
  (AGENTS.md).
- **Lille solution — TO BUILD (lesson generalised):** before any
  metered action, a pre-flight budget check (credits/status read
  first, batch releases, one deploy per meaningful change). Any
  future Lille publishing lane inherits: *metered doors are
  checked before they are knocked on.*

### W21. Unchecked upstream response poisons state (Blockstream 429) — class C/I
- **Evidence:** DripBot keeper crash-looped 2026-11-14: a 429 error
  body was read as the chain-tip hash ("bad tip hash") until the
  quota reset; fixed with status-check + shape validation +
  fallback host (AGENTS.md).
- **Lille solution — TO BUILD: response-validation guard in her
  HTTP/tool layer** — no response feeds her state until status and
  shape are validated, and every critical read has a fallback
  source (W9's rule, enforced in one place).

### W22. Provider outage cuts a whole family (Chainflip) — class I
- **Evidence:** Chainflip down since the 2026-11-21 exploit (safe
  mode, quoting disabled) — Polkadot swaps fully cut off; LP
  refunds are a return of balances, not a restart (MEMORY.md).
- **Lille solution — TO BUILD: dependency health probes.** Any
  lane she works that depends on an external rail gets a
  creation-shaped health probe on a cadence, and a dead rail is
  labelled NO-ROUTE with the date — never silently retried, never
  reported as available.

### W23. Silent provider failure dressed as success (fake zeros) — class I
- **Evidence:** Solana lesson, 2026-11-08: free RPCs began refusing
  token reads in browsers while balance rows silently showed fake
  zeros for ~2 days (AGENTS.md, glaring-bug sweep).
- **Lille solution — BUILT (as doctrine, enforced in her graders):**
  verify-by-state — a claimed result needs independent evidence
  (ffprobe line, git hash, file on disk, observed CHALLENGE), and
  "couldn't load" is a legitimate answer while a silent zero is a
  failed run. This is why W8's fix exists.

### W24. Search-surface bot walls: DDG HTML 202, DoraHacks — class A
- **Evidence:** sweep #1 coverage note: DuckDuckGo HTML answered
  HTTP 202 (bot challenge; a manual curl minutes earlier had
  received results — fingerprint-sensitive); DoraHacks public API
  errored/bot-walled (live-tests/2026-11-25-v07-sweep-report-1.md:47;
  BENCHMARK.md:207).
- **Lille solution — BUILT (partial):** her sweep already attempts
  both every time and names them NOT CHECKED with reasons — the
  gap is printed, never hidden (Thoroughness Law). **TO BUILD:**
  browser-session surfaces for both (her v0.8 browser, later the
  on-device lane), so "checked" stops depending on keyless HTTP.

---

## TikTok — her next acceptance task (after the X test)

No TikTok attempt exists anywhere in the record yet — every wall
below is *anticipated* from the platform's known shape, labelled as
such, not evidence.

**What the X lane gives her for free:** the browser tools and
domain allowlist; persistent-profile sessions; vault-generated
credentials typed by key name only; challenge detection that stops
and reports; human pacing and posting rate limits; the identity-kit
spec pattern; evidence-graded pass/fail acceptance; and — uniquely
hers — the v0.6 video pipeline: she can *make* TikTok-native
content (vertical 720×1280 capture + cards + TTS + ffmpeg) at $0,
which is the entire content side of a TikTok presence.

**TikTok-specific walls to expect:**
- **Signup challenges (A/F, anticipated):** TikTok signup leans on
  puzzle/slider CAPTCHAs and aggressive automation detection —
  harder than X's. Expect the host-Chromium lane to be challenged;
  the on-device lane (W1) is the realistic door, and a slider only
  Ethuram can pass is HUMAN-HANDOFF under the protocol.
- **App-first design (B, anticipated):** parts of TikTok (first
  login trust, some verification, region setting) are app-only or
  app-trusted; web sessions are second-class. Her TikTok lane
  likely *requires* the on-device lane, not merely prefers it.
- **Phone/email verification (F, anticipated):** signup and
  trust-raising may demand a phone number — she has none; email
  route first (W13's owned mailbox matters here), phone demand =
  HUMAN-HANDOFF, never a borrowed or virtual number.
- **Region/geo set at first use (D, anticipated):** TikTok fixes
  region signals early (SIM/network/locale); a datacentre-IP first
  session may brand the account's region wrongly. First session
  from the phone, again.
- **New-account limits (C/F, anticipated):** fresh accounts face
  posting/follow limits and shadow-review; her pacing policy
  (slow, human-shaped, few actions/day at first) is the mitigation,
  stated as mitigation, not guarantee — same honest risk note as X:
  TikTok may restrict accounts it detects as automated; the account
  is hers, created at Ethuram's explicit direction.

**Acceptance shape (same as X):** PASS = her run produces a live
TikTok account in her name, profile set (name, bio, mascot avatar),
persistent session, and her first video post live with a URL —
the video produced by her own v0.6 pipeline. Anything less is
recorded as it is, never relabelled.

---

## The HUMAN-HANDOFF protocol (applies to every F-class wall)

1. Everything pre-staged: form filled, files ready, session parked
   *at* the wall — no reload that would wipe the work.
2. ONE step named, in plain words, with what happens after it.
3. She stops. No looped prompts (W2), no second attempt through
   the same door, no bypass, ever.
4. The moment he takes the step, her run resumes automatically
   from the parked session and finishes the task herself.
5. The report says NEEDS ETHURAM + the exact step — and the
   acceptance test stays open, resumable, not failed.

---

## Build next — for v0.9, in order

1. **On-device browser lane** — app-driven WebView/Chrome on the
   A12 (real fingerprint, real network, her perception + Sentinel
   gates on top); lane-picker chooses host vs device per wall
   class. Kills W1, strengthens W3/W24, unlocks TikTok.
2. **Pre-flight wall diagnosis + attempt budgets in the loop** —
   the diagnose-before-retry skill as code: classify the wall,
   two same-shape failures max, then switch driver/route or hand
   off. Kills W2 loops, disciplines W3/W13/W24.
3. **Parked-session handoff as a first-class task state** —
   PARKED_AT_WALL with screenshot, the one named step, and
   auto-resume on completion. Formalises the protocol above.
4. **Session vault** — one persistent profile per service with a
   health check (the `x_session_status` pattern generalised);
   login happens once per service, ever. Kills W4, most of W2.
5. **Request-shaping + response-validation layer** — pacing,
   batching, dequeue-reserved slots, status/shape validation
   before any response touches state. Kills W9/W17/W21.
6. **Her own mailbox** — a free mailbox on a domain she controls,
   readable by her tools, ending disposable-email dependence.
   Kills most of W13; unblocks TikTok email signup.
7. **Sweep browser surfaces** — DDG HTML and DoraHacks checked via
   her browser session when keyless HTTP is walled. Closes W24.
8. **Run hygiene** — logs/checkpoints outside /tmp by default;
   every run prunes its own scratch on exit. Closes W18.
