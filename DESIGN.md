# DESIGN.md — Lille's design principles

Distilled from a deep-research comparison of every major autonomous
agent (Muse, Manus, ChatGPT Agent/Work, Gemini, Claude Computer Use,
Grok Bot, Devin, OpenHands, Browser Use, Skyvern, AutoGPT, OpenClaw,
and the Android-native agents DroidPilot / Buddy / JARVIS), researched
2026-11-24. Full report with sources:
`~/workspace/research_notes/autonomous-agents-deep-research-20261124-0017/report.md`
(research figures are index-sourced and volatile — the principles are
the durable part).

The headline finding: **all successful agents share one skeleton** —
a persistent computer + planner loop + connectors + file memory +
cron + approvals + takeover UI. They differ in *where the computer
lives, how the screen is perceived, and how the meter runs*. Lille's
differentiation is therefore not a better model; it is where the
computer lives (the phone), how the meter runs (free, visible,
fallback-routed), and how trust is enforced (deterministically, not
by the model).

## North Star

Lille's destination, in Ethuram's terms (restated 2026-11-24): a
**fully autonomous universal agent that handles every task Muse
handles — including building whole products and delivering them.**
His example: he says "build me X", and Lille answers *"send me an
APK of something I said you should do"* made real — the finished
thing comes back to his phone, not a plan, not a code dump, a
deliverable. An agent capable of creating something like Fluid is
the bar.

**The build-and-deliver pattern.** Lille directs from the phone;
free cloud runners (GitHub Actions) act as her build hands; she
delivers the finished artifact or link back. This is not a fantasy
architecture — it is the exact loop that built Lille herself:
specified from a phone, built and tested on GitHub's free runners,
and delivered back as a one-tap APK link. The pattern is proven;
the work is widening what she can direct.

**The staged parity ladder.** Each rung is shipped and live-tested
before it is claimed — no rung is described as done from intention:

1. Chat + fallback routing — **Lille stands here (v0.2.1).**
2. Tool-call loop
3. Code & file tools
4. Build-and-deliver via Actions
5. Browser control (structural perception first — principle 2)
6. Scheduling / proactivity
7. Sub-agent delegation
8. Memory / goals system

The ladder is the honest distance between "chat that routes well"
and the North Star. Every release names the rung it stands on.

## The principles

### 1. The phone is the computer; the model is a rented brain
All state — memory files, job store, credentials vault, browser
profile — lives on the user's phone in user-readable formats
(Markdown + SQLite). Losing every provider loses zero user data.
This also deletes the hidden $5–17/mo VPS line item that makes most
"free" self-hosted agents not actually free.
*Lille today: HOSTING.md Layer 0. In force.*

### 2. Perceive structurally first; vision only as a last resort
Strict ladder: official API → accessibility/DOM element list
(kilobytes) → screenshot/vision. A screenshot costs ~1,000–1,800
tokens *per step*; an accessibility list costs ~1–3 KB total
(DroidPilot evidence). Vision loops on metered free tiers are how
budgets die.
*Lille today: rule adopted for all future browser/app work; the
Python browser skeleton and the on-phone WebView plan both follow
API → structure → vision.*

### 3. Every successful task becomes a recorded macro
Record the trace of a task that worked; compile repeat runs into
deterministic macros that execute with **no model call** and stop on
first mismatch. The model is for novel situations, not routines —
and a macro library is a growing stock of *free* tasks.
*Lille today: planned (post-v0.2 roadmap). Design constraint on the
tool layer from now on: tools must emit replayable step traces.*

### 4. A deterministic non-LLM sentinel owns permissions + credentials
Credentials never enter model context or environment variables —
ever. The evidence is not theoretical: one injected email made Manus
run attacker code and reach its Gmail token (its Drive/GitHub
credentials sat in env vars), and six AI browsers were talked into
copying SSH keys by a puzzle page. So: permissions, credential
handling, taint rules (read personal data → no egress without
approval) and budgets are enforced by **plain deterministic code**,
never by asking the model to behave.
*Lille today: the seed exists — `approvals.py` gates, privacy
routing, redaction, encrypted vault, keys only in the vault. Growing
this into the full sentinel is the security roadmap; the rule
"credentials never in model context/env" is already absolute.*

### 5. Show the quota meter BEFORE running a task
Manus's top user complaint is no pre-task cost estimate; Devin
abandoned its own opaque credit unit. Lille shows an estimate in
quota the user understands ("≈40 of today's 1,000 free Groq
requests", "this will use the keyless route, ~45s between steps")
**before** a run, enforces a hard per-task budget, and issues a
post-run receipt (which route served, what it cost).
*Lille today: the router already knows every route's public limits
(ROUTES.md) and logs which route served; the pre-run estimate UI is
planned. The rule binds all future task UIs.*

### 6. Verify by checking state, never by narrating success
A task is done when a state check passes — the event exists, the
file changed, the message is in Sent, the price was recorded — not
when the model says "done". Failed verification → one replan, then
escalate to the user with the trace attached.
*Lille today: adopted as the acceptance rule for all future tool
work (it is also how this project's own builds are verified: tests
run, artifacts downloaded and compared byte-for-byte).*

### 7. Proactive = prepared drafts; sends stay approval-gated
Scheduled jobs and nudges produce drafts, digests and pre-filled
actions awaiting one tap. An agent that sends unprompted multiplies
the prompt-injection and hallucination blast radius. Sending
autonomy per channel is earned after a history of approved drafts,
never granted by default.
*Lille today: in force — approval gates for send/post/delete/money
are core policy (SECURITY.md).*

### 8. Free-tier router ordered by cap scarcity, degrading gracefully
One OpenAI-compatible client, N free routes, fallback ordered by
daily-cap scarcity (scarcest caps last), health/429-aware with
cooldowns (the Fallback Law, ROUTES.md). When every tier is
exhausted, Lille **queues, replays macros, and uses the on-device
small model** — it degrades; it does not die.
*Lille today: fallback law implemented for text + search (v0.2);
macro replay and the on-device small model are the degradation
layers still to build.*

### 9. OSI licences only, pinned dependencies, no telemetry
MIT/Apache-2.0 components only. Traps named in the research:
AutoGPT **Platform** is PolyForm Shield (source-available, NOT OSI —
only Classic is MIT); **Skyvern is AGPL** — do not copy either.
Browser Use / OpenHands / LangGraph / CrewAI are MIT and safe to
learn from. Pin and hash dependencies (a popular MIT router's PyPI
releases carried a credential stealer in 2026), keep the dependency
surface minimal, ship no telemetry.
*Lille today: in force — this repo is MIT, stdlib-first Python,
minimal Android dependencies, no analytics anywhere.*

### 10. Distribution via GitHub / F-Droid, not Play
Google Play has already rejected an autonomous-accessibility agent
(Buddy: "Accessibility API cannot be requested for performing
autonomous tasks") and distributes via GitHub/F-Droid instead.
Lille ships as a direct GitHub APK download now; F-Droid is the
store path if any. Play is later/never, and accessibility
permissions get honest plain-language disclosure in-product.
*Lille today: GitHub APK distribution live (v0.1+); F-Droid planned.*

### 11. No hard-coded branding — identity lives in user config/memory
Sign-offs, brand lines, house style and persona flourishes belong to
the *user's* config and memory files (SOUL.md / USER.md / MEMORY.md,
per-user settings), never to code, shipped prompts, or tests. The
lesson is dated 2026-11-24: a test once baked one project's X-profile
sign-off into a Lille task as if it were a Lille rule — it was that
profile's branding, nothing more. Code that hard-codes branding
breaks the moment the same agent serves a different user, project,
or mood; config that carries branding travels with its owner.
*Lille today: in force — v0.2.1 audited the repo (code, prompts,
docs) for hard-coded sign-offs; the one occurrence was a test file,
corrected with a dated note.*

## What we deliberately do NOT copy
- Opaque credit meters (Manus/Devin lesson).
- Vision-only perception loops as the default (Claude CU token math).
- Shared computers/logins across agents (Grok Bot's blast radius).
- Sandbox-off-by-default host execution (OpenClaw's advisory count).
- Credentials in environment variables (the Manus breach).
- Demo-first autonomy claims (Rabbit: 0 rides, 0 meals in review
  testing). Lille's roadmap table marks built / in-progress /
  planned honestly for exactly this reason.
