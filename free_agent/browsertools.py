"""Browser tools (v0.8) — general interactive browser control for
Lille's loop, plus the X lane.

Design (DESIGN #2 — structural perception first):
- A persistent headless Chromium (tools/capture/browse.mjs, the
  v0.6 capture engine's CDP machinery) with a PERSISTENT profile
  dir: sessions survive between runs. The profile is host-only
  state (~/.freeagent/browser-profile, 0700, gitignored) — never
  committed, never exported, never read back into a prompt.
- Perception is the page's visible text + an element list with
  stable refs. No vision, no screenshots-as-input (screenshots are
  evidence for humans).
- Deterministic wrappers only: the model names a domain-allowlisted
  URL, an element ref, or visible text — never raw JS, never a
  shell. The eval RPC exists for THIS module's fixed X-lane
  expressions and is not a loop tool.
- Secrets: browser_type REFUSES password/secret fields (checked
  from the last read snapshot AND live in the driver).
  browser_type_secret types a value resolved from Lille's encrypted
  vault BY KEY NAME — the value crosses only the local driver pipe;
  it never enters model context, args summaries, traces or logs.
- Challenges: if a page presents a human-only wall (CAPTCHA/
  Arkose, an emailed code, a phone demand), the tool result is a
  CHALLENGE observation. Lille's rule (and the loop prompt's):
  STOP and report it. Nothing here solves or bypasses one.
- Human pacing: the driver pauses before clicks/typing; the X lane
  rate-limits posting. No mass actions.
"""
import json, os, re, secrets, string, subprocess, threading, time
import urllib.parse

from . import sandbox
from .vault import Vault, VaultUnavailable

HERE = os.path.dirname(os.path.abspath(__file__))
BROWSE_JS = os.path.join(HERE, "tools", "capture", "browse.mjs")
ASSETS_DIR = os.path.join(HERE, "..", "assets")

MAX_TEXT_CHARS = 1400
_KEY_RE = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")

# Runtime context, pushed in by tasktools from the Sentinel's spec
# context: {"browser_domains": [...], "x_post_mode": None|"own"|"user",
# "x_post_approved": bool}. The Sentinel gates calls; this module
# enforces the same rules again at execution time (defence in depth).
_CONTEXT = {"browser_domains": [], "x_post_mode": None,
            "x_post_approved": False}


def set_context(ctx):
    if isinstance(ctx, dict):
        for k in _CONTEXT:
            if k in ctx:
                _CONTEXT[k] = ctx[k]


def context():
    return dict(_CONTEXT)


def freeagent_home():
    return os.environ.get("FREEAGENT_HOME",
                          os.path.expanduser("~/.freeagent"))


def profile_dir():
    d = os.environ.get("LILLE_BROWSER_PROFILE",
                       os.path.join(freeagent_home(), "browser-profile"))
    os.makedirs(d, mode=0o700, exist_ok=True)
    try:
        os.chmod(d, 0o700)
    except OSError:
        pass
    return d


def _chrome_path():
    chrome = os.environ.get("LILLE_CHROME", "/opt/meta-chromium/chrome")
    if os.path.isfile(chrome):
        return chrome
    import shutil
    return (shutil.which("chromium") or shutil.which("chromium-browser")
            or shutil.which("google-chrome"))


# ---------- allowlist ----------

def effective_domains():
    doms = [str(d).lower().strip() for d in
            (_CONTEXT.get("browser_domains") or []) if str(d).strip()]
    env = os.environ.get("FREEAGENT_BROWSER_DOMAINS", "")
    doms += [d.lower().strip() for d in env.split(",") if d.strip()]
    return sorted(set(doms))


def domain_of(url):
    try:
        return (urllib.parse.urlparse(str(url)).hostname or "").lower()
    except ValueError:
        return ""


def domain_allowed(url, domains=None):
    host = domain_of(url)
    if not host:
        return False
    for d in (domains if domains is not None else effective_domains()):
        if host == d or host.endswith("." + d):
            return True
    return False


# ---------- challenge detection (mirror of browse.mjs, for tests
# and for classifying stored page text) ----------

_CHALLENGE_TEXT = [
    ("captcha", "verify you are human"),
    ("captcha", "are you a robot"),
    ("captcha", "confirm you’re human"),
    ("captcha", "confirm you're human"),
    ("code", "enter the code we sent"),
    ("code", "we sent a code"),
    ("code", "enter the verification code"),
    ("code", "check your email for a code"),
    ("code", "we emailed you a code"),
    ("phone", "confirm your phone number"),
    ("phone", "enter your phone number"),
    ("phone", "add a phone number"),
    ("login-challenge", "unusual login activity"),
    ("login-challenge", "help us keep your account secure"),
]


def detect_challenge_text(url, text, frames=()):
    u = (url or "").lower()
    if "arkose" in u or "funcaptcha" in u:
        return {"kind": "captcha", "detail": f"challenge in URL: {url}"}
    for fr in frames or ():
        src = fr.get("src", "") if isinstance(fr, dict) else (fr or "")
        s = (src or "").lower()
        if any(k in s for k in ("arkose", "funcaptcha", "hcaptcha",
                                "recaptcha")):
            # Presented challenges only (see browse.mjs): a
            # passive/hidden scoring iframe is not a wall.
            presented = not isinstance(fr, dict) \
                or fr.get("visible") is None \
                or (fr.get("visible") and (fr.get("w") or 0) >= 40
                    and (fr.get("h") or 0) >= 40)
            if presented:
                return {"kind": "captcha",
                        "detail": f"human-verification iframe: {src[:120]}"}
    t = (text or "").lower()
    for kind, needle in _CHALLENGE_TEXT:
        if needle in t:
            return {"kind": kind, "detail": f'page text: "{needle}"'}
    if "/account/access" in u:
        return {"kind": "login-challenge",
                "detail": f"account access wall: {url}"}
    return None


def challenge_observation(ch):
    return (f"CHALLENGE kind={ch.get('kind')} detail={ch.get('detail')} "
            "— STOP that route. This is a human-only wall: do NOT "
            "attempt to solve, bypass, or work around it, and do "
            "not retry the action. If the spec names another "
            "legitimate route (another provider, another entry "
            "point), take it now. Finish with a wall claim only "
            "when every route in the spec has been worked and "
            "walled — opening a homepage is not working a route.")


# ---------- the driver session (one per process) ----------

class BrowserError(RuntimeError):
    pass


class _Session:
    def __init__(self):
        self.proc = None
        self.lock = threading.Lock()
        self.next_id = 0
        self.last_elements = {}   # ref -> element dict from last read
        self.current_url = ""

    def start(self):
        if self.proc and self.proc.poll() is None:
            return
        node = None
        import shutil
        node = shutil.which("node")
        if not node:
            raise BrowserError("node not found on this host "
                               "(browser engine needs node)")
        chrome = _chrome_path()
        if not chrome:
            raise BrowserError("no Chromium binary found for the browser")
        cmd = [node, BROWSE_JS, "--profile", profile_dir(),
               "--chrome", chrome]
        self.proc = subprocess.Popen(
            cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, text=True, bufsize=1)
        line = self.proc.stdout.readline()
        if not line or '"ready"' not in line:
            raise BrowserError("browser driver did not start")

    def rpc(self, action, timeout=120, **args):
        with self.lock:
            self.start()
            self.next_id += 1
            rid = self.next_id
            payload = {"id": rid, "action": action}
            payload.update(args)
            try:
                self.proc.stdin.write(json.dumps(payload) + "\n")
                self.proc.stdin.flush()
            except (BrokenPipeError, OSError) as e:
                self.proc = None
                raise BrowserError(f"browser driver pipe broke: {e}")
            # Read until our response id arrives (the driver answers
            # strictly in order; the ready line was consumed at start).
            import select
            deadline = time.time() + timeout
            buf = ""
            while time.time() < deadline:
                r, _, _ = select.select([self.proc.stdout], [], [],
                                        max(0.1, deadline - time.time()))
                if not r:
                    break
                line = self.proc.stdout.readline()
                if not line:
                    break
                try:
                    msg = json.loads(line)
                except ValueError:
                    continue
                if msg.get("id") != rid:
                    continue
                if msg.get("ok"):
                    result = msg.get("result") or {}
                    if isinstance(result, dict):
                        if result.get("elements") is not None:
                            self.last_elements = {
                                e["ref"]: e for e in result["elements"]}
                        if result.get("url"):
                            self.current_url = result["url"]
                    return result
                err = BrowserError(msg.get("error", "browser error"))
                err.code = msg.get("code")
                raise err
            self.proc = None
            raise BrowserError(f"browser driver timed out on {action}")


_SESSION = _Session()


def _rpc(action, **kw):
    timeout = kw.pop("_timeout", 120)
    return _SESSION.rpc(action, timeout=timeout, **kw)


def close_session():
    try:
        if _SESSION.proc and _SESSION.proc.poll() is None:
            _SESSION.rpc("close", timeout=10)
    except Exception:
        pass
    _SESSION.proc = None


# ---------- result formatting ----------

def _fmt_elements(elements, cap=120):
    lines = []
    for e in (elements or [])[:cap]:
        bits = [f"[{e['ref']}]", e.get("tag", "?")]
        if e.get("type"):
            bits.append(f"type={e['type']}")
        if e.get("testid"):
            bits.append(f"testid={e['testid']}")
        if e.get("secret"):
            bits.append("SECRET")
        label = e.get("text") or e.get("placeholder") or e.get("name") or ""
        if label:
            bits.append(repr(label[:70]))
        elif e.get("type") == "submit" or e.get("tag") == "button":
            # An unlabelled submit button is invisible as a
            # control to the model (v0.8 X proof: the signup's
            # submit is exactly this — the model clicked legal
            # links and repeated no-op clicks for want of this
            # one word). The type attribute is page truth.
            bits.append("(submit button)" if e.get("type") == "submit"
                        else "(button)")
        if e.get("href"):
            bits.append(f"href={e['href'][:70]}")
        lines.append(" ".join(bits))
    return "\n".join(lines)


def _fmt_state(state, header):
    if state.get("challenge"):
        return challenge_observation(state["challenge"]) + \
            f"\n(page: {state.get('url', '')})"
    out = [header, f"URL: {state.get('url', '')}",
           f"Title: {state.get('title', '')}"]
    full = state.get("text") or ""
    text = full[:MAX_TEXT_CHARS]
    if text:
        out.append("TEXT:\n" + text)
    if len(full) > MAX_TEXT_CHARS + 400:
        # Form errors and footers live at the END of a page's
        # text; a head-only excerpt hid them in the v0.8 X proof
        # (attempt 11 stalled on a form it could not see).
        out.append("TEXT TAIL:\n" + full[-500:])
    els = state.get("elements") or []
    if els:
        out.append(f"ELEMENTS ({len(els)}):\n" + _fmt_elements(els))
    if state.get("net_errors"):
        # The page's own failed requests — an inert submit with
        # a 403/failed POST behind it is a network fact, not a
        # form problem (v0.8 X proof, attempts 18-19).
        out.append("NET ERRORS (the page's recent failed "
                   "requests):\n" + "\n".join(
                       f"- {e}" for e in state["net_errors"]))
    return "\n".join(out)


def _fmt_lite(state, header):
    if state.get("challenge"):
        return challenge_observation(state["challenge"]) + \
            f"\n(page: {state.get('url', '')})"
    excerpt = (state.get("text") or state.get("text_excerpt") or "")[:700]
    out = (f"{header}\nURL: {state.get('url', '')}\n"
           f"Title: {state.get('title', '')}\n{excerpt}")
    els = state.get("elements") or []
    if els:
        # Lite results follow clicks/types — acting blind on a
        # text excerpt alone stalled attempt 11. Compact element
        # refs ride along (labels cut harder than _fmt_state).
        lines = []
        for e in els[:28]:
            bits = [f"[{e['ref']}]", e.get("tag", "?")]
            if e.get("type"):
                bits.append(f"type={e['type']}")
            if e.get("secret"):
                bits.append("SECRET")
            label = e.get("text") or e.get("placeholder") \
                or e.get("name") or ""
            if label:
                bits.append(repr(label[:40]))
            elif e.get("type") == "submit":
                bits.append("(submit button)")
            if e.get("href"):
                # Anchors must show where they go in lite too:
                # attempt 12 clicked a 'Terms of Service' link
                # believing it was the flow's Continue control.
                bits.append(f"href={e['href'][:44]}")
            lines.append(" ".join(bits))
        out += f"\nELEMENTS ({len(els)}):\n" + "\n".join(lines)
    if state.get("net_errors"):
        out += "\nNET ERRORS: " + "; ".join(state["net_errors"])
    return out


def _domain_guard(state):
    """After any action, if the browser has wandered outside the
    allowlist (a click can navigate), say so — further typing/clicks
    are refused by _pre_action until it returns."""
    url = state.get("url") or _SESSION.current_url
    if url and not domain_allowed(url):
        return (f"error: the browser is now on {url}, whose domain is "
                "NOT in this task's allowlist "
                f"({', '.join(effective_domains()) or 'none'}). "
                "browser_open an allowed URL to continue.")
    return None


def _pre_action():
    url = _SESSION.current_url
    if url and not domain_allowed(url):
        return (f"error: current page {url} is outside this task's "
                "domain allowlist; browser_open an allowed URL first.")
    return None


# ---------- vault ----------

def _vault():
    home = freeagent_home()
    os.makedirs(home, mode=0o700, exist_ok=True)
    return Vault(os.path.join(home, "vault.enc"))


def vault_gen_secret(key, length=24):
    """Generate a strong secret and store it in the vault under
    `key`. Returns ONLY metadata — the value is never returned,
    printed, or written anywhere else."""
    if not _KEY_RE.match(str(key or "")):
        return "error: vault key names must match [A-Za-z0-9_.-]{1,64}"
    alphabet = string.ascii_letters + string.digits + "!@#$%^&*-_=+"
    value = "".join(secrets.choice(alphabet)
                    for _ in range(max(12, min(int(length or 24), 64))))
    try:
        _vault().set(str(key), value)
    except VaultUnavailable as e:
        return f"error: vault unavailable: {e}"
    return (f"VAULT_OK key={key} stored in Lille's encrypted vault "
            "(the value is never shown; type it with "
            "browser_type_secret using this key name)")


def vault_record_account(site, username, vault_key):
    """Record account METADATA (never a secret value) in
    <home>/accounts.json so Lille — and Ethuram — can see what
    accounts she holds and which vault key belongs to each."""
    if not _KEY_RE.match(str(vault_key or "")):
        return "error: vault_key must be a vault key name"
    home = freeagent_home()
    os.makedirs(home, mode=0o700, exist_ok=True)
    path = os.path.join(home, "accounts.json")
    data = {}
    if os.path.isfile(path):
        try:
            with open(path) as f:
                data = json.load(f)
        except Exception:
            data = {}
    data.setdefault("accounts", []).append({
        "site": str(site), "username": str(username),
        "vault_key": str(vault_key),
        "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ",
                                    time.gmtime())})
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, path)
    return (f"ACCOUNT_RECORDED site={site} username={username} "
            f"vault_key={vault_key} (metadata only — no secret stored "
            "in this record)")


def _vault_value(key):
    try:
        return _vault().get(str(key))
    except VaultUnavailable:
        return None


# ---------- loop tool handlers ----------

def h_browser_open(task_dir, args):
    url = str(args.get("url", ""))
    if not re.match(r"^https?://", url):
        return f"error: browser_open needs an http(s) URL: {url[:80]!r}"
    if not domain_allowed(url):
        return (f"error: domain {domain_of(url)!r} is not in this "
                "task's browser allowlist "
                f"({', '.join(effective_domains()) or 'none authorised'})")
    try:
        state = _rpc("open", url=url,
                     settle=float(args.get("settle") or 4))
    except BrowserError as e:
        return f"error: {e}"
    guard = _domain_guard(state)
    if guard and not state.get("challenge"):
        return guard
    return _fmt_state(state, f"BROWSER_OK opened {url}")


def h_browser_read(task_dir, args):
    try:
        state = _rpc("read")
    except BrowserError as e:
        return f"error: {e}"
    return _fmt_state(state, "PAGE")


def h_browser_click(task_dir, args):
    err = _pre_action()
    if err:
        return err
    try:
        if args.get("ref") is not None:
            state = _rpc("click", ref=int(args["ref"]))
        elif args.get("text"):
            state = _rpc("click", text=str(args["text"]))
        else:
            return "error: browser_click needs a ref or a text"
    except BrowserError as e:
        return f"error: {e}"
    guard = _domain_guard(state)
    if guard and not state.get("challenge"):
        return guard
    return _fmt_lite(state, "CLICK_OK")


def h_browser_type(task_dir, args):
    err = _pre_action()
    if err:
        return err
    ref = args.get("ref")
    if ref is None:
        return "error: browser_type needs a ref (from browser_read)"
    el = _SESSION.last_elements.get(int(ref))
    if el and el.get("secret"):
        return ("error: SECRET_FIELD — element "
                f"[{ref}] is a password/secret field. browser_type "
                "refuses it. Use browser_type_secret with the vault "
                "key name instead (the value never enters your "
                "context).")
    try:
        state = _rpc("type", ref=int(ref), text=str(args.get("text", "")))
    except BrowserError as e:
        if getattr(e, "code", None) == "SECRET_FIELD":
            return f"error: {e}"
        return f"error: {e}"
    guard = _domain_guard(state)
    if guard and not state.get("challenge"):
        return guard
    return _fmt_lite(state, "TYPE_OK")


def h_browser_type_secret(task_dir, args):
    err = _pre_action()
    if err:
        return err
    ref, key = args.get("ref"), str(args.get("vault_key", ""))
    if ref is None or not _KEY_RE.match(key):
        return ("error: browser_type_secret needs a ref and a "
                "vault_key NAME (never a value)")
    # Mirror of browser_type's refusal (v0.8 X attempt 18): the
    # vault value may ONLY land in an element the page itself
    # flags SECRET (a password field). Typing it into a plain
    # field both leaks it into a visible input and silently
    # corrupts the form — the model did exactly that and the
    # signup never validated.
    el = _SESSION.last_elements.get(int(ref))
    if el is not None and not el.get("secret"):
        return ("error: NOT_A_SECRET_FIELD — element "
                f"[{ref}] is not a password field, so the vault "
                "value was NOT typed. browser_read again and use "
                "the ref whose element is marked SECRET.")
    value = _vault_value(key)
    if value is None:
        return (f"error: no vault entry named {key!r} — create it "
                "with vault_gen_secret first")
    try:
        state = _rpc("type", ref=int(ref), text=value, secret=True)
    except BrowserError as e:
        return f"error: {e}"
    # The value existed only in this local variable + the driver
    # pipe. It is never placed in the result, the trace, or a file.
    del value
    guard = _domain_guard(state)
    if guard and not state.get("challenge"):
        return guard
    return _fmt_lite(state, f"TYPE_SECRET_OK vault_key={key} "
                            "(value typed from the vault; never shown)")


def h_browser_upload(task_dir, args):
    err = _pre_action()
    if err:
        return err
    ref = args.get("ref")
    path = str(args.get("path", ""))
    try:
        apath = sandbox.resolve_inside(task_dir, path)
    except sandbox.SandboxError:
        assets = os.path.abspath(ASSETS_DIR)
        cand = os.path.abspath(path)
        if cand.startswith(assets + os.sep) and os.path.isfile(cand):
            apath = cand
        else:
            return ("error: browser_upload path must be inside the "
                    "task sandbox or Lille's repo assets/ dir")
    if not os.path.isfile(apath):
        return f"error: no such file for upload: {path}"
    if ref is None:
        return "error: browser_upload needs the file input's ref"
    try:
        state = _rpc("upload", ref=int(ref), path=apath)
    except BrowserError as e:
        return f"error: {e}"
    return _fmt_lite(state, f"UPLOAD_OK file={os.path.basename(apath)}")


def h_browser_screenshot(task_dir, args):
    # "/" survives the sanitiser: deliverable names are paths
    # inside the sandbox (repo/wiki.png). resolve_inside confines
    # them — v0.8 proof attempt 2 flattened the name to
    # repo_wiki.png and the deliverable missed its criterion.
    name = re.sub(r"[^A-Za-z0-9_/.-]", "_",
                  str(args.get("name", "shot.png")))
    if not name.endswith(".png"):
        name += ".png"
    try:
        apath = sandbox.resolve_inside(task_dir, name)
    except sandbox.SandboxError as e:
        return f"error: {e}"
    os.makedirs(os.path.dirname(apath), exist_ok=True)
    try:
        r = _rpc("screenshot", path=apath)
    except BrowserError as e:
        return f"error: {e}"
    if r.get("challenge"):
        return challenge_observation(r["challenge"])
    return (f"SCREENSHOT_OK path={name} bytes={r.get('bytes')} "
            f"url={r.get('url', '')}")


def h_browser_wait(task_dir, args):
    try:
        if args.get("text"):
            t = float(args.get("timeout") or 30)
            state = _rpc("wait", text=str(args["text"]),
                         wait_timeout=t, _timeout=int(t) + 30)
        else:
            state = _rpc("wait", seconds=float(args.get("seconds") or 2))
    except BrowserError as e:
        return f"error: {e}"
    return _fmt_lite(state, "WAIT_OK")


def h_vault_gen_secret(task_dir, args):
    return vault_gen_secret(args.get("key", ""),
                            args.get("length", 24))


def h_vault_record_account(task_dir, args):
    return vault_record_account(args.get("site", ""),
                                args.get("username", ""),
                                args.get("vault_key", ""))


# ---------- X lane ----------

X_HOME = "https://x.com/home"

_EXTRACT_ARTICLES = """(() => [...document.querySelectorAll('article')]
  .slice(0, %d).map(a => {
    const t = a.querySelector('a[href*="/status/"] time');
    const link = t ? t.closest('a') : null;
    const userBox = a.querySelector('[data-testid="User-Name"]');
    return {
      author: userBox ? userBox.innerText.replace(/\\s+/g, ' ').slice(0, 90) : '',
      text: (a.innerText || '').replace(/\\s+/g, ' ').slice(0, 420),
      url: link ? link.href : ''};
  }))()"""

_HANDLE_EXPR = """(() => {
  const a = document.querySelector('a[data-testid="AppTabBar_Profile_Link"]');
  if (a) return a.getAttribute('href');
  const nav = document.querySelector('[data-testid="SideNav_AccountSwitcher_Button"]');
  return nav ? ('text:' + (nav.innerText || '').replace(/\\s+/g, ' ')) : null;
})()"""


def _x_open(url, settle=4):
    if not domain_allowed(url):
        raise BrowserError("x.com is not in this task's browser "
                           "allowlist — the spec must authorise it")
    return _rpc("open", url=url, settle=settle)


def _x_handle():
    r = _rpc("eval", expression=_HANDLE_EXPR)
    v = r.get("value")
    if not v:
        return None, None
    if isinstance(v, str) and v.startswith("/"):
        return v.strip("/"), None
    if isinstance(v, str) and v.startswith("text:"):
        m = re.search(r"@([A-Za-z0-9_]{1,15})", v)
        return (m.group(1) if m else None), v[5:].strip()
    return None, None


def x_session_status(task_dir):
    """Logged-in state read from the PAGE (never from cookies)."""
    try:
        state = _x_open(X_HOME, settle=4)
    except BrowserError as e:
        return f"error: {e}"
    if state.get("challenge"):
        return challenge_observation(state["challenge"])
    handle, label = _x_handle()
    if handle:
        rec = {"handle": handle, "label": label,
               "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ",
                                   time.gmtime())}
        try:
            with open(os.path.join(task_dir, "x_session.json"),
                      "w") as f:
                json.dump(rec, f, indent=2)
        except OSError:
            pass
        return (f"X_SESSION logged_in as @{handle} — session is live "
                "in Lille's persistent browser profile")
    return ("X_SESSION logged_out — no account is signed in in "
            "Lille's browser profile (page: "
            f"{(state.get('url') or '')[:80]})")


def _x_articles(n):
    r = _rpc("eval", expression=_EXTRACT_ARTICLES % int(n))
    if r.get("challenge"):
        return None, challenge_observation(r["challenge"])
    return r.get("value") or [], None


def _fmt_posts(header, posts):
    lines = [header]
    for i, p in enumerate(posts, 1):
        lines.append(f"{i}. {p.get('author', '')} — "
                     f"{p.get('text', '')[:300]}"
                     + (f" [{p['url']}]" if p.get("url") else ""))
    return "\n".join(lines)


def x_read_feed(task_dir, args):
    n = max(1, min(int(args.get("count") or 10), 20))
    try:
        state = _x_open(X_HOME, settle=4)
        if state.get("challenge"):
            return challenge_observation(state["challenge"])
        handle, _ = _x_handle()
        if not handle:
            return ("error: X_FEED unavailable — not logged in. "
                    "x_session_status explains the session state.")
        posts, err = _x_articles(n)
        if err:
            return err
        if len(posts) < n:  # one scroll to load more, then re-read
            _rpc("eval", expression="window.scrollBy(0, 1600); true")
            time.sleep(2)
            more, err = _x_articles(n)
            if not err and more and len(more) > len(posts):
                posts = more
    except BrowserError as e:
        return f"error: {e}"
    return _fmt_posts(f"X_FEED_OK posts={len(posts)}", posts)


def x_search(task_dir, args):
    q = str(args.get("query", "")).strip()
    if not q:
        return "error: x_search needs a query"
    n = max(1, min(int(args.get("count") or 10), 20))
    url = "https://x.com/search?" + urllib.parse.urlencode(
        {"q": q, "src": "typed_query"})
    try:
        state = _x_open(url, settle=4)
        if state.get("challenge"):
            return challenge_observation(state["challenge"])
        posts, err = _x_articles(n)
        if err:
            return err
    except BrowserError as e:
        return f"error: {e}"
    return _fmt_posts(f"X_SEARCH_OK results={len(posts)} query={q!r}",
                      posts)


_LAST_POST_TS = [0.0]
_POST_MIN_INTERVAL = 90

# Copy rules (Ethuram): no invented offers/prizes/commitments in
# copy Lille posts; the ₿ sign-off is @Bitcoinornatin's branding.
_COPY_BLOCKS = ("giveaway", "giving away", "win $", "prize pool",
                "airdrop")


def x_post(task_dir, args):
    text = str(args.get("text", "")).strip()
    if not text:
        return "error: x_post needs text"
    if len(text) > 280:
        return f"error: post is {len(text)} chars (X limit 280)"
    mode = _CONTEXT.get("x_post_mode")
    low = text.lower()
    for bad in _COPY_BLOCKS:
        if bad in low:
            return (f"error: COPY_RULE — the post mentions {bad!r}; "
                    "Lille never posts invented offers, prizes or "
                    "commitments (Ethuram's rule). Rewrite it.")
    if mode == "own" and "₿itcoin or nothing" in text:
        return ("error: COPY_RULE — '₿itcoin or nothing' is "
                "@Bitcoinornatin's branding; it must not appear on "
                "Lille's own page.")
    wait = _POST_MIN_INTERVAL - (time.time() - _LAST_POST_TS[0])
    if wait > 0:
        time.sleep(wait)
    try:
        state = _x_open(X_HOME, settle=4)
        if state.get("challenge"):
            return challenge_observation(state["challenge"])
        handle, _ = _x_handle()
        if not handle:
            return "error: X_POST unavailable — not logged in."
        clicked = _rpc("eval", expression="""(() => {
          const box = document.querySelector(
            '[data-testid="tweetTextarea_0"]');
          if (!box) return false;
          box.focus(); box.click(); return true; })()""")
        if not clicked.get("value"):
            return "error: X_POST — composer box not found on the page"
        _rpc("insert_text", text=text)
        time.sleep(1)
        posted = _rpc("eval", expression="""(() => {
          const b = document.querySelector('[data-testid="tweetButton"]')
                 || document.querySelector('[data-testid="tweetButtonInline"]');
          if (!b || b.getAttribute('aria-disabled') === 'true') return false;
          b.click(); return true; })()""")
        if not posted.get("value"):
            return "error: X_POST — the Post button was not clickable"
        time.sleep(3)
        # Verify by state: the new post must be findable with a
        # status URL (on the profile timeline, newest first).
        _x_open(f"https://x.com/{handle}", settle=4)
        posts, err = _x_articles(5)
        if err:
            return err
        probe = text[:40].lower()
        for p in posts or []:
            if probe in (p.get("text") or "").lower() and p.get("url"):
                _LAST_POST_TS[0] = time.time()
                return (f"X_POST_OK url={p['url']} — posted as "
                        f"@{handle} and verified on the profile "
                        "timeline")
        return ("X_POST_UNVERIFIED — the Post button was clicked but "
                "the post could not be found on the profile timeline; "
                "treating as not posted (honesty rule). Check with "
                "x_read_feed before retrying.")
    except BrowserError as e:
        return f"error: {e}"


# ---------- sweep integration ----------

def sweep_x_items(topic, limit=10):
    """The sweep's X surface, backed by Lille's own session when one
    exists in her persistent profile. Returns item dicts, or raises
    sweep.SurfaceError with the coverage-note reason. Imported
    lazily by sweep.py to avoid a cycle."""
    from .sweep import SurfaceError
    prof = os.path.join(freeagent_home(), "browser-profile")
    if not os.path.isdir(prof) or not os.listdir(prof):
        raise SurfaceError(
            "no browser profile yet — Lille has no X session on this "
            "host, so X cannot be checked via her session")
    try:
        state = _x_open(X_HOME, settle=4)
        if state.get("challenge"):
            raise SurfaceError(
                "X presented a human-only challenge "
                f"({state['challenge'].get('kind')}) to Lille's "
                "session — reported, never bypassed")
        handle, _ = _x_handle()
        if not handle:
            raise SurfaceError(
                "Lille's browser profile exists but no X account is "
                "logged in — X cannot be checked via her session yet")
        url = "https://x.com/search?" + urllib.parse.urlencode(
            {"q": topic, "src": "typed_query"})
        _x_open(url, settle=4)
        posts, err = _x_articles(limit)
        if err:
            raise SurfaceError(err)
    except BrowserError as e:
        raise SurfaceError(f"Lille's X session could not be used: {e}")
    items = []
    for p in posts or []:
        title = (p.get("text") or "")[:120].strip()
        if title:
            items.append({"title": title,
                          "url": p.get("url") or url,
                          "snippet": (p.get("author") or "")[:80]})
    if not items:
        raise SurfaceError("X search via Lille's session returned no "
                           "readable posts")
    return items
