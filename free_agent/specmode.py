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

    Watch (optional, v0.7 — the Proactivity Law)
    - <check spec> [every <minutes>]
      e.g. - actions-run:Townsvilledh/free-agent:123456 every 5
    Each Watch item is auto-registered as a commitment when the spec
    finishes: the result it names will be checked by proactive
    passes and reported when it lands — nobody has to ask.

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

from . import gittools, lanes, sandbox
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
    "watch": "watch",
    "browser domains": "browser",
    "browser": "browser",
    "allowed domains": "browser",
    "x lane": "x_lane",
    "lane": "lane",
    "browser lane": "lane",
}

_WATCH_EVERY = re.compile(r"\s+every\s+(\d+(?:\.\d+)?)\s*$")

_CHECK_ITEM = re.compile(r"^\s*(?:[-*]\s*)?(?:\[[ xX]\]\s*)?(.+?)\s*$")
_BRANCH_TOKEN = re.compile(r"`([^`]+)`|(?:^|\s)([\w][\w./-]*\/[\w./-]+)(?:\s|$)")
_FILE_TOKEN = re.compile(r"\b[\w./-]+\.(?:py|md|txt|js|ts|json|yml|yaml|html|css|mp4|mov|webm|png|jpe?g|webp|gif)\b")
_DURATION_RANGE = re.compile(
    r"(\d+(?:\.\d+)?)\s*(?:[-–—]|to)\s*(\d+(?:\.\d+)?)\s*(?:s\b|sec|seconds)")
# Hard boundary (VIDEO.md): where rules require the ENTRANT PERSONALLY
# on camera, a Lille-made video does not satisfy the rule and must
# never be passed off as Ethuram. Specs demanding that are refused
# deterministically, before any work runs.
_PERSONAL_APPEARANCE = ("personally", "on camera", "in person",
                        "record yourself", "your face", "face to camera",
                        "appear on screen", "must appear",
                        "speaking to the camera", "presents himself",
                        "presents herself", "explains himself",
                        "explains herself")


def personal_appearance_blocker(spec):
    """Return the matched phrase if the spec demands a human appearing
    personally (a wall Lille must report, never fake), else None."""
    hay = "\n".join([spec.get("goal", "")] + spec.get("hard_rules", [])
                    + spec.get("criteria", [])).lower()
    for phrase in _PERSONAL_APPEARANCE:
        if phrase in hay:
            return phrase
    return None


_MAIL_SIGNAL = re.compile(r"\bgmail\b|\binbox\b|\bemail\b|\be-mail\b")
_CALENDAR_SIGNAL = re.compile(r"\bcalendar\b")


def _spec_hay(spec):
    return " ".join([spec.get("goal", "")] + spec.get("criteria", [])
                    + spec.get("deliverables", [])).lower()


def wants_mail(spec):
    return bool(_MAIL_SIGNAL.search(_spec_hay(spec)))


def wants_calendar(spec):
    return bool(_CALENDAR_SIGNAL.search(_spec_hay(spec)))


def is_video_spec(spec):
    hay = " ".join([spec.get("goal", "")] + spec.get("criteria", [])
                   + spec.get("deliverables", [])).lower()
    return "video" in hay or ".mp4" in hay


def spec_tool_names(spec):
    """The tool schemas a spec run sends to the model (v0.8
    payload discipline: the keyless endpoint 413s oversized
    bodies, and the full 32-tool set is 13.2 KB of schemas
    alone). Sections declare the need: Browser domains -> the
    browser set, X lane -> the X set, video signals -> the video
    set, push/commit/test signals -> the git set. Core file
    tools always ride along."""
    from . import tasktools
    browserish = bool(spec.get("browser_domains")
                      or spec.get("x_post_mode"))
    names = list(tasktools.CORE_MIN_TOOLS if browserish
                 else tasktools.CORE_TOOLS)
    hay = " ".join([spec.get("goal", "")] + spec.get("criteria", [])
                   + spec.get("deliverables", [])).lower()
    if spec.get("browser_domains"):
        names += tasktools.BROWSER_TOOLS
    if spec.get("x_post_mode"):
        names += tasktools.BROWSER_TOOLS + tasktools.X_TOOLS
    if is_video_spec(spec):
        names += tasktools.VIDEO_TOOLS
    if "sweep" in hay:
        names += tasktools.SWEEP_TOOLS
    if wants_mail(spec):
        names += tasktools.MAIL_TOOLS
    if wants_calendar(spec):
        names += tasktools.CALENDAR_TOOLS
    # Word-precise git signals only: "commitments" and
    # "acceptance test" in the X spec false-triggered this once.
    if (spec.get("push_branches") or re.search(
            r"\bgit\b|\bclone\b|run_tests|test suite|unit tests|"
            r"push to\b|commit the\b", hay)):
        names += tasktools.GIT_TOOLS
    # De-dup, preserve order.
    seen, out = set(), []
    for n in names:
        if n not in seen:
            seen.add(n)
            out.append(n)
    return out
_HUMAN_SIGNALS = ("authentication failed", "permission denied",
                  "could not read from remote repository",
                  "invalid username or password", "host key verification failed")


def _heading_of(line):
    s = line.strip().lstrip("#").strip().rstrip(":").lower()
    return _HEADINGS.get(s)


def parse_spec(text):
    """Parse a spec pack. Returns a dict with goal, hard_rules,
    criteria, deliverables, push_branches, main_authorised, watch."""
    sections = {"goal": [], "hard_rules": [], "criteria": [],
                "deliverables": [], "push": [], "watch": [],
                "browser": [], "x_lane": [], "lane": []}
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
    watch = []
    for item in sections["watch"]:
        every_seconds = 1800
        m = _WATCH_EVERY.search(item)
        if m:
            every_seconds = int(float(m.group(1)) * 60)
            item = item[:m.start()].strip()
        if item:
            watch.append({"check": item, "every_seconds": every_seconds})
    browser_domains = []
    for item in sections["browser"]:
        for tok in re.findall(r"`([^`]+)`|([\w-]+(?:\.[\w-]+)+)", item):
            d = (tok[0] or tok[1]).lower().strip()
            if d and "." in d and " " not in d and d not in browser_domains:
                browser_domains.append(d)
    x_post_mode, x_post_approved = None, False
    for item in sections["x_lane"]:
        low = item.lower()
        if "account:" in low and "own" in low:
            x_post_mode = "own"
        elif "account:" in low and "user" in low:
            x_post_mode = "user"
        if "post approved" in low and ("yes" in low or "approved" in low):
            x_post_approved = True
    # Lane (v0.9, free_agent/lanes.py): a `Lane` section naming
    # host | device | auto. Absent = auto. The DECISION (chosen
    # lane + reason) is made in run_spec and stored back on the
    # spec as lane_choice/lane_reason — recorded evidence.
    lane = lanes.DEFAULT_LANE
    for item in sections["lane"]:
        parsed = lanes.parse_lane(item)
        if parsed:
            lane = parsed
            break
    return {
        "goal": " ".join(sections["goal"]).strip(),
        "hard_rules": sections["hard_rules"],
        "criteria": sections["criteria"],
        "deliverables": sections["deliverables"],
        "browser_domains": browser_domains,
        "x_post_mode": x_post_mode,
        "x_post_approved": x_post_approved,
        "push_branches": push_branches,
        "main_authorised": main_authorised,
        "watch": watch,
        "lane": lane,
        "lane_choice": None,
        "lane_reason": None,
    }


def build_task_brief(spec):
    """The compact form of a spec for model calls after the
    first (v0.8 payload discipline): goal + hard rules +
    criteria, without the workflow boilerplate the system
    prompt already carries. The full text is used once; the
    brief keeps the wire floor under the keyless provider's
    body limit on long runs."""
    lines = [f"SPEC TASK (continued) — GOAL: {spec['goal'] or '(see spec)'}"]
    if spec["hard_rules"]:
        lines.append("HARD RULES:")
        lines += [f"- {r}" for r in spec["hard_rules"]]
    if spec["criteria"]:
        lines.append("CRITERIA (verified from evidence):")
        lines += [f"- {c[:110]}" for c in spec["criteria"]]
    lines.append("(Full spec text: spec.json in this task's "
                 "directory — read_file it if a detail is missing.)")
    return "\n".join(lines)


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
    if is_video_spec(spec):
        lines.append(
            "\nVIDEO WORKFLOW (this spec's deliverable is a video):\n"
            "1. Plan scenes from the goal: a title card (render_card), "
            "captured app frames (capture_walkthrough), an end card.\n"
            "2. capture_walkthrough: build the step list from the shot "
            "list in the goal/rules; end screenshot steps with hold "
            "seconds and a short caption each; pass expect_text = the "
            "phrases the brief says must be visible. For step lists "
            "longer than a few steps, FIRST write the steps JSON array "
            "to a file with write_file (e.g. capture-steps.json), then "
            "call capture_walkthrough with steps_file instead of "
            "steps — long nested JSON inside one tool call fails.\n"
            "3. narrate: one short line per scene (skip a scene's line "
            "only if the brief allows captions-only). If narrate returns "
            "NARRATION_FAILED, proceed captions-only and say so.\n"
            "4. assemble_video: scenes in order — card/capture frames "
            "with their captions and matching audio files — out = "
            "repo/<deliverable>.mp4. Scene durations must sum inside "
            "the brief's duration range (narration extends a scene to "
            "fit its line).\n"
            "5. verify_video on the result. The finish guard requires "
            "VIDEO_OK evidence; criteria are graded from the ffprobe "
            "numbers, the capture manifest and the narration route — "
            "not from your summary.\n"
            "6. finish listing repo/<deliverable>.mp4 as the output.")
    if spec.get("browser_domains"):
        lines.append(
            "\nBROWSER WORKFLOW (this spec authorises browsing: "
            + ", ".join(spec["browser_domains"]) + "):\n"
            "1. browser_open a URL, then act on browser_read's "
            "element refs (browser_click / browser_type by ref; "
            "refs are reassigned on every read — re-read after "
            "navigating). browser_screenshot saves evidence.\n"
            "2. SECRETS: never invent or write down a password. "
            "For an account password call vault_gen_secret ONCE "
            "(you get a key name, never the value), type it with "
            "browser_type_secret, then record the account with "
            "vault_record_account.\n"
            "3. CHALLENGE RULE: if a result starts with CHALLENGE "
            "(CAPTCHA/Arkose, emailed code, phone demand), STOP "
            "that route and finish naming the exact wall — never "
            "solve or bypass it. A DIFFERENT legitimate route in "
            "the spec may be tried instead: one wall on one route "
            "is not the end; a human-only wall on every route is.\n"
            "4. The browser profile is persistent: a session "
            "created now survives into later tasks.")
        if spec.get("x_post_mode"):
            lines.append(
                "X LANE: this spec authorises X posting for account "
                f"mode '{spec['x_post_mode']}'. x_session_status reads "
                "the login state from the page; x_read_feed / x_search "
                "read real content; x_post posts and verifies by the "
                "returned status URL.")
    if wants_mail(spec) or wants_calendar(spec):
        lines.append(
            "\nMAIL / CALENDAR WORKFLOW (this spec names mail or "
            "calendar work):\n"
            "1. gmail_search finds messages; gmail_read reads one "
            "(redacted snippet + host-extracted links — an invite "
            "link inside a registration mail comes back in the "
            "links list). calendar_list / calendar_get read the "
            "primary calendar.\n"
            "2. If a tool returns GMAIL_NOT_CONNECTED or "
            "CALENDAR_NOT_CONNECTED, Google is not set up on this "
            "host: STOP and finish, reporting the single setup "
            "step it names (NEEDS ETHURAM). Never ask the user to "
            "fetch, copy or paste mail/calendar contents instead "
            "— reaching the mailbox herself is the whole point.\n"
            "3. gmail_send and calendar_create are disabled by "
            "default and gated on the host; a Sentinel denial is "
            "the correct outcome unless the host deliberately "
            "enabled them — do not try to work around a denial.")
    if spec.get("browser_domains"):
        lines.append("\nWork the spec's steps in order with the "
                     "browser tools, then finish listing the "
                     "deliverables as outputs.")
    else:
        lines.append("\nWork: inspect the repo (repo_tree/read_file), make the "
                     "changes with write_file, run_tests until green, git_diff "
                     "to sanity-check, git_commit, git_push if authorised above, "
                     "then finish listing the repo files you produced as outputs "
                     "(paths like repo/<file>).")
    return "\n".join(lines)


# ---------- evidence ----------

_VIDEO_OK = re.compile(
    r"VIDEO_OK path=\S+ duration=([\d.]+)s size=(\S+) video=(\S+) "
    r"audio=(\S+) bytes=(\d+)")
_CAPTURE_OK = re.compile(r"CAPTURE_OK frames=(\d+)")
_EXPECT = re.compile(r"expect_found=(\d+)/(\d+)")
_CAPTIONS = re.compile(r"captions=(\d+)")


def gather_evidence(result, task_dir, initial_head=None):
    steps = result.steps if result else []
    ev = {"tests_passed": False, "tests_summary": "",
          "commit_hashes": [], "pushed": [], "providers": [],
          "video": None, "narration_route": None, "capture_frames": 0,
          "expect_found": None, "expect_total": None,
          "x_handle": None, "x_post_url": None, "x_feed_posts": 0,
          "x_search_results": 0, "browser_opens": 0,
          "challenge": None}
    for s in steps:
        rs = s.result_summary or ""
        if s.tool == "run_tests" and "exit_code=0" in rs:
            ev["tests_passed"] = True
            ev["tests_summary"] = rs[:160]
        if s.tool == "git_commit" and rs.startswith("committed "):
            ev["commit_hashes"].append(rs.split()[1])
        if s.tool == "git_push" and rs.startswith("pushed branch "):
            ev["pushed"].append(rs.split()[2])
        m = _VIDEO_OK.search(rs)
        if m and s.tool in ("verify_video", "assemble_video"):
            cap = _CAPTIONS.search(rs)
            nar = re.search(r"narrated_scenes=(\d+)", rs)
            prev = ev.get("video") or {}
            ev["video"] = {"duration": float(m.group(1)),
                           "size": m.group(2), "vcodec": m.group(3),
                           "audio": m.group(4), "bytes": int(m.group(5)),
                           "captions": int(cap.group(1)) if cap
                                       else prev.get("captions", 0),
                           "narrated_scenes": int(nar.group(1)) if nar
                                              else prev.get("narrated_scenes", 0)}
        if s.tool == "narrate" and rs.startswith("NARRATION_OK route="):
            ev["narration_route"] = rs.split("route=")[1].split()[0]
        if s.tool == "browser_open" and rs.startswith("BROWSER_OK"):
            ev["browser_opens"] += 1
        if rs.startswith("CHALLENGE"):
            ev["challenge"] = rs[:200]
        if s.tool == "x_session_status" and "logged_in as @" in rs:
            m = re.search(r"logged_in as @([A-Za-z0-9_]+)", rs)
            if m:
                ev["x_handle"] = m.group(1)
        if s.tool == "x_post":
            m = re.search(r"X_POST_OK url=(https://x\.com/\S+?/status/\d+)", rs)
            if m:
                ev["x_post_url"] = m.group(1)
        if s.tool == "x_read_feed":
            m = re.search(r"X_FEED_OK posts=(\d+)", rs)
            if m:
                ev["x_feed_posts"] = max(ev["x_feed_posts"], int(m.group(1)))
        if s.tool == "x_search":
            m = re.search(r"X_SEARCH_OK results=(\d+)", rs)
            if m:
                ev["x_search_results"] = max(ev["x_search_results"],
                                             int(m.group(1)))
        if s.tool == "capture_walkthrough":
            cm = _CAPTURE_OK.search(rs)
            if cm:
                ev["capture_frames"] = max(ev["capture_frames"],
                                           int(cm.group(1)))
                em = _EXPECT.search(rs)
                if em:
                    ev["expect_found"] = int(em.group(1))
                    ev["expect_total"] = int(em.group(2))
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
    if not ev["x_handle"]:
        sess = os.path.join(task_dir, "x_session.json")
        if os.path.isfile(sess):
            try:
                import json as _json
                with open(sess) as f:
                    ev["x_handle"] = _json.load(f).get("handle")
            except Exception:
                pass
    ev["repo_dir"] = repo
    return ev


def evaluate_criteria(spec, ev, task_dir):
    """Return [(criterion, met_bool, evidence_str)]."""
    out = []
    repo = ev["repo_dir"]
    for c in spec["criteria"]:
        low = c.lower()
        dur_m = _DURATION_RANGE.search(c)
        if dur_m:
            lo, hi = float(dur_m.group(1)), float(dur_m.group(2))
            v = ev.get("video")
            if v and lo <= v["duration"] <= hi:
                out.append((c, True, f"ffprobe duration {v['duration']:.1f}s "
                                     f"inside {lo:.0f}-{hi:.0f}s "
                                     f"({v['size']}, {v['vcodec']})"))
            elif v:
                out.append((c, False, f"ffprobe duration {v['duration']:.1f}s "
                                      f"outside {lo:.0f}-{hi:.0f}s"))
            else:
                out.append((c, False, "no VIDEO_OK ffprobe evidence recorded"))
        elif "narration" in low:
            v = ev.get("video") or {}
            narrated = v.get("narrated_scenes", 0)
            if ev.get("narration_route") and narrated > 0:
                out.append((c, True, f"narration synthesized via "
                                     f"{ev['narration_route']} and mixed "
                                     f"into {narrated} scene(s) of the MP4"))
            elif "caption" in low and (narrated > 0
                                       or v.get("captions", 0) > 0):
                detail = (f"narration in {narrated} scene(s)" if narrated
                          else f"captions burned into {v.get('captions', 0)} "
                                 "scene(s), no narration audio")
                out.append((c, True, detail))
            else:
                out.append((c, False, "no NARRATION_OK step mixed into "
                                      "the MP4 (and no captions to fall "
                                      "back on)"))
        elif "capture" in low or "walkthrough" in low or "shows" in low \
                or "show " in low:
            if ev.get("capture_frames", 0) >= 3 and (
                    ev.get("expect_total") is None
                    or ev.get("expect_found") == ev.get("expect_total")):
                ev_str = f"capture recorded {ev['capture_frames']} frames"
                if ev.get("expect_total"):
                    ev_str += (f"; brief phrases found in captured page "
                               f"text: {ev['expect_found']}/{ev['expect_total']}")
                out.append((c, True, ev_str))
            elif ev.get("capture_frames", 0) >= 3:
                out.append((c, False, f"capture recorded "
                                      f"{ev['capture_frames']} frames but only "
                                      f"{ev.get('expect_found')}/{ev.get('expect_total')} "
                                      "brief phrases appear in the captured text"))
            else:
                out.append((c, False, "no completed capture_walkthrough "
                                      "with >= 3 frames recorded"))
        elif "post" in low and ("live" in low or "url" in low) \
                and ("x" in low or "post" in low):
            if ev.get("x_post_url"):
                out.append((c, True, f"x_post verified on the timeline: "
                                     f"{ev['x_post_url']}"))
            else:
                out.append((c, False, "no X_POST_OK step with a verified "
                                      "status URL recorded"))
        elif "feed" in low and ("x" in low or "timeline" in low
                                or "home" in low):
            if ev.get("x_feed_posts", 0) >= 1:
                out.append((c, True, f"x_read_feed returned "
                                     f"{ev['x_feed_posts']} real posts "
                                     "from the logged-in home timeline"))
            else:
                out.append((c, False, "no X_FEED_OK step with >= 1 post "
                                      "recorded"))
        elif "x_search" in low or "search on x" in low \
                or "x search" in low:
            if ev.get("x_search_results", 0) >= 1:
                out.append((c, True, f"x_search returned "
                                     f"{ev['x_search_results']} real "
                                     "results via the session"))
            else:
                out.append((c, False, "no X_SEARCH_OK step with >= 1 "
                                      "result recorded"))
        elif ("account" in low or "profile" in low) and \
                ("x" in low or "handle" in low):
            handle = ev.get("x_handle")
            named = re.findall(r"@([A-Za-z0-9_]{2,15})", c)
            if handle and (not named or
                           handle.lower() in [n.lower() for n in named]):
                out.append((c, True, f"x_session_status verified a live "
                                     f"session as @{handle} (read from "
                                     "the page; x_session.json in the "
                                     "task record)"))
            elif handle:
                out.append((c, False, f"session is @{handle}, not the "
                                      f"@{named[0] if named else '?'} the "
                                      "criterion names"))
            else:
                out.append((c, False, "no logged-in X session verified "
                                      "(no x_session_status success, no "
                                      "x_session.json)"))
        elif "browser" in low:
            if ev.get("browser_opens", 0) >= 1:
                out.append((c, True, f"browser_open succeeded "
                                     f"{ev['browser_opens']} time(s) in "
                                     "the run"))
            else:
                out.append((c, False, "no successful browser_open step "
                                      "recorded"))
        elif "cost" in low and "$0" in c:
            out.append((c, True, "report carries the enforced Cost: $0 "
                                 "line; every route used is keyless"))
        elif "test" in low:
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
    wall = _wall_signal(summary, ev)
    # A run that DIED of a machinery failure (model unavailable —
    # e.g. the keyless endpoint's 413) with routes still
    # unexhausted grades BLOCKED, even if it observed a wall on
    # one route earlier: NEEDS ETHURAM means the run stopped AT
    # the wall with nothing left to try (v0.8 X attempt 2 met a
    # reCAPTCHA on email route 1, adapted to route 2, then died
    # of 413 — grading that NEEDS ETHURAM would have closed a
    # test that was still mid-routes).
    model_dead = "model unavailable" in low
    if all_met:
        status = "SHIPPED"
    elif (wall and not model_dead) or \
            (any(sig in low for sig in _HUMAN_SIGNALS)
             and not model_dead):
        status = "NEEDS ETHURAM"
    else:
        status = "BLOCKED"
    routes = ", ".join(ev["providers"]) or "none recorded"
    lines = [f"STATUS: {status}",
             f"Cost: $0 — keyless routes only (routes served: {routes})"]
    if spec.get("lane_choice"):
        lines.append(f"Lane: requested={spec.get('lane') or 'auto'} "
                     f"chosen={spec['lane_choice']} — "
                     f"{spec.get('lane_reason') or ''}")
    lines.append("Acceptance criteria:")
    for c, met, evidence in evaluated:
        lines.append(f"- [{'x' if met else ' '}] {c} — evidence: {evidence}")
    lines.append(f"Commits: {', '.join(ev['commit_hashes']) or 'none'}")
    if status == "BLOCKED":
        tried = "; ".join(f"step {s.n} {s.kind} {s.tool}" for s in (result.steps[-4:] if result else []))
        lines.append(f"Blocker: {summary[:300]} | last steps: {tried}")
    if status == "NEEDS ETHURAM":
        if wall:
            lines.append(
                "Needs Ethuram: pass the human-only wall Lille's run "
                f"reached — {wall}. Everything else is pre-staged: her "
                "browser profile is parked with the session and the "
                "identity kit in the task record; once the wall is "
                "passed (by Ethuram, in her browser session), re-run "
                f"the spec to resume. Detail: {summary[:240]}")
        else:
            lines.append("Needs Ethuram: complete the git authentication/sign-in step "
                         "on this host (the push could not authenticate), then re-run "
                         f"the spec. Everything else is done. Detail: {summary[:200]}")
    return "\n".join(lines)


_WALL_SIGNALS = ("challenge", "captcha", "arkose", "funcaptcha",
                 "verification code", "code was sent", "code sent to",
                 "phone number", "verify you are human")


_OPEN_URL = re.compile(r"url='https?://([^'/]+)")


def _unworked_routes(spec, steps):
    """Allowlisted domains the run never actually WORKED —
    attribution: browser steps belong to the domain of the most
    recent browser_open; a domain is worked when any non-open
    step (or a CHALLENGE result) is attributed to it, or it was
    opened twice. Opening a homepage once and moving on is not
    working the route (v0.8 X attempt 6: maildrop.cc was opened,
    never used, then declared exhausted)."""
    domains = [d.lower() for d in spec.get("browser_domains") or []]
    if not domains:
        return []
    worked, opens, current = set(), {}, None
    for s in steps:
        if s.kind != "tool":
            continue
        if s.tool == "browser_open":
            m = _OPEN_URL.search(s.args_summary or "")
            if m:
                current = m.group(1).lower().lstrip("www.")
                opens[current] = opens.get(current, 0) + 1
            continue
        if (s.tool or "").startswith(("browser_", "x_", "vault_")) \
                and current:
            worked.add(current)
        if (s.result_summary or "").startswith("CHALLENGE") and current:
            worked.add(current)
    return [d for d in domains
            if d.lstrip("www.") not in worked
            and opens.get(d.lstrip("www."), 0) < 2]


def _wall_signal(summary, ev):
    """A browser run that stopped at a human-only wall grades as
    NEEDS ETHURAM (the one step only he can take), never as a plain
    failure — and never as a pass. The wall must be OBSERVED: a
    CHALLENGE tool result in the trace. A summary merely naming a
    wall is narration, not evidence (v0.8 X attempt 1: the model
    finished at step 2 claiming a CAPTCHA it had never met, having
    made zero tool calls — the claim graded NEEDS ETHURAM before
    this fix)."""
    if ev.get("challenge"):
        return ev["challenge"][:160]
    return None


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
    # Lane decision (v0.9): made once, deterministically, and
    # recorded on the spec — it lands in spec.json, the report,
    # and (for the device lane) the device-task package.
    decision = lanes.choose_lane(spec.get("lane"),
                                 spec.get("browser_domains"))
    spec["lane_choice"] = decision["lane"]
    spec["lane_reason"] = decision["reason"]
    blocker_phrase = personal_appearance_blocker(spec)
    if blocker_phrase:
        # Hard boundary: never run, never fake. The report names the
        # single step only Ethuram can take (appear himself).
        lines = ["STATUS: NEEDS ETHURAM",
                 "Cost: $0 — keyless routes only (routes served: none — "
                 "task not run)",
                 "Acceptance criteria:"]
        for c in spec["criteria"]:
            lines.append(f"- [ ] {c} — evidence: not attempted; the spec "
                         "requires a person appearing personally, which a "
                         "Lille-made video cannot satisfy")
        lines.append("Commits: none")
        lines.append(
            "Needs Ethuram: this brief requires the entrant to appear "
            f"personally ({blocker_phrase!r} in the spec) — e.g. on "
            "camera explaining the project himself. A Lille-produced "
            "video must never be passed off as him, so Lille will not "
            "make it. The single step only Ethuram can take: record "
            "that appearance himself (Lille can still prepare the "
            "script, slides and edit around his recording).")
        report = "\n".join(lines)
        with open(os.path.join(task_dir, "SPEC_REPORT.md"), "w") as f:
            f.write(report + "\n")
        return report, None, task_dir
    if decision["lane"] == "device":
        # The device lane runs in the Lille app on Ethuram's
        # phone (v0.9) — the host core must not fake it with the
        # host browser (that is precisely the lane WALLS.md says
        # these domains refuse). Package the spec for the device
        # and report the single step only Ethuram can take:
        # start the packaged in-app task. The acceptance verdict
        # comes from the on-device run's graded report, not here.
        import json as _json
        package = {
            "kind": "lille-device-task",
            "version": 1,
            "lane": "device",
            "lane_requested": decision["requested"],
            "lane_reason": decision["reason"],
            "goal": spec["goal"],
            "hard_rules": spec["hard_rules"],
            "criteria": spec["criteria"],
            "deliverables": spec["deliverables"],
            "browser_domains": spec["browser_domains"],
            "x_post_mode": spec["x_post_mode"],
            "x_post_approved": spec["x_post_approved"],
            "spec_text": spec_text,
        }
        with open(os.path.join(task_dir, "device_task.json"), "w") as f:
            _json.dump(package, f, indent=2)
        try:
            with open(os.path.join(task_dir, "spec.json"), "w") as f:
                _json.dump(spec, f)
        except Exception:
            pass
        lines = ["STATUS: NEEDS ETHURAM",
                 "Cost: $0 — keyless routes only (routes served: "
                 "none — packaged for the device lane, not run on "
                 "the host)",
                 f"Lane: requested={decision['requested']} "
                 f"chosen=device — {decision['reason']}",
                 "Acceptance criteria:"]
        for c in spec["criteria"]:
            lines.append(f"- [ ] {c} — evidence: not attempted on "
                         "the host lane; this spec runs on the "
                         "device lane in the Lille app, and its "
                         "verdict comes from that run's graded "
                         "report")
        lines.append("Commits: none")
        lines.append(
            "Needs Ethuram: update the Lille app to v0.9+ and "
            "start the packaged in-app task for this spec from "
            "the Tasks screen (the X-account task ships ready-made "
            "there). It runs in her on-device browser session; if "
            "a human-only check appears it will park as "
            "PARKED_AT_WALL with a 'Lille needs you' card — pass "
            "the check in her session and tap 'Done — hand back "
            "to Lille', and she finishes alone. Device package: "
            "device_task.json in this task's directory.")
        report = "\n".join(lines)
        with open(os.path.join(task_dir, "SPEC_REPORT.md"), "w") as f:
            f.write(report + "\n")
        return report, None, task_dir
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
    return _execute_spec(spec, task_dir, task_id, initial_head, ws,
                         config=config, model_call=model_call,
                         pace_seconds=pace_seconds,
                         max_steps=max_steps, approver=approver,
                         task_text=task_text)


def _make_finish_guard(spec):
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
        if spec.get("browser_domains"):
            browser_steps = [s for s in steps if s.kind == "tool"
                             and (s.tool or "").startswith(
                                 ("browser_", "x_", "vault_"))]
            if not browser_steps:
                return ("this spec requires browser work, but no "
                        "browser tool has been called yet. Start "
                        "with browser_open on an allowed domain and "
                        "work the routes in the spec before "
                        "finishing.")
            summary = str((_args or {}).get("summary") or "").lower()
            # Premature-finish guard (v0.8 X attempt 14): a run
            # that gave up after one route tangled, with the
            # other authorised routes untouched, must not end —
            # whatever words the summary uses. Success evidence
            # in the trace (a verified session, a live post, a
            # feed/search read) always permits finishing.
            success_evidence = any(
                "logged_in as @" in (s.result_summary or "")
                or (s.result_summary or "").startswith(
                    ("X_POST_OK", "X_FEED_OK", "X_SEARCH_OK"))
                for s in steps)
            if not success_evidence:
                unworked = _unworked_routes(spec, steps)
                if len(unworked) >= 2:
                    return ("this run is not done and the spec's "
                            "routes are not exhausted — these "
                            "authorised domains were never actually "
                            f"worked: {', '.join(unworked)}. Work "
                            "each route in the spec (interact, "
                            "read what it gives you, poll an inbox "
                            "for a few minutes before deciding mail "
                            "did not arrive) before finishing.")
            if any(sig in summary for sig in _WALL_SIGNALS) and not any(
                    (s.result_summary or "").startswith("CHALLENGE")
                    for s in steps):
                return ("you name a human-only wall, but no tool "
                        "result in this run observed a CHALLENGE — "
                        "walls are graded from observed evidence, "
                        "not narration. Keep working the routes in "
                        "the spec; finish with a wall claim only "
                        "after a tool returns CHALLENGE.")
            if any(sig in summary for sig in _WALL_SIGNALS):
                unworked = _unworked_routes(spec, steps)
                if len(unworked) >= 2:
                    return ("you claim the routes are exhausted, "
                            "but these authorised domains were "
                            "never actually worked (opened at most "
                            f"once, never used): {', '.join(unworked)}"
                            ". Work each route — interact with it, "
                            "read what it gives you — or meet its "
                            "wall, before finishing.")
        if is_video_spec(spec) and not any(
                t in ("verify_video", "assemble_video")
                and r.startswith("VIDEO_OK") for t, r in done_tools):
            return ("this is a video spec, but no VIDEO_OK ffprobe "
                    "evidence is recorded yet. Assemble the video with "
                    "assemble_video (and/or verify it with verify_video) "
                    "before finishing — an unverified video is not a "
                    "deliverable.")
        return None
    return finish_guard


def _execute_spec(spec, task_dir, task_id, initial_head, ws, *,
                  config=None, model_call=None, pace_seconds=None,
                  max_steps=25, approver=None, task_text=None,
                  snap=None):
    """Drive a parsed spec to its graded report. With `snap`
    (a loop checkpoint restored from disk), the run RESUMES
    where a dead process left it — completed steps are never
    redone and their evidence is never lost."""
    import json as _json
    try:
        with open(os.path.join(task_dir, "spec.json"), "w") as f:
            _json.dump(spec, f)
    except Exception:
        pass
    _snap_holder = {"snap": snap}

    def _keep_snap(s):
        _snap_holder["snap"] = s
        # Persist EVERY checkpoint to the task dir: a host
        # restart must not be able to kill a long spec run
        # (v0.8 X proof attempts 3, 10 and 13 all died with
        # their process, traces unpersisted). resume_spec()
        # continues from this file in a fresh process.
        try:
            tmp = os.path.join(task_dir, "checkpoint.json.tmp")
            with open(tmp, "w") as f:
                _json.dump(s, f)
            os.replace(tmp, os.path.join(task_dir,
                                         "checkpoint.json"))
        except Exception:
            pass

    loop = TaskLoop(config=config, model_call=model_call,
                    workspace_root=ws, max_steps=max_steps,
                    approver=approver, pace_seconds=pace_seconds,
                    finish_guard=_make_finish_guard(spec),
                    on_checkpoint=_keep_snap,
                    task_brief=build_task_brief(spec),
                    tool_names=spec_tool_names(spec),
                    sentinel_context={
                        "push_branches": spec["push_branches"],
                        "main_authorised": spec["main_authorised"],
                        "browser_domains": spec["browser_domains"],
                        "x_post_mode": spec["x_post_mode"],
                        "x_post_approved": spec["x_post_approved"]})
    # Fallback Law patience: if every route is cooling down (or the
    # network dropped), WAIT for the earliest retry and RESUME from
    # the last checkpoint — completed steps are never redone and
    # their evidence is never lost. (Learned live in the v0.6 proof:
    # a cold restart after a successful capture+assembly re-ran the
    # capture against an already-registered demo name, failed, and
    # the fresh segment's empty trace graded a finished video
    # BLOCKED.) Bounded: ~30 min of waiting.
    result = None
    waited = 0.0
    while True:
        try:
            result = loop.resume(snap) if snap else \
                loop.run(task_text, task_id=task_id)
            break
        except (WaitingRouteError, WaitingNetworkError) as e:
            snap = _snap_holder["snap"] or snap
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
    # Proactivity Law (v0.7): a spec's Watch items become commitments
    # the moment the spec finishes — the pending result is then
    # checked by proactive passes and reported when it lands,
    # without anybody asking. Registration failures are stated in
    # the report, never silent.
    if spec.get("watch"):
        from .commitments import CommitmentStore
        store = CommitmentStore(ws)
        for w in spec["watch"]:
            try:
                rec = store.create(
                    title=f"{spec['goal'] or 'spec'} — watch: {w['check']}",
                    check=w["check"], every_seconds=w["every_seconds"])
                report += (f"\nWatch registered: commitment {rec['id']} "
                           f"({w['check']}, every "
                           f"{w['every_seconds'] // 60} min) — proactive "
                           "passes will report it when it lands.")
            except ValueError as e:
                report += f"\nWatch NOT registered ({w['check']}): {e}"
    problems = validate_report(report, spec)
    if problems:  # our own builder must never emit a malformed report
        report += "\n[report self-check FAILED: " + "; ".join(problems) + "]"
    with open(os.path.join(task_dir, "SPEC_REPORT.md"), "w") as f:
        f.write(report + "\n")
    return report, result, task_dir


def resume_spec(task_dir, config=None, model_call=None,
                pace_seconds=None, max_steps=70, approver=None):
    """Resume a spec run from its on-disk checkpoint (written on
    every step by _execute_spec) after the process died — host
    restart, kill, crash. Returns (report, result, task_dir), or
    None when the task dir holds no resumable checkpoint."""
    import json as _json
    spec_path = os.path.join(task_dir, "spec.json")
    snap_path = os.path.join(task_dir, "checkpoint.json")
    if not (os.path.isfile(spec_path) and os.path.isfile(snap_path)):
        return None
    with open(spec_path) as f:
        spec = _json.load(f)
    with open(snap_path) as f:
        snap = _json.load(f)
    ws = os.path.dirname(os.path.abspath(task_dir))
    return _execute_spec(spec, task_dir, snap["task_id"], None, ws,
                         config=config, model_call=model_call,
                         pace_seconds=pace_seconds,
                         max_steps=max_steps, approver=approver,
                         snap=snap)
