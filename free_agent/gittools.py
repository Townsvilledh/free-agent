"""Repo tools (v0.5) — deterministic git wrappers for the loop.

The model NEVER gets a raw shell. Every repo operation is a plain
function here with its own validation:

- git_clone: github.com URLs only (https or ssh), cloned into the
  task sandbox. Local paths are NOT cloneable by the model — spec
  mode may pre-clone a local repo host-side (see specmode.py).
- run_tests: auto-detects the suite (python unittest/pytest, npm
  test) or runs a spec-named command whose executable is on a small
  allowlist. Same sandbox discipline as run_python (scrubbed env,
  timeout, network namespace where the host supports it).
- git_commit: BLOCKED by a secret scan over the staged diff. A hit
  aborts the commit and reports the pattern NAME + file — never the
  matched value.
- git_push: gated in the Sentinel (tasktools.py): the branch must be
  authorised by the spec, main/master additionally need explicit
  spec authorisation, force-push does not exist here.

Git itself runs host-side (not inside the model sandbox) because it
needs the host's existing SSH route for pushes. No credential is
ever read, printed, or passed to the model: git uses the host's SSH
config exactly as the operator's own git does.
"""
import os, re, shlex, shutil, subprocess, sys

from . import sandbox

GIT_TIMEOUT = 180
TEST_TIMEOUT = 300
MAX_TREE_ENTRIES = 400
MAX_DIFF_CHARS = 12000

_GITHUB_HTTPS = re.compile(r"^https://github\.com/[\w.-]+/[\w.-]+?(\.git)?$")
_GITHUB_SSH = re.compile(r"^git@github\.com:[\w.-]+/[\w.-]+?(\.git)?$")

# Secret scan: (pattern name, regex). Names are reported; matched
# values NEVER are. Extends the spirit of redact.py's patterns with
# the token shapes that actually leak into repos.
SECRET_PATTERNS = [
    ("github-token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b|\bgithub_pat_[A-Za-z0-9_]{20,}\b")),
    ("aws-access-key-id", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("private-key-block", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----")),
    ("openai-style-key", re.compile(r"\bsk-[A-Za-z0-9_-]{16,}\b")),
    ("slack-token", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b")),
    ("google-api-key", re.compile(r"\bAIza[A-Za-z0-9_-]{30,}\b")),
    ("generic-secret-assignment", re.compile(
        r"(?i)\b(?:api[_-]?key|client[_-]?secret|password|passwd|access[_-]?token|auth[_-]?token)"
        r"\s*[:=]\s*['\"][^'\"]{8,}['\"]")),
    ("bearer-token", re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._-]{20,}\b")),
]


def is_github_url(url):
    return bool(_GITHUB_HTTPS.match(str(url or "")) or _GITHUB_SSH.match(str(url or "")))


def _git_env():
    env = dict(os.environ)
    # Host SSH route (config only — no credential material is read).
    ssh_config = os.path.expanduser("~/.ssh/config")
    if os.path.isfile(ssh_config) and "GIT_SSH_COMMAND" not in env:
        env["GIT_SSH_COMMAND"] = f"ssh -F {ssh_config}"
    env["GIT_TERMINAL_PROMPT"] = "0"
    return env


def _git(repo_dir, *args, timeout=GIT_TIMEOUT):
    proc = subprocess.run(["git", "-C", repo_dir, *args],
                          capture_output=True, text=True, timeout=timeout,
                          env=_git_env())
    return proc.returncode, (proc.stdout or ""), (proc.stderr or "")


def _truncate(text, limit):
    return text if len(text) <= limit else text[:limit] + f"\n... [truncated, {len(text) - limit} more chars]"


def clone(task_dir, url):
    """Clone a github.com repo into <task_dir>/repo. Returns str."""
    if not is_github_url(url):
        return ("error: git_clone accepts github.com URLs only "
                "(https://github.com/owner/repo or git@github.com:owner/repo)")
    dest = os.path.join(task_dir, "repo")
    if os.path.exists(dest):
        return "error: repo/ already exists in this task sandbox"
    proc = subprocess.run(["git", "clone", "--quiet", str(url), dest],
                          capture_output=True, text=True, timeout=GIT_TIMEOUT,
                          env=_git_env())
    if proc.returncode != 0:
        return f"error: git clone failed (exit {proc.returncode}): {_truncate(proc.stderr.strip(), 500)}"
    rc, out, _ = _git(dest, "rev-parse", "--abbrev-ref", "HEAD")
    return f"cloned {url} into repo/ (branch {out.strip() if rc == 0 else '?'})"


def preclone_local(task_dir, local_path):
    """Host-side (spec mode only): clone a LOCAL repo into the task
    sandbox. If the source has a github origin, the clone's origin is
    reset to it so a spec-authorised push still reaches GitHub.
    Returns (ok, message)."""
    dest = os.path.join(task_dir, "repo")
    if os.path.exists(dest):
        return False, "repo/ already exists"
    proc = subprocess.run(["git", "clone", "--quiet", str(local_path), dest],
                          capture_output=True, text=True, timeout=GIT_TIMEOUT,
                          env=_git_env())
    if proc.returncode != 0:
        return False, f"local pre-clone failed: {proc.stderr.strip()[:300]}"
    rc, origin, _ = _git(local_path, "remote", "get-url", "origin")
    if rc == 0 and is_github_url(origin.strip()):
        _git(dest, "remote", "set-url", "origin", origin.strip())
    return True, f"pre-cloned local repo into repo/ (origin: {origin.strip() if rc == 0 else 'local only'})"


def repo_dir_of(task_dir, repo_arg):
    """Resolve the repo subdir inside the sandbox (default 'repo')."""
    return sandbox.resolve_inside(task_dir, repo_arg or "repo")


def repo_tree(task_dir, repo_arg=None):
    repo = repo_dir_of(task_dir, repo_arg)
    if not os.path.isdir(os.path.join(repo, ".git")):
        return "error: no git repo at that path (clone first)"
    out = []
    for root, dirs, files in os.walk(repo):
        dirs[:] = sorted(d for d in dirs if d != ".git")
        for fn in sorted(files):
            rel = os.path.relpath(os.path.join(root, fn), repo)
            out.append(rel)
            if len(out) >= MAX_TREE_ENTRIES:
                out.append(f"... [capped at {MAX_TREE_ENTRIES} entries]")
                return "\n".join(out)
    return "\n".join(out) if out else "(empty repo)"


def scan_text_for_secrets(text):
    """Return [(pattern_name, line_number)] — never the values."""
    hits = []
    for lineno, line in enumerate(str(text).splitlines(), 1):
        for name, pat in SECRET_PATTERNS:
            if pat.search(line):
                hits.append((name, lineno))
    return hits


def scan_staged(repo):
    """Scan the staged diff (added lines) + staged file names.
    Returns [(pattern_name, file, diff_line)] — never values."""
    rc, diff, _ = _git(repo, "diff", "--cached", "--unified=0")
    if rc != 0:
        return [("diff-failed", "?", 0)]
    hits = []
    current_file = "?"
    for lineno, line in enumerate(diff.splitlines(), 1):
        if line.startswith("+++ b/"):
            current_file = line[6:]
        elif line.startswith("+") and not line.startswith("+++"):
            for name, pat in SECRET_PATTERNS:
                if pat.search(line):
                    hits.append((name, current_file, lineno))
    return hits


def commit(task_dir, repo_arg, message):
    repo = repo_dir_of(task_dir, repo_arg)
    if not os.path.isdir(os.path.join(repo, ".git")):
        return "error: no git repo at that path (clone first)"
    if not str(message or "").strip():
        return "error: commit message is empty"
    _git(repo, "add", "-A")
    rc, status, _ = _git(repo, "status", "--porcelain")
    if rc == 0 and not status.strip():
        return "error: nothing to commit (working tree clean)"
    hits = scan_staged(repo)
    if hits:
        detail = "; ".join(f"{name} in {f} (diff line {ln})" for name, f, ln in hits[:5])
        _git(repo, "reset", "--quiet")
        return (f"COMMIT BLOCKED by secret scan: {detail}. "
                "Remove the secret material and try again. "
                "(Pattern names only — the values were never recorded.)")
    # Identity: set a repo-local Lille identity only if none exists.
    rc, email, _ = _git(repo, "config", "user.email")
    if rc != 0 or not email.strip():
        _git(repo, "config", "user.email", "lille@localhost")
        _git(repo, "config", "user.name", "Lille")
    rc, out, err = _git(repo, "commit", "--quiet", "-m", str(message))
    if rc != 0:
        return f"error: git commit failed: {_truncate(err.strip(), 500)}"
    rc, head, _ = _git(repo, "rev-parse", "HEAD")
    return f"committed {head.strip()[:12]} ({str(message)[:80]})"


def diff(task_dir, repo_arg, full=False):
    repo = repo_dir_of(task_dir, repo_arg)
    if not os.path.isdir(os.path.join(repo, ".git")):
        return "error: no git repo at that path (clone first)"
    _git(repo, "add", "-A")  # so untracked work shows in the summary
    rc, stat, _ = _git(repo, "diff", "--cached", "--stat")
    rc2, names, _ = _git(repo, "status", "--porcelain")
    summary = (stat.strip() or "(no changes)") + "\nstatus:\n" + (names.strip() or "clean")
    if not full:
        _git(repo, "reset", "--quiet")
        return "diff summary:\n" + _truncate(summary, 2000)
    rc, body, _ = _git(repo, "diff", "--cached")
    _git(repo, "reset", "--quiet")
    return "diff summary:\n" + _truncate(summary, 2000) + "\nfull diff:\n" + _truncate(body, MAX_DIFF_CHARS)


_TEST_ARGV_OK = ("python3", "python", "pytest", "npm", "node")


def detect_test_command(repo):
    has_py_tests = False
    for root, dirs, files in os.walk(repo):
        dirs[:] = [d for d in dirs if d not in (".git", "node_modules", "__pycache__")]
        if any(f.startswith("test_") and f.endswith(".py") or f.endswith("_test.py") for f in files):
            has_py_tests = True
            break
    if has_py_tests:
        if shutil.which("pytest"):
            return ["python3", "-m", "pytest", "-q"]
        return ["python3", "-m", "unittest", "discover", "-v"]
    pkg = os.path.join(repo, "package.json")
    if os.path.isfile(pkg):
        try:
            import json
            if "test" in (json.load(open(pkg)).get("scripts") or {}):
                return ["npm", "test"]
        except Exception:
            pass
    return None


def run_tests(task_dir, repo_arg, command=None):
    repo = repo_dir_of(task_dir, repo_arg)
    if not os.path.isdir(repo):
        return "error: no repo at that path (clone first)"
    if command:
        try:
            argv = shlex.split(str(command))
        except ValueError as e:
            return f"error: test command did not parse: {e}"
        if not argv or argv[0] not in _TEST_ARGV_OK:
            return (f"error: test command must start with one of {_TEST_ARGV_OK} "
                    "(deterministic wrappers only — no raw shell)")
    else:
        argv = detect_test_command(repo)
        if argv is None:
            return "error: no test suite detected (no python tests, no npm test script)"
    env = sandbox._scrubbed_env(repo)
    cmd = argv
    net_isolated = sandbox._unshare_works()
    if net_isolated:
        cmd = [shutil.which("unshare"), "-n"] + argv
    try:
        proc = subprocess.run(cmd, cwd=repo, env=env, capture_output=True,
                              text=True, timeout=TEST_TIMEOUT)
        out = _truncate((proc.stdout or "") + (proc.stderr or ""), sandbox.MAX_OUTPUT_CHARS)
        return (f"command: {' '.join(argv)}\nexit_code={proc.returncode} "
                f"network_isolated={net_isolated}\n{out}")
    except subprocess.TimeoutExpired:
        return f"error: test suite timed out after {TEST_TIMEOUT}s (command: {' '.join(argv)})"


def push(task_dir, repo_arg, branch):
    """Push an already-gated branch (Sentinel checked authorisation
    before this runs). Never force. Returns a result string."""
    repo = repo_dir_of(task_dir, repo_arg)
    if not os.path.isdir(os.path.join(repo, ".git")):
        return "error: no git repo at that path (clone first)"
    rc, out, err = _git(repo, "push", "origin", f"HEAD:refs/heads/{branch}",
                        timeout=GIT_TIMEOUT)
    if rc != 0:
        return f"error: git push failed (exit {rc}): {_truncate(err.strip(), 600)}"
    rc, head, _ = _git(repo, "rev-parse", "HEAD")
    return f"pushed branch {branch} at {head.strip()[:12]}"


def head_hash(repo_dir):
    rc, out, _ = _git(repo_dir, "rev-parse", "HEAD")
    return out.strip() if rc == 0 else None


def log_hashes(repo_dir, limit=20):
    rc, out, _ = _git(repo_dir, "log", f"-{limit}", "--format=%H")
    return out.split() if rc == 0 else []
