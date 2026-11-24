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
    if not isinstance(obj, dict) or "tool" not in obj:
        return None, "JSON block has no 'tool' field"
    args = obj.get("args", {})
    if not isinstance(args, dict):
        return None, "'args' is not a JSON object"
    return {"name": str(obj["tool"]), "args": args}, None

class TaskLoop:
    def __init__(self, config=None, model_call=None, workspace_root=None,
                 max_steps=DEFAULT_MAX_STEPS, approver=None,
                 max_total_chars=DEFAULT_MAX_TOTAL_CHARS, clock=None,
                 pace_seconds=None):
        """model_call(messages, tools) -> {"content": str|None,
        "tool_calls": [{"id","name","args"}] | None, "provider": str}
        When omitted, one is built on the Router (Fallback Law)."""
        self.workspace_root = workspace_root or os.environ.get(
            "FREEAGENT_WORKSPACE", os.path.expanduser("~/.freeagent/tasks"))
        self.max_steps = max_steps
        self.max_total_chars = max_total_chars
        self.sentinel = tasktools.Sentinel(approver=approver)
        self.clock = clock or time.time
        # Pacing: the keyless anonymous tier allows ~1 request per
        # ~45s per IP. pace_seconds (or FREEAGENT_PACE_SECONDS) makes
        # the loop wait between model calls instead of tripping the
        # limiter and burning routes on 402s.
        if pace_seconds is None:
            pace_seconds = float(os.environ.get("FREEAGENT_PACE_SECONDS", "0") or 0)
        self.pace_seconds = pace_seconds
        self._last_call_ts = 0.0
        if model_call is not None:
            self.model_call = model_call
        else:
            router = Router(config)
            self.model_call = self._router_model_call(router)

    # ---------- default model call: Router-backed ----------
    @staticmethod
    def _router_model_call(router):
        def call(messages, tools):
            out = router.complete(messages, tools=tools, category="task")
            if out.get("queued"):
                raise AllProvidersFailed(out.get("text", "all routes cooling down"))
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

    def _call_model(self, messages):
        if self.pace_seconds > 0 and self._last_call_ts:
            wait = self.pace_seconds - (time.time() - self._last_call_ts)
            if wait > 0:
                time.sleep(wait)
        self._last_call_ts = time.time()
        return self.model_call(messages, tasktools.TOOL_SCHEMAS)

    # ---------- the loop ----------
    def run(self, task_text):
        task_id, task_dir = sandbox.make_task_dir(self.workspace_root)
        steps = []
        total_chars = 0

        def record(kind, tool="", args_summary="", result_summary=""):
            steps.append(Step(len(steps) + 1, kind, tool, args_summary,
                              _summarise(result_summary)))

        messages = [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": task_text},
        ]
        reframed = False
        nudged = False
        result = None

        for step_no in range(1, self.max_steps + 1):
            try:
                reply = self._call_model(messages)
            except (PrivacyBlocked, AllProvidersFailed) as e:
                record("stop", result_summary=f"model unavailable: {e}")
                result = TaskResult("error", f"Model unavailable: {e}",
                                    task_dir, task_id, steps)
                break
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
                    continue
                if not nudged:
                    nudged = True
                    messages.append({"role": "assistant", "content": content or ""})
                    messages.append({"role": "user", "content":
                        "You replied with plain text. If the task is done, call the "
                        "finish tool; otherwise call a tool to continue."})
                    record("text", result_summary="plain-text reply; nudged to use tools/finish")
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

    @staticmethod
    def _observe(messages, call, observation):
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
