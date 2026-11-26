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
import json, os, re, time
from dataclasses import dataclass, field

from . import recovery, sandbox, tasktools
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
- When the task is fully done, call the finish tool with a summary and list every output file you produced in "outputs" — claimed outputs are verified to exist, so do not claim files you did not create.
- If a tool result is an error, read it, fix the cause, and try again. Do not repeat an identical failing call.
- Never invent results: only claim what tool outputs actually showed.
- Browser tasks: work from browser_read's element refs (refs are reassigned on every read — re-read after each navigation). Passwords are NEVER typed by you or written into any file: create them with vault_gen_secret (you receive only a key name) and type them with browser_type_secret. If any tool result starts with CHALLENGE, the page is a human-only wall (CAPTCHA, an emailed code, a phone demand): STOP immediately and call finish with a summary naming that exact wall — never try to solve, bypass, or route around it.
"""

_FENCE_RE = re.compile(r"```(?:json)?\s*(.*?)```", re.S)

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

def parse_text_tool_call(content):
    """Text-fallback protocol. Returns (call, error):
    call = {"name":..., "args": {...}} or None; error = str when the
    reply clearly TRIED to make a tool call but it was malformed."""
    if not content:
        return None, None
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

class WaitingRouteError(RuntimeError):
    """All routes are cooling down (Fallback Law). NOT a failure:
    the task waits and resumes at/after retry_at."""
    def __init__(self, retry_at=None, message="all routes cooling down"):
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
        self.sentinel = tasktools.Sentinel(approver=approver,
                                           spec_context=sentinel_context)
        tasktools.set_runtime_context(sentinel_context)
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
                text = str(e)
                now = time.time()
                unlocks = [u for u in router.cooldowns.values() if u > now]
                retry_at = min(unlocks) if unlocks else now + 60
                if ", 0)" in text or text.rstrip().endswith(", 0)]"):
                    raise WaitingNetworkError(retry_at,
                        f"network unreachable (all transports failed): {e}")
                if unlocks:
                    raise WaitingRouteError(retry_at,
                        f"routes unavailable, cooling down: {e}")
                raise
            if out.get("queued"):
                raise WaitingRouteError(out.get("retry_at"),
                                        out.get("text", "all routes cooling down"))
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
        for m in reversed(messages):
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
        state = {
            "task_id": task_id, "task_text": task_text, "task_dir": task_dir,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": task_text},
            ],
            "steps": [], "iterations_done": 0, "total_chars": 0,
            "reframed": False, "nudged": 0, "provider": None,
        }
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
        }
        return self._execute(state)

    def _execute(self, state):
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
                record("stop", result_summary="model ended with plain text, no finish call")
                result = TaskResult("stopped",
                                    content or "Model stopped without a summary.",
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
                    record("finish", name, tasktools.args_summary(name, args),
                           args.get("summary", ""))
                    finished = TaskResult("done", args.get("summary", "Task done."),
                                          task_dir, task_id, steps,
                                          provider=reply.get("provider"))
                    break
                obs = tasktools.execute(name, args, task_dir)
                total_chars += len(obs)
                record("tool", name, tasktools.args_summary(name, args), obs)
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
