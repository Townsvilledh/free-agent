"""Spec mode (v0.5) — Lille works a written spec pack the way a
build engineer would: spec in -> worked -> verified -> reported.

    python3 -m free_agent --spec spec.md [--repo <url-or-local-path>]

Spec format (headings case-insensitive, '#' optional):

    Goal
    <what to build>

    Hard rules
    - <rule>

    Acceptance criteria
    - [ ] <criterion the verifier can check>
    - [ ] <...>

    Deliverables
    - <file or outcome>

    Push (optional)
    Authorised branches: `some/branch`   (main/master must be named
    here explicitly to be pushable at all)

The spec becomes a durable v0.4 task. After the loop, spec mode
builds the final report DETERMINISTICALLY from evidence (the step
trace + the repo's own git state) — the model cannot grade its own
homework. A criterion is "met" only with checkable evidence:

- mentions tests  -> a recorded run_tests step with exit_code=0
- mentions commit -> a git_commit step succeeded, or the repo HEAD
                     differs from the clone's initial HEAD
- mentions push / a branch name -> a recorded git_push success
- mentions a filename (x.py, x.md, ...) -> that file exists in repo/
- anything else   -> unmet, "no checkable evidence" (honest default)

Report shape (enforced by validate_report):

    STATUS: SHIPPED | BLOCKED | NEEDS ETHURAM
    Cost: $0 — keyless routes only (routes served: <providers>)
    Acceptance criteria:
    - [x] <criterion> — evidence: <...>
    - [ ] <criterion> — evidence: <...>
    Commits: <hashes or none>
    Blocker: <exact blocker + what was tried>          (BLOCKED only)
    Needs Ethuram: <the single step only he can take>  (NEEDS ETHURAM only)
"""
import os, re, time

from . import gittools, sandbox
from .loop import TaskLoop, WaitingNetworkError, WaitingRouteError

_HEADINGS = {
    "goal": "goal",
    "hard rules": "hard_rules",
    "rules": "hard_rules",
    "acceptance criteria": "criteria",
    "acceptance": "criteria",
    "deliverables": "deliverables",
    "push": "push",
    "authorised push branches": "push",
    "authorized push branches": "push",
    "authorised branches": "push",
    "authorized branches": "push",
}

_CHECK_ITEM = re.compile(r"^\s*(?:[-*]\s*)?(?:\[[ xX]\]\s*)?(.+?)\s*$")
_BRANCH_TOKEN = re.compile(r"`([^`]+)`|(?:^|\s)([\w][\w./-]*\/[\w./-]+)(?:\s|$)")
_FILE_TOKEN = re.compile(r"\b[\w./-]+\.(?:py|md|txt|js|ts|json|yml|yaml|html|css)\b")
_HUMAN_SIGNALS = ("authentication failed", "permission denied",
                  "could not read from remote repository",
                  "invalid username or password", "host key verification failed")


def _heading_of(line):
    s = line.strip().lstrip("#").strip().rstrip(":").lower()
    return _HEADINGS.get(s)


def parse_spec(text):
    """Parse a spec pack. Returns a dict with goal, hard_rules,
    criteria, deliverables, push_branches, main_authorised."""
    sections = {"goal": [], "hard_rules": [], "criteria": [],
                "deliverables": [], "push": []}
    current = None
    for raw in str(text).splitlines():
        h = _heading_of(raw)
        if h is not None:
            current = h
            continue
        if current is None:
            continue
        line = raw.rstrip()
        if current == "goal":
            if line.strip():
                sections["goal"].append(line.strip())
            continue
        m = _CHECK_ITEM.match(line)
        if m and line.strip():
            sections[current].append(m.group(1).strip())
    push_branches = []
    push_text = "\n".join(sections["push"])
    for tick, bare in _BRANCH_TOKEN.findall(push_text):
        tok = tick or bare
        if tok and tok not in push_branches and " " not in tok:
            push_branches.append(tok)
    main_authorised = any(b in ("main", "master") for b in push_branches)
    return {
        "goal": " ".join(sections["goal"]).strip(),
        "hard_rules": sections["hard_rules"],
        "criteria": sections["criteria"],
        "deliverables": sections["deliverables"],
        "push_branches": push_branches,
        "main_authorised": main_authorised,
    }


def build_task_text(spec, repo_note):
    lines = ["SPEC TASK — work this spec end to end in the repo.",
             f"\nGOAL: {spec['goal'] or '(see spec)'}", f"\n{repo_note}"]
    if spec["hard_rules"]:
        lines.append("\nHARD RULES:")
        lines += [f"- {r}" for r in spec["hard_rules"]]
    lines.append("\nACCEPTANCE CRITERIA (each will be verified from evidence):")
    lines += [f"- {c}" for c in spec["criteria"]]
    if spec["deliverables"]:
        lines.append("\nDELIVERABLES:")
        lines += [f"- {d}" for d in spec["deliverables"]]
    if spec["push_branches"]:
        lines.append(f"\nPUSH: you may git_push ONLY to: {', '.join(spec['push_branches'])}")
    lines.append("\nWork: inspect the repo (repo_tree/read_file), make the "
                 "changes with write_file, run_tests until green, git_diff "
                 "to sanity-check, git_commit, git_push if authorised above, "
                 "then finish listing the repo files you produced as outputs "
                 "(paths like repo/<file>).")
    return "\n".join(lines)


# ---------- evidence ----------

def gather_evidence(result, task_dir, initial_head=None):
    steps = result.steps if result else []
    ev = {"tests_passed": False, "tests_summary": "",
          "commit_hashes": [], "pushed": [], "providers": []}
    for s in steps:
        rs = s.result_summary or ""
        if s.tool == "run_tests" and "exit_code=0" in rs:
            ev["tests_passed"] = True
            ev["tests_summary"] = rs[:160]
        if s.tool == "git_commit" and rs.startswith("committed "):
            ev["commit_hashes"].append(rs.split()[1])
        if s.tool == "git_push" and rs.startswith("pushed branch "):
            ev["pushed"].append(rs.split()[2])
    if result is not None and result.provider:
        ev["providers"].append(result.provider)
    repo = os.path.join(task_dir, "repo")
    if os.path.isdir(os.path.join(repo, ".git")):
        head = gittools.head_hash(repo)
        if head and initial_head and head != initial_head:
            if head[:12] not in ev["commit_hashes"]:
                ev["commit_hashes"].append(head[:12])
        elif head and not initial_head and ev["commit_hashes"]:
            pass
    ev["repo_dir"] = repo
    return ev


def evaluate_criteria(spec, ev, task_dir):
    """Return [(criterion, met_bool, evidence_str)]."""
    out = []
    repo = ev["repo_dir"]
    for c in spec["criteria"]:
        low = c.lower()
        if "test" in low:
            if ev["tests_passed"]:
                out.append((c, True, f"run_tests green: {ev['tests_summary']}"))
            else:
                out.append((c, False, "no recorded run_tests step with exit_code=0"))
        elif "push" in low or any(b in c for b in spec["push_branches"]):
            if ev["pushed"]:
                out.append((c, True, f"git_push recorded: {', '.join(ev['pushed'])}"))
            else:
                out.append((c, False, "no successful git_push step recorded"))
        elif "commit" in low:
            if ev["commit_hashes"]:
                out.append((c, True, f"commit(s) in repo: {', '.join(ev['commit_hashes'])}"))
            else:
                out.append((c, False, "no commit found in repo git log"))
        else:
            files = _FILE_TOKEN.findall(c)
            found = [f for f in files
                     if os.path.isfile(os.path.join(repo, f))]
            if files and len(found) == len(files):
                out.append((c, True, f"file(s) exist in repo: {', '.join(found)}"))
            elif files:
                missing = [f for f in files if f not in found]
                out.append((c, False, f"file(s) missing from repo: {', '.join(missing)}"))
            else:
                out.append((c, False, "no checkable evidence for this criterion"))
    return out


def build_report(spec, result, ev, evaluated):
    all_met = bool(evaluated) and all(m for _, m, _ in evaluated)
    summary = (result.summary if result else "") or ""
    low = summary.lower()
    # SHIPPED is decided by the deterministic criterion evidence —
    # that IS the verification. (Seen live: a model can meet every
    # criterion and then fumble the finish call's format; grading
    # the evidence, not the narration, is the whole point.)
    if all_met:
        status = "SHIPPED"
    elif any(sig in low for sig in _HUMAN_SIGNALS):
        status = "NEEDS ETHURAM"
    else:
        status = "BLOCKED"
    routes = ", ".join(ev["providers"]) or "none recorded"
    lines = [f"STATUS: {status}",
             f"Cost: $0 — keyless routes only (routes served: {routes})",
             "Acceptance criteria:"]
    for c, met, evidence in evaluated:
        lines.append(f"- [{'x' if met else ' '}] {c} — evidence: {evidence}")
    lines.append(f"Commits: {', '.join(ev['commit_hashes']) or 'none'}")
    if status == "BLOCKED":
        tried = "; ".join(f"step {s.n} {s.kind} {s.tool}" for s in (result.steps[-4:] if result else []))
        lines.append(f"Blocker: {summary[:300]} | last steps: {tried}")
    if status == "NEEDS ETHURAM":
        lines.append("Needs Ethuram: complete the git authentication/sign-in step "
                     "on this host (the push could not authenticate), then re-run "
                     f"the spec. Everything else is done. Detail: {summary[:200]}")
    return "\n".join(lines)


_REPORT_STATUS = re.compile(r"^STATUS: (SHIPPED|BLOCKED|NEEDS ETHURAM)$", re.M)


def validate_report(text, spec):
    """Enforce the report shape. Returns a list of problems ([] = ok)."""
    problems = []
    if not _REPORT_STATUS.search(text or ""):
        problems.append("missing/invalid STATUS line")
    if "Cost: $0 — keyless routes only" not in (text or ""):
        problems.append("missing Cost line (bar 1: every report states its cost)")
    for c in spec["criteria"]:
        if c not in (text or ""):
            problems.append(f"criterion not listed in report: {c[:60]}")
    if "STATUS: BLOCKED" in (text or "") and "Blocker:" not in text:
        problems.append("BLOCKED report must name the blocker")
    if "STATUS: NEEDS ETHURAM" in (text or "") and "Needs Ethuram:" not in text:
        problems.append("NEEDS ETHURAM report must name the single step")
    return problems


# ---------- runner ----------

def run_spec(spec_text, repo=None, workspace_root=None, config=None,
             model_call=None, pace_seconds=None, max_steps=25, approver=None):
    """Run a spec end to end. Returns (report_text, result, task_dir)."""
    spec = parse_spec(spec_text)
    ws = workspace_root or os.environ.get(
        "FREEAGENT_WORKSPACE", os.path.expanduser("~/.freeagent/tasks"))
    task_id, task_dir = sandbox.make_task_dir(ws)
    initial_head = None
    if repo and os.path.isdir(repo):
        ok, msg = gittools.preclone_local(task_dir, repo)
        if not ok:
            report = build_report(spec, None, gather_evidence(None, task_dir),
                                  [(c, False, msg) for c in spec["criteria"]])
            return report, None, task_dir
        initial_head = gittools.head_hash(os.path.join(task_dir, "repo"))
        repo_note = ("The repo is ALREADY cloned at repo/ (host-side pre-clone). "
                     "Do not git_clone.")
    elif repo:
        repo_note = f"Clone the repo first with git_clone: {repo} (it lands at repo/)."
    else:
        repo_note = ("There is no repo to clone: create repo/ yourself is NOT "
                     "possible — work with write_file under repo/ paths only if "
                     "files are the deliverable; otherwise report the blocker.")
        # For file-only specs, create the repo dir + git init host-side.
        repo_dir = os.path.join(task_dir, "repo")
        os.makedirs(repo_dir, exist_ok=True)
        import subprocess
        subprocess.run(["git", "init", "--quiet", repo_dir],
                       capture_output=True, timeout=30)
        repo_note = ("A fresh empty git repo is ready at repo/ — build the "
                     "deliverable there.")
    task_text = build_task_text(spec, repo_note)

    def finish_guard(steps, _args):
        """Deterministic: a spec whose criteria demand tests/commit/
        push cannot finish until the trace holds that evidence."""
        done_tools = {(s.tool, s.result_summary or "") for s in steps
                      if s.kind == "tool"}
        for c in spec["criteria"]:
            low = c.lower()
            if "test" in low and not any(
                    t == "run_tests" and "exit_code=0" in r for t, r in done_tools):
                return ("the spec requires passing tests, but no run_tests "
                        "step with exit_code=0 is recorded yet. Run the "
                        "tests (and fix failures) before finishing.")
            if "commit" in low and not any(
                    t == "git_commit" and r.startswith("committed ")
                    for t, r in done_tools):
                return ("the spec requires a commit, but no successful "
                        "git_commit step is recorded yet. Commit the work "
                        "before finishing.")
            if "push" in low and not any(
                    t == "git_push" and r.startswith("pushed branch ")
                    for t, r in done_tools):
                return ("the spec requires a push, but no successful "
                        "git_push step is recorded yet. Push to the "
                        "authorised branch before finishing.")
        return None

    loop = TaskLoop(config=config, model_call=model_call,
                    workspace_root=ws, max_steps=max_steps,
                    approver=approver, pace_seconds=pace_seconds,
                    finish_guard=finish_guard,
                    sentinel_context={
                        "push_branches": spec["push_branches"],
                        "main_authorised": spec["main_authorised"]})
    # Fallback Law patience: if every route is cooling down (or the
    # network dropped), WAIT for the earliest retry and restart the
    # loop — repo work already done persists in the task dir, and the
    # model re-inspects before acting. Bounded: ~30 min of waiting.
    result = None
    waited = 0.0
    while True:
        try:
            result = loop.run(task_text, task_id=task_id)
            break
        except (WaitingRouteError, WaitingNetworkError) as e:
            retry_at = getattr(e, "retry_at", None) or (time.time() + 60)
            delay = max(5.0, min(retry_at - time.time(), 300.0)) + 5.0
            if waited + delay > 1800:
                from .loop import TaskResult
                result = TaskResult("error",
                                    f"routes stayed unavailable after ~30 min of "
                                    f"waiting: {e}", task_dir, task_id, [])
                break
            time.sleep(delay)
            waited += delay
    ev = gather_evidence(result, task_dir, initial_head=initial_head)
    evaluated = evaluate_criteria(spec, ev, task_dir)
    report = build_report(spec, result, ev, evaluated)
    problems = validate_report(report, spec)
    if problems:  # our own builder must never emit a malformed report
        report += "\n[report self-check FAILED: " + "; ".join(problems) + "]"
    with open(os.path.join(task_dir, "SPEC_REPORT.md"), "w") as f:
        f.write(report + "\n")
    return report, result, task_dir
