"""Durable task engine (v0.4) — Lille keeps working when the user
goes away (OFFLINE.md modes A/B/C).

A task is a record in a persistent store (one JSON file per task
under <workspace>/task_store/, written atomically) plus a checkpoint
snapshot written by the loop after EVERY step: task id, original
request, full message state, step count, sandbox dir, tool trace,
timestamps. Killing the process loses at most the in-flight model
call; a NEW process resumes from the checkpoint and step numbering
CONTINUES — completed steps are never repeated.

States: queued, running, waiting_network, waiting_route, paused,
failed, completed, cancelled, parked_at_wall.

Classification rule (honest failures still fail): transport/network
errors and all-routes-cooling are WAITING states with a next-retry
time — never "failed". Real failures (model errors, double-malformed
tool calls, privacy blocks) are failed. A task that exhausts its
step budget is paused, not failed: resume it with a bigger budget.

parked_at_wall (v0.9, WALLS.md build-next #3): a browser task whose
run OBSERVED a human-only wall (a CHALLENGE tool result in the
trace — evidence only, never the model's narration; W8) parks
instead of completing or failing. The record carries the wall
(domain, kind, detail) in `wall`; the task resumes with resume()
once the human step is taken. On the device lane the wall is
reachable in Lille's own session (the app hands the parked
WebView to Ethuram); on the host lane parking is state + report
only, exactly as v0.8 graded it.
"""
import json, os, re, time

from . import sandbox
from .loop import (TaskLoop, TaskResult, WaitingNetworkError,
                   WaitingRouteError, TaskPausedSignal)

STATES = ("queued", "running", "waiting_network", "waiting_route",
          "paused", "failed", "completed", "cancelled",
          "parked_at_wall")
TERMINAL = ("failed", "completed", "cancelled")
RESUMABLE = ("queued", "running", "waiting_network", "waiting_route",
             "paused", "parked_at_wall")

_CHALLENGE_RE = re.compile(
    r"^CHALLENGE kind=(\S+) detail=(.*?) — ", re.S)


def observed_wall(result):
    """The wall a finished run actually met, from its trace: the
    LAST step whose result was a CHALLENGE observation. Returns
    {"kind", "detail"} or None. Narration never counts (W8)."""
    wall = None
    for s in (getattr(result, "steps", None) or []):
        rs = getattr(s, "result_summary", "") or ""
        m = _CHALLENGE_RE.match(rs)
        if m:
            wall = {"kind": m.group(1), "detail": m.group(2)[:200]}
    return wall


def _now():
    return time.time()


class TaskStore:
    """One JSON file per task; atomic writes (tmp + rename) so a
    kill mid-write never corrupts the record."""

    def __init__(self, workspace_root):
        self.workspace_root = workspace_root
        self.dir = os.path.join(workspace_root, "task_store")
        os.makedirs(self.dir, exist_ok=True)

    def _path(self, task_id):
        return os.path.join(self.dir, task_id + ".json")

    def create(self, task_text, task_id=None):
        if task_id is None:
            task_id, _ = sandbox.make_task_dir(self.workspace_root)
        rec = {
            "id": task_id, "text": task_text, "state": "queued",
            "created_at": _now(), "updated_at": _now(),
            "next_retry_at": None, "summary": None, "error": None,
            "checkpoint": None, "wall": None,
        }
        self.save(rec)
        return rec

    def save(self, rec):
        rec["updated_at"] = _now()
        tmp = self._path(rec["id"]) + ".tmp"
        with open(tmp, "w") as f:
            json.dump(rec, f, indent=2)
        os.replace(tmp, self._path(rec["id"]))

    def load(self, task_id):
        p = self._path(task_id)
        if not os.path.isfile(p):
            return None
        with open(p) as f:
            return json.load(f)

    def list(self):
        out = []
        for fn in sorted(os.listdir(self.dir)):
            if fn.endswith(".json"):
                try:
                    with open(os.path.join(self.dir, fn)) as f:
                        out.append(json.load(f))
                except Exception:
                    continue
        return out

    def set_state(self, task_id, state, **kw):
        rec = self.load(task_id)
        if rec is None:
            return None
        rec["state"] = state
        rec.update(kw)
        self.save(rec)
        return rec


class TaskEngine:
    def __init__(self, workspace_root=None, config=None, model_call=None,
                 max_steps=12, approver=None, pace_seconds=None, clock=None):
        self.workspace_root = workspace_root or os.environ.get(
            "FREEAGENT_WORKSPACE", os.path.expanduser("~/.freeagent/tasks"))
        os.makedirs(self.workspace_root, exist_ok=True)
        self.store = TaskStore(self.workspace_root)
        self.config = config
        self.model_call = model_call
        self.max_steps = max_steps
        self.approver = approver
        self.pace_seconds = pace_seconds
        self.clock = clock or _now

    # ---------- internals ----------
    def _loop_for(self, rec):
        def on_checkpoint(snap):
            # Preserve a pause/cancel that landed in the store from
            # another process — a checkpoint must never resurrect a
            # task the user just stopped.
            fresh = self.store.load(rec["id"])
            if fresh and fresh["state"] in ("cancelled", "paused"):
                rec["state"] = fresh["state"]
            elif rec["state"] == "queued":
                rec["state"] = "running"
            rec["checkpoint"] = snap
            self.store.save(rec)

        def control_check():
            fresh = self.store.load(rec["id"])
            if fresh and fresh["state"] == "cancelled":
                return "cancel"
            if fresh and fresh["state"] == "paused":
                return "pause"
            return "run"

        return TaskLoop(config=self.config, model_call=self.model_call,
                        workspace_root=self.workspace_root,
                        max_steps=self.max_steps, approver=self.approver,
                        pace_seconds=self.pace_seconds,
                        on_checkpoint=on_checkpoint,
                        control_check=control_check)

    def _drive(self, rec, loop, starter):
        """Run starter(loop) and fold the outcome into the record."""
        rec["state"] = "running"
        rec["next_retry_at"] = None
        self.store.save(rec)
        try:
            result = starter(loop)
        except WaitingNetworkError as e:
            rec["state"] = "waiting_network"
            rec["next_retry_at"] = e.retry_at or (self.clock() + 60)
            rec["error"] = str(e)
            self.store.save(rec)
            return rec
        except WaitingRouteError as e:
            rec["state"] = "waiting_route"
            rec["next_retry_at"] = e.retry_at or (self.clock() + 60)
            rec["error"] = str(e)
            self.store.save(rec)
            return rec
        except TaskPausedSignal as sig:
            rec["state"] = "cancelled" if str(sig) == "cancel" else "paused"
            self.store.save(rec)
            return rec
        wall = observed_wall(result)
        if wall is not None:
            # A human-only wall was OBSERVED in the trace: park,
            # whatever the model's finish claimed. The task is
            # neither done nor failed — it waits for the one
            # human step, then resume() continues it.
            rec["state"] = "parked_at_wall"
            rec["wall"] = wall
            rec["summary"] = result.summary
            rec["error"] = None
        elif result.status == "done":
            rec["state"] = "completed"
            rec["summary"] = result.summary
            rec["error"] = None
        elif result.status == "error":
            rec["state"] = "failed"
            rec["error"] = result.summary
        else:  # stopped: budget/plain-text stop — resumable, not failed
            rec["state"] = "paused"
            rec["summary"] = result.summary
        self.store.save(rec)
        return rec

    # ---------- public API ----------
    def start(self, task_text, task_id=None):
        rec = self.store.create(task_text, task_id=task_id)
        loop = self._loop_for(rec)
        return self._drive(rec, loop,
                           lambda lp: lp.run(task_text, task_id=rec["id"]))

    def resume(self, task_id):
        rec = self.store.load(task_id)
        if rec is None:
            raise KeyError(f"no such task: {task_id}")
        if rec["state"] in TERMINAL:
            return rec
        snap = rec.get("checkpoint")
        loop = self._loop_for(rec)
        if not snap:
            # Never got a first checkpoint (killed instantly): start
            # the loop fresh under the same task id/dir.
            return self._drive(rec, loop,
                               lambda lp: lp.run(rec["text"], task_id=rec["id"]))
        return self._drive(rec, loop, lambda lp: lp.resume(snap))

    def cancel(self, task_id):
        return self.store.set_state(task_id, "cancelled")

    def pause(self, task_id):
        return self.store.set_state(task_id, "paused")

    def list(self):
        return self.store.list()

    def get(self, task_id):
        return self.store.load(task_id)
