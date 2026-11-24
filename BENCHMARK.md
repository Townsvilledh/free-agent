# BENCHMARK.md — Lille vs Manus, measured

Ethuram's standing standard (2026-11-24 ~20:45 WAT): **"Lille should
be better than Manus by every bar there is."** These seven bars are
the benchmark for EVERY rung from v0.5 on. Each bar states how it is
measured, Lille's current measured status, and Manus's baseline from
the 2026-11-24 deep-research report
(`~/workspace/research_notes/autonomous-agents-deep-research-20261124-0017/report.md`;
figures there are index-sourced from vendor docs via trackers and
are volatile — the direction of each comparison is the durable part).

Where a bar cannot be measured yet, this file says so and names the
measurement to build. Where Manus genuinely leads today, the gap is
named plainly — see "Open gaps" at the bottom. Nothing here is
softened.

## The 7 bars

### 1. COST
- **Measure:** money spent per completed task, from the task report.
- **Lille:** $0. Every spec-mode final report carries the line
  `Cost: $0 — keyless routes only` plus which routes served
  (enforced by `specmode.validate_report` — a report without the
  line fails validation). Keyless public APIs only; no key is
  collected from anybody.
- **Manus baseline:** paid credits. Free tier: 300 daily-refresh
  credits capped at 1,500/month, Lite model only. Pro $20/mo = 4,000
  credits. Manus's own examples: ~200 credits per 15-min task, ~900
  credits per 80-min app build — and **no pre-task cost estimate**
  (its users' top complaint).
- **Verdict:** Lille ahead, structurally — the meter cannot run out
  because there is no meter.

### 2. TASK COMPLETION
- **Measure:** end-to-end completion rate on real tasks
  (spec → built → tested → delivered), live-proven, tallied below.
  Mocked-model tests prove machinery; only live runs count here.
- **Lille:** running tally below — every entry is pass/fail as it
  actually happened.
- **Manus baseline:** capable cloud builds (web apps with backend +
  DB + auth), wide-research fan-out; completion is real but metered
  per task in credits, and free tier is capped at 1 concurrent task.
- **Verdict:** Manus ahead on task *size* today; Lille's rate on
  small repo tasks is now being measured (tally below). The gap is
  closed rung by rung, with the tally as the receipt.

### 3. HONESTY
- **Measure:** does "done" mean verified state, with proof shown?
  Count of fake successes / silent failures in live proofs: must
  stay 0.
- **Lille:** verify-by-state (DESIGN #6). `finish` claims are checked
  against the filesystem; spec-mode criteria are graded
  deterministically from the trace + git state, never by the model;
  waiting states (network/route) are reported as waiting, never as
  failures or successes. Live proofs to date: 0 fake successes —
  failures (v0.3's first Fibonacci attempt, v0.2.1's refusal) were
  recorded as failures in their own files.
- **Manus baseline:** session replay shows the timeline, but success
  is the model's own narration of that timeline; the Oct 2026
  incident (below) was flagged malicious only *after* code ran.
- **Verdict:** Lille ahead by design and by record so far.

### 4. PRIVACY
- **Measure:** where credentials and private content can physically
  go. Audit = code paths, not promises.
- **Lille:** credentials live only in the encrypted vault; they never
  enter model context, tool args, or the sandbox environment
  (env-scrubbed). Private content is blocked from community relays by
  the router (privacy routing, tested with zero transport calls).
  Open source (MIT), phone-hosted: the user can read every line and
  the data never has to leave the device except to a model route the
  rules allow.
- **Manus baseline:** closed cloud. Oct 2026 (Salt Labs, via the
  research report): one injected email made Manus execute attacker
  code and reach its Gmail access token; Drive/GitHub credentials
  sat in environment variables.
- **Verdict:** Lille ahead, architecturally.

### 5. PERSISTENCE
- **Measure:** kill the process mid-task; does the task resume from
  checkpoint and finish, with no repeated steps?
- **Lille:** PROVEN (v0.4): cross-process resume — a task stopped
  after step 1 was resumed by a new process at step 2 and completed;
  waiting_network / waiting_route states instead of fake failures;
  GitHub Actions runner lane proven live (run 45465252658) for
  non-private tasks while the phone is off.
- **Manus baseline:** cloud sessions persist on Manus's servers and
  are replayable; automations are capped (free tier: 2). Persistence
  is real but lives on their computer, under their account rules.
- **Verdict:** Lille proven at small scale; Manus's persistence is
  rented. Different risk shapes — Lille's survives Manus-the-company
  disappearing; Manus's survives the user's phone dying.

### 6. CONTROL
- **Measure:** who can stop the agent, inspect it, or take it away?
- **Lille:** MIT code, user's phone, user's rules. Deterministic
  Sentinel gates every tool call (allowlist, sandbox, secret-scan
  commit block, spec-authorised push branches only, approval hooks).
  No account to suspend, no credits to exhaust, no ToS kill-switch
  on the agent itself.
- **Manus baseline:** closed, account-based, credit-metered; access
  can be suspended and pricing changed unilaterally.
- **Verdict:** Lille ahead, structurally.

### 7. DELIVERY
- **Measure:** does the user end with the artifact in hand (file /
  link / commit on their repo), or with a status narration?
- **Lille:** deliverables are files in the task dir, commits with
  hashes in the report, pushed branches named in the report, APKs at
  a stable direct link. Every v0.5 spec report lists commits and
  per-criterion evidence.
- **Manus baseline:** deliverable-first output is a genuine Manus
  strength (files/apps out of sessions, replayable).
- **Verdict:** comparable in intent; Lille's receipts are git hashes
  and files the user owns outright.

## Running tally — live task completion (bar 2)

| Date | Task | Result |
|---|---|---|
| 2026-11-24 | v0.3 Fibonacci: write program + self-test, run, fix, deliver file (core loop, live brain) | PASS — 10 steps; first attempt failed its own test (4181≠6765), diagnosed + fixed; fib.txt verified 20 lines, 20th = 6765 |
| 2026-11-24 | v0.4 cross-process resume: task killed after step 1, resumed by a new process | PASS — resumed at step 2, completed; no repeated steps |
| 2026-11-24 | v0.4 runner lane: non-private task executed by GitHub Actions while "user away" | PASS — run 45465252658 completed; artifact retrieved; task completed with trace + out.txt |
| 2026-11-24 | v0.5 proof (a): implement-from-spec (calc.py + tests, commit) | PASS (SHIPPED on attempt 3; attempts 1–2 exposed a missing finish guard + a finish-call dialect the parser rejected — both fixed in the machinery and unit-tested; final run: tests green, commits b65d04f + 3e637a3, independently re-verified) |
| 2026-11-24 | v0.5 proof (b): repair a failing suite without touching the tests, commit | PASS (SHIPPED; commit 7be3bc4; independent check: suite green, diff = calc.py only, test file untouched) |
| 2026-11-24 | v0.5 proof (c): spec-authorised push to branch `lille-proof/v05` on the real repo, branch deleted after verification | PASS (branch verified on the remote at 9dbe96752a29 with exactly the specified file/line; branch then deleted, hash recorded. The run's own report file was lost when a host restart wiped /tmp after the push — the push itself is the proven part; see the proof file) |

Tally so far: **6 live tasks, 6 completed** (3 of them after
machinery fixes driven by the failed attempts — the attempts are
counted in the proof files, not hidden). All at $0.

## Open gaps — where Manus genuinely leads today

1. **Raw model power.** Manus routes to frontier-class models;
   Lille's keyless brain is GPT-OSS 20B. On ambiguous, high-stakes,
   or very long tasks the 20B model's judgement is shallower (the
   v0.2 live battery: 32/40, with small fidelity slips). *Closes
   when:* stronger free routes join the Fallback Law chains (rung:
   routing — optional user-supplied keys already supported; keyless
   frontier access does not exist today and is not promised).
2. **Browser polish.** Manus drives a real browser with a mature
   harness and session replay. **Lille has no browser control yet**
   (a Playwright skeleton exists; it has never completed a live
   browser task). *Closes at:* the browser rung — structural
   perception first (API → accessibility/DOM → vision last, per
   DESIGN #2), proven on live tasks before it is claimed.
3. **Task size / long-horizon builds.** Manus completes 80-minute
   multi-file app builds. Lille's proven envelope today is small
   repo tasks (single-digit files, ≤25-step spec runs). *Closes
   when:* the tally above accumulates larger specs — the Stellar
   readiness checklist below is the measuring stick.

## The proficiency standard — Stellar Buildathon spec pack, Part 1

The benchmark spec is the Stellar Buildathon pack prepared for a
delegation agent (`~/workspace/goals/fluid-universal-wallet-and-ogo-era/files/stellar-buildathon-manus-spec-pack.md`,
Part 1). It demands, of one agent, working alone for a week on a
large existing codebase (Fluid). **Bluntly: v0.5 does NOT make Lille
proficient at that size yet.** Readiness by capability:

| Capability the spec demands | Lille after v0.5 | Evidence / gap |
|---|---|---|
| Long-spec comprehension (multi-thousand-word pack, hard rules, acceptance criteria) | Partial | Spec mode parses Goal/Hard rules/Criteria/Deliverables/Push and grades criteria from evidence — proven on small specs. A pack of Buildathon size has never been run; context limits of the 20B brain are the binding constraint. Measurement to build: run a sectioned large spec and score criterion coverage. |
| Multi-workstream planning (3 workstreams over 6 days, day-by-day plan) | Not yet | The loop plans step-by-step inside one task; there is no multi-day planner, no workstream state. Gap closes at the scheduling/proactivity rung (durable tasks exist; a planner layer does not). |
| Code changes in a large existing codebase (Fluid: a very large single-file wallet core + tests) | Partial | Repo tools (clone/tree/read/edit/test/diff/commit) are proven on small repos. Never attempted on a Fluid-sized repo; file-context limits and 25-step budgets bind. Measurement to build: a real Fluid maintenance task (e.g. a test-covered utility fix) as a spec, scored like proofs (a)–(c). |
| Test/build verification (run the suite, first-user walk of the live build) | Partial | run_tests auto-detects and reports real exit codes — proven. The "first-user walk" (open the live app, click every control) needs the browser rung — not yet. |
| Deadline discipline (freeze dates, submission by a fixed midnight) | Not yet | Durable tasks + waiting states exist; no calendar/deadline awareness, no freeze enforcement. Gap closes at the scheduling rung. |
| Honest status reporting (shipped / in build / unproven, exact blockers, single-step asks) | Proven (small scale) | The spec report shape (SHIPPED / BLOCKED / NEEDS ETHURAM with per-criterion evidence + Cost line) is enforced by code and validated in tests. Proven on v0.5's small proofs; unproven at Buildathon scale. |

**What would change the verdict:** Lille completing one real
Fluid-sized maintenance spec end-to-end (clone → change → suite
green → commit → report), then a multi-workstream week simulated
against the clock. Until then, Muse owns the Buildathon build;
Lille's proficiency at that size is the target, not a claim.
