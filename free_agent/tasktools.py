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
from . import sandbox
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
    if name == FINISH:
        return f"outputs={args.get('outputs', [])!r}"
    return json.dumps({k: (str(v)[:80]) for k, v in args.items()})[:200]

def default_approver(name, summary):
    """Default approval hook: sandbox tools allowed, everything else
    denied. (Non-allowlist tools never reach the hook — they are
    denied by the allowlist first.)"""
    return name in ALLOWLIST

class Sentinel:
    def __init__(self, approver=None):
        self.approver = approver or default_approver

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

HANDLERS = {
    "write_file": _h_write_file,
    "read_file": _h_read_file,
    "list_files": _h_list_files,
    "run_python": _h_run_python,
    "web_search": _h_web_search,
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
