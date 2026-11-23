"""Provider router: fallback + PRIVACY ROUTING.

Fallback: AgentRouter first; on HTTP 402 (daily batch quota exhausted),
429, 503, or a network error, fall through to Groq, then other enabled
free providers in config order.

Privacy routing (strict mode): if content is marked private (Gmail, other
account data), providers NOT marked trusted_for_private are SKIPPED —
AgentRouter is a community relay with unknown retention, Groq is external.
Private content goes to a trusted provider (e.g. local Ollama) or is
handled by the local redacted-summarisation path. A per-task explicit
override flag is the only way past this, and it is logged (metadata only).

Audit log: provider name + content category + timestamp ONLY. Never content.
"""
import json, urllib.request, urllib.error
from dataclasses import dataclass

FALLBACK_STATUSES = {402, 429, 503}

@dataclass
class RouteEvent:
    provider: str
    category: str      # e.g. "public", "private:gmail" — never content
    outcome: str       # ok | skipped-privacy | http-<code> | error

class PrivacyBlocked(RuntimeError):
    pass

class AllProvidersFailed(RuntimeError):
    pass

class Router:
    def __init__(self, config, transport=None, audit=None):
        self.config = config
        self.transport = transport or self._http_transport
        self.audit = audit if audit is not None else []   # list[RouteEvent]

    def _http_transport(self, provider, messages):
        """OpenAI-compatible chat call. Returns (status, text)."""
        body = json.dumps({"model": provider.models[0], "messages": messages}).encode()
        req = urllib.request.Request(provider.base_url.rstrip("/") + "/chat/completions",
                                     data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        key = provider.key()
        if key:
            req.add_header("Authorization", "Bearer " + key)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                data = json.loads(r.read().decode())
                return r.status, data["choices"][0]["message"]["content"]
        except urllib.error.HTTPError as e:
            return e.code, ""
        except Exception:
            return 0, ""

    def chat(self, messages, category="public", private=False, override_privacy=False):
        tried = []
        for p in self.config.providers:
            if not p.enabled or not p.models:
                continue
            if private and not p.trusted_for_private and not override_privacy:
                self.audit.append(RouteEvent(p.name, category, "skipped-privacy"))
                continue
            if private and not p.trusted_for_private and override_privacy:
                self.audit.append(RouteEvent(p.name, category, "override-used"))
            status, text = self.transport(p, messages)
            if status == 200 and text:
                self.audit.append(RouteEvent(p.name, category, "ok"))
                return {"provider": p.name, "text": text}
            self.audit.append(RouteEvent(p.name, category, f"http-{status}"))
            tried.append((p.name, status))
            if status not in FALLBACK_STATUSES and status != 0:
                # Non-quota errors also fall through: availability beats loyalty
                # to one free provider. Recorded above either way.
                pass
        if private and not override_privacy and all(
                e.outcome == "skipped-privacy" for e in self.audit if e.category == category):
            raise PrivacyBlocked(
                "Private content has no trusted provider enabled. Enable local-ollama "
                "or pass an explicit per-task override.")
        raise AllProvidersFailed(f"All providers failed: {tried}")
