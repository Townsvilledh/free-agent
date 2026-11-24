"""Sandbox: the ONLY place Lille's tool loop may touch the filesystem
or execute code (v0.3).

Rules (deterministic, enforced by code — DESIGN #4):
- Every task gets its own directory under a workspace root. All file
  tools resolve paths INSIDE that directory; anything that escapes
  (.., absolute paths, symlinks pointing out) is rejected.
- run_python executes with cwd = task dir, a scrubbed environment
  (no API keys / tokens / secrets are passed through), a hard timeout,
  and — on Linux hosts where `unshare` is available — inside a
  network namespace with NO network interfaces up, so executed code
  genuinely cannot reach the network. Where unshare is unavailable
  the run still happens (cwd jail + env scrub + timeout) and the
  result says so honestly: network isolation is reported per-run,
  never assumed.
- Output is captured and truncated; the process is killed on timeout.
"""
import os, shutil, subprocess, sys, time, uuid

MAX_OUTPUT_CHARS = 6000
MAX_CODE_CHARS = 20000
MAX_FILE_CHARS = 100000

_SECRET_ENV_MARKERS = ("KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "AUTH", "COOKIE")

class SandboxError(RuntimeError):
    pass

def make_task_dir(workspace_root):
    os.makedirs(workspace_root, exist_ok=True)
    task_id = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
    path = os.path.join(workspace_root, task_id)
    os.makedirs(path)
    return task_id, path

def resolve_inside(task_dir, rel):
    """Resolve a user/model-supplied path inside the task dir or raise."""
    if rel is None or str(rel).strip() == "":
        rel = "."
    rel = str(rel)
    if os.path.isabs(rel):
        raise SandboxError(f"absolute paths are not allowed: {rel!r}")
    base = os.path.realpath(task_dir)
    target = os.path.realpath(os.path.join(base, rel))
    if target != base and not target.startswith(base + os.sep):
        raise SandboxError(f"path escapes the task sandbox: {rel!r}")
    return target

def _scrubbed_env(task_dir):
    env = {}
    for k, v in os.environ.items():
        ku = k.upper()
        if any(m in ku for m in _SECRET_ENV_MARKERS):
            continue
        env[k] = v
    env["HOME"] = task_dir
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    env.pop("PYTHONPATH", None)
    return env

def _truncate(text, limit=MAX_OUTPUT_CHARS):
    if len(text) <= limit:
        return text
    return text[:limit] + f"\n... [truncated, {len(text) - limit} more chars]"

_UNSHARE_OK = None

def _unshare_works():
    """Probe once: can this host actually create a network namespace?
    (Exists-but-EPERM is common in containers; never claim isolation
    we did not verify.)"""
    global _UNSHARE_OK
    if _UNSHARE_OK is None:
        unshare = shutil.which("unshare")
        if not unshare or not sys.platform.startswith("linux"):
            _UNSHARE_OK = False
        else:
            try:
                _UNSHARE_OK = subprocess.run(
                    [unshare, "-n", "true"], capture_output=True,
                    timeout=10).returncode == 0
            except Exception:
                _UNSHARE_OK = False
    return _UNSHARE_OK

def run_python(task_dir, code, timeout=30):
    """Execute `code` in the sandbox. Returns a result dict; never
    raises for ordinary code failures (they are observations)."""
    if len(code) > MAX_CODE_CHARS:
        raise SandboxError(f"code too large ({len(code)} chars > {MAX_CODE_CHARS})")
    timeout = max(1, min(int(timeout or 30), 120))
    script = os.path.join(task_dir, "_lille_run.py")
    with open(script, "w") as f:
        f.write(code)
    cmd = [sys.executable, script]
    net_isolated = _unshare_works()
    if net_isolated:
        cmd = [shutil.which("unshare"), "-n", sys.executable, script]
    try:
        proc = subprocess.run(cmd, cwd=task_dir, env=_scrubbed_env(task_dir),
                              capture_output=True, text=True, timeout=timeout)
        return {"exit_code": proc.returncode,
                "stdout": _truncate(proc.stdout or ""),
                "stderr": _truncate(proc.stderr or ""),
                "timed_out": False,
                "network_isolated": net_isolated}
    except subprocess.TimeoutExpired as e:
        return {"exit_code": None,
                "stdout": _truncate((e.stdout or b"").decode("utf-8", "replace")
                                    if isinstance(e.stdout, bytes) else (e.stdout or "")),
                "stderr": _truncate((e.stderr or b"").decode("utf-8", "replace")
                                    if isinstance(e.stderr, bytes) else (e.stderr or "")),
                "timed_out": True,
                "network_isolated": net_isolated}
