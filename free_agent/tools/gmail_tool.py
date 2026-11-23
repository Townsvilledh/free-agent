"""Gmail tool — least-privilege OAuth2, local-first, redacted output.

Scopes: starts with gmail.readonly ONLY. Send/modify scopes are separate,
disabled by default, and require FREEAGENT_GMAIL_SEND=1 plus per-send
user approval (see approvals.py).

Flow: OAuth token lives ONLY in the vault. Messages are fetched to the
host, then passed through redact.snippet() — an LLM only ever receives a
short redacted snippet, never a full mailbox dump. One-time codes are
redacted by redact.py and must never be logged/stored/sent to a model.
"""
from ..redact import snippet
from ..approvals import require_approval

READONLY_SCOPES = ["https://www.googleapis.com/auth/gmail.readonly"]
SEND_SCOPES = ["https://www.googleapis.com/auth/gmail.send"]  # opt-in only

class GmailTool:
    def __init__(self, vault, config, service=None):
        """service: a googleapiclient Gmail service, or None (stub mode)."""
        self.vault, self.config, self.service = vault, config, service

    def _require_service(self):
        if self.service is None:
            raise RuntimeError(
                "Gmail not connected. Run setup (SETUP.md): OAuth with the "
                "readonly scope, token saved to the vault. No token in chat/code.")

    # ---- read path (allowed within granted scope) ----
    def search(self, query, max_results=10):
        self._require_service()
        res = self.service.users().messages().list(
            userId="me", q=query, maxResults=max_results).execute()
        return [m["id"] for m in res.get("messages", [])]

    def get_snippet_for_llm(self, message_id):
        """The ONLY way mail content may leave this tool toward an LLM."""
        self._require_service()
        msg = self.service.users().messages().get(
            userId="me", id=message_id, format="metadata").execute()
        return snippet(msg.get("snippet", ""), limit=500)

    # ---- write path (gated twice: config opt-in AND user approval) ----
    def send(self, to, subject, body, approver=None):
        if not self.config.gmail_send_enabled:
            raise RuntimeError("Gmail send is disabled (FREEAGENT_GMAIL_SEND != 1).")
        require_approval(self.config, "send_email", approver,
                         detail=f"to domain only logged; subject length {len(subject)}")
        self._require_service()
        raise NotImplementedError("Send wiring lands after readonly path is verified live.")
