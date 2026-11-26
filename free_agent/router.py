"""Provider router: FALLBACK LAW + PRIVACY ROUTING.

Fallback law (Lubrica's routing discipline, applied to AI providers):
every capability has an ordered route chain (see routes.py /
ROUTES.md). The router:

- tries routes in order — the KEYLESS public provider (Pollinations)
  is the default first brain; optional personal-key providers
  (AgentRouter, Groq, ...) follow, enabled only when the user supplied
  their OWN key;
- respects rate limits: on HTTP 402/429/503 (or a network error) it
  COOLS THAT ROUTE DOWN — records an unlock time from the provider's
  Retry-After when the transport reports one, else the route's
  configured cooldown — and moves to the next route automatically;
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

from . import recovery

FALLBACK_STATUSES = {402, 429, 503}
MAX_STREAM_CONTINUATIONS = 3

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
            "Client-Agent": "lille-core:0.12.0:free-agent",
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

    def complete(self, messages, tools=None, category="task", private=False,
                 override_privacy=False):
        """Route one raw completion (Fallback Law + privacy routing,
        same rules as _chat_once) for the tool-call loop. Returns
        {"provider", "message"} or a queued dict; raises
        PrivacyBlocked / AllProvidersFailed exactly like chat()."""
        now = self.clock()
        tried, unlock_times, saw_trusted = [], [], False
        for p in self.config.providers:
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
            if status == 200 and message is not None and (
                    message.get("content") or message.get("tool_calls")):
                self.cooldowns.pop(p.name, None)
                self.fail_counts.pop(p.name, None)
                self.audit.append(RouteEvent(p.name, category, "ok"))
                return {"provider": p.name, "message": message}
            self.audit.append(RouteEvent(p.name, category, f"http-{status}"))
            tried.append((p.name, status))
            if status in FALLBACK_STATUSES or status == 0:
                # v0.12 route health: repeated failures
                # escalate this route's cooldown (x1, x2, x4,
                # x8 cap) — a demotion that recovers on its
                # own when the cooldown expires and the next
                # probe succeeds (success pops both records).
                fails = self.fail_counts.get(p.name, 0) + 1
                self.fail_counts[p.name] = fails
                if retry_after:
                    cd = retry_after
                else:
                    cd = p.cooldown_seconds * (2 ** min(fails - 1, 3))
                unlock = self.clock() + cd
                self.cooldowns[p.name] = unlock
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
        text = (
            "All routes for this are cooling down right now (free-tier rate "
            f"limits). Earliest retry at {_fmt_ts(earliest)}. Your task is "
            "queued and will continue automatically — you don't need to "
            "re-send it."
        )
        if private:
            text = (
                "The trusted route for private content is cooling down right "
                f"now. Earliest retry at {_fmt_ts(earliest)}. Your task is "
                "queued — private content is never rerouted to a public or "
                "untrusted provider just because one is available."
            )
        self.audit.append(RouteEvent("none", category, "queued-all-routes-down"))
        return {"provider": None, "text": text, "queued": True, "retry_at": earliest}

    def chat(self, messages, category="public", private=False, override_privacy=False):
        """Route one request, with refusal recovery (v0.2.1).

        If the serving model refuses the *phrasing* of a vague outcome
        request, Lille reframes it ONCE into a concrete task form and
        retries through the chain, telling the user it did so.
        Refusals of genuinely harmful/illegal requests are never
        reframed — they are delivered plainly, as are second refusals
        (with a suggested manual rephrase appended)."""
        result = self._chat_once(messages, category=category,
                                 private=private, override_privacy=override_privacy)
        text = result.get("text") or ""
        if result.get("queued") or not recovery.is_refusal(text):
            return result
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
        retry_text = retry.get("text") or ""
        if retry.get("queued"):
            return retry
        if recovery.is_refusal(retry_text):
            retry["text"] = retry_text + recovery.REFUSAL_STILL
            return retry
        retry["text"] = recovery.REFUSAL_NOTE + "\n\n" + retry_text
        retry["reframed_from_refusal"] = True
        return retry

    def _chat_once(self, messages, category="public", private=False, override_privacy=False):
        now = self.clock()
        tried = []            # (provider, status) actually attempted
        unlock_times = []     # unlock ts of every route that is cooling
        saw_trusted = False   # a privacy-eligible route exists at all

        for p in self.config.providers:
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

            status, text, retry_after = self._dispatch(p, messages)
            if status == 200 and text:
                self.cooldowns.pop(p.name, None)
                self.fail_counts.pop(p.name, None)
                self.audit.append(RouteEvent(p.name, category, "ok"))
                return {"provider": p.name, "text": text}
            self.audit.append(RouteEvent(p.name, category, f"http-{status}"))
            tried.append((p.name, status))
            if status in FALLBACK_STATUSES or status == 0:
                # v0.12 route health: repeated failures
                # escalate this route's cooldown (x1, x2, x4,
                # x8 cap) — a demotion that recovers on its
                # own when the cooldown expires and the next
                # probe succeeds (success pops both records).
                fails = self.fail_counts.get(p.name, 0) + 1
                self.fail_counts[p.name] = fails
                if retry_after:
                    cd = retry_after
                else:
                    cd = p.cooldown_seconds * (2 ** min(fails - 1, 3))
                unlock = self.clock() + cd
                self.cooldowns[p.name] = unlock
                unlock_times.append(unlock)
            # Non-quota errors also fall through (availability beats
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
