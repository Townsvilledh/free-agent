"""Gmail + Calendar tools for the loop (v0.13) — Lille reaches the
mailbox and the calendar HERSELF, inside her own security design.

Why this exists: given a job whose first step was "read the invite
from my Gmail", Lille handed the user a list of asks — her engine
had no mail tool, so she could not reach the mailbox. Ethuram's
law: she handles tasks end to end; gaps are closed by building,
not by asking him to fetch things.

Security shape (SECURITY.md, DESIGN #4 — deterministic, not the
model's job):

- OAuth scopes start READ-ONLY: gmail.readonly + calendar.readonly.
  The write scopes (gmail.send, calendar.events) are granted only
  when the user explicitly asks for them at connect time
  (--google-connect --google-send / --google-calendar-write).
- The OAuth token lives ONLY in Lille's encrypted vault
  (vault.enc, key name google_oauth_token). It never enters model
  context, a task trace, a log, code, git, or chat. Setup is a
  one-time HOST-side command; the consent happens in the user's
  own browser on the host.
- Everything a tool returns passes through redact.py first. Mail
  bodies reach the model only as redact.snippet() (<= 500 chars):
  one-time codes, card numbers, emails, phone numbers are stripped
  BEFORE the text can leave this module. Links are extracted
  host-side from the already-redacted body (query/fragment
  stripped; credential-cue URLs — reset/token/verify/password —
  dropped), which is how an invite link inside a long mail can
  still reach the task without dumping the mailbox.
- Writes are gated THREE times: the host config flag
  (FREEAGENT_GMAIL_SEND / FREEAGENT_CALENDAR_WRITE, both default
  OFF), a per-action approval via approvals.require_approval with
  a host-supplied approver (the Sentinel additionally refuses the
  default allow-all hook for these tools), and the connected
  token actually carrying the write scope. No path enables a
  write silently.
- Not connected is an honest state, not a failure and never a
  workaround: the read tools return a *_NOT_CONNECTED result
  naming the ONE setup step only the user can take. Lille must
  report that step — never ask the user to fetch or paste mail
  contents instead.

The google client libraries are OPTIONAL (requirements.txt):
this module imports them lazily, and without them the tools
report the not-connected state with the install named.
"""
import base64, json, os, re
from datetime import datetime, timedelta, timezone

from .approvals import ApprovalRequired, require_approval
from .browsertools import freeagent_home
from .config import Config
from .redact import redact, snippet
from .vault import Vault, VaultUnavailable

TOKEN_VAULT_KEY = "google_oauth_token"

GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
CALENDAR_READONLY_SCOPE = "https://www.googleapis.com/auth/calendar.readonly"
GMAIL_SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send"
CALENDAR_WRITE_SCOPE = "https://www.googleapis.com/auth/calendar.events"
READ_SCOPES = [GMAIL_READONLY_SCOPE, CALENDAR_READONLY_SCOPE]

_LIBS_HINT = ("google client libraries are not installed — run: "
              "pip install google-api-python-client google-auth-oauthlib")

GMAIL_NOT_CONNECTED = (
    "GMAIL_NOT_CONNECTED: Gmail is not connected on this host, so "
    "Lille cannot read the mailbox herself. The single setup step "
    "only the user can take: on the host, run "
    "`python3 -m free_agent --google-connect` once and approve the "
    "Google consent (read-only scopes) — the token is stored only "
    "in Lille's encrypted vault. Do NOT ask the user to fetch, "
    "copy or paste message contents; report this setup step and "
    "finish.")
CALENDAR_NOT_CONNECTED = (
    "CALENDAR_NOT_CONNECTED: Google Calendar is not connected on "
    "this host, so Lille cannot read the calendar herself. The "
    "single setup step only the user can take: on the host, run "
    "`python3 -m free_agent --google-connect` once and approve the "
    "Google consent (read-only scopes) — the token is stored only "
    "in Lille's encrypted vault. Do NOT ask the user to fetch or "
    "paste calendar contents; report this setup step and finish.")

# ---------- vault / credentials ----------

_VAULT_OVERRIDE = None
_FACTORIES = {}


def set_vault_override(vault):
    """Test hook: replace the vault the tools read (None restores)."""
    global _VAULT_OVERRIDE
    _VAULT_OVERRIDE = vault


def set_factory(kind, fn):
    """Test hook: replace the service builder for 'gmail' or
    'calendar'. fn(creds) -> service, or None to restore."""
    if fn is None:
        _FACTORIES.pop(kind, None)
    else:
        _FACTORIES[kind] = fn


def _vault():
    if _VAULT_OVERRIDE is not None:
        return _VAULT_OVERRIDE
    home = freeagent_home()
    os.makedirs(home, mode=0o700, exist_ok=True)
    return Vault(os.path.join(home, "vault.enc"))


def _load_token_info():
    """(info_dict, reason). The token JSON itself never leaves
    this module — callers get state and scopes, never the value."""
    try:
        raw = _vault().get(TOKEN_VAULT_KEY)
    except VaultUnavailable:
        return None, "vault-unavailable"
    if not raw:
        return None, "no-token"
    try:
        info = json.loads(raw)
    except (ValueError, TypeError):
        return None, "token-unreadable"
    if not isinstance(info, dict) or not info.get("refresh_token"):
        return None, "token-unreadable"
    return info, "ok"


def granted_scopes():
    info, _reason = _load_token_info()
    if not info:
        return []
    return list(info.get("scopes") or [])


def connection_state():
    """(connected, reason, scopes) — metadata only, safe to log."""
    info, reason = _load_token_info()
    if info is None:
        return False, reason, []
    try:
        import google.oauth2.credentials  # noqa: F401
        import googleapiclient.discovery  # noqa: F401
    except ImportError:
        return False, "libs-missing", list(info.get("scopes") or [])
    return True, "ok", list(info.get("scopes") or [])


def _credentials():
    """(creds, reason). Refreshes an expired token and persists
    the refreshed JSON back to the vault — the value crosses only
    between Google and the vault, never into a trace or log."""
    info, reason = _load_token_info()
    if info is None:
        return None, reason
    try:
        from google.auth.transport.requests import Request
        from google.oauth2.credentials import Credentials
    except ImportError:
        return None, "libs-missing"
    try:
        creds = Credentials.from_authorized_user_info(
            info, scopes=info.get("scopes"))
    except Exception:
        return None, "token-unreadable"
    if not creds.valid:
        if creds.expired and creds.refresh_token:
            try:
                creds.refresh(Request())
            except Exception:
                return None, "refresh-failed"
            try:
                _vault().set(TOKEN_VAULT_KEY, creds.to_json())
            except VaultUnavailable:
                pass  # refreshed creds still work for this run
        else:
            return None, "refresh-failed"
    return creds, "ok"


def _service(kind):
    """(service, reason). kind is 'gmail' or 'calendar'."""
    if kind in _FACTORIES:
        creds, reason = (None, "ok")
        try:
            return _FACTORIES[kind](creds), "ok"
        except Exception as e:
            return None, f"factory-error: {e}"
    creds, reason = _credentials()
    if creds is None:
        return None, reason
    try:
        from googleapiclient.discovery import build
    except ImportError:
        return None, "libs-missing"
    api, version = ("gmail", "v1") if kind == "gmail" else ("calendar", "v3")
    try:
        return build(api, version, credentials=creds,
                     cache_discovery=False), "ok"
    except Exception as e:
        return None, f"build-failed: {type(e).__name__}"


def _not_connected(base, reason):
    if reason == "libs-missing":
        return f"{base} (reason: {_LIBS_HINT})"
    if reason == "vault-unavailable":
        return (f"{base} (reason: the encrypted vault is unavailable "
                "on this host — cryptography not installed; run: "
                "pip install -r requirements.txt)")
    if reason == "refresh-failed":
        return (f"{base} (reason: the stored token could not be "
                "refreshed — re-run the one-time setup: "
                "`python3 -m free_agent --google-connect`)")
    return base


# ---------- shared helpers ----------

def _headers(msg):
    out = {}
    for h in (msg.get("payload", {}) or {}).get("headers", []) or []:
        out[h.get("name", "").lower()] = h.get("value", "")
    return out


def _plain_body(msg):
    """Extract the text/plain body of a Gmail message (decoded
    host-side; the caller redacts before anything leaves)."""
    def walk(part):
        mime = part.get("mimeType", "")
        body = part.get("body", {}) or {}
        data = body.get("data")
        if mime == "text/plain" and data:
            try:
                return base64.urlsafe_b64decode(
                    data + "=" * (-len(data) % 4)).decode(
                        "utf-8", errors="replace")
            except Exception:
                return ""
        for sub in part.get("parts", []) or []:
            text = walk(sub)
            if text:
                return text
        return ""
    return walk(msg.get("payload", {}) or {})


_LINK_RE = re.compile(r"https?://[^\s<>\"')\]]+")
_LINK_DROP_CUES = ("reset", "token", "password", "passwd", "verify",
                   "confirm", "unsubscribe", "code=", "auth=", "key=")


def extract_links(redacted_text, limit=10):
    """Links from ALREADY-REDACTED text. Query strings and
    fragments are stripped and credential-cue URLs dropped, so a
    password-reset or verification link can never ride along; a
    plain invite link (the registration-mail case) survives."""
    out = []
    for m in _LINK_RE.finditer(redacted_text or ""):
        url = m.group(0).rstrip(".,;:!?")
        low = url.lower()
        if any(cue in low for cue in _LINK_DROP_CUES):
            continue
        url = url.split("?")[0].split("#")[0]
        if url and url not in out:
            out.append(url)
        if len(out) >= limit:
            break
    return out


# ---------- Gmail handlers (called only after the Sentinel) ----------

def gmail_search(task_dir, args, ctx=None):
    svc, reason = _service("gmail")
    if svc is None:
        return _not_connected(GMAIL_NOT_CONNECTED, reason)
    query = str(args.get("query", ""))
    try:
        max_results = max(1, min(int(args.get("max_results", 10)), 20))
    except (TypeError, ValueError):
        max_results = 10
    res = svc.users().messages().list(
        userId="me", q=query, maxResults=max_results).execute()
    ids = [m["id"] for m in res.get("messages", [])]
    lines = [f"GMAIL_SEARCH_OK results={len(ids)}"]
    total = 0
    for mid in ids:
        msg = svc.users().messages().get(
            userId="me", id=mid, format="metadata",
            metadataHeaders=["From", "Subject", "Date"]).execute()
        h = _headers(msg)
        block = (f"- id={mid} date={h.get('date', '?')}\n"
                 f"  from: {redact(h.get('from', ''))}\n"
                 f"  subject: {redact(h.get('subject', ''))}\n"
                 f"  snippet: {redact(msg.get('snippet', ''))}")
        total += len(block)
        if total > 6000:
            lines.append("  (further results truncated)")
            break
        lines.append(block)
    return "\n".join(lines)


def gmail_read(task_dir, args, ctx=None):
    svc, reason = _service("gmail")
    if svc is None:
        return _not_connected(GMAIL_NOT_CONNECTED, reason)
    mid = str(args.get("message_id", "")).strip()
    if not mid:
        return "error: gmail_read needs a message_id (from gmail_search)"
    msg = svc.users().messages().get(
        userId="me", id=mid, format="full").execute()
    h = _headers(msg)
    body = _plain_body(msg) or msg.get("snippet", "")
    redacted_body = redact(body)
    lines = [f"GMAIL_READ_OK id={mid}",
             f"from: {redact(h.get('from', ''))}",
             f"subject: {redact(h.get('subject', ''))}",
             f"date: {h.get('date', '?')}",
             "body (redacted, first 500 chars — the only shape of "
             "mail content that may reach a model):",
             snippet(body, 500)]
    links = extract_links(redacted_body)
    if links:
        lines.append("links found in the message (host-extracted "
                     "from the redacted body; query strings stripped, "
                     "credential links dropped):")
        lines += [f"- {u}" for u in links]
    return "\n".join(lines)


def _write_gate(ctx, flag_key, scope, action):
    """Shared write gate for gmail_send / calendar_create.
    Returns an error string, or None when every gate passes.
    (The Sentinel has already checked the flag + approver shape;
    this is the handler-side defence in depth, and the only place
    the token's granted scopes can be checked.)"""
    ctx = ctx or {}
    if not ctx.get(flag_key):
        return (f"error: this write is disabled by default — the host "
                f"config flag for it ({flag_key}) is off, and enabling "
                "it is the user's decision on the host, never the "
                "model's")
    config = ctx.get("_config") or Config.from_env()
    approver = ctx.get("_approver")
    try:
        require_approval(config, action, approver,
                         detail="mail/calendar write via loop tool")
    except ApprovalRequired as e:
        return f"error: {e}"
    if scope not in granted_scopes():
        return (f"error: the connected Google token does not carry "
                f"the {scope} scope — writes need the user to "
                "re-run the one-time setup with the write scope "
                "explicitly requested (--google-connect with the "
                "matching --google-* flag)")
    return None


def gmail_send(task_dir, args, ctx=None):
    err = _write_gate(ctx, "gmail_send_enabled", GMAIL_SEND_SCOPE,
                      "send_email")
    if err:
        return err
    to = str(args.get("to", "")).strip()
    subject = str(args.get("subject", ""))
    body = str(args.get("body", ""))
    if not to or not subject:
        return "error: gmail_send needs 'to' and 'subject'"
    svc, reason = _service("gmail")
    if svc is None:
        return _not_connected(GMAIL_NOT_CONNECTED, reason)
    from email.message import EmailMessage
    msg = EmailMessage()
    msg["To"] = to
    msg["Subject"] = subject
    msg.set_content(body)
    raw = base64.urlsafe_b64encode(msg.as_bytes()).decode()
    resp = svc.users().messages().send(
        userId="me", body={"raw": raw}).execute()
    return f"GMAIL_SEND_OK id={resp.get('id', '?')}"


# ---------- Calendar handlers ----------

def calendar_list(task_dir, args, ctx=None):
    svc, reason = _service("calendar")
    if svc is None:
        return _not_connected(CALENDAR_NOT_CONNECTED, reason)
    try:
        days = max(1, min(int(args.get("days", 7)), 31))
    except (TypeError, ValueError):
        days = 7
    try:
        max_results = max(1, min(int(args.get("max_results", 20)), 50))
    except (TypeError, ValueError):
        max_results = 20
    now = datetime.now(timezone.utc)
    res = svc.events().list(
        calendarId="primary",
        timeMin=now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        timeMax=(now + timedelta(days=days)).strftime("%Y-%m-%dT%H:%M:%SZ"),
        maxResults=max_results, singleEvents=True,
        orderBy="startTime").execute()
    items = res.get("items", [])
    lines = [f"CALENDAR_LIST_OK events={len(items)} window_days={days}"]
    for ev in items:
        start = (ev.get("start", {}) or {}).get(
            "dateTime") or (ev.get("start", {}) or {}).get("date", "?")
        end = (ev.get("end", {}) or {}).get(
            "dateTime") or (ev.get("end", {}) or {}).get("date", "?")
        lines.append(
            f"- id={ev.get('id', '?')} start={start} end={end}\n"
            f"  summary: {redact(ev.get('summary', ''))}\n"
            f"  location: {redact(ev.get('location', ''))}")
    return "\n".join(lines)


def calendar_get(task_dir, args, ctx=None):
    svc, reason = _service("calendar")
    if svc is None:
        return _not_connected(CALENDAR_NOT_CONNECTED, reason)
    eid = str(args.get("event_id", "")).strip()
    if not eid:
        return "error: calendar_get needs an event_id (from calendar_list)"
    ev = svc.events().get(calendarId="primary", eventId=eid).execute()
    start = (ev.get("start", {}) or {}).get(
        "dateTime") or (ev.get("start", {}) or {}).get("date", "?")
    end = (ev.get("end", {}) or {}).get(
        "dateTime") or (ev.get("end", {}) or {}).get("date", "?")
    lines = [f"CALENDAR_GET_OK id={ev.get('id', eid)}",
             f"start: {start}", f"end: {end}",
             f"summary: {redact(ev.get('summary', ''))}",
             f"location: {redact(ev.get('location', ''))}",
             "description (redacted snippet):",
             snippet(ev.get("description", ""), 500)]
    return "\n".join(lines)


def calendar_create(task_dir, args, ctx=None):
    err = _write_gate(ctx, "calendar_write_enabled",
                      CALENDAR_WRITE_SCOPE, "create_calendar_event")
    if err:
        return err
    summary = str(args.get("summary", "")).strip()
    start = str(args.get("start", "")).strip()
    end = str(args.get("end", "")).strip()
    if not summary or not start or not end:
        return ("error: calendar_create needs 'summary', 'start' and "
                "'end' (ISO 8601 date-times, e.g. 2026-11-27T18:00:00Z)")
    svc, reason = _service("calendar")
    if svc is None:
        return _not_connected(CALENDAR_NOT_CONNECTED, reason)
    body = {"summary": summary,
            "start": {"dateTime": start}, "end": {"dateTime": end}}
    if args.get("description"):
        body["description"] = str(args["description"])
    if args.get("location"):
        body["location"] = str(args["location"])
    ev = svc.events().insert(calendarId="primary", body=body).execute()
    return f"CALENDAR_CREATE_OK id={ev.get('id', '?')}"


# ---------- one-time host setup ----------

def default_client_secrets_path():
    return os.environ.get(
        "FREEAGENT_GOOGLE_CLIENT_SECRETS",
        os.path.join(freeagent_home(), "client_secrets.json"))


def connect(with_send=False, with_calendar_write=False,
            client_secrets=None, flow_factory=None):
    """The one-time setup ONLY the user can do (consent in their
    own browser, on the host). Returns (ok, message). The token
    goes straight into the encrypted vault; its value is never
    printed, logged or returned."""
    path = client_secrets or default_client_secrets_path()
    if not os.path.isfile(path):
        return False, (
            "Google is not connected yet, and the OAuth client file "
            f"is missing: {path}\n"
            "One-time setup (only you can do this — it is your "
            "Google account):\n"
            "1. In Google Cloud Console, create a project (or use "
            "an existing one) and enable the Gmail API and the "
            "Google Calendar API.\n"
            "2. Create an OAuth client of type 'Desktop app' and "
            "download its JSON.\n"
            f"3. Save that JSON as {path} (or point "
            "FREEAGENT_GOOGLE_CLIENT_SECRETS at it).\n"
            "4. Re-run: python3 -m free_agent --google-connect\n"
            "The consent screen will list READ-ONLY scopes "
            "(gmail.readonly, calendar.readonly) unless you add "
            "--google-send / --google-calendar-write. The token is "
            "stored only in Lille's encrypted vault on this host.")
    scopes = list(READ_SCOPES)
    if with_send:
        scopes.append(GMAIL_SEND_SCOPE)
    if with_calendar_write:
        scopes.append(CALENDAR_WRITE_SCOPE)
    if flow_factory is not None:
        factory = flow_factory
    else:
        try:
            from google_auth_oauthlib.flow import InstalledAppFlow
        except ImportError:
            return False, f"Cannot connect: {_LIBS_HINT}"
        factory = InstalledAppFlow.from_client_secrets_file
    try:
        flow = factory(path, scopes)
        creds = flow.run_local_server(port=0)
    except Exception as e:
        return False, (f"Google consent did not complete "
                       f"({type(e).__name__}). Nothing was stored. "
                       "Re-run --google-connect to try again.")
    try:
        _vault().set(TOKEN_VAULT_KEY, creds.to_json())
    except VaultUnavailable as e:
        return False, (f"Consent completed but the token could NOT "
                       f"be stored: {e}. Nothing was saved anywhere "
                       "else.")
    return True, (
        "Google connected. Granted scopes: "
        + ", ".join(scopes)
        + ". The token is stored only in Lille's encrypted vault "
          "on this host; its value was never printed. Lille's mail "
          "and calendar tools can now work — reads only, unless a "
          "write scope above was explicitly requested AND its host "
          "config flag is enabled.")


def status():
    connected, reason, scopes = connection_state()
    if not connected:
        return ("Google: NOT connected (reason: "
                f"{reason}). One-time setup on the host: "
                "python3 -m free_agent --google-connect")
    return ("Google: connected. Granted scopes (names only): "
            + (", ".join(scopes) or "none recorded")
            + ". Token lives only in the encrypted vault.")


def disconnect():
    try:
        _vault().delete(TOKEN_VAULT_KEY)
    except VaultUnavailable as e:
        return f"Could not reach the vault: {e}"
    return ("Google disconnected: the token was deleted from "
            "Lille's encrypted vault. (To revoke Google's side too, "
            "remove Lille under your Google account's third-party "
            "access page.)")
