"""The tool-call loop (v0.3) — the rung that turns Lille from chat
into an agent: plan -> act -> observe, repeated until the task is
verified done, the step budget runs out, or the loop stops honestly
with its trace.

Tool protocol, two paths into ONE executor:
1. Native: the provider returns OpenAI-style `tool_calls` (the
   keyless GPT-OSS endpoint advertises tools support on the
   anonymous tier).
2. Text fallback: the model emits a fenced JSON block
   {"tool": "<name>", "args": {...}} in its reply text, which the
   loop parses. Models that cannot do native calls can still act.

Safety is NOT the model's job (DESIGN #4): every call passes the
deterministic Sentinel (tasktools.py) — allowlist, sandbox
confinement, approval hook, budgets — before anything executes.

Verification (DESIGN #6): a `finish` call that claims output files
is accepted only if those files actually exist in the sandbox. The
model's narration is never the evidence.

Fallback Law + refusal recovery (v0.2/v0.2.1) apply inside the loop:
model calls go through the Router (route chains, cooldowns, privacy
routing), and a phrasing refusal of the task gets ONE transparent
reframe + retry; harmful requests are never reframed.
"""
import ast, hashlib, json, os, re, time
from dataclasses import dataclass, field

from . import recovery, sandbox, selffacts, tasktools
from .config import Config
from .router import Router, PrivacyBlocked, AllProvidersFailed

DEFAULT_MAX_STEPS = 12
DEFAULT_MAX_TOTAL_CHARS = 200_000

SYSTEM_PROMPT = """You are Lille, an autonomous agent completing a task by calling tools.

Rules:
- Work step by step: call ONE tool (or a few independent ones) per reply, observe the result, then continue.
- Prefer native tool calls. If you cannot emit native tool calls, emit exactly one fenced JSON block like:
```json
{"tool": "write_file", "args": {"path": "main.py", "content": "..."}}
```
- All files live in your task sandbox; use relative paths only.
- When the task is fully done, call the finish tool with a summary and list every output file you produced in "outputs" — claimed outputs are verified against the sandbox: a claimed file must exist AND have been created or changed by this run's own steps, so do not claim files you did not actually write.
- If you say you verified something, an execution step (run_python / run_tests) must actually follow your last change to it — a finish claiming verification without one is rejected.
- If a tool result is an error, read it, fix the cause, and try again. Do not repeat an identical failing call.
- Never invent results: only claim what tool outputs actually showed. Values a tool printed outrank your recollection — report them exactly as printed, never override them with your own calculation.
- Browser tasks: work from browser_read's element refs (refs are reassigned on every read — re-read after each navigation). Passwords are NEVER typed by you or written into any file: create them with vault_gen_secret (you receive only a key name) and type them with browser_type_secret. If any tool result starts with CHALLENGE, the page is a human-only wall (CAPTCHA, an emailed code, a phone demand): STOP immediately and call finish with a summary naming that exact wall — never try to solve, bypass, or route around it.
- Mail/calendar tasks: if a gmail/calendar tool returns a *_NOT_CONNECTED result, Google is not set up on this host — STOP and call finish naming the single setup step it describes. Never ask the user to fetch, copy or paste mail or calendar contents for you: reaching the mailbox yourself is the point of the tool. gmail_send / calendar_create denials are correct-by-default; never work around one.
""" + "\n\n" + selffacts.facts_block()

_FENCE_RE = re.compile(r"```(?:json)?\s*(.*?)```", re.S)
_TOOLCALL_XML_RE = re.compile(
    r"<tool_call>\s*([A-Za-z_][\w.-]*)\s*(.*?)</tool_call>", re.S)
_ARG_PAIR_RE = re.compile(
    r"<arg_key>(.*?)</arg_key>\s*<arg_value>(.*?)</arg_value>", re.S)

@dataclass
class Step:
    n: int
    kind: str            # tool | finish | finish-rejected | sentinel-denied |
                         # malformed | repair | refusal-reframe | text | stop
    tool: str = ""
    args_summary: str = ""
    result_summary: str = ""

@dataclass
class TaskResult:
    status: str          # done | stopped | error
    summary: str
    task_dir: str
    task_id: str
    steps: list = field(default_factory=list)
    provider: str = None

    def trace_text(self):
        lines = [f"task {self.task_id} [{self.status}] dir={self.task_dir}"]
        for s in self.steps:
            lines.append(f"  step {s.n}: {s.kind} {s.tool} {s.args_summary}"
                         f" -> {s.result_summary}".rstrip())
        lines.append(f"summary: {self.summary}")
        return "\n".join(lines)

def _summarise(text, limit=300):
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[:limit] + "…"

_FINISH_CALL_RE = re.compile(r"^finish\s*\(")

def _split_call_args(inner):
    """Split a call's argument text on top-level commas,
    respecting quotes and bracket nesting."""
    parts, depth, quote, esc, cur = [], 0, None, False, []
    for ch in inner:
        if esc:
            cur.append(ch)
            esc = False
            continue
        if quote:
            cur.append(ch)
            if ch == "\\":
                esc = True
            elif ch == quote:
                quote = None
            continue
        if ch in "\"'":
            quote = ch
            cur.append(ch)
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    tail = "".join(cur).strip()
    if tail:
        parts.append(tail)
    return parts

def _literal(text):
    """Parse one call argument: a Python literal first (the
    dialect is Python call syntax), then JSON."""
    try:
        return ast.literal_eval(text), True
    except Exception:
        pass
    try:
        return json.loads(text), True
    except Exception:
        return text, False

def _parse_finish_call(content):
    """Recover finish(...) emitted as literal text — the
    schedule-a / format-a dialect from the 1.0.13 battery:
    the model's whole reply is a Python-style call, e.g.
    finish("summary", ["out.txt"]) or finish({...}, []).
    Anchored at the reply's start: a finish(...) mentioned
    inside narration (code-b's confabulated shell session)
    is NOT a call and is never recovered."""
    s = (content or "").strip()
    if not _FINISH_CALL_RE.match(s):
        return None
    open_idx = s.index("(")
    depth, quote, esc, close_idx = 0, None, False, -1
    for i in range(open_idx, len(s)):
        ch = s[i]
        if esc:
            esc = False
            continue
        if quote:
            if ch == "\\":
                esc = True
            elif ch == quote:
                quote = None
            continue
        if ch in "\"'":
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                close_idx = i
                break
    if close_idx < 0:
        return None
    inner = s[open_idx + 1:close_idx]
    summary, outputs = None, []
    for part in _split_call_args(inner):
        m = re.match(r"^([A-Za-z_]\w*)\s*=\s*(.*)$", part, re.S)
        key, raw = (m.group(1), m.group(2)) if m else (None, part)
        val, ok = _literal(raw.strip())
        if key == "outputs" or (key is None and isinstance(val, list)):
            if isinstance(val, list):
                outputs = [str(x) for x in val]
        elif key == "summary" or (key is None and summary is None):
            if isinstance(val, dict):
                if "summary" in val:
                    summary = str(val.get("summary", ""))
                    o = val.get("outputs")
                    if isinstance(o, list):
                        outputs = [str(x) for x in o]
                else:
                    # format-a's shape: the deliverable itself
                    # rode in as the first argument — it IS
                    # the answer the task asked for.
                    summary = json.dumps(val)
            elif isinstance(val, str):
                summary = val
            elif ok:
                summary = json.dumps(val)
    if summary is None:
        return None
    return {"name": "finish",
            "args": {"summary": summary, "outputs": outputs}}

def parse_text_tool_call(content):
    """Text-fallback protocol. Returns (call, error):
    call = {"name":..., "args": {...}} or None; error = str when the
    reply clearly TRIED to make a tool call but it was malformed."""
    if not content:
        return None, None
    # Dialect (1.0.13 battery, schedule-a/format-a): finish(...)
    # emitted as literal call-syntax text instead of a tool
    # call. Recovered as the call — spec mode's grading never
    # needed it (it grades the trace), the plain loop did.
    fc = _parse_finish_call(content)
    if fc:
        return fc, None
    # Dialect seen live (2026-11-26, AI Horde community models on
    # the loop path): an XML-ish tool call —
    #   <tool_call>name<arg_key>k</arg_key><arg_value>v</arg_value></tool_call>
    # It cost a whole spec run before it was recognised (three
    # well-formed calls read as plain text; the nudge budget ran
    # out). Values that parse as JSON (arrays, objects, numbers)
    # are decoded; anything else stays a string.
    tm = _TOOLCALL_XML_RE.search(content)
    if tm:
        pairs = _ARG_PAIR_RE.findall(tm.group(2))
        if pairs or not tm.group(2).strip():
            args = {}
            for k, v in pairs:
                v = v.strip()
                try:
                    args[k.strip()] = json.loads(v)
                except ValueError:
                    args[k.strip()] = v
            return {"name": tm.group(1), "args": args}, None
    m = _FENCE_RE.search(content)
    candidate = None
    if m:
        candidate = m.group(1).strip()
    elif content.strip().startswith("{") and '"tool"' in content:
        candidate = content.strip()
    if candidate is None:
        return None, None
    try:
        obj = json.loads(candidate)
    except ValueError as e:
        return None, f"JSON did not parse: {e}"
    if not isinstance(obj, dict):
        return None, "JSON block is not an object"
    if "tool" not in obj:
        # Dialect seen live (v0.5 proof): the model emits the finish
        # tool's ARGS as bare JSON ({"summary": ..., "outputs": [...]})
        # with no wrapper. Treat exactly that shape as a finish call.
        if "summary" in obj and ("outputs" in obj or len(obj) <= 3):
            return {"name": "finish", "args": obj}, None
        return None, "JSON block has no 'tool' field"
    args = obj.get("args", {})
    if not isinstance(args, dict):
        return None, "'args' is not a JSON object"
    return {"name": str(obj["tool"]), "args": args}, None

# ---------- grounding: tool output outranks narration ----------
# (v1.0.14, battery fixes 4 + 6.) schedule-b: her run_python
# printed 2026-12-11; her text answered "Friday, 14 January
# 2027". verify-a/b: her summaries presented invented hosts
# for her own product. Both are the same shape — a claim in
# the report that the trace's evidence contradicts or never
# supported. The checks below run at finish acceptance: dates
# are judged against EXECUTION evidence only (run_python /
# run_tests output — a value the machine computed, not text
# a search snippet carried), and URLs against all tool
# evidence plus her canonical sources (selffacts).

_MONTHS = {name: i + 1 for i, name in enumerate([
    "january", "february", "march", "april", "may", "june",
    "july", "august", "september", "october", "november",
    "december"])}
_ISO_DATE_RE = re.compile(r"\b(\d{4})-(\d{2})-(\d{2})\b")
_LONG_DATE_RE = re.compile(
    r"(?:\b(?:monday|tuesday|wednesday|thursday|friday|"
    r"saturday|sunday)\s*,?\s+)?\b(\d{1,2})\s+"
    r"(january|february|march|april|may|june|july|august|"
    r"september|october|november|december)\s+(\d{4})\b",
    re.I)
_LONG_DATE_US_RE = re.compile(
    r"(?:\b(?:monday|tuesday|wednesday|thursday|friday|"
    r"saturday|sunday)\s*,?\s+)?\b"
    r"(january|february|march|april|may|june|july|august|"
    r"september|october|november|december)\s+(\d{1,2})"
    r"(?:st|nd|rd|th)?\s*,?\s+(\d{4})\b",
    re.I)
_URL_RE = re.compile(r"https?://[^\s)\]<>\"']+")

# Tools whose output is EXECUTION evidence: the machine ran
# code and printed the value. Only these can outrank a claim.
EXECUTION_TOOLS = ("run_python", "run_tests")


def extract_dates(text):
    """All dates a text asserts, as (start, end, iso) spans —
    ISO and long forms, weekday prefix included in the span
    so a correction replaces the whole assertion."""
    out = []
    text = text or ""
    for m in _ISO_DATE_RE.finditer(text):
        out.append((m.start(), m.end(),
                    f"{m.group(1)}-{m.group(2)}-{m.group(3)}"))
    for m in _LONG_DATE_RE.finditer(text):
        iso = (f"{m.group(3)}-{_MONTHS[m.group(2).lower()]:02d}"
               f"-{int(m.group(1)):02d}")
        out.append((m.start(), m.end(), iso))
    for m in _LONG_DATE_US_RE.finditer(text):
        iso = (f"{m.group(3)}-{_MONTHS[m.group(1).lower()]:02d}"
               f"-{int(m.group(2)):02d}")
        out.append((m.start(), m.end(), iso))
    out.sort()
    # Drop spans contained in an earlier span (weekday forms
    # can double-match their own tail).
    deduped = []
    for span in out:
        if deduped and span[0] >= deduped[-1][0] \
                and span[1] <= deduped[-1][1]:
            continue
        deduped.append(span)
    return deduped


def _observation_texts(messages, tools=None):
    """The tool observations in a message history, optionally
    restricted to named tools. Native turns resolve through
    the assistant tool_calls' ids — walked IN ORDER, so an
    observation is attributed to the call that preceded it
    even if a later turn reuses an id; text-mode observations
    carry the tool name in their prefix."""
    id_to_name = {}
    out = []
    for m in messages:
        for tc in m.get("tool_calls") or []:
            fn = tc.get("function") or {}
            if tc.get("id"):
                id_to_name[tc["id"]] = fn.get("name", "")
        content = m.get("content")
        if not isinstance(content, str):
            continue
        if m.get("role") == "tool":
            name = id_to_name.get(m.get("tool_call_id", ""), "")
            if tools is None or name in tools:
                out.append(content)
        elif m.get("role") == "user" \
                and content.startswith("Tool result ("):
            name = content[len("Tool result ("):].split(")", 1)[0]
            if tools is None or name in tools:
                out.append(content)
    return out


def verified_dates(messages):
    """Dates an execution tool printed in this run — the
    values a report must use when it speaks of them."""
    found = set()
    for obs in _observation_texts(messages, EXECUTION_TOOLS):
        for _, _, iso in extract_dates(obs):
            found.add(iso)
    return found


def date_contradiction(summary, messages):
    """(asserted_spans, verified) when the summary asserts
    date(s) and NONE of them is a date an execution tool
    verified, while such a verified date exists — the
    schedule-b shape. None when there is nothing to judge
    or the summary agrees with the evidence."""
    verified = verified_dates(messages)
    if not verified:
        return None
    asserted = extract_dates(summary or "")
    if not asserted:
        return None
    if any(iso in verified for _, _, iso in asserted):
        return None
    return asserted, verified


def correct_dates(summary, verified):
    """Substitute the single verified date for the summary's
    contradicted assertions. Only defined when exactly one
    verified date exists — otherwise there is no honest
    mechanical correction and the caller must not close."""
    if len(verified) != 1:
        return None
    target = next(iter(verified))
    spans = extract_dates(summary or "")
    if not spans:
        return None
    out, last = [], 0
    for start, end, _ in spans:
        out.append(summary[last:start])
        out.append(target)
        last = end
    out.append(summary[last:])
    return "".join(out)


def ungrounded_urls(summary, messages):
    """URLs the summary presents that appear in NO tool
    observation and are not among her canonical sources —
    invented addresses (verify-a/b's lille.dev/download,
    fluidnames.com). Only http(s) URLs are judged."""
    evidence = "\n".join(_observation_texts(messages))
    evidence_hosts = set()
    for m in _URL_RE.finditer(evidence):
        evidence_hosts.add(
            m.group(0).split("://", 1)[-1].split("/", 1)[0].lower())
    bad = []
    for m in _URL_RE.finditer(summary or ""):
        url = m.group(0).rstrip(".,;")
        host = url.split("://", 1)[-1].split("/", 1)[0].lower()
        if host in evidence_hosts or host in selffacts.CANONICAL_HOSTS:
            continue
        bad.append(url)
    return bad


class WaitingRouteError(RuntimeError):
    """All routes are cooling down (Fallback Law). NOT a failure:
    the task waits and resumes at/after retry_at. The message
    is user-visible (it lands in the task record), so it is
    plain by law (seamless, 2026-11-26): no route names, no
    mechanics — the per-route facts live in the router audit."""
    def __init__(self, retry_at=None, message="waiting — continues automatically"):
        super().__init__(message)
        self.retry_at = retry_at

class WaitingNetworkError(RuntimeError):
    """Transport/network failure reaching every route. NOT a failure:
    the task waits and resumes at/after retry_at."""
    def __init__(self, retry_at=None, message="network unreachable"):
        super().__init__(message)
        self.retry_at = retry_at

class TaskPausedSignal(BaseException):
    """Raised inside the loop when the durable store says pause/cancel.
    BaseException so ordinary error handling cannot swallow it."""

class TaskLoop:
    def __init__(self, config=None, model_call=None, workspace_root=None,
                 max_steps=DEFAULT_MAX_STEPS, approver=None,
                 max_total_chars=DEFAULT_MAX_TOTAL_CHARS, clock=None,
                 pace_seconds=None, on_checkpoint=None, control_check=None,
                 sentinel_context=None, finish_guard=None,
                 tool_names=None, task_brief=None):
        """model_call(messages, tools) -> {"content": str|None,
        "tool_calls": [{"id","name","args"}] | None, "provider": str}
        When omitted, one is built on the Router (Fallback Law)."""
        self.workspace_root = workspace_root or os.environ.get(
            "FREEAGENT_WORKSPACE", os.path.expanduser("~/.freeagent/tasks"))
        self.max_steps = max_steps
        self.max_total_chars = max_total_chars
        # v0.13 mail/calendar write gates: the Sentinel reads the
        # write flags from its spec context, so the loop injects
        # them from the host Config (env flags — the user's
        # decision on the host, never the model's) unless the
        # caller set them explicitly. The runtime context also
        # carries the Config + approver so the mail/calendar
        # handlers can run their defence-in-depth gate
        # (approvals.require_approval + token-scope check).
        ctx = dict(sentinel_context or {})
        cfg = config or Config.from_env()
        ctx.setdefault("gmail_send_enabled",
                       bool(cfg.gmail_send_enabled))
        ctx.setdefault("calendar_write_enabled",
                       bool(cfg.calendar_write_enabled))
        self.sentinel = tasktools.Sentinel(approver=approver,
                                           spec_context=ctx)
        runtime_ctx = dict(ctx)
        runtime_ctx["_config"] = cfg
        runtime_ctx["_approver"] = approver
        tasktools.set_runtime_context(runtime_ctx)
        self.clock = clock or time.time
        # Pacing: the keyless anonymous tier allows ~1 request per
        # ~45s per IP. pace_seconds (or FREEAGENT_PACE_SECONDS) makes
        # the loop wait between model calls instead of tripping the
        # limiter and burning routes on 402s.
        if pace_seconds is None:
            pace_seconds = float(os.environ.get("FREEAGENT_PACE_SECONDS", "0") or 0)
        self.pace_seconds = pace_seconds
        self._last_call_ts = 0.0
        # v0.4 durable tasks: on_checkpoint(state_dict) is called after
        # EVERY step (and before the first) so a killed process loses
        # at most the in-flight model call; control_check() returns
        # "run" | "pause" | "cancel" and is consulted between steps.
        self.on_checkpoint = on_checkpoint
        # v0.8 payload discipline: a long task text (a spec with
        # an identity kit + rules runs ~4 KB) is re-sent on EVERY
        # model call and sets the wire floor compaction cannot
        # go below — attempt 17 died of a 413 at step 5 with the
        # floor above the provider's limit. When a brief is
        # given, the full text is used for the first call only;
        # later calls carry the brief (goal + rules + criteria),
        # and the full text stays on disk in the task dir.
        self.task_brief = task_brief
        self.control_check = control_check
        # Per-task tool subsetting (v0.8): the keyless endpoint
        # rejects oversized bodies (HTTP 413); a spec run sends
        # only the schemas its spec calls for. None = full set.
        self.tool_names = tool_names
        # v0.5 spec mode: optional deterministic finish guard —
        # callable(steps_so_far, finish_args) -> rejection str | None.
        self.finish_guard = finish_guard
        if model_call is not None:
            self.model_call = model_call
        else:
            router = Router(config)
            self.model_call = self._router_model_call(router)

    # ---------- default model call: Router-backed ----------
    @staticmethod
    def _router_model_call(router):
        def call(messages, tools):
            try:
                out = router.complete(messages, tools=tools, category="task")
            except AllProvidersFailed as e:
                # Distinguish "network down" (every attempt status 0)
                # from a real provider failure: both are WAITING
                # states for a durable task, never silent failures.
                # The waiting messages are PLAIN (seamless law):
                # the provider detail stays in the exception and
                # the audit, never in the record's user-visible text.
                text = str(e)
                now = time.time()
                unlocks = [u for u in router.cooldowns.values() if u > now]
                retry_at = min(unlocks) if unlocks else now + 60
                if ", 0)" in text or text.rstrip().endswith(", 0)]"):
                    raise WaitingNetworkError(retry_at, "network unreachable")
                if unlocks:
                    raise WaitingRouteError(retry_at,
                                            "waiting — continues automatically")
                raise
            if out.get("queued"):
                raise WaitingRouteError(out.get("retry_at"),
                                        out.get("text", "waiting — continues automatically"))
            msg = out["message"]
            calls = []
            for tc in msg.get("tool_calls") or []:
                fn = tc.get("function", {})
                raw = fn.get("arguments", "{}")
                try:
                    args = json.loads(raw) if isinstance(raw, str) else (raw or {})
                    malformed = None
                except ValueError as e:
                    args, malformed = {}, f"native tool_call arguments did not parse: {e}"
                calls.append({"id": tc.get("id", ""), "name": fn.get("name", ""),
                              "args": args, "malformed": malformed})
            return {"content": msg.get("content"), "tool_calls": calls or None,
                    "provider": out.get("provider")}
        return call

    # Plain-text replies get this many nudges before the run
    # stops. One was too few for long browser work: the 20B
    # brain narrates a discouragement, gets nudged, and very
    # often recovers into productive tool steps (v0.8 X proof —
    # attempts repeatedly ended one nudge after a wobble).
    MAX_PLAIN_TEXT_NUDGES = 3

    # History budget for the model's view (v0.8): the keyless
    # anonymous endpoint 413s oversized request bodies, and a long
    # browser run accumulates an observation per step. Older
    # observations are compacted to stubs in the model's view —
    # the on-disk trace always keeps the full record.
    HISTORY_BUDGET_CHARS = 9500
    KEEP_FULL_OBSERVATIONS = 1

    @staticmethod
    def _compact_history(messages):
        def size(m):
            c = m.get("content")
            n = len(c) if isinstance(c, str) else 0
            if m.get("tool_calls"):
                # Assistant tool-call messages carry their
                # arguments on the wire too — count them, or the
                # budget lies (v0.8 X attempt 2 died of 413 with
                # the budget reporting headroom).
                n += len(json.dumps(m["tool_calls"]))
            return n

        total = sum(size(m) for m in messages)
        if total <= TaskLoop.HISTORY_BUDGET_CHARS:
            return
        obs_idx = [i for i, m in enumerate(messages)
                   if m.get("role") == "tool"
                   or (m.get("role") == "user"
                       and isinstance(m.get("content"), str)
                       and m["content"].startswith("Tool result ("))]
        for i in obs_idx[:-TaskLoop.KEEP_FULL_OBSERVATIONS] or []:
            c = messages[i].get("content") or ""
            if len(c) <= 190:
                continue
            messages[i] = dict(
                messages[i],
                content=c[:130] + " …[compacted — full result is in "
                                  "the task trace]")
            total -= len(c) - len(messages[i]["content"])
            if total <= TaskLoop.HISTORY_BUDGET_CHARS:
                break
        if total > TaskLoop.HISTORY_BUDGET_CHARS:
            # Still over: stub OLD assistant prose. The 20B brain
            # narrates as it works; ten steps of narration alone
            # crossed the keyless body limit in the v0.8 X proof
            # (attempt 7 died at step 10 with every observation
            # already stubbed). Recent replies stay full.
            for m in messages[2:-3]:
                if total <= TaskLoop.HISTORY_BUDGET_CHARS:
                    break
                c = m.get("content")
                if m.get("role") == "assistant" \
                        and isinstance(c, str) and len(c) > 220:
                    m["content"] = (c[:160] + " …[earlier reply "
                                    "compacted]")
                    total -= len(c) - len(m["content"])
        if total > TaskLoop.HISTORY_BUDGET_CHARS:
            # Still over: shrink OLD assistant tool-call arguments
            # (the results already carry the outcomes; the trace
            # keeps the true record). Pairing stays valid — the
            # call ids and names are untouched.
            for m in messages[2:-4]:
                if total <= TaskLoop.HISTORY_BUDGET_CHARS:
                    break
                for tc in m.get("tool_calls") or []:
                    fn = tc.get("function") or {}
                    args = fn.get("arguments")
                    if isinstance(args, str) and len(args) > 60:
                        total -= len(args) - 2
                        fn["arguments"] = "{}"

    # Hard wire cap for the keyless route (v0.8): the anonymous
    # endpoint accepted a 12,081-byte body and rejected 16,102
    # bytes in direct probes. Content-char budgets kept lying
    # (JSON structure, roles and tool-call payloads ride along),
    # so the loop now measures the actual request body and
    # compacts until it fits — the trace keeps the full record.
    WIRE_CAP_BYTES = 14800

    @staticmethod
    def _wire_size(messages, schemas):
        body = {"model": "openai-fast", "messages": messages,
                "tools": schemas or [], "tool_choice": "auto"}
        return len(json.dumps(body))

    @staticmethod
    def _compact_aggressive(messages):
        """Second-stage compaction, driven by the measured wire
        size: stub ALL assistant prose beyond the last two
        messages, shrink every older tool-call's arguments, and
        truncate the newest observation if it alone is huge."""
        for m in messages[2:-2]:
            c = m.get("content")
            if m.get("role") == "assistant" \
                    and isinstance(c, str) and len(c) > 140:
                m["content"] = c[:120] + " …[compacted]"
            for tc in m.get("tool_calls") or []:
                fn = tc.get("function") or {}
                args = fn.get("arguments")
                if isinstance(args, str) and len(args) > 60:
                    fn["arguments"] = "{}"
        # Truncate the newest long OBSERVATION — never the task
        # itself. The first user message IS the job (for spec runs
        # it carries the whole copy-exact work plan); cutting it to
        # 2400 chars silently amputates the plan mid-run — the
        # model then improvises calls with empty arguments because
        # the instructions literally left its view (root cause of
        # the 2026-11-26 login-walkthrough attempts 2-3 failures:
        # call 1 fit under the cap and ran perfectly; from call 2
        # the added history crossed it and the plan was cut).
        first_user = next((i for i, m in enumerate(messages)
                           if m.get("role") == "user"), None)
        for j in range(len(messages) - 1, -1, -1):
            if j == first_user:
                continue
            m = messages[j]
            if m.get("role") in ("tool", "user") \
                    and isinstance(m.get("content"), str) \
                    and len(m["content"]) > 2400:
                m["content"] = (m["content"][:2400] +
                                " …[truncated to fit the route's "
                                "request limit]")
                break

    @staticmethod
    def _trim_history(messages, schemas, cap):
        """Last resort for long runs: DELETE the oldest
        assistant+result groups from the model's view. Stub
        residue grows ~400 bytes/step no matter how tight the
        stubs are (v0.8 X attempt 9 died at step 17 fully
        stubbed); a 70-step browser run fits only if old groups
        leave. The page holds the live state (she re-reads it —
        refs are per-read anyway) and the on-disk trace keeps
        the full record. The last three groups always stay."""
        def groups():
            idx, cur = [], []
            for i in range(2, len(messages)):
                if messages[i].get("role") == "assistant" and cur:
                    idx.append(cur)
                    cur = []
                cur.append(i)
            if cur:
                idx.append(cur)
            return idx

        while TaskLoop._wire_size(messages, schemas) > cap:
            gs = groups()
            if len(gs) <= 3:
                return
            drop = set(gs[0])
            messages[:] = [m for i, m in enumerate(messages)
                           if i not in drop]

    def _call_model(self, messages):
        self._compact_history(messages)
        if self.pace_seconds > 0 and self._last_call_ts:
            wait = self.pace_seconds - (time.time() - self._last_call_ts)
            if wait > 0:
                time.sleep(wait)
        self._last_call_ts = time.time()
        schemas = tasktools.schemas_for(self.tool_names)
        if self.tool_names is not None:
            # Spec runs ride the keyless route: send the
            # description-stripped schemas (see compact_schemas).
            schemas = tasktools.compact_schemas(schemas)
            if self._wire_size(messages, schemas) > self.WIRE_CAP_BYTES:
                self._compact_aggressive(messages)
            if self._wire_size(messages, schemas) > self.WIRE_CAP_BYTES:
                self._trim_history(messages, schemas,
                                   self.WIRE_CAP_BYTES)
        return self.model_call(messages, schemas)

    # ---------- sandbox state: baseline / produced ----------
    # (v1.0.14, battery fixes 1 + 2.) Verify-by-state needs to
    # know what THIS run actually did: a fingerprint snapshot
    # of the sandbox at run start (the baseline — fixtures a
    # task was handed are in it), refreshed after every tool
    # step. `produced` = files new or changed against the
    # baseline; `changed_step` = the step that last changed
    # each. .git internals are not work products and are
    # never fingerprinted.
    _HASH_CAP = 8 * 1024 * 1024

    @staticmethod
    def _fingerprint(path):
        try:
            st = os.stat(path)
        except OSError:
            return None
        if st.st_size > TaskLoop._HASH_CAP:
            return f"{st.st_size}:{st.st_mtime_ns}"
        try:
            h = hashlib.sha256()
            with open(path, "rb") as f:
                for chunk in iter(lambda: f.read(65536), b""):
                    h.update(chunk)
            return h.hexdigest()
        except OSError:
            return None

    @staticmethod
    def _snapshot(task_dir):
        out = {}
        for root, dirs, files in os.walk(task_dir):
            dirs[:] = sorted(d for d in dirs if d != ".git")
            for fn in sorted(files):
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, task_dir)
                fp = TaskLoop._fingerprint(full)
                if fp is not None:
                    out[rel] = fp
        return out

    def _refresh_produced(self, state, step_no):
        """Diff the sandbox against the baseline after a tool
        step: refresh `produced` and stamp the step that last
        changed each produced file."""
        current = self._snapshot(state["task_dir"])
        baseline = state.get("baseline") or {}
        last_seen = state.get("_last_fp") or {}
        produced = state.setdefault("produced", [])
        changed_step = state.setdefault("changed_step", {})
        for rel, fp in current.items():
            if baseline.get(rel) != fp and rel not in produced:
                produced.append(rel)
            if last_seen.get(rel) != fp \
                    and baseline.get(rel) != fp:
                # This step is the latest one to change it.
                changed_step[rel] = step_no
        state["_last_fp"] = current
        produced.sort()

    def _derive_browser_domains(self, state):
        """Fix 5 (v1.0.14): the core Sentinel's browser
        allowlist is derived from the job's own words, as the
        device runner has done since v1.0.3 — a spec context
        that already names domains keeps them; the derivation
        only adds what the job itself authorises."""
        existing = self.sentinel.spec_context.get(
            "browser_domains") or []
        derived = tasktools.derive_browser_domains(
            state["task_text"])
        merged = list(existing) + [d for d in derived
                                   if d not in existing]
        if merged:
            self.sentinel.spec_context["browser_domains"] = merged
        state["browser_domains"] = merged

    # ---------- checkpointing (v0.4 durable tasks) ----------
    def _checkpoint(self, state):
        if self.on_checkpoint is None:
            return
        snap = {
            "task_id": state["task_id"], "task_text": state["task_text"],
            "task_dir": state["task_dir"],
            "messages": state["messages"],
            "steps": [vars(s) for s in state["steps"]],
            "iterations_done": state["iterations_done"],
            "total_chars": state["total_chars"],
            "reframed": state["reframed"], "nudged": state["nudged"],
            "provider": state.get("provider"),
            # v1.0.14 finish-landing state: the baseline the
            # produced-diff judges against, the pending finish
            # (her own completion statement, applicable without
            # a model call), and the resume steer flag.
            "baseline": state.get("baseline"),
            "baseline_late": state.get("baseline_late", False),
            "produced": state.get("produced", []),
            "changed_step": state.get("changed_step", {}),
            "pending_finish": state.get("pending_finish"),
            "resume_steered": state.get("resume_steered", False),
            "browser_domains": state.get("browser_domains", []),
            "ts": self.clock(),
        }
        try:
            self.on_checkpoint(snap)
        except Exception:
            pass  # checkpointing must never kill the loop itself

    def _control(self):
        if self.control_check is None:
            return "run"
        try:
            return self.control_check() or "run"
        except Exception:
            return "run"

    # ---------- the loop ----------
    def run(self, task_text, task_id=None):
        if task_id is None:
            task_id, task_dir = sandbox.make_task_dir(self.workspace_root)
        else:
            task_dir = os.path.join(self.workspace_root, task_id)
            os.makedirs(task_dir, exist_ok=True)
        baseline = self._snapshot(task_dir)
        state = {
            "task_id": task_id, "task_text": task_text, "task_dir": task_dir,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": task_text},
            ],
            "steps": [], "iterations_done": 0, "total_chars": 0,
            "reframed": False, "nudged": 0, "provider": None,
            # v1.0.14: verify-by-state + finish-landing state.
            "baseline": baseline, "baseline_late": False,
            "produced": [], "changed_step": {},
            "pending_finish": None, "resume_steered": False,
            "_last_fp": dict(baseline),
        }
        self._derive_browser_domains(state)
        return self._execute(state)

    def resume(self, snap):
        """Continue a checkpointed task in THIS (new) process. Step
        numbering continues from the checkpoint — completed steps are
        never repeated because the full message state is restored."""
        state = {
            "task_id": snap["task_id"], "task_text": snap["task_text"],
            "task_dir": snap["task_dir"], "messages": snap["messages"],
            "steps": [Step(**s) for s in snap.get("steps", [])],
            "iterations_done": snap.get("iterations_done", 0),
            "total_chars": snap.get("total_chars", 0),
            "reframed": snap.get("reframed", False),
            "nudged": snap.get("nudged", 0),
            "provider": snap.get("provider"),
            # v1.0.14: a pre-1.0.14 checkpoint has no baseline —
            # capture one now and mark it late (the produced
            # check then stands down for this run: files the
            # earlier segment produced are indistinguishable
            # from fixtures, and existence still applies).
            "baseline": snap.get("baseline"),
            "baseline_late": snap.get("baseline") is None,
            "produced": list(snap.get("produced", []) or []),
            "changed_step": dict(snap.get("changed_step", {}) or {}),
            "pending_finish": snap.get("pending_finish"),
            "resume_steered": snap.get("resume_steered", False),
        }
        if state["baseline"] is None:
            state["baseline"] = self._snapshot(state["task_dir"])
        state["_last_fp"] = self._snapshot(state["task_dir"])
        self._derive_browser_domains(state)
        # ROUTE RECOVERY RESUMES TO FINISH (v1.0.14): if the
        # interrupted segment had already stated completion in
        # her own words with work products on disk, the resume
        # closes the record from that state — no model call is
        # spent re-deciding what she already decided.
        if state["pending_finish"]:
            done = self._try_deterministic_finish(state)
            if done is not None:
                self._persist_trace(done)
                return done
            # The pending finish no longer verifies (outputs
            # gone, guard or grounding refuses): clear it and
            # let the loop work — never close on stale words.
            state["pending_finish"] = None
        return self._execute(state)

    # ---------- finish acceptance (v1.0.14) ----------
    _CODE_EXTS = (".py", ".js", ".ts", ".sh", ".rb", ".go",
                  ".rs", ".java", ".kt", ".php")

    def _check_finish(self, state, args, deterministic=False):
        """Verify a finish claim against state before the
        completed state is written. Returns (rejection,
        corrected_summary): rejection names the contradicting
        evidence; corrected_summary is set only for a
        deterministic close whose sole defect is a date the
        execution evidence contradicts (exactly one verified
        date exists to put in its place — the report uses
        the tool's value)."""
        summary = args.get("summary", "") or ""
        outputs = args.get("outputs", []) or []
        # (fix 2) Claimed outputs must be THIS run's work:
        # created or changed against the run-start baseline.
        # code-b claimed a cap fix on a fixture her trace
        # never wrote; existence alone let it through.
        if outputs and not state.get("baseline_late"):
            produced = set(state.get("produced", []))
            unproduced = [rel for rel in outputs
                          if rel not in produced]
            if unproduced:
                return (f"claimed output(s) {unproduced} were not "
                        "created or changed by any step in this "
                        "run — they are identical to the state "
                        "the task started from. A completion "
                        "claim must rest on work this run "
                        "actually did: make the change with a "
                        "tool, verify it, then finish."), None
            # (fix 2) A verification claim needs an execution
            # step AFTER the last change to the claimed file.
            if re.search(r"verif", summary, re.I):
                exec_steps = [s.n for s in state["steps"]
                              if s.kind == "tool"
                              and s.tool in EXECUTION_TOOLS]
                changed = state.get("changed_step", {})
                for rel in outputs:
                    if rel.lower().endswith(self._CODE_EXTS):
                        last_change = changed.get(rel, 0)
                        if not any(n > last_change
                                   for n in exec_steps):
                            return (
                                f"you claim verification, but no "
                                f"run_python/run_tests step "
                                f"follows your last change to "
                                f"{rel} (step {last_change}). Run "
                                "it, read its output, then "
                                "finish."), None
        # (fix 6) Tool-verified dates outrank the summary's.
        contra = date_contradiction(summary, state["messages"])
        if contra:
            _, verified = contra
            if deterministic:
                corrected = correct_dates(summary, verified)
                if corrected is not None:
                    return None, corrected
            return (f"your summary's date(s) contradict the "
                    f"value your own tool verified in this run "
                    f"({', '.join(sorted(verified))}). Report "
                    "the tool's value exactly."), None
        # (fix 4) Addresses for her own product must be real:
        # grounded in tool evidence or her canonical sources.
        bad_urls = ungrounded_urls(summary, state["messages"])
        if bad_urls:
            return (f"your summary presents address(es) that "
                    f"appear in no tool result and are not your "
                    f"recorded sources: {bad_urls}. Your "
                    f"canonical sources are: "
                    f"{', '.join(selffacts.CANONICAL_URLS)}. "
                    "Fetch the real source with a tool, then "
                    "finish."), None
        return None, None

    def _try_deterministic_finish(self, state):
        """Apply the pending finish — her own completion
        statement plus verified work products — as a STATE
        WRITE, with no model call (fix 1: a finish is a state
        write, not a model call). Returns a done TaskResult,
        or None when the pending finish does not verify
        (outputs gone, the spec guard refuses, or a grounding
        check refuses): the task then waits/resumes exactly
        as before — never closed on stale words."""
        pf = state.get("pending_finish")
        if not pf:
            return None
        task_dir = state["task_dir"]
        self._refresh_produced(state, len(state["steps"]))
        outputs = list(pf.get("outputs", []) or [])
        for rel in outputs:
            try:
                target = sandbox.resolve_inside(task_dir, rel)
            except sandbox.SandboxError:
                return None
            if not os.path.isfile(target):
                return None
        args = {"summary": pf.get("summary", ""), "outputs": outputs}
        if self.finish_guard is not None \
                and self.finish_guard(state["steps"], args):
            return None
        rejection, corrected = self._check_finish(
            state, args, deterministic=True)
        if rejection:
            return None
        summary = corrected if corrected is not None \
            else args["summary"]
        steps = state["steps"]
        steps.append(Step(
            len(steps) + 1, "finish", "finish",
            tasktools.args_summary("finish", args),
            _summarise("closed deterministically from her own "
                       "completion statement — the finish is "
                       "a state write, not a model call")))
        state["pending_finish"] = None
        self._checkpoint(state)
        return TaskResult("done", summary, task_dir,
                          state["task_id"], steps,
                          provider=state.get("provider"))

    def _execute(self, state):
        try:
            return self._execute_inner(state)
        except (WaitingRouteError, WaitingNetworkError):
            # The route dropped mid-task. If she had already
            # stated completion over verified work products,
            # the finish lands NOW — the record closes from
            # state instead of parking finished work until
            # some later resume (fix 1).
            done = self._try_deterministic_finish(state)
            if done is not None:
                self._persist_trace(done)
                return done
            raise

    def _execute_inner(self, state):
        task_id = state["task_id"]
        task_dir = state["task_dir"]
        task_text = state["task_text"]
        steps = state["steps"]
        messages = state["messages"]
        total_chars = state["total_chars"]
        reframed = state["reframed"]
        # Older checkpoints store `nudged` as a bool.
        nudged = state["nudged"]
        if isinstance(nudged, bool):
            nudged = 1 if nudged else 0
        result = None

        def record(kind, tool="", args_summary="", result_summary=""):
            steps.append(Step(len(steps) + 1, kind, tool, args_summary,
                              _summarise(result_summary)))

        def sync():
            state.update(total_chars=total_chars, reframed=reframed,
                         nudged=nudged)
            self._checkpoint(state)

        self._checkpoint(state)
        # RESUME STEER (v1.0.14): a resumed run is told, once,
        # what its earlier segment already produced — the
        # finish-first fact pattern behind "route recovery
        # resumes to FINISH". Fresh runs (iterations_done == 0)
        # never see it.
        if state["iterations_done"] >= 1 \
                and not state.get("resume_steered") and messages:
            state["resume_steered"] = True
            made = ", ".join(state.get("produced", [])) or "none recorded"
            messages.append({"role": "user", "content":
                "This run was interrupted and has now resumed. "
                f"Work products already in the sandbox from the "
                f"earlier steps: {made}. If the task is "
                "complete, call finish NOW — do not redo "
                "finished work. Otherwise continue from the "
                "trace."})
            self._checkpoint(state)
        steered_streaks = set()
        brief_swapped = state["iterations_done"] >= 1
        for step_no in range(state["iterations_done"] + 1, self.max_steps + 1):
            if self.task_brief and not brief_swapped \
                    and len(messages) > 1:
                messages[1] = {"role": "user",
                               "content": self.task_brief}
                brief_swapped = True
            # Click-loop guard (v0.8 X attempt 15-resumed): three
            # identical consecutive tool actions with no change
            # of course mean the model is stuck in a loop (it
            # clicked the same ToS-page element repeatedly).
            # Steer once per streak: name the loop and point at
            # re-opening the task's pages instead.
            # Malformed-streak steer (v0.8 X attempt 16): the
            # brain repeatedly emitted a ~2 KB write_file whose
            # JSON broke the same way, burning the run before
            # any real work. Two malformed calls of the same
            # tool in a row -> steer to short, different calls.
            mal_steps = [s for s in steps if s.kind == "malformed"]
            if len(mal_steps) >= 2 and len(steps) >= 2 \
                    and steps[-1].kind == "malformed" \
                    and steps[-2].kind == "malformed" \
                    and steps[-1].tool == steps[-2].tool \
                    and steps[-1].tool not in steered_streaks:
                steered_streaks.add(steps[-1].tool)
                messages.append({"role": "user", "content":
                    f"Your {steps[-1].tool} calls keep arriving "
                    "malformed — the arguments are too long or "
                    "broken. Do NOT retry that call. Keep every "
                    "argument SHORT (one line), and take the "
                    "task's first real step now (for a browser "
                    "task: browser_open)."}) 
            tool_steps = [s for s in steps if s.kind == "tool"]
            if len(tool_steps) >= 3:
                last3 = tool_steps[-3:]
                sig = (last3[0].tool, last3[0].args_summary)
                if sig[0] and all(
                        (s.tool, s.args_summary) == sig for s in last3) \
                        and sig not in steered_streaks:
                    steered_streaks.add(sig)
                    messages.append({"role": "user", "content":
                        f"Loop check: you have called {sig[0]} with the "
                        "same arguments three times in a row and the "
                        "situation has not changed. Stop repeating it. "
                        "Take a different action: browser_read the "
                        "current page carefully, or browser_open the "
                        "page the task actually needs, and continue "
                        "from there."})
            ctl = self._control()
            if ctl in ("pause", "cancel"):
                record("stop", result_summary=f"task {ctl}led by user")
                state.update(total_chars=total_chars, reframed=reframed,
                             nudged=nudged)
                self._checkpoint(state)
                raise TaskPausedSignal(ctl)
            try:
                reply = self._call_model(messages)
            except (WaitingRouteError, WaitingNetworkError):
                state.update(total_chars=total_chars, reframed=reframed,
                             nudged=nudged)
                self._checkpoint(state)
                raise
            except (PrivacyBlocked, AllProvidersFailed) as e:
                record("stop", result_summary=f"model unavailable: {e}")
                result = TaskResult("error", f"Model unavailable: {e}",
                                    task_dir, task_id, steps)
                break
            state["iterations_done"] = step_no
            state["provider"] = reply.get("provider")
            total_chars += len(reply.get("content") or "")
            content = reply.get("content")
            total_chars += len(reply.get("content") or "")
            content = reply.get("content")
            calls = reply.get("tool_calls") or []

            # Text-fallback path when no native calls arrived.
            malformed_err = None
            if not calls:
                call, malformed_err = parse_text_tool_call(content)
                if call:
                    calls = [{"id": "", "name": call["name"],
                              "args": call["args"], "text_mode": True}]

            if malformed_err:
                # One repair retry inside this step, then honest stop.
                record("malformed", result_summary=malformed_err)
                messages.append({"role": "assistant", "content": content or ""})
                messages.append({"role": "user", "content":
                    f"Your tool call was malformed: {malformed_err}. Reply with ONE "
                    "valid native tool call, or one fenced JSON block "
                    '{"tool": "<name>", "args": {...}} with valid JSON.'})
                try:
                    reply = self._call_model(messages)
                except (PrivacyBlocked, AllProvidersFailed) as e:
                    result = TaskResult("error", f"Model unavailable: {e}",
                                        task_dir, task_id, steps)
                    break
                content = reply.get("content")
                calls = reply.get("tool_calls") or []
                if not calls:
                    call, malformed_err2 = parse_text_tool_call(content)
                    if call:
                        calls = [{"id": "", "name": call["name"],
                                  "args": call["args"], "text_mode": True}]
                    else:
                        record("stop", result_summary=
                               f"tool call still malformed after one repair: {malformed_err2 or 'no tool call'}")
                        result = TaskResult("error",
                            "The model produced a malformed tool call twice; "
                            "stopping honestly instead of guessing.",
                            task_dir, task_id, steps)
                        break
                record("repair", result_summary="repaired tool call accepted")

            if not calls:
                # No tool call at all: refusal? plain-text ending?
                if content and recovery.is_refusal(content) and not reframed \
                        and not recovery.is_harmful_request(task_text):
                    reframed = True
                    new_task = recovery.reframe_request(task_text)
                    messages[1] = {"role": "user", "content": new_task}
                    record("refusal-reframe",
                           result_summary="task rephrased once after a phrasing refusal")
                    sync()
                    continue
                # PENDING FINISH (v1.0.14, fix 1): a plain-text
                # reply AFTER real tool work is her completion
                # statement in words. Record it — summary in
                # her own words, outputs the verified work
                # products — so the finish can land as a state
                # write if the route drops, the nudges run out,
                # or the process dies before a finish call.
                if content and any(s.kind == "tool" for s in steps):
                    self._refresh_produced(state, step_no)
                    if state.get("produced") or any(
                            s.tool in EXECUTION_TOOLS
                            for s in steps if s.kind == "tool"):
                        state["pending_finish"] = {
                            "summary": content,
                            "outputs": list(state.get("produced", [])),
                        }
                if nudged < TaskLoop.MAX_PLAIN_TEXT_NUDGES:
                    nudged += 1
                    messages.append({"role": "assistant", "content": content or ""})
                    messages.append({"role": "user", "content":
                        "You replied with plain text. If the task is done, call the "
                        "finish tool; otherwise call a tool to continue. Giving up "
                        "in words is not a result — the task is graded from tool "
                        "evidence, and routes in the task that you have not tried "
                        "yet are still open to you."})
                    record("text", result_summary="plain-text reply; nudged to use tools/finish")
                    sync()
                    continue
                done = self._try_deterministic_finish(state)
                if done is not None:
                    result = done
                    break
                record("stop", result_summary="model ended with plain text, no finish call")
                stopped_summary = content or "Model stopped without a summary."
                # (fix 6) Even a stopped report uses the
                # tool-verified value over her narration.
                contra = date_contradiction(stopped_summary, messages)
                if contra:
                    corrected = correct_dates(stopped_summary,
                                              contra[1])
                    if corrected is not None:
                        stopped_summary = corrected
                        record("grounding-correction",
                               result_summary="report date corrected "
                                              "to the tool-verified value")
                result = TaskResult("stopped",
                                    stopped_summary,
                                    task_dir, task_id, steps,
                                    provider=reply.get("provider"))
                break

            # Append the assistant turn, then execute each call.
            assistant_msg = {"role": "assistant", "content": content or ""}
            native = [c for c in calls if not c.get("text_mode")]
            if native:
                assistant_msg["tool_calls"] = [
                    {"id": c.get("id") or f"call_{step_no}_{i}", "type": "function",
                     "function": {"name": c["name"],
                                  "arguments": json.dumps(c.get("args") or {})}}
                    for i, c in enumerate(calls)]
            messages.append(assistant_msg)

            finished = None
            for c in calls:
                name = c["name"]
                # Deterministic repair of a known GPT-OSS emission
                # quirk: the channel marker leaks into native tool
                # names ("browser_click<|channel|>commentary").
                # Truncate at the first character outside
                # [a-z0-9_]; accept the repair only when the result
                # is a real tool — anything else still meets the
                # Sentinel under its true (denied) name. (v0.8 X
                # attempt 9 burned six steps on corrupted names
                # one form-submit short of the verification email.)
                if name not in tasktools.HANDLERS and name != "finish":
                    cleaned = re.split(r"[^a-z0-9_]", name or "")[0]
                    if cleaned in tasktools.HANDLERS or cleaned == "finish":
                        name = cleaned
                        c = dict(c, name=cleaned)
                args = c.get("args") or {}
                if c.get("malformed"):
                    obs = f"error: {c['malformed']}"
                    record("malformed", name, tasktools.args_summary(name, args), obs)
                    self._observe(messages, c, obs)
                    continue
                allowed, reason = self.sentinel.check(name, args, task_dir)
                if not allowed:
                    obs = f"SENTINEL DENIED: {reason}"
                    record("sentinel-denied", name,
                           tasktools.args_summary(name, args), obs)
                    self._observe(messages, c, obs)
                    continue
                if name == tasktools.FINISH:
                    if self.finish_guard is not None:
                        rejection = self.finish_guard(steps, args)
                        if rejection:
                            obs = f"finish REJECTED: {rejection}"
                            record("finish-rejected", name,
                                   tasktools.args_summary(name, args), obs)
                            self._observe(messages, c, obs)
                            continue
                    outputs = args.get("outputs", []) or []
                    missing = [rel for rel in outputs
                               if not os.path.isfile(
                                   sandbox.resolve_inside(task_dir, rel))]
                    if missing:
                        obs = ("finish REJECTED: claimed output(s) do not exist "
                               f"in the sandbox: {missing}. Create them first, "
                               "then call finish again.")
                        record("finish-rejected", name,
                               tasktools.args_summary(name, args), obs)
                        self._observe(messages, c, obs)
                        continue
                    # VERIFY-BY-STATE (v1.0.14): existence is
                    # not evidence of work — the claim is
                    # checked against the run's produced set,
                    # its verification evidence, and the
                    # trace's tool-verified values before the
                    # completed state may be written.
                    self._refresh_produced(state, len(steps))
                    rejection2, _ = self._check_finish(state, args)
                    if rejection2:
                        obs = f"finish REJECTED: {rejection2}"
                        record("finish-rejected", name,
                               tasktools.args_summary(name, args), obs)
                        self._observe(messages, c, obs)
                        continue
                    record("finish", name, tasktools.args_summary(name, args),
                           args.get("summary", ""))
                    finished = TaskResult("done", args.get("summary", "Task done."),
                                          task_dir, task_id, steps,
                                          provider=reply.get("provider"))
                    break
                obs = tasktools.execute(name, args, task_dir)
                total_chars += len(obs)
                record("tool", name, tasktools.args_summary(name, args), obs)
                # len(steps) is the recorded step's own number
                # — the numbering changed_step is judged in.
                self._refresh_produced(state, len(steps))
                self._observe(messages, c, obs)

            sync()
            if finished is not None:
                result = finished
                break
            if total_chars > self.max_total_chars:
                record("stop", result_summary="per-task output budget exhausted")
                result = TaskResult("stopped",
                    "Stopped: the per-task output budget was exhausted.",
                    task_dir, task_id, steps)
                break
        else:
            done = self._try_deterministic_finish(state)
            if done is not None:
                result = done
            else:
                record("stop", result_summary=f"step budget ({self.max_steps}) exhausted")
                result = TaskResult("stopped",
                    f"Stopped after {self.max_steps} steps without a verified finish. "
                    "The trace above shows how far the task got.",
                    task_dir, task_id, steps)

        self._persist_trace(result)
        return result

    # Keyless anonymous endpoints reject oversized requests
    # (HTTP 413, hit live in the v0.8 browser proof attempt 3
    # after a few full-page browser observations). No single
    # observation may bloat the context past this cap.
    MAX_OBSERVATION_CHARS = 6000

    @staticmethod
    def _observe(messages, call, observation):
        if observation and len(observation) > TaskLoop.MAX_OBSERVATION_CHARS:
            cap = TaskLoop.MAX_OBSERVATION_CHARS
            observation = (observation[:cap] +
                           f"\n[observation truncated at {cap} chars "
                           "to protect the model context]")
        if call.get("text_mode"):
            messages.append({"role": "user",
                             "content": f"Tool result ({call['name']}): {observation}"})
        else:
            messages.append({"role": "tool",
                             "tool_call_id": call.get("id") or "",
                             "content": observation})

    def _persist_trace(self, result):
        try:
            log_dir = os.path.join(self.workspace_root, "task_logs")
            os.makedirs(log_dir, exist_ok=True)
            data = {"task_id": result.task_id, "status": result.status,
                    "summary": result.summary, "task_dir": result.task_dir,
                    "provider": result.provider,
                    "steps": [vars(s) for s in result.steps],
                    "ts": self.clock()}
            with open(os.path.join(log_dir, result.task_id + ".json"), "w") as f:
                json.dump(data, f, indent=2)
        except Exception:
            pass  # the trace lives in the result even if the log write fails
