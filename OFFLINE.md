# OFFLINE.md — Lille works while you are away (v0.4)

Ethuram's requirement, in his words: Lille must handle tasks even
when he goes offline, like Muse does. "Offline" is three different
situations, and v0.4 treats them differently and honestly:

## Mode A — you leave; the phone stays on and connected
You create a task (Tasks screen in the app, or `--task` in the core),
press Run, then close the app, lock the phone, and walk away.

- **Core:** the durable task engine (`free_agent/taskstore.py`)
  checkpoints the task after **every step** — request, full message
  state, step count, sandbox dir, tool trace, timestamps — into
  `<workspace>/task_store/<id>.json` (atomic writes). Killing the
  process loses at most the in-flight model call. A new process
  resumes with `--resume <id>`; step numbering continues and
  completed steps are never repeated.
- **Android:** a Tasks screen backed by a persistent local store,
  executed by WorkManager as a **foreground worker** (persistent
  notification while active), checkpointing after every step to
  `filesDir/lille_tasks.json`. WorkManager's queue itself persists,
  and a boot receiver re-queues unfinished tasks after a reboot.

## Mode B — the network drops mid-task
This is a **waiting state, never a failure**:

- Transport failure on every route → `waiting_network`, with a
  next-retry time recorded on the task.
- Every free route rate-limited / cooling down → `waiting_route`,
  retrying at the earliest route unlock.
- Android: WorkManager retries with backoff under a CONNECTED
  network constraint — a task dispatched with no network simply
  waits in the queue and starts when the network returns.
- The checkpoint means the retry **resumes** the task where it
  stopped; nothing is re-sent by the user and no step is repeated.

Task states (same names in core and Android):
`queued, running, waiting_network, waiting_route, paused, failed,
completed, cancelled` — plus one Android-only state,
`needs_runner` (below). Real failures still fail honestly; only
network/route conditions wait.

## Mode C — the phone goes fully offline after dispatch
Some tasks cannot run on the phone at all — above all **code
execution**, which the Android app refuses to fake. Such a task is
marked `needs_runner` and exported as a task spec JSON
(`filesDir/specs/<id>.json`). Non-private specs can run on the free
**GitHub Actions runner lane** (`.github/workflows/lille-task.yml`):

1. Dispatching needs **one online moment** (you, or Muse, trigger
   `workflow_dispatch` with the task id + text).
2. After that the phone can go **fully offline**: the runner runs
   the Python core loop, checkpointing as it goes.
3. When you are back, the run's artifact **`lille-task-<task_id>`**
   holds the task spec, the checkpoint/task-store record, the step
   trace, and every file the task produced.

**Non-private only — enforced, not promised:** the workflow's first
step refuses any spec flagged `private`. Private content never runs
on shared CI (public logs, third-party routing); that is the
privacy-routing law applied to hosting. The app stores **no** GitHub
credentials for this lane; in-app, the lane stops at exporting the
spec and showing the `needs_runner` state honestly.

## What survives what

| Event | Survives? | Mechanism |
|---|---|---|
| App closed / swiped away | Yes | checkpoints on disk; WorkManager queue |
| Process killed | Yes | checkpoint after every step; resume continues numbering |
| Phone reboot | Yes | WorkManager persistence + boot receiver re-queue |
| Network loss mid-task | Yes | `waiting_network` + retry; checkpointed resume |
| Free route rate-limited | Yes | `waiting_route` until earliest unlock |
| Battery dies mid-task | On return | last checkpoint resumes after reboot |
| Task needs code execution (phone) | Via runner | `needs_runner` → spec → Actions lane |

## Honest Android limits (read this before trusting mode A)

- **Samsung battery optimisation can still kill background work.**
  Android's guarantees are requests, not laws, on aggressive OEM
  builds. For tasks that matter: exempt Lille from battery
  optimisation (Settings → Apps → Lille → Battery → Unrestricted)
  and keep the foreground notification visible while a task runs.
- **Doze and app standby** can delay WorkManager retries; a waiting
  task resumes when Android lets it, which may be minutes later
  than its retry time on a sleeping phone.
- WorkManager work is **deferrable, not real-time**: Android, not
  Lille, picks the exact run moment within the constraints.
- Foreground workers show a persistent notification by design —
  that visibility is what buys the stronger execution guarantee.
- On-device tools are deliberately small (model calls, web search,
  sandbox notes). Anything heavier belongs on the runner lane —
  the phone marks it `needs_runner` instead of pretending.

## CLI (core)

```bash
python -m free_agent --task "Write a haiku about offline agents and save it to out.txt" --pace 50
python -m free_agent --tasks            # list tasks + states
python -m free_agent --resume <id>      # continue in a NEW process
python -m free_agent --pause <id>       # takes effect at the next step
python -m free_agent --cancel <id>      # takes effect at the next step
```
