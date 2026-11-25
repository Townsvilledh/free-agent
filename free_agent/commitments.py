"""Commitments register (v0.7) — the Proactivity Law as data.

The law (Ethuram, 2026-11-25 ~10:50 WAT): Lille works the queue
without waiting to be reminded and reports what she has DONE
unprompted. A judged competition result once sat unreported until
he chased it; this register is the machinery that makes that
impossible for anything Lille has entered, submitted, enqueued or
been asked to watch.

A commitment is a promise with a machine-checkable landing
condition, persisted as one JSON file per entry under
<workspace>/commitments/ (atomic writes, same discipline as the
task store):

    {id, kind, title, check, every_seconds, next_check_at,
     deadline, private, state, created_at, updated_at,
     last_checked_at, check_count, result, reported,
     reported_at, report_file, task_id}

kind:  watch       — a result pending outside Lille (a run, a page)
       deadline    — a date that must not pass silently
       queued_task — wraps a durable task enqueued for idle pickup

check spec (the "what landed looks like" string):
    url:<URL>                        landed when GET returns 200
    url-contains:<URL>::<text>       landed when 200 and body has text
    file:<relpath>                   landed when the file exists
                                     (relative to the workspace root)
    actions-run:<owner>/<repo>:<id>  landed when the Actions run's
                                     status is "completed"; the
                                     result payload carries the
                                     conclusion. Public repos are
                                     readable without any credential.
    task-state:<task_id>[:<state>]   landed when the durable task
                                     reaches <state> (default
                                     "completed")

States: open -> landed | expired | closed. A landed/expired
commitment is never re-checked; `reported` records that the outbox
report exists, which is what makes proactive passes idempotent.

Privacy: a commitment flagged private NEVER leaves the device. The
scheduled Actions lane (lille-proactive.yml) runs non-private
commitments only — export_for_lane() refuses private entries in
code, and a lane-mode proactive pass skips them even if one is
somehow present in lane state.
"""
import json, os, time, urllib.error, urllib.request, uuid

KINDS = ("watch", "deadline", "queued_task")
STATES = ("open", "landed", "expired", "closed")
COST_LINE = "Cost: $0 — keyless routes only"


def _now():
    return time.time()


def new_commitment_id():
    return time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]


def parse_check(spec):
    """Parse a check spec string -> (kind, params dict).
    Raises ValueError on anything unrecognised — a commitment whose
    landing cannot be checked mechanically is not a commitment."""
    spec = (spec or "").strip()
    if spec.startswith("url-contains:"):
        rest = spec[len("url-contains:"):]
        if "::" not in rest:
            raise ValueError("url-contains check needs <URL>::<text>")
        url, text = rest.split("::", 1)
        if not url.strip() or not text.strip():
            raise ValueError("url-contains check needs a URL and text")
        return "url-contains", {"url": url.strip(), "text": text.strip()}
    if spec.startswith("url:"):
        url = spec[len("url:"):].strip()
        if not url:
            raise ValueError("url check needs a URL")
        return "url", {"url": url}
    if spec.startswith("file:"):
        rel = spec[len("file:"):].strip()
        if not rel or os.path.isabs(rel) or ".." in rel.split("/"):
            raise ValueError("file check needs a workspace-relative path")
        return "file", {"path": rel}
    if spec.startswith("actions-run:"):
        rest = spec[len("actions-run:"):].strip()
        repo, _, run_id = rest.rpartition(":")
        if "/" not in repo or not run_id.isdigit():
            raise ValueError("actions-run check needs <owner>/<repo>:<run_id>")
        return "actions-run", {"repo": repo, "run_id": run_id}
    if spec.startswith("task-state:"):
        rest = spec[len("task-state:"):].strip()
        task_id, _, state = rest.partition(":")
        if not task_id:
            raise ValueError("task-state check needs a task id")
        return "task-state", {"task_id": task_id,
                              "state": state or "completed"}
    raise ValueError(f"unrecognised check spec: {spec!r} — expected "
                     "url:, url-contains:, file:, actions-run: or "
                     "task-state:")


def _http_get(url, timeout=20):
    req = urllib.request.Request(url, headers={"User-Agent": "lille/0.7"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read(512_000).decode("utf-8", "replace")


def run_check(check_spec, workspace_root, task_lookup=None, http_get=None):
    """Execute one check. Returns (landed: bool, payload: dict).
    A check that ERRORS (network down, 404, bad JSON) is not landed
    and not a failure of the commitment — the payload says why, and
    the commitment stays open for its next scheduled check."""
    kind, p = parse_check(check_spec)
    get = http_get or _http_get
    try:
        if kind == "url":
            status, _ = get(p["url"])
            return status == 200, {"url": p["url"], "http_status": status}
        if kind == "url-contains":
            status, body = get(p["url"])
            found = p["text"] in body
            return (status == 200 and found), {
                "url": p["url"], "http_status": status,
                "text_found": found, "text": p["text"]}
        if kind == "file":
            path = os.path.join(workspace_root, p["path"])
            exists = os.path.isfile(path)
            payload = {"path": p["path"], "exists": exists}
            if exists:
                payload["size"] = os.path.getsize(path)
            return exists, payload
        if kind == "actions-run":
            api = (f"https://api.github.com/repos/{p['repo']}"
                   f"/actions/runs/{p['run_id']}")
            status, body = get(api)
            if status != 200:
                return False, {"repo": p["repo"], "run_id": p["run_id"],
                               "http_status": status,
                               "note": "Actions API did not answer 200"}
            data = json.loads(body)
            payload = {"repo": p["repo"], "run_id": p["run_id"],
                       "status": data.get("status"),
                       "conclusion": data.get("conclusion"),
                       "html_url": data.get("html_url"),
                       "run_started_at": data.get("run_started_at"),
                       "updated_at": data.get("updated_at")}
            return data.get("status") == "completed", payload
        if kind == "task-state":
            if task_lookup is None:
                return False, {"note": "no task store attached to this check"}
            rec = task_lookup(p["task_id"])
            if rec is None:
                return False, {"task_id": p["task_id"],
                               "note": "task not found (yet)"}
            return rec.get("state") == p["state"], {
                "task_id": p["task_id"], "task_state": rec.get("state"),
                "wanted_state": p["state"],
                "summary": rec.get("summary"), "error": rec.get("error")}
    except (urllib.error.URLError, OSError, ValueError) as e:
        return False, {"check_error": f"{type(e).__name__}: {e}"}
    return False, {"note": "unhandled check kind"}


class CommitmentStore:
    """One JSON file per commitment under <workspace>/commitments/;
    atomic writes (tmp + rename), mirroring TaskStore."""

    def __init__(self, workspace_root):
        self.workspace_root = workspace_root
        self.dir = os.path.join(workspace_root, "commitments")
        os.makedirs(self.dir, exist_ok=True)

    def _path(self, cid):
        return os.path.join(self.dir, cid + ".json")

    def create(self, title, check=None, every_seconds=1800, deadline=None,
               private=False, kind="watch", task_id=None):
        if kind not in KINDS:
            raise ValueError(f"kind must be one of {KINDS}")
        if check is not None:
            parse_check(check)  # validate eagerly: refuse uncheckable
        now = _now()
        rec = {
            "id": new_commitment_id(), "kind": kind, "title": title,
            "check": check, "every_seconds": int(every_seconds),
            "next_check_at": now, "deadline": deadline,
            "private": bool(private), "state": "open",
            "created_at": now, "updated_at": now,
            "last_checked_at": None, "check_count": 0,
            "result": None, "reported": False, "reported_at": None,
            "report_file": None, "task_id": task_id,
        }
        self.save(rec)
        return rec

    def save(self, rec):
        rec["updated_at"] = _now()
        tmp = self._path(rec["id"]) + ".tmp"
        with open(tmp, "w") as f:
            json.dump(rec, f, indent=2)
        os.replace(tmp, self._path(rec["id"]))

    def load(self, cid):
        p = self._path(cid)
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

    def close(self, cid):
        rec = self.load(cid)
        if rec is None:
            return None
        rec["state"] = "closed"
        self.save(rec)
        return rec


def export_for_lane(store, dest_dir):
    """Copy lane-eligible state (commitments + outbox reports) into
    dest_dir for the scheduled Actions lane. PRIVATE commitments are
    REFUSED — never copied — and their ids are returned so the
    refusal is on the record, not silent. Returns
    (exported_ids, refused_private_ids)."""
    os.makedirs(os.path.join(dest_dir, "commitments"), exist_ok=True)
    os.makedirs(os.path.join(dest_dir, "reports"), exist_ok=True)
    exported, refused = [], []
    for rec in store.list():
        if rec.get("private"):
            refused.append(rec["id"])
            continue
        src = os.path.join(store.dir, rec["id"] + ".json")
        dst = os.path.join(dest_dir, "commitments", rec["id"] + ".json")
        with open(src, "rb") as f:
            data = f.read()
        with open(dst, "wb") as f:
            f.write(data)
        exported.append(rec["id"])
    reports_src = os.path.join(store.workspace_root, "reports")
    if os.path.isdir(reports_src):
        for fn in sorted(os.listdir(reports_src)):
            if fn.endswith(".md"):
                with open(os.path.join(reports_src, fn), "rb") as f:
                    data = f.read()
                with open(os.path.join(dest_dir, "reports", fn), "wb") as f:
                    f.write(data)
    return exported, refused
