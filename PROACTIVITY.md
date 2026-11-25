# PROACTIVITY.md — the Proactivity Law and its machinery (v0.7)

## The law

Ethuram, 2026-11-25 ~10:50 WAT, binding:

> Lille must be proactive. She must not stay laid back while tasks
> sit on the ground; she works the queue without waiting to be
> reminded, and she reports what she has DONE unprompted. Ethuram
> must never have to chase "have you submitted this / did you do
> that". Results of anything she entered or submitted are checked
> and surfaced when they land, not when asked.

Born of a concrete failure: a make.fun competition result
(news-image-20261123, judged 01:00 WAT on 2026-11-25) sat unreported
until Ethuram chased it that morning. The law exists so that class
of failure is impossible for anything Lille tracks — not unlikely,
*impossible*, because the tracking and the reporting are machinery,
not intentions. This is BENCHMARK.md bar 8.

## The machinery

### 1. Commitments register (`free_agent/commitments.py`)

Every pending result becomes a **commitment**: a persisted record
(one JSON file per entry under `<workspace>/commitments/`) holding

- what it is (title, kind: `watch` / `deadline` / `queued_task`),
- a **machine-checkable landing condition** (the check spec),
- how often to check, an optional deadline, a privacy flag,
- its state: `open` → `landed` | `expired` | `closed`, plus the
  result payload and whether its report has been written.

Check specs (a commitment whose landing cannot be checked
mechanically is refused at registration):

| Spec | Lands when |
|---|---|
| `url:<URL>` | GET returns 200 |
| `url-contains:<URL>::<text>` | 200 and the body contains the text |
| `file:<relpath>` | the file exists under the workspace |
| `actions-run:<owner>/<repo>:<run_id>` | the Actions run's status is `completed` (payload carries the conclusion; public repos need no credential) |
| `task-state:<task_id>[:<state>]` | the durable task reaches the state (default `completed`) |

Register from the CLI:

```bash
python -m free_agent --commit "make.fun news-image result" \
    --check "url-contains:https://…::results" --every 30 \
    --deadline 2026-11-26T01:00:00Z
python -m free_agent --commitments          # the register, with states
python -m free_agent --close-commitment ID  # stop watching
python -m free_agent --enqueue "…"          # queue a task for idle pickup
```

**Auto-registration:** a spec pack can declare a `Watch:` section
(see specmode.py); when the spec finishes, each Watch item becomes
a commitment automatically. Entering something and watching for its
result is one act, not two.

### 2. The proactive pass (`free_agent/proactive.py`)

```bash
python -m free_agent --proactive-run [--lane] [--pace 50]
```

One pass, in order:

1. **Due checks** — every open commitment whose check is due is
   checked mechanically. Landed → state `landed` + report. Past
   deadline unlanded → `expired` + a BLOCKED report (a missed
   result is surfaced, never silently dropped).
2. **Idle pickup** — durable tasks sitting in `queued` (enqueued,
   never started) are run through the v0.4 engine.
3. **Waiting resume** — tasks in `waiting_network` /
   `waiting_route` whose retry time has passed resume from
   checkpoint.
4. **Outbox** — every outcome writes ONE report to
   `<workspace>/reports/`, named `<timestamp>-<id>-<slug>.md`:

   ```
   # Lille — proactive report
   STATUS: DONE | LANDED | BLOCKED
   Cost: $0 — keyless routes only
   When: …
   Subject: …
   …
   ```

A pass with nothing due writes **nothing** — proactivity is not
spam. Reports are written only in the same moment a persisted
state transition happens, and transitions are one-way: re-running
a pass can never duplicate a report or re-execute a landed check
(idempotency is structural, and unit-tested).

### 3. The two lanes that run passes unprompted

| Lane | Cadence | Scope | Honest limits |
|---|---|---|---|
| **On-device** (Android `ProactiveWorker`, WorkManager periodic) | 15 min (WorkManager's minimum period) | The device's own commitments (URL checks), queued-task pickup via TaskWorker, settle-and-report on the Tasks screen's "While you were away" list | Doze / battery optimisation can defer periodic work; the pass runs when Android lets it. URL checks only on-device — richer checks live in the core. |
| **Scheduled** (`.github/workflows/lille-proactive.yml`) | cron `13,43 * * * *` (~30 min) | Lane state on the dedicated `lille-state` branch: commitments + task store + outbox, restored each run, committed back after the pass | GitHub cron is best-effort: runs can be delayed or dropped under load, and schedules can be disabled on inactive repos/forks. **Non-private only** — see below. |

**Privacy, enforced in code, twice:** a commitment flagged
`--private` never leaves the device. `export_for_lane()` refuses
private entries when state is exported, and a lane pass
(`--proactive-run --lane`) skips — and names — any private
commitment that somehow appears in lane state. Private results are
watched by the on-device lane only.

### 4. Android surface

The Tasks screen carries, under the task list:

- **"While you were away"** — the outbox: reports written by
  proactive passes without anybody asking (a watched result
  landed, a queued task finished, a deadline passed unlanded).
- **"Commitments"** — the register, with each entry's state and
  how many checks have run.

The periodic worker is armed on app start and re-armed on boot
(`BootReceiver`).

## What is proven, and what is not

Live proofs: `live-tests/2026-11-25-v07-proof.md` — (a) a real
GitHub Actions run watched to its conclusion and reported by
proactive passes only; (b) an enqueued task nobody started,
picked up and completed by a pass, with its DONE report; (c) the
scheduled lane executing the proactive entrypoint on Actions with
state committed back to `lille-state`.

Not claimed: hard real-time delivery (both lanes are best-effort
cadences, stated above); on-device periodic survival under Samsung
battery optimisation (only Ethuram's A12 can prove that — the same
honest gap as v0.4); private commitments on the scheduled lane
(refused by design, not a gap).
