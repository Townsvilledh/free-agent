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

FALLBACK_STATUSES = {402, 429, 503}

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

class Router:
    def __init__(self, config, transport=None, audit=None, clock=None):
        self.config = config
        self.transport = transport or self._http_transport
        self.audit = audit if audit is not None else []   # list[RouteEvent]
        self.clock = clock or time.time                   # injectable for tests
        self.cooldowns = {}                               # provider -> unlock ts

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

            status, text, retry_after = self._call(p, messages)
            if status == 200 and text:
                self.cooldowns.pop(p.name, None)
                self.audit.append(RouteEvent(p.name, category, "ok"))
                return {"provider": p.name, "text": text}
            self.audit.append(RouteEvent(p.name, category, f"http-{status}"))
            tried.append((p.name, status))
            if status in FALLBACK_STATUSES or status == 0:
                cd = retry_after if retry_after else p.cooldown_seconds
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
