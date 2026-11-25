"""Task tools (v0.3): the tool set the loop may call, and the Sentinel
that gates every call.

Sentinel (DESIGN #4 — deterministic, non-LLM): permissions are decided
by plain code here, never by asking the model to behave.

- ALLOWLIST: only the tools defined in this module exist. Anything
  else is denied by construction.
- File tools are confined to the per-task sandbox dir (sandbox.py).
- run_python is confined + timed out + network-isolated where the
  host supports it (sandbox.py reports which).
- An approval hook (callable(tool_name, args_summary) -> bool) may
  deny any call. The default hook allows the sandboxed tools and
  denies everything not on the allowlist (which cannot be called
  anyway). Hosts can inject a stricter hook (e.g. ask the user).
- `finish` is special: the loop, not this module, verifies its claims
  against sandbox state before accepting them.
"""
import json, os
from . import gittools, sandbox, videotools
from .tools import search_tool

FINISH = "finish"

TOOL_SCHEMAS = [
    {"type": "function", "function": {
        "name": "write_file",
        "description": "Write a text file inside the task sandbox (overwrites).",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string", "description": "Relative path, e.g. fib.py"},
            "content": {"type": "string"}},
            "required": ["path", "content"]}}},
    {"type": "function", "function": {
        "name": "read_file",
        "description": "Read a text file from the task sandbox.",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string"}}, "required": ["path"]}}},
    {"type": "function", "function": {
        "name": "list_files",
        "description": "List files in the task sandbox (or a subdirectory of it).",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string", "description": "Relative dir, default '.'"}}}}},
    {"type": "function", "function": {
        "name": "run_python",
        "description": "Run a Python program in the sandbox (no network, 30s default timeout). Returns exit code, stdout, stderr.",
        "parameters": {"type": "object", "properties": {
            "code": {"type": "string"},
            "timeout": {"type": "integer", "description": "Seconds, max 120"}},
            "required": ["code"]}}},
    {"type": "function", "function": {
        "name": "web_search",
        "description": "Search the public web via the keyless search chain. Returns an answer snippet or an honest no-result.",
        "parameters": {"type": "object", "properties": {
            "query": {"type": "string"}}, "required": ["query"]}}},
    {"type": "function", "function": {
        "name": "sweep",
        "description": "THOROUGH multi-surface sweep on a topic (Thoroughness Law): queries the web (several phrasings), Devpost, DoraHacks, GitHub and Hacker News keylessly, dedups against the persisted known-items record, and writes a report with a mandatory coverage note (surfaces checked + NOT checked with reasons) to sweep-report.md in the task dir. Use this — not a single web_search — whenever the task is to FIND opportunities or check what exists on a topic.",
        "parameters": {"type": "object", "properties": {
            "topic": {"type": "string"}}, "required": ["topic"]}}},
    {"type": "function", "function": {
        "name": "git_clone",
        "description": "Clone a github.com repo (https or ssh URL only) into the sandbox as repo/.",
        "parameters": {"type": "object", "properties": {
            "url": {"type": "string"}}, "required": ["url"]}}},
    {"type": "function", "function": {
        "name": "repo_tree",
        "description": "List the files in the cloned repo (capped).",
        "parameters": {"type": "object", "properties": {
            "repo": {"type": "string", "description": "Repo subdir, default 'repo'"}}}}},
    {"type": "function", "function": {
        "name": "run_tests",
        "description": "Run the repo's test suite (auto-detected: python unittest/pytest or npm test) inside the sandbox rules. Returns the command, exit code and output.",
        "parameters": {"type": "object", "properties": {
            "repo": {"type": "string", "description": "Repo subdir, default 'repo'"},
            "command": {"type": "string", "description": "Optional spec-named test command (allowlisted executables only)"}}}}},
    {"type": "function", "function": {
        "name": "git_diff",
        "description": "Show a summary (or full) diff of uncommitted work in the repo.",
        "parameters": {"type": "object", "properties": {
            "repo": {"type": "string", "description": "Repo subdir, default 'repo'"},
            "full": {"type": "boolean"}}}}},
    {"type": "function", "function": {
        "name": "git_commit",
        "description": "Commit all changes in the repo with the given message. BLOCKED automatically if the staged diff trips the secret scan.",
        "parameters": {"type": "object", "properties": {
            "repo": {"type": "string", "description": "Repo subdir, default 'repo'"},
            "message": {"type": "string"}}, "required": ["message"]}}},
    {"type": "function", "function": {
        "name": "git_push",
        "description": "Push the current HEAD to the given branch on origin. Only works when the task's spec explicitly authorises that exact branch; never force-pushes; main/master need explicit spec authorisation.",
        "parameters": {"type": "object", "properties": {
            "repo": {"type": "string", "description": "Repo subdir, default 'repo'"},
            "branch": {"type": "string"}}, "required": ["branch"]}}},
    {"type": "function", "function": {
        "name": "capture_walkthrough",
        "description": "Scripted capture of a live web app in headless Chromium: run a step list (goto/wait/wait_text/click_text/click_selector/click_if_text/type/check/parse_seed/solve_word_quiz/scroll_into_view/screenshot) and record PNG frames + a manifest with hold durations and page-text excerpts. Pass expect_text: phrases the brief says must be shown — they are checked against the captured pages' real text. Returns CAPTURE_OK with the frame count and expect results.",
        "parameters": {"type": "object", "properties": {
            "steps": {"type": "array", "items": {"type": "object"},
                      "description": "Step list; screenshot steps: {action:'screenshot', name, hold (seconds), caption}"},
            "steps_file": {"type": "string",
                      "description": "PREFERRED for long step lists: path (inside the task dir) of a JSON file holding the step array — write it with write_file first, then pass this instead of steps"},
            "name": {"type": "string", "description": "Frames subdirectory, default 'capture'"},
            "width": {"type": "integer", "description": "Viewport width, default 450"},
            "height": {"type": "integer", "description": "Viewport height, default 800"},
            "expect_text": {"type": "array", "items": {"type": "string"}}},
            "required": []}}},
    {"type": "function", "function": {
        "name": "render_card",
        "description": "Render a title/section card PNG in code (PIL): title, subtitle, bg/fg/accent hex colours, optional mascot image path.",
        "parameters": {"type": "object", "properties": {
            "out": {"type": "string", "description": "Output PNG path in the sandbox"},
            "title": {"type": "string"},
            "subtitle": {"type": "string"},
            "bg": {"type": "string"}, "fg": {"type": "string"},
            "accent": {"type": "string"},
            "width": {"type": "integer"}, "height": {"type": "integer"},
            "mascot": {"type": "string"}},
            "required": ["out", "title"]}}},
    {"type": "function", "function": {
        "name": "narrate",
        "description": "Synthesize voiceover for scenes [{id, text}] via the free TTS fallback chain (edge-tts -> Google Translate TTS -> local flite/espeak). Returns NARRATION_OK naming the route that served and per-scene durations, or NARRATION_FAILED with the reasons — audio is never faked.",
        "parameters": {"type": "object", "properties": {
            "scenes": {"type": "array", "items": {"type": "object"}},
            "out_dir": {"type": "string", "description": "Audio subdirectory, default 'audio'"}},
            "required": ["scenes"]}}},
    {"type": "function", "function": {
        "name": "assemble_video",
        "description": "Assemble scenes [{image, duration, caption, audio (optional), blur (optional [[x,y,w,h] fractions])}] into an MP4 (H.264/yuv420p) with captions burned in and narration mixed; scene durations extend to fit their audio. Returns VIDEO_OK ffprobe evidence (duration, size, codecs).",
        "parameters": {"type": "object", "properties": {
            "scenes": {"type": "array", "items": {"type": "object"}},
            "out": {"type": "string", "description": "Output MP4 path in the sandbox"},
            "width": {"type": "integer"}, "height": {"type": "integer"}},
            "required": ["scenes", "out"]}}},
    {"type": "function", "function": {
        "name": "verify_video",
        "description": "Run ffprobe on an existing video file and return the VIDEO_OK/VIDEO_FAIL evidence line (duration, resolution, video/audio codecs, bytes).",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string"}}, "required": ["path"]}}},
    {"type": "function", "function": {
        "name": FINISH,
        "description": "Declare the task complete. List any output files you claim to have produced in 'outputs' — they are verified to exist before the task is accepted as done.",
        "parameters": {"type": "object", "properties": {
            "summary": {"type": "string"},
            "outputs": {"type": "array", "items": {"type": "string"}}},
            "required": ["summary"]}}},
]

ALLOWLIST = {t["function"]["name"] for t in TOOL_SCHEMAS}

def args_summary(name, args):
    """Short, log-safe summary of a tool call's args (no file bodies)."""
    if not isinstance(args, dict):
        return "(non-dict args)"
    if name == "write_file":
        return f"path={args.get('path')!r} chars={len(str(args.get('content', '')))}"
    if name == "run_python":
        return f"code_chars={len(str(args.get('code', '')))} timeout={args.get('timeout', 30)}"
    if name == "capture_walkthrough":
        # Step bodies can contain typed values (e.g. a demo password):
        # summarise counts only, never contents.
        return (f"steps={len(args.get('steps', []) or [])} "
                f"steps_file={args.get('steps_file')!r} "
                f"name={args.get('name', 'capture')!r} "
                f"expect={len(args.get('expect_text', []) or [])}")
    if name == "narrate":
        scenes = args.get("scenes", []) or []
        return f"scenes={len(scenes)} chars={sum(len(str(s.get('text', ''))) for s in scenes if isinstance(s, dict))}"
    if name == "assemble_video":
        return (f"scenes={len(args.get('scenes', []) or [])} "
                f"out={args.get('out')!r}")
    if name == "render_card":
        return f"out={args.get('out')!r} title={str(args.get('title', ''))[:40]!r}"
    if name == FINISH:
        return f"outputs={args.get('outputs', [])!r}"
    return json.dumps({k: (str(v)[:80]) for k, v in args.items()})[:200]

def default_approver(name, summary):
    """Default approval hook: sandbox tools allowed, everything else
    denied. (Non-allowlist tools never reach the hook — they are
    denied by the allowlist first.)"""
    return name in ALLOWLIST

class Sentinel:
    def __init__(self, approver=None, spec_context=None):
        self.approver = approver or default_approver
        # v0.5 spec mode: {"push_branches": [...], "main_authorised": bool}
        # Without a spec context, git_push is always denied.
        self.spec_context = spec_context or {}

    def check(self, name, args, task_dir):
        """Return (allowed, reason). Deterministic; no model involved."""
        if name not in ALLOWLIST:
            return False, f"tool {name!r} is not on the allowlist"
        if not isinstance(args, dict):
            return False, "tool args must be a JSON object"
        if name in ("write_file", "read_file", "list_files"):
            try:
                sandbox.resolve_inside(task_dir, args.get("path", "."))
            except sandbox.SandboxError as e:
                return False, str(e)
        if name in ("repo_tree", "run_tests", "git_diff", "git_commit", "git_push"):
            try:
                gittools.repo_dir_of(task_dir, args.get("repo"))
            except sandbox.SandboxError as e:
                return False, str(e)
        if name == "git_clone":
            if not gittools.is_github_url(args.get("url", "")):
                return False, "git_clone accepts github.com URLs only"
        if name == "capture_walkthrough":
            steps, serr = videotools.resolve_steps(task_dir, args)
            if serr:
                return False, serr
            for s in steps:
                if not isinstance(s, dict):
                    return False, "capture steps must be JSON objects"
                if s.get("action") == "goto":
                    import re as _re
                    if not _re.match(r"^https?://", str(s.get("url", ""))):
                        return False, "capture goto URLs must be http(s) — file:/data:/javascript: are denied"
        if name in ("render_card", "verify_video"):
            try:
                sandbox.resolve_inside(task_dir, args.get("out") or args.get("path", ""))
            except sandbox.SandboxError as e:
                return False, str(e)
        if name == "assemble_video":
            try:
                sandbox.resolve_inside(task_dir, args.get("out", ""))
                for scene in args.get("scenes", []) or []:
                    sandbox.resolve_inside(task_dir, scene.get("image", ""))
                    if scene.get("audio"):
                        sandbox.resolve_inside(task_dir, scene["audio"])
            except sandbox.SandboxError as e:
                return False, str(e)
        if name == "git_push":
            branch = str(args.get("branch", ""))
            authorised = self.spec_context.get("push_branches", [])
            if branch not in authorised:
                return False, (f"push to {branch!r} is not authorised by the spec "
                               f"(authorised: {authorised or 'none'})")
            if branch in ("main", "master") and not self.spec_context.get("main_authorised"):
                return False, (f"push to {branch} needs the spec to explicitly "
                               "authorise that exact branch")
        if name == FINISH:
            for rel in args.get("outputs", []) or []:
                try:
                    sandbox.resolve_inside(task_dir, rel)
                except sandbox.SandboxError as e:
                    return False, str(e)
        if not self.approver(name, args_summary(name, args)):
            return False, "denied by the approval hook"
        return True, "ok"

# ---------- handlers (called only after the Sentinel approves) ----------

def _h_write_file(task_dir, args):
    target = sandbox.resolve_inside(task_dir, args["path"])
    content = str(args.get("content", ""))
    if len(content) > sandbox.MAX_FILE_CHARS:
        raise sandbox.SandboxError("file content too large")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "w") as f:
        f.write(content)
    return f"wrote {args['path']} ({len(content)} chars)"

def _h_read_file(task_dir, args):
    target = sandbox.resolve_inside(task_dir, args["path"])
    if not os.path.isfile(target):
        return f"error: no such file: {args['path']}"
    with open(target, errors="replace") as f:
        data = f.read(sandbox.MAX_FILE_CHARS)
    return data

def _h_list_files(task_dir, args):
    target = sandbox.resolve_inside(task_dir, args.get("path", "."))
    if not os.path.isdir(target):
        return f"error: not a directory: {args.get('path', '.')}"
    out = []
    for root, dirs, files in os.walk(target):
        dirs.sort()
        for fn in sorted(files):
            full = os.path.join(root, fn)
            rel = os.path.relpath(full, task_dir)
            out.append(f"{rel} ({os.path.getsize(full)} bytes)")
    return "\n".join(out) if out else "(empty)"

def _h_run_python(task_dir, args):
    r = sandbox.run_python(task_dir, str(args.get("code", "")),
                           timeout=args.get("timeout", 30))
    parts = [f"exit_code={r['exit_code']}",
             f"network_isolated={r['network_isolated']}"]
    if r["timed_out"]:
        parts.append("TIMED OUT")
    if r["stdout"]:
        parts.append("stdout:\n" + r["stdout"])
    if r["stderr"]:
        parts.append("stderr:\n" + r["stderr"])
    return "\n".join(parts)

def _h_web_search(task_dir, args):
    res = search_tool.search(str(args.get("query", "")))
    if res.get("text"):
        return f"[{res.get('source', 'web')}] {res['text']}"
    return "No result from any search route. " + str(res.get("note", ""))

def _h_sweep(task_dir, args):
    # The known-items record lives at the workspace root (the task
    # dir's parent) so dedup persists across tasks and CLI sweeps.
    from . import sweep as sweep_mod
    workspace_root = os.path.dirname(os.path.abspath(task_dir))
    report, summary = sweep_mod.run_sweep(
        str(args.get("topic", "")), workspace_root)
    with open(os.path.join(task_dir, "sweep-report.md"), "w") as f:
        f.write(report)
    checked = ", ".join(f"{c['surface']} ({c['count']})"
                        for c in summary["checked"]) or "none"
    missed = ", ".join(n["surface"] for n in summary["not_checked"]) \
        or "none"
    return (f"SWEEP_{summary['status']}: new={summary['new']} "
            f"already_known={summary['known']} "
            f"surfaces_checked=[{checked}] not_checked=[{missed}] "
            "full report (with the mandatory coverage note) written "
            "to sweep-report.md")

def _h_git_clone(task_dir, args):
    return gittools.clone(task_dir, args.get("url", ""))

def _h_repo_tree(task_dir, args):
    return gittools.repo_tree(task_dir, args.get("repo"))

def _h_run_tests(task_dir, args):
    return gittools.run_tests(task_dir, args.get("repo"), args.get("command"))

def _h_git_diff(task_dir, args):
    return gittools.diff(task_dir, args.get("repo"), bool(args.get("full")))

def _h_git_commit(task_dir, args):
    return gittools.commit(task_dir, args.get("repo"), args.get("message", ""))

def _h_git_push(task_dir, args):
    return gittools.push(task_dir, args.get("repo"), str(args.get("branch", "")))

def _h_capture_walkthrough(task_dir, args):
    return videotools.capture_walkthrough(task_dir, args)

def _h_render_card(task_dir, args):
    return videotools.render_card(task_dir, args)

def _h_narrate(task_dir, args):
    return videotools.narrate(task_dir, args)

def _h_assemble_video(task_dir, args):
    return videotools.assemble_video(task_dir, args)

def _h_verify_video(task_dir, args):
    return videotools.verify_video(task_dir, args)

HANDLERS = {
    "write_file": _h_write_file,
    "read_file": _h_read_file,
    "list_files": _h_list_files,
    "run_python": _h_run_python,
    "web_search": _h_web_search,
    "sweep": _h_sweep,
    "git_clone": _h_git_clone,
    "repo_tree": _h_repo_tree,
    "run_tests": _h_run_tests,
    "git_diff": _h_git_diff,
    "git_commit": _h_git_commit,
    "git_push": _h_git_push,
    "capture_walkthrough": _h_capture_walkthrough,
    "render_card": _h_render_card,
    "narrate": _h_narrate,
    "assemble_video": _h_assemble_video,
    "verify_video": _h_verify_video,
}

def execute(name, args, task_dir):
    """Run an approved tool. Returns a result string (errors are
    observations, not exceptions)."""
    try:
        return HANDLERS[name](task_dir, args)
    except sandbox.SandboxError as e:
        return f"error: {e}"
    except Exception as e:  # tool failures are data for the loop
        return f"error: {type(e).__name__}: {e}"
