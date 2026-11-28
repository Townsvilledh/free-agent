"""Provider router: FALLBACK LAW + PRIVACY ROUTING.

Fallback law (Lubrica's routing discipline, applied to AI providers):
every capability has an ordered route chain (see routes.py /
ROUTES.md). The router:

- tries routes in order — OWN KEYS LEAD (v1.0.2, Ethuram 2026-11-27):
  an ENABLED personal-key provider (the user's OWN key: Groq first,
  then Gemini, Cerebras, OpenRouter, AgentRouter, custom) is tried
  FIRST, and the KEYLESS public providers (Pollinations, AI Horde)
  are the fallback; with no own key supplied the keyless pair leads
  exactly as it always has. The reorder is order-only: it never
  widens which routes are eligible for a content class (privacy
  routing below is applied per provider, before and regardless of
  position);
- respects rate limits: on HTTP 402/429/503 (or a network error) it
  COOLS THAT ROUTE DOWN — records an unlock time from the provider's
  Retry-After when the transport reports one, else the route's
  configured cooldown — and moves to the next route automatically;
  auth/config failures (401/403/404) demote the same way (v1.0.15),
  a 404 first rotates to the route's next listed model, and when
  every route is cooling but the earliest recovery is near, the
  call WAITS it out in-run and re-walks rather than queueing;
- SKIPS routes still cooling down, and returns to the preferred route
  once its cooldown expires (health re-probe is simply trying it
  first again);
- keeps the surface seamless: the caller gets ONE answer, never a raw
  provider error. The audit log records which route served (provider
  + capability/category only, never content);
- has NO silent dead ends: if every route is cooling down, it returns
  a queued result saying so plainly, with the earliest retry time —
  the task is queued, the user never has to re-send it. One-move
  composition: a multi-step task auto-continues across route switches
  because switching happens inside a single chat() call.

Privacy routing (strict, overrides availability): private content
(Gmail, account data) is NEVER routed to an untrusted route just
because it is available. Untrusted = the keyless PUBLIC endpoint,
AgentRouter (community relay, unknown retention), Groq, and every
other external provider. Private content goes only to a trusted route
(e.g. local Ollama); with no trusted route it raises PrivacyBlocked,
and with a trusted route that is merely cooling down it returns the
honest queued result. A per-task explicit override is the only way
past this, and it is logged (metadata only).

Transports return (status, text) or (status, text, retry_after_seconds).
"""
import json, time, urllib.request, urllib.error
from dataclasses import dataclass
from datetime import datetime, timezone

from . import chatguard, recovery

FALLBACK_STATUSES = {402, 429, 503}
# v1.0.15 (battery round 2, class 1): statuses that DEMOTE a
# route in the health system. 402/429/503 are quota/transient.
# 401/403/404 are auth/config failures — the route is not
# merely busy, it is answering wrongly for this caller (a dead
# key, a credential the egress never swapped, a model the
# account does not have). Before this fix they recorded NO
# cooldown and NO fail count: a dead route led every walk,
# burned a round trip on every call, never demoted — and
# contributed no unlock time, which also hid it from the
# queued result's retry maths.
DEMOTE_STATUSES = FALLBACK_STATUSES | {401, 403, 404}
MAX_STREAM_CONTINUATIONS = 3
# v1.0.15 (class 1): how many times one call may wait out a
# near route recovery in-run before returning its queued
# result (see Router.route_wait_budget).
MAX_ROUTE_WAITS = 2

# Groq's edge rejects the default Python urllib User-Agent with an
# HTTP 403 (live-verified 2026-11-26 via the hostkeys bridge: a valid
# credential 403'd with the default UA and succeeded with this one).
# The custom.groq connector helper sets the identical browser UA for
# the same reason. Scoped to the Groq provider ONLY — every other
# provider's headers are unchanged.
GROQ_USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/126.0 Safari/537.36")


def _apply_provider_headers(req, provider):
    """Per-provider request headers. Today: Groq's browser UA only."""
    if provider.name == "groq":
        req.add_header("User-Agent", GROQ_USER_AGENT)

@dataclass
class RouteEvent:
    provider: str
    category: str      # e.g. "public", "private:gmail" — never content
    outcome: str       # ok | skipped-privacy | cooldown-skip | http-<code> | error

class PrivacyBlocked(RuntimeError):
    pass

class AllProvidersFailed(RuntimeError):
    pass

def _fmt_ts(ts):
    return datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%H:%M:%S UTC")

def _with_tool_catalogue(messages, tools):
    """Render the tool catalogue as TEXT and append it to the
    system message — for raw-completion routes (AI Horde) that
    never see the API `tools` parameter. The loop's text protocol
    (one fenced JSON block per reply) is how such a route calls
    tools; it can only call tools it has been told about. Native
    routes are never sent this (they get the real parameter)."""
    if not tools:
        return messages
    lines = ["",
             "TOOLS AVAILABLE ON THIS ROUTE (no native tool calls "
             "here — call a tool by replying with exactly one fenced "
             "JSON block: {\"tool\": \"<name>\", \"args\": {...}}):"]
    for t in tools:
        fn = (t or {}).get("function") or {}
        name = fn.get("name", "?")
        desc = " ".join(str(fn.get("description", "")).split())[:160]
        props = ((fn.get("parameters") or {}).get("properties") or {})
        arglist = ", ".join(props.keys())
        lines.append(f"- {name}({arglist}) — {desc}")
    extra = "\n".join(lines)
    out, done = [], False
    for m in messages:
        m = dict(m)
        if not done and m.get("role") == "system":
            m["content"] = str(m.get("content", "")) + "\n" + extra
            done = True
        out.append(m)
    if not done:
        out.insert(0, {"role": "system", "content": extra.strip()})
    return out

# Own-key provider names in walk order — mirrors the app's
# BrainFailure.OWN_KEY_ORDER (Groq leads among own keys).
OWN_KEY_ORDER = [
    "groq", "gemini-free", "cerebras",
    "openrouter-free", "agentrouter", "custom-openai",
]


def effective_providers(providers):
    """The walk order for one call (OWN KEYS LEAD, v1.0.2):
    enabled own-key providers first (in OWN_KEY_ORDER), then
    the keyless providers, then everything else in catalogue
    order (the trusted local path, disabled stubs). Pure
    reordering of the SAME set — eligibility (enabled,
    has-models, privacy trust) is judged by the caller per
    provider exactly as before, so no content can reach a
    route this order merely moved."""
    own = [p for p in providers
           if p.name in OWN_KEY_ORDER and p.enabled]
    own.sort(key=lambda p: OWN_KEY_ORDER.index(p.name))
    own_ids = {id(p) for p in own}
    keyless = [p for p in providers
               if getattr(p, "keyless", False) and id(p) not in own_ids]
    keyless_ids = {id(p) for p in keyless}
    rest = [p for p in providers
            if id(p) not in own_ids and id(p) not in keyless_ids]
    return own + keyless + rest


class Router:
    def __init__(self, config, transport=None, audit=None, clock=None, stream_transport=None):
        self.config = config
        self.transport = transport or self._http_transport
        # Streaming (v0.2.1): used when available so a connection that
        # dies mid-answer preserves the partial text and auto-continues
        # instead of losing the whole reply (battery finding: 3 of 8
        # first attempts died in transport). The real HTTP transport
        # streams by default; injected test transports opt in by
        # passing stream_transport explicitly.
        if stream_transport is not None:
            self.stream_transport = stream_transport
        elif transport is None:
            self.stream_transport = self._http_stream_transport
        else:
            self.stream_transport = None
        self.raw_transport = self._http_raw_transport     # injectable in tests
        self.horde_transport = self._http_horde_transport  # injectable in tests
        self.horde_poll_seconds = 15                      # tests may shrink
        self.horde_max_polls = 24                         # ~6 min of queue
        self.audit = audit if audit is not None else []   # list[RouteEvent]
        self.clock = clock or time.time                   # injectable for tests
        self.sleeper = time.sleep                         # injectable for tests (v1.0.15 in-run route waiting)
        # v1.0.15 (class 1): when a walk ends with every route
        # cooling but the earliest unlock is near, the router
        # WAITS it out in-run and re-walks instead of returning
        # a queued result that parks the task — a single
        # route's transient exhaustion must not park work
        # while recovery is seconds away. The budget covers
        # second-scale recoveries (a rate limiter's
        # Retry-After of a few seconds); minute-scale
        # recoveries stay the durable scheduler's job (it
        # resumes the task at retry_at). Bounded: at most
        # MAX_ROUTE_WAITS waits per call, and a re-walk that
        # comes back queued with no later retry_at than the
        # one just waited out is never waited on again.
        self.route_wait_budget = 40.0
        self.cooldowns = {}                               # provider -> unlock ts
        self.fail_counts = {}                             # provider -> consecutive failures (v0.12 health: escalates the cooldown; a success clears it)

    # ---------- transport ----------
    def _http_transport(self, provider, messages):
        """OpenAI-compatible chat call. Returns (status, text, retry_after).

        POST (never GET-with-prompt-in-URL): prompts in URLs get logged
        and cached by intermediaries. Keyless providers send no
        Authorization header at all.
        """
        body = json.dumps({"model": provider.models[0], "messages": messages}).encode()
        url = provider.base_url.rstrip("/") + provider.chat_path
        req = urllib.request.Request(url, data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        _apply_provider_headers(req, provider)
        key = provider.key()
        if key:
            req.add_header("Authorization", "Bearer " + key)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                data = json.loads(r.read().decode())
                return r.status, data["choices"][0]["message"]["content"], None
        except urllib.error.HTTPError as e:
            ra = e.headers.get("Retry-After") if e.headers else None
            try:
                ra = float(ra) if ra is not None else None
            except (TypeError, ValueError):
                ra = None
            return e.code, "", ra
        except Exception:
            return 0, "", None

    def _call(self, provider, messages):
        out = self.transport(provider, messages)
        if len(out) == 2:
            status, text = out
            return status, text, None
        return out

    # ---------- streaming transport (v0.2.1) ----------
    def _http_stream_transport(self, provider, messages):
        """SSE streaming chat call (stream=true). Returns
        (status, text, retry_after) on a clean finish; raises
        recovery.StreamDropped(partial) if the connection dies
        mid-body, so the caller can auto-continue instead of losing
        the whole answer. A provider that ignores stream=true and
        answers with one plain JSON body is handled too."""
        body = json.dumps({"model": provider.models[0], "messages": messages,
                           "stream": True}).encode()
        url = provider.base_url.rstrip("/") + provider.chat_path
        req = urllib.request.Request(url, data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        _apply_provider_headers(req, provider)
        key = provider.key()
        if key:
            req.add_header("Authorization", "Bearer " + key)
        try:
            resp = urllib.request.urlopen(req, timeout=120)
        except urllib.error.HTTPError as e:
            ra = e.headers.get("Retry-After") if e.headers else None
            try:
                ra = float(ra) if ra is not None else None
            except (TypeError, ValueError):
                ra = None
            return e.code, "", ra
        except Exception:
            return 0, "", None
        partial = ""
        saw_sse = False
        try:
            with resp:
                for raw in resp:
                    line = raw.decode("utf-8", "replace").strip()
                    if not line:
                        continue
                    if line.startswith("data:"):
                        saw_sse = True
                        payload = line[5:].strip()
                        if payload == "[DONE]":
                            break
                        try:
                            delta = json.loads(payload)["choices"][0]["delta"].get("content") or ""
                        except Exception:
                            delta = ""
                        partial += delta
                    elif line.startswith("{") and not saw_sse:
                        # Non-streaming JSON body despite stream=true.
                        rest = line + resp.read().decode("utf-8", "replace")
                        try:
                            data = json.loads(rest)
                            return resp.status, data["choices"][0]["message"]["content"], None
                        except Exception:
                            return resp.status, "", None
            return resp.status, partial, None
        except Exception:
            if partial:
                raise recovery.StreamDropped(partial)
            return 0, "", None

    def _call_streamed(self, provider, messages):
        """One logical call over the streaming transport: if the
        stream drops mid-body, auto-continue ('continue from where
        you stopped') up to MAX_STREAM_CONTINUATIONS times and stitch
        the parts into one whole answer."""
        parts = []
        msgs = messages
        continuations = 0
        while True:
            try:
                out = self.stream_transport(provider, msgs)
            except recovery.StreamDropped as dropped:
                parts.append(dropped.partial)
                continuations += 1
                if continuations > MAX_STREAM_CONTINUATIONS:
                    return 200, recovery.stitch(parts), None
                so_far = recovery.stitch(parts)
                msgs = messages + [
                    {"role": "assistant", "content": so_far},
                    {"role": "user", "content": recovery.CONTINUE_PROMPT},
                ]
                continue
            if len(out) == 2:
                status, text = out
                retry_after = None
            else:
                status, text, retry_after = out
            if status == 200 and text:
                parts.append(text)
                return 200, recovery.stitch(parts), None
            return status, text, retry_after

    def _dispatch(self, provider, messages):
        if getattr(provider, "kind", "openai") == "horde":
            return self.horde_transport(provider, messages)
        if self.stream_transport is not None:
            return self._call_streamed(provider, messages)
        return self._call(provider, messages)

    # ---------- AI Horde transport (v0.12) ----------
    def _http_horde_transport(self, provider, messages):
        """AI Horde keyless text generation — the fallback of
        last resort (live-verified 2026-11-26, ROUTES.md).

        Horde is a raw-completion queue API, not a chat API:
        the conversation is flattened into one prompt, the job
        is submitted with the service's public anonymous key
        ("0000000000"), and the status endpoint is polled until
        a community worker answers. Returns the same
        (status, text, retry_after) triple as every transport.
        Slow by nature — that is the honest price of a second
        genuinely keyless route."""
        lines = []
        for m in messages:
            role = m.get("role", "user")
            label = {"system": "System", "user": "User",
                     "assistant": "Lille"}.get(role, "User")
            lines.append(f"{label}: {m.get('content', '')}")
        prompt = "\n".join(lines) + "\nLille:"
        base = provider.base_url.rstrip("/")
        headers = {
            "Content-Type": "application/json",
            "apikey": "0000000000",
            "Client-Agent": "lille-core:0.12.1:free-agent",
        }
        body = json.dumps({
            "prompt": prompt,
            "params": {"max_length": 1024, "temperature": 0.7,
                       "stop_sequence": ["\nUser:"]},
            "models": [],
            "slow_workers": True,
        }).encode()
        req = urllib.request.Request(
            base + "/generate/text/async", data=body, method="POST")
        for k, v in headers.items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                job_id = json.loads(r.read().decode()).get("id", "")
        except urllib.error.HTTPError as e:
            ra = e.headers.get("Retry-After") if e.headers else None
            try:
                ra = float(ra) if ra is not None else None
            except (TypeError, ValueError):
                ra = None
            return e.code, "", ra
        except Exception:
            return 0, "", None
        if not job_id:
            return 0, "", None
        for _ in range(self.horde_max_polls):
            time.sleep(self.horde_poll_seconds)
            req = urllib.request.Request(
                base + f"/generate/text/status/{job_id}", method="GET")
            for k, v in headers.items():
                req.add_header(k, v)
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    data = json.loads(r.read().decode())
            except Exception:
                continue  # a dropped poll is not a dead job
            if data.get("faulted"):
                return 0, "", None
            if data.get("done"):
                gens = data.get("generations") or []
                text = (gens[0].get("text") or "").strip() if gens else ""
                return (200, text, None) if text else (0, "", None)
        return 0, "", None

    # ---------- raw completion (v0.3: tool-call loop) ----------
    def _http_raw_transport(self, provider, messages, tools=None):
        """Non-streaming chat call returning the RAW assistant message
        (content + tool_calls). Tool calls need a whole body, so the
        loop path does not stream. Returns (status, message|None,
        retry_after)."""
        body = {"model": provider.models[0], "messages": messages}
        if tools:
            body["tools"] = tools
            body["tool_choice"] = "auto"
        data = json.dumps(body).encode()
        url = provider.base_url.rstrip("/") + provider.chat_path
        req = urllib.request.Request(url, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        _apply_provider_headers(req, provider)
        key = provider.key()
        if key:
            req.add_header("Authorization", "Bearer " + key)
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                payload = json.loads(r.read().decode())
                return r.status, payload["choices"][0]["message"], None
        except urllib.error.HTTPError as e:
            ra = e.headers.get("Retry-After") if e.headers else None
            try:
                ra = float(ra) if ra is not None else None
            except (TypeError, ValueError):
                ra = None
            return e.code, None, ra
        except Exception:
            return 0, None, None

    # ---------- route health (v1.0.15, class 1) ----------
    def _note_failure(self, provider, status, retry_after):
        """Record a route failure in the health system and
        return the route's unlock time, or None when the
        status carries no health signal (a plain 400, say —
        the walk simply falls through). Fallback, transport
        (0) and auth/config failures all demote: the cooldown
        is the provider's Retry-After when the transport
        reported one, else the configured cooldown escalated
        x1/x2/x4/x8 by the consecutive-failure count (v0.12
        maths, unchanged); a success clears both records."""
        if status not in DEMOTE_STATUSES and status != 0:
            return None
        fails = self.fail_counts.get(provider.name, 0) + 1
        self.fail_counts[provider.name] = fails
        if retry_after:
            cd = retry_after
        else:
            cd = provider.cooldown_seconds * (2 ** min(fails - 1, 3))
        unlock = self.clock() + cd
        self.cooldowns[provider.name] = unlock
        return unlock

    @staticmethod
    def _rotate_model(provider):
        """A 404 from a multi-model route usually means THIS
        account cannot serve the lead model — the config
        lists spares precisely for that, but every transport
        sends models[0] only, so before v1.0.15 the spare
        was unreachable. Move the failed model to the back
        and let the walk retry the same route on the spare.
        The rotation persists for the process (the config
        object is per-process); a success on the new lead
        clears the route's health record as usual."""
        if len(provider.models) > 1:
            provider.models.append(provider.models.pop(0))
            return True
        return False

    def _wait_out_recovery(self, result, category, waits, waited_for=()):
        """True when this queued result should be waited out
        in-run: the earliest route recovery is near (inside
        route_wait_budget), this call has waits left, and
        this exact retry_at was not already waited out once
        (a re-walk that returns the same unlock made no
        progress — waiting on it again would only sleep
        twice for nothing). Sleeps until the unlock; the
        re-walk then re-probes the recovered route first,
        as the Fallback Law already prescribes."""
        if not result.get("queued") or waits >= MAX_ROUTE_WAITS:
            return False
        retry_at = result.get("retry_at") or 0
        if retry_at in waited_for:
            return False
        delay = retry_at - self.clock()
        if delay <= 0 or delay > self.route_wait_budget:
            return False
        self.audit.append(RouteEvent("none", category, "route-wait"))
        self.sleeper(delay + 0.05)
        return True

    def complete(self, messages, tools=None, category="task", private=False,
                 override_privacy=False):
        """Route one raw completion (Fallback Law + privacy routing,
        same rules as _chat_once) for the tool-call loop. Returns
        {"provider", "message"} or a queued dict; raises
        PrivacyBlocked / AllProvidersFailed exactly like chat().

        v1.0.15 (class 1): a queued result whose earliest
        recovery is near is waited out in-run and the walk
        retried — the caller only sees the queued result when
        recovery is genuinely minutes away."""
        waits, waited_for = 0, []
        while True:
            result = self._complete_walk(
                messages, tools=tools, category=category,
                private=private, override_privacy=override_privacy)
            if not self._wait_out_recovery(result, category, waits,
                                           waited_for):
                return result
            waited_for.append(result.get("retry_at") or 0)
            waits += 1

    def _complete_walk(self, messages, tools=None, category="task", private=False,
                       override_privacy=False):
        """One pass over the route chain (the pre-v1.0.15
        complete() body)."""
        now = self.clock()
        tried, unlock_times, saw_trusted = [], [], False
        for p in effective_providers(self.config.providers):
            if not p.enabled or not p.models:
                continue
            if private and not p.trusted_for_private and not override_privacy:
                self.audit.append(RouteEvent(p.name, category, "skipped-privacy"))
                continue
            if private and not p.trusted_for_private and override_privacy:
                self.audit.append(RouteEvent(p.name, category, "override-used"))
            if private:
                saw_trusted = True
            unlock = self.cooldowns.get(p.name, 0)
            if unlock > now:
                self.audit.append(RouteEvent(p.name, category, "cooldown-skip"))
                unlock_times.append(unlock)
                continue
            while True:
                if getattr(p, "kind", "openai") == "horde":
                    # Raw-completion route: it cannot honour NATIVE
                    # tool calls, but the loop's text protocol (one
                    # fenced JSON block per reply, parsed by the loop)
                    # runs fine over plain completions — so the loop
                    # path routes through it as the fallback of last
                    # resort, with the tool catalogue rendered as text
                    # (the route never sees the API tools parameter).
                    # Wired 2026-11-26 after the keyless primary hit
                    # its hard 100-replies/day cap (402 FREE_LIMIT)
                    # and the loop path had NO second route at all.
                    status, text, retry_after = self.horde_transport(
                        p, _with_tool_catalogue(messages, tools))
                    message = {"role": "assistant", "content": text} \
                        if status == 200 and text else None
                else:
                    status, message, retry_after = self.raw_transport(
                        p, messages, tools)
                if status == 404 and self._rotate_model(p):
                    self.audit.append(RouteEvent(p.name, category,
                                                 "model-rotated"))
                    continue
                break
            if status == 200 and message is not None and (
                    message.get("content") or message.get("tool_calls")):
                self.cooldowns.pop(p.name, None)
                self.fail_counts.pop(p.name, None)
                self.audit.append(RouteEvent(p.name, category, "ok"))
                return {"provider": p.name, "message": message}
            self.audit.append(RouteEvent(p.name, category, f"http-{status}"))
            tried.append((p.name, status))
            unlock = self._note_failure(p, status, retry_after)
            if unlock is not None:
                unlock_times.append(unlock)
        if private and not override_privacy and not saw_trusted:
            raise PrivacyBlocked(
                "Private content has no trusted provider enabled. Enable local-ollama "
                "or pass an explicit per-task override.")
        if unlock_times:
            return self._queued_result(category, unlock_times, private=private)
        raise AllProvidersFailed(f"All providers failed: {tried}")

    # ---------- routing ----------
    def _queued_result(self, category, unlock_times, private=False):
        earliest = min(unlock_times)
        # Seamless law (2026-11-26): the user-facing text is
        # ONE plain statement — no route names, no limits, no
        # mechanics. The retry time and the per-route facts
        # live in the result fields and the audit (evidence),
        # never in the sentence.
        text = (
            "I can't reach a brain just now — this is queued and will "
            "continue automatically; you don't need to re-send it."
        )
        if private:
            text = (
                "I can't reach a brain for private content just now — "
                "this is queued and will continue automatically. "
                "Private content is never sent to a public provider "
                "instead."
            )
        self.audit.append(RouteEvent("none", category, "queued-all-routes-down"))
        return {"provider": None, "text": text, "queued": True, "retry_at": earliest}

    def chat(self, messages, category="public", private=False, override_privacy=False):
        """Route one request, with refusal recovery (v0.2.1) and
        the chat-output guard (2026-11-26) — both INVISIBLE
        (seamless law): the user sees the answer, never the
        machinery that produced it.

        Refusal recovery: if the serving model refuses the
        *phrasing* of a vague outcome request, Lille reframes
        it ONCE into a concrete task form and retries through
        the chain. Refusals of genuinely harmful/illegal
        requests are never reframed — they are delivered
        plainly, as are second refusals. No note is prepended
        or appended either way (the recovery is recorded in
        the audit + the reframed_from_refusal flag only).

        Output guard: the final text is screened by
        chatguard (placeholder tokens, self-dialogue). A hit
        regenerates ONCE on the next route (the serving
        provider excluded); a second hit replaces the reply
        with chatguard.PLAIN_LINE and the rejected text
        stays in the audit only."""
        result = self._chat_once(messages, category=category,
                                 private=private, override_privacy=override_privacy)
        text = result.get("text") or ""
        if not result.get("queued") and recovery.is_refusal(text):
            user_text = ""
            for m in reversed(messages):
                if m.get("role") == "user":
                    user_text = m.get("content", "")
                    break
            if recovery.is_harmful_request(user_text):
                self.audit.append(RouteEvent(result.get("provider") or "none", category, "refusal-harmful-stands"))
                return result
            reframed = recovery.reframe_request(user_text)
            new_messages = list(messages)
            for i in range(len(new_messages) - 1, -1, -1):
                if new_messages[i].get("role") == "user":
                    new_messages[i] = {"role": "user", "content": reframed}
                    break
            self.audit.append(RouteEvent(result.get("provider") or "none", category, "refusal-reframed-retry"))
            retry = self._chat_once(new_messages, category=category,
                                    private=private, override_privacy=override_privacy)
            if retry.get("queued"):
                return retry
            retry["reframed_from_refusal"] = True
            result = retry
            text = result.get("text") or ""
        # ---- chat-output guard ----
        if result.get("queued") or not text:
            return result
        reason = chatguard.rejection_reason(text)
        if reason is None:
            return result
        provider = result.get("provider")
        self.audit.append(RouteEvent(provider or "none", category,
                                     f"guard-rejected-{reason}"))
        retry = self._chat_once(
            messages, category=category, private=private,
            override_privacy=override_privacy,
            exclude={provider} if provider else None)
        retry_text = retry.get("text") or ""
        if retry.get("queued"):
            return retry
        if retry_text and chatguard.rejection_reason(retry_text) is None:
            retry["guard_regenerated"] = True
            return retry
        self.audit.append(RouteEvent(provider or "none", category,
                                     "guard-rejected-twice"))
        return {"provider": provider, "text": chatguard.PLAIN_LINE,
                "guard_rejected": True}

    def _chat_once(self, messages, category="public", private=False, override_privacy=False,
                   exclude=None):
        """One logical chat call: a walk over the chain, plus
        (v1.0.15, class 1) in-run waiting when the walk ends
        queued with a near recovery — same contract as
        complete()."""
        waits, waited_for = 0, []
        while True:
            result = self._chat_walk(messages, category=category,
                                     private=private,
                                     override_privacy=override_privacy,
                                     exclude=exclude)
            if not self._wait_out_recovery(result, category, waits,
                                           waited_for):
                return result
            waited_for.append(result.get("retry_at") or 0)
            waits += 1

    def _chat_walk(self, messages, category="public", private=False, override_privacy=False,
                   exclude=None):
        now = self.clock()
        tried = []            # (provider, status) actually attempted
        unlock_times = []     # unlock ts of every route that is cooling
        saw_trusted = False   # a privacy-eligible route exists at all

        for p in effective_providers(self.config.providers):
            if not p.enabled or not p.models:
                continue
            if exclude and p.name in exclude:
                continue
            if private and not p.trusted_for_private and not override_privacy:
                self.audit.append(RouteEvent(p.name, category, "skipped-privacy"))
                continue
            if private and not p.trusted_for_private and override_privacy:
                self.audit.append(RouteEvent(p.name, category, "override-used"))
            if private:
                saw_trusted = True

            unlock = self.cooldowns.get(p.name, 0)
            if unlock > now:
                self.audit.append(RouteEvent(p.name, category, "cooldown-skip"))
                unlock_times.append(unlock)
                continue

            while True:
                status, text, retry_after = self._dispatch(p, messages)
                if status == 404 and self._rotate_model(p):
                    self.audit.append(RouteEvent(p.name, category,
                                                 "model-rotated"))
                    continue
                break
            if status == 200 and text:
                self.cooldowns.pop(p.name, None)
                self.fail_counts.pop(p.name, None)
                self.audit.append(RouteEvent(p.name, category, "ok"))
                return {"provider": p.name, "text": text}
            self.audit.append(RouteEvent(p.name, category, f"http-{status}"))
            tried.append((p.name, status))
            unlock = self._note_failure(p, status, retry_after)
            if unlock is not None:
                unlock_times.append(unlock)
            # Non-demote errors also fall through (availability beats
            # loyalty to one free provider); they just don't cool down.

        # Nothing served. Decide the honest ending:
        if private and not override_privacy and not saw_trusted:
            raise PrivacyBlocked(
                "Private content has no trusted provider enabled. Enable local-ollama "
                "or pass an explicit per-task override.")
        if unlock_times:
            # At least one route is merely cooling down: queue honestly
            # instead of dying. This covers both "all skipped for
            # cooldown" and "all attempted routes just got limited".
            return self._queued_result(category, unlock_times, private=private)
        raise AllProvidersFailed(f"All providers failed: {tried}")
