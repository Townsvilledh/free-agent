"""Gmail tool — least-privilege OAuth2, local-first, redacted output.

v0.1 skeleton, rewired in v0.13: the real implementation lives in
free_agent/mailtools.py (the loop's gmail_search / gmail_read /
gmail_send tools, the vault-held token, the one-time
--google-connect setup). This class remains as a thin facade over
that layer so there is exactly ONE Gmail implementation.

Scopes: gmail.readonly ONLY by default. The send scope is a
separate opt-in at connect time, and sending is additionally
gated by FREEAGENT_GMAIL_SEND=1 plus per-send user approval.
Messages are fetched to the host, then passed through
redact.snippet() — an LLM only ever receives a short redacted
snippet, never a full mailbox dump. One-time codes are redacted
by redact.py and must never be logged/stored/sent to a model.
"""
from .. import mailtools
from ..redact import snippet

READONLY_SCOPES = mailtools.READ_SCOPES
SEND_SCOPES = [mailtools.GMAIL_SEND_SCOPE]  # opt-in only


class GmailTool:
    def __init__(self, vault=None, config=None, service=None):
        """service: a googleapiclient Gmail service, or None to use
        the vault-connected one from mailtools."""
        self.vault, self.config, self.service = vault, config, service

    def _service(self):
        if self.service is not None:
            return self.service
        svc, reason = mailtools._service("gmail")
        if svc is None:
            raise RuntimeError(mailtools._not_connected(
                mailtools.GMAIL_NOT_CONNECTED, reason))
        return svc

    # ---- read path (allowed within granted scope) ----
    def search(self, query, max_results=10):
        svc = self._service()
        res = svc.users().messages().list(
            userId="me", q=query, maxResults=max_results).execute()
        return [m["id"] for m in res.get("messages", [])]

    def get_snippet_for_llm(self, message_id):
        """The ONLY way mail content may leave this tool toward an LLM."""
        svc = self._service()
        msg = svc.users().messages().get(
            userId="me", id=message_id, format="metadata").execute()
        return snippet(msg.get("snippet", ""), limit=500)

    # ---- write path (gated: config opt-in AND scope AND approval,
    # all enforced inside mailtools.gmail_send) ----
    def send(self, to, subject, body, approver=None):
        ctx = {"gmail_send_enabled": bool(
                   self.config and self.config.gmail_send_enabled),
               "_config": self.config, "_approver": approver}
        out = mailtools.gmail_send(None, {"to": to, "subject": subject,
                                          "body": body}, ctx)
        if out.startswith("error:"):
            raise RuntimeError(out[len("error: "):])
        return out
