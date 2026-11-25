"""Proactive runner (v0.7) — one pass that works the queue
without being asked, and reports what it DID.

    python3 -m free_agent --proactive-run

A pass does four things, in order:

1. DUE CHECKS — every open commitment whose next_check_at has
   arrived gets its landing condition checked mechanically
   (commitments.run_check). Landed -> state landed + an outbox
   report. Past its deadline unlanded -> expired + a BLOCKED
   report (a missed result is reported, never silently dropped).
2. IDLE PICKUP — durable tasks sitting in `queued` (enqueued and
   never started) are run through the v0.4 engine. A queued_task
   commitment wrapping one lands with the task's own summary as
   its result.
3. WAITING RESUME — tasks in waiting_network / waiting_route whose
   retry time has passed are resumed from checkpoint.
4. OUTBOX — every outcome above writes ONE markdown report to
   <workspace>/reports/, named <timestamp>-<id>-<slug>.md, in the
   standard shape (STATUS: DONE / LANDED / BLOCKED + the Cost
   line). A pass with nothing due writes NOTHING — proactivity is
   not spam.

Idempotency is structural: a report is written only in the same
moment a persisted state transition happens, and transitions are
one-way. Re-running a pass can never duplicate a report or
re-execute a landed check. Tasks driven by a pass carry a
`proactive_reported` flag on their record for the same reason.

Lane mode (--lane, used by lille-proactive.yml): PRIVATE
commitments are refused — skipped, counted, and named in the pass
summary. Private work never runs on shared CI.
"""
import json, os, re, time

from .commitments import COST_LINE, CommitmentStore, run_check
from .taskstore import TaskEngine, TaskStore

REPORT_STATUSES = ("DONE", "LANDED", "BLOCKED")


def _iso(ts):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ts))


def _slug(text, limit=40):
    s = re.sub(r"[^a-z0-9]+", "-", (text or "").lower()).strip("-")
    return (s or "event")[:limit]


class Outbox:
    """The reports/ dir: Lille's unprompted mail to the user."""

    def __init__(self, workspace_root):
        self.dir = os.path.join(workspace_root, "reports")
        os.makedirs(self.dir, exist_ok=True)

    def write(self, status, title, body_lines, ref_id, now=None):
        if status not in REPORT_STATUSES:
            raise ValueError(f"report status must be one of {REPORT_STATUSES}")
        now = now or time.time()
        stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime(now))
        fn = f"{stamp}-{ref_id[-6:]}-{_slug(title)}.md"
        path = os.path.join(self.dir, fn)
        lines = ["# Lille — proactive report",
                 f"STATUS: {status}",
                 COST_LINE,
                 f"When: {_iso(now)}",
                 f"Subject: {title}",
                 ""]
        lines += body_lines
        with open(path, "w") as f:
            f.write("\n".join(lines) + "\n")
        return fn


def _result_lines(payload):
    if not payload:
        return ["Result: (no payload)"]
    return ["Result:"] + [f"- {k}: {v}" for k, v in sorted(payload.items())
                          if v is not None]


class ProactiveRunner:
    def __init__(self, workspace_root=None, config=None, model_call=None,
                 max_steps=12, pace_seconds=None, lane=False, clock=None,
                 http_get=None):
        self.workspace_root = workspace_root or os.environ.get(
            "FREEAGENT_WORKSPACE", os.path.expanduser("~/.freeagent/tasks"))
        os.makedirs(self.workspace_root, exist_ok=True)
        self.commitments = CommitmentStore(self.workspace_root)
        self.task_store = TaskStore(self.workspace_root)
        self.outbox = Outbox(self.workspace_root)
        self.config = config
        self.model_call = model_call
        self.max_steps = max_steps
        self.pace_seconds = pace_seconds
        self.lane = lane
        self.clock = clock or time.time
        self.http_get = http_get
        self._engine = None

    def _get_engine(self):
        if self._engine is None:
            self._engine = TaskEngine(
                workspace_root=self.workspace_root, config=self.config,
                model_call=self.model_call, max_steps=self.max_steps,
                pace_seconds=self.pace_seconds)
        return self._engine

    # ---------- step 1: due checks ----------
    def _run_due_checks(self, now, summary):
        for rec in self.commitments.list():
            if rec["state"] != "open":
                continue
            if rec.get("private") and self.lane:
                summary["refused_private"].append(rec["id"])
                continue
            if rec["kind"] == "queued_task":
                continue  # driven by idle pickup, not by check polling
            due = rec["next_check_at"] <= now
            deadline_hit = rec.get("deadline") is not None \
                and rec["deadline"] <= now
            if not due and not deadline_hit:
                continue
            if rec.get("check"):
                landed, payload = run_check(
                    rec["check"], self.workspace_root,
                    task_lookup=self.task_store.load,
                    http_get=self.http_get)
                rec["check_count"] += 1
                rec["last_checked_at"] = now
                summary["checked"] += 1
            elif rec["kind"] == "deadline" and deadline_hit:
                landed, payload = True, {"deadline": _iso(rec["deadline"]),
                                         "note": "the date arrived"}
            else:
                landed, payload = False, {}
            if landed:
                rec["state"] = "landed"
                rec["result"] = payload
                fn = self.outbox.write(
                    "LANDED", rec["title"],
                    [f"Commitment: {rec['id']} ({rec['kind']})",
                     f"Registered: {_iso(rec['created_at'])}",
                     f"Landed: {_iso(now)}",
                     f"Checks executed: {rec['check_count']}"]
                    + _result_lines(payload),
                    rec["id"], now=now)
                rec["reported"] = True
                rec["reported_at"] = now
                rec["report_file"] = fn
                summary["landed"].append(rec["id"])
                summary["reports"].append(fn)
            elif deadline_hit:
                rec["state"] = "expired"
                rec["result"] = payload or {"note": "deadline passed"}
                fn = self.outbox.write(
                    "BLOCKED", rec["title"],
                    [f"Commitment: {rec['id']} ({rec['kind']})",
                     f"Registered: {_iso(rec['created_at'])}",
                     f"Deadline: {_iso(rec['deadline'])} — passed with "
                     "the result still not landed.",
                     "Blocker: the watched result never landed before "
                     "its deadline; Lille will not chase it silently — "
                     "this report IS the surfacing."]
                    + _result_lines(payload),
                    rec["id"], now=now)
                rec["reported"] = True
                rec["reported_at"] = now
                rec["report_file"] = fn
                summary["expired"].append(rec["id"])
                summary["reports"].append(fn)
            else:
                rec["next_check_at"] = now + rec["every_seconds"]
            self.commitments.save(rec)

    # ---------- steps 2+3: drive tasks ----------
    def _settle_task(self, task_rec, now, summary, verb):
        """After a pass drove (or found) a task in a terminal state:
        write its report once and settle any linked commitment."""
        state = task_rec["state"]
        if state not in ("completed", "failed"):
            return
        linked = None
        for c in self.commitments.list():
            if c.get("task_id") == task_rec["id"] and c["state"] == "open":
                linked = c
                break
        if task_rec.get("proactive_reported") and linked is None:
            return
        status = "DONE" if state == "completed" else "BLOCKED"
        title = (linked["title"] if linked else task_rec["text"])
        body = [f"Task: {task_rec['id']} — {verb}",
                f"Final state: {state}"]
        if task_rec.get("summary"):
            body.append(f"Summary: {task_rec['summary']}")
        if task_rec.get("error"):
            body.append(f"Blocker: {task_rec['error']}")
        ck = task_rec.get("checkpoint") or {}
        if ck.get("task_dir"):
            body.append(f"Proof: task dir {ck['task_dir']} "
                        "(checkpoint + outputs on disk)")
        fn = self.outbox.write(status, title, body, task_rec["id"], now=now)
        summary["reports"].append(fn)
        if linked is not None:
            linked["state"] = "landed" if state == "completed" else "closed"
            linked["result"] = {"task_id": task_rec["id"],
                                "task_state": state,
                                "summary": task_rec.get("summary"),
                                "error": task_rec.get("error")}
            linked["reported"] = True
            linked["reported_at"] = now
            linked["report_file"] = fn
            self.commitments.save(linked)
            if state == "completed":
                summary["landed"].append(linked["id"])
        task_rec["proactive_reported"] = True
        self.task_store.save(task_rec)

    def _drive_tasks(self, now, summary):
        for rec in self.task_store.list():
            state = rec["state"]
            if state == "queued":
                done = self._get_engine().resume(rec["id"])
                summary["picked_up"].append(rec["id"])
                self._settle_task(done, now, summary,
                                  "picked up from the queue by a "
                                  "proactive pass (nobody started it)")
            elif state in ("waiting_network", "waiting_route"):
                retry = rec.get("next_retry_at") or 0
                if retry <= now:
                    done = self._get_engine().resume(rec["id"])
                    summary["resumed"].append(rec["id"])
                    self._settle_task(done, now, summary,
                                      "resumed from its waiting state "
                                      "by a proactive pass")
            elif state in ("completed", "failed"):
                # Finished elsewhere (e.g. by the runner lane) with a
                # linked commitment still open: settle it so the
                # result is reported, not left silent.
                linked_open = any(
                    c.get("task_id") == rec["id"] and c["state"] == "open"
                    for c in self.commitments.list())
                if linked_open:
                    self._settle_task(rec, now, summary,
                                      "completed outside a proactive pass; "
                                      "settled and reported by one")

    # ---------- the pass ----------
    def run_pass(self):
        now = self.clock()
        summary = {"checked": 0, "landed": [], "expired": [],
                   "picked_up": [], "resumed": [], "reports": [],
                   "refused_private": []}
        self._run_due_checks(now, summary)
        self._drive_tasks(now, summary)
        summary["when"] = _iso(now)
        return summary
