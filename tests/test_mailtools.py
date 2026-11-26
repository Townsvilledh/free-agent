"""v0.13 Gmail + Calendar tool tests — fake Google services, real
Sentinel, real redactor, stub vault. No network, no google libs:
the tools must behave honestly without them."""
import base64, json, os, shutil, sys, tempfile, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from free_agent import mailtools, specmode, tasktools
from free_agent.config import Config
from free_agent.loop import TaskLoop
from free_agent.vault import Vault


class _Exec:
    def __init__(self, value):
        self._v = value

    def execute(self):
        return self._v


def _b64(text):
    return base64.urlsafe_b64encode(text.encode()).decode()


BODY = (
    "Welcome to Agentware 2026!\n"
    "Join the event Discord here: https://discord.gg/AbC123xYz — "
    "perk codes drop in the server before kickoff.\n"
    "Your verification code is 482913 — do not share it.\n"
    "Reset link (do not forward): "
    "https://example.com/reset-password?token=topsecret123\n"
    "Card on file: 4111 1111 1111 1111\n"
    "More info: https://agentwarehack.com/brief?ref=email\n"
    + "Filler line about the hackathon schedule and tracks. " * 30)

MSG = {
    "id": "m1",
    "snippet": "Your verification code is 482913 — welcome aboard",
    "payload": {
        "headers": [
            {"name": "From",
             "value": "Agentware Event Team <official@agentwarehack.com>"},
            {"name": "Subject", "value": "You're registered for Agentware"},
            {"name": "Date", "value": "Thu, 26 Nov 2026 12:26:27 +0000"},
        ],
        "mimeType": "multipart/alternative",
        "parts": [
            {"mimeType": "text/plain",
             "body": {"data": _b64(BODY)}},
        ],
    },
}


class FakeGmail:
    def __init__(self, messages):
        self._messages = messages
        self.sent = []

    def users(self):
        return self

    def messages(self):
        return self

    def list(self, userId=None, q=None, maxResults=None):
        return _Exec({"messages": [{"id": i} for i in self._messages]})

    def get(self, userId=None, id=None, format=None, metadataHeaders=None):
        return _Exec(self._messages[id])

    def send(self, userId=None, body=None):
        self.sent.append(body)
        return _Exec({"id": "sent-1"})


class FakeCalendar:
    def __init__(self, items):
        self._items = items
        self.inserted = []

    def events(self):
        return self

    def list(self, **kw):
        return _Exec({"items": self._items})

    def get(self, calendarId=None, eventId=None):
        return _Exec(self._items[0])

    def insert(self, calendarId=None, body=None):
        self.inserted.append(body)
        return _Exec({"id": "ev-new"})


EVENT = {"id": "ev1", "summary": "Call with mentor +2348012345678",
         "location": "Lagos",
         "start": {"dateTime": "2026-11-27T17:00:00Z"},
         "end": {"dateTime": "2026-11-27T18:00:00Z"},
         "description": "Bring the code 7712 for the door"}


def native(name, args, cid="c1"):
    return {"content": None,
            "tool_calls": [{"id": cid, "name": name, "args": args}]}


def text(content):
    return {"content": content, "tool_calls": None}


class ScriptedModel:
    def __init__(self, replies):
        self.replies = list(replies)

    def __call__(self, messages, tools):
        if self.replies:
            return self.replies.pop(0)
        return text("script exhausted")


class MailtoolsBase(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-mail-")
        self.vault = Vault(os.path.join(self.root, "vault.enc"),
                           insecure_stub=True)
        mailtools.set_vault_override(self.vault)
        self.gmail = FakeGmail({"m1": MSG})
        self.calendar = FakeCalendar([EVENT])
        mailtools.set_factory("gmail", lambda creds: self.gmail)
        mailtools.set_factory("calendar", lambda creds: self.calendar)

    def tearDown(self):
        mailtools.set_vault_override(None)
        mailtools.set_factory("gmail", None)
        mailtools.set_factory("calendar", None)
        tasktools.set_runtime_context({})
        shutil.rmtree(self.root, ignore_errors=True)

    def set_token_scopes(self, scopes):
        self.vault.set(mailtools.TOKEN_VAULT_KEY, json.dumps(
            {"refresh_token": "rt", "token": "at", "scopes": scopes}))


class UnconfiguredTests(unittest.TestCase):
    """No token in the vault -> the honest NOT_CONNECTED state,
    never a fake failure and never an ask-the-user workaround."""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-mail-")
        mailtools.set_vault_override(Vault(
            os.path.join(self.root, "vault.enc"), insecure_stub=True))

    def tearDown(self):
        mailtools.set_vault_override(None)
        shutil.rmtree(self.root, ignore_errors=True)

    def test_gmail_search_not_connected(self):
        out = mailtools.gmail_search(self.root, {"query": "discord"})
        self.assertTrue(out.startswith("GMAIL_NOT_CONNECTED"), out)
        self.assertIn("--google-connect", out)
        self.assertIn("Do NOT ask the user to fetch", out)

    def test_calendar_list_not_connected(self):
        out = mailtools.calendar_list(self.root, {})
        self.assertTrue(out.startswith("CALENDAR_NOT_CONNECTED"), out)
        self.assertIn("--google-connect", out)

    def test_connection_state_no_token(self):
        connected, reason, scopes = mailtools.connection_state()
        self.assertFalse(connected)
        self.assertEqual(reason, "no-token")
        self.assertEqual(scopes, [])


class GmailReadTests(MailtoolsBase):
    def test_search_redacts_codes_and_addresses(self):
        out = mailtools.gmail_search(self.root, {"query": "agentware"})
        self.assertIn("GMAIL_SEARCH_OK results=1", out)
        self.assertNotIn("482913", out)          # one-time code
        self.assertIn("REDACTED-CODE", out)
        self.assertNotIn("official@agentwarehack.com", out)
        self.assertIn("Agentware Event Team", out)  # name survives

    def test_read_snippet_cap_and_redaction(self):
        out = mailtools.gmail_read(self.root, {"message_id": "m1"})
        self.assertIn("GMAIL_READ_OK id=m1", out)
        self.assertNotIn("482913", out)
        self.assertNotIn("4111 1111 1111 1111", out)
        self.assertIn("REDACTED-CARD", out)
        body_section = out.split("model):", 1)[1].split("links found")[0]
        self.assertLessEqual(len(body_section.strip()), 520)

    def test_read_extracts_invite_link_drops_credential_links(self):
        out = mailtools.gmail_read(self.root, {"message_id": "m1"})
        self.assertIn("https://discord.gg/AbC123xYz", out)
        self.assertNotIn("topsecret123", out)
        # the reset link must not appear in the extracted links
        # (the channel that hands a usable link onward) — its
        # token is redacted in the body snippet, and the link
        # itself is dropped from the links list
        links_section = out.split("links found", 1)[1]
        self.assertNotIn("example.com", links_section)
        self.assertNotIn("reset-password", links_section)
        # query strings are stripped from surviving extracted links
        self.assertIn("https://agentwarehack.com/brief", links_section)
        self.assertNotIn("ref=email", links_section)

    def test_read_needs_message_id(self):
        out = mailtools.gmail_read(self.root, {})
        self.assertTrue(out.startswith("error:"), out)


class CalendarReadTests(MailtoolsBase):
    def test_list_redacts_phone_in_summary(self):
        out = mailtools.calendar_list(self.root, {"days": 7})
        self.assertIn("CALENDAR_LIST_OK events=1", out)
        self.assertNotIn("+2348012345678", out)
        self.assertIn("Call with mentor", out)

    def test_get_redacts_description_code(self):
        out = mailtools.calendar_get(self.root, {"event_id": "ev1"})
        self.assertIn("CALENDAR_GET_OK id=ev1", out)
        self.assertNotIn("7712", out)


class SentinelGateTests(unittest.TestCase):
    SEND = {"to": "a@b.com", "subject": "hi", "body": "x"}
    CREATE = {"summary": "x", "start": "2026-11-27T18:00:00Z",
              "end": "2026-11-27T19:00:00Z"}

    def test_send_denied_by_default(self):
        ok, why = tasktools.Sentinel().check(
            "gmail_send", self.SEND, "/tmp")
        self.assertFalse(ok)
        self.assertIn("disabled by default", why)

    def test_send_denied_with_flag_but_default_approver(self):
        s = tasktools.Sentinel(spec_context={"gmail_send_enabled": True})
        ok, why = s.check("gmail_send", self.SEND, "/tmp")
        self.assertFalse(ok)
        self.assertIn("approval hook", why)

    def test_send_denied_when_approver_refuses(self):
        s = tasktools.Sentinel(approver=lambda n, s_: False,
                               spec_context={"gmail_send_enabled": True})
        ok, _why = s.check("gmail_send", self.SEND, "/tmp")
        self.assertFalse(ok)

    def test_send_allowed_only_with_flag_and_real_approver(self):
        s = tasktools.Sentinel(approver=lambda n, s_: True,
                               spec_context={"gmail_send_enabled": True})
        ok, _why = s.check("gmail_send", self.SEND, "/tmp")
        self.assertTrue(ok)

    def test_create_denied_by_default(self):
        ok, why = tasktools.Sentinel().check(
            "calendar_create", self.CREATE, "/tmp")
        self.assertFalse(ok)
        self.assertIn("disabled by default", why)

    def test_create_allowed_only_with_flag_and_real_approver(self):
        s = tasktools.Sentinel(
            approver=lambda n, s_: True,
            spec_context={"calendar_write_enabled": True})
        ok, _why = s.check("calendar_create", self.CREATE, "/tmp")
        self.assertTrue(ok)

    def test_read_tools_on_allowlist(self):
        s = tasktools.Sentinel()
        for name, args in (("gmail_search", {"query": "x"}),
                           ("gmail_read", {"message_id": "m1"}),
                           ("calendar_list", {}),
                           ("calendar_get", {"event_id": "e1"})):
            ok, why = s.check(name, args, "/tmp")
            self.assertTrue(ok, f"{name}: {why}")


class HandlerGateTests(MailtoolsBase):
    def test_send_blocked_without_flag(self):
        out = mailtools.gmail_send(
            self.root, {"to": "a@b.com", "subject": "s", "body": "b"}, {})
        self.assertTrue(out.startswith("error:"), out)
        self.assertIn("disabled by default", out)
        self.assertEqual(self.gmail.sent, [])

    def test_send_blocked_without_approval(self):
        ctx = {"gmail_send_enabled": True,
               "_config": Config(gmail_send_enabled=True),
               "_approver": None}
        out = mailtools.gmail_send(
            self.root, {"to": "a@b.com", "subject": "s", "body": "b"}, ctx)
        self.assertTrue(out.startswith("error:"), out)
        self.assertIn("Approval required", out)
        self.assertEqual(self.gmail.sent, [])

    def test_send_blocked_without_scope(self):
        self.set_token_scopes(mailtools.READ_SCOPES)
        ctx = {"gmail_send_enabled": True,
               "_config": Config(gmail_send_enabled=True),
               "_approver": lambda action, detail: True}
        out = mailtools.gmail_send(
            self.root, {"to": "a@b.com", "subject": "s", "body": "b"}, ctx)
        self.assertTrue(out.startswith("error:"), out)
        self.assertIn("gmail.send", out)
        self.assertEqual(self.gmail.sent, [])

    def test_send_runs_when_all_gates_pass(self):
        self.set_token_scopes(
            mailtools.READ_SCOPES + [mailtools.GMAIL_SEND_SCOPE])
        ctx = {"gmail_send_enabled": True,
               "_config": Config(gmail_send_enabled=True),
               "_approver": lambda action, detail: True}
        out = mailtools.gmail_send(
            self.root, {"to": "a@b.com", "subject": "Hello there",
                        "body": "body text"}, ctx)
        self.assertEqual(out, "GMAIL_SEND_OK id=sent-1")
        self.assertEqual(len(self.gmail.sent), 1)
        raw = base64.urlsafe_b64decode(
            self.gmail.sent[0]["raw"]).decode()
        self.assertIn("Subject: Hello there", raw)

    def test_create_blocked_without_flag(self):
        out = mailtools.calendar_create(
            self.root, {"summary": "x", "start": "2026-11-27T18:00:00Z",
                        "end": "2026-11-27T19:00:00Z"}, {})
        self.assertTrue(out.startswith("error:"), out)
        self.assertEqual(self.calendar.inserted, [])

    def test_create_runs_when_all_gates_pass(self):
        self.set_token_scopes(
            mailtools.READ_SCOPES + [mailtools.CALENDAR_WRITE_SCOPE])
        ctx = {"calendar_write_enabled": True,
               "_config": Config(calendar_write_enabled=True),
               "_approver": lambda action, detail: True}
        out = mailtools.calendar_create(
            self.root, {"summary": "Kickoff", "start": "2026-11-27T18:00:00Z",
                        "end": "2026-11-27T19:00:00Z"}, ctx)
        self.assertEqual(out, "CALENDAR_CREATE_OK id=ev-new")
        self.assertEqual(self.calendar.inserted[0]["summary"], "Kickoff")


class SummarySafetyTests(unittest.TestCase):
    def test_gmail_send_summary_hides_address_and_body(self):
        s = tasktools.args_summary("gmail_send", {
            "to": "kanu.secret@example.com", "subject": "private subject",
            "body": "private body"})
        self.assertNotIn("kanu.secret", s)
        self.assertNotIn("private", s)
        self.assertIn("to_domain=example.com", s)

    def test_gmail_search_summary_redacts_query(self):
        s = tasktools.args_summary(
            "gmail_search", {"query": "from:official@agentwarehack.com"})
        self.assertNotIn("official@agentwarehack.com", s)


class LoopIntegrationTests(MailtoolsBase):
    def test_loop_gmail_search_then_finish(self):
        model = ScriptedModel([
            native("gmail_search", {"query": "agentware discord"}),
            native("finish", {"summary": "found the invite mail"}),
        ])
        loop = TaskLoop(model_call=model, workspace_root=self.root,
                        tool_names=list(tasktools.MAIL_TOOLS) + ["finish"])
        res = loop.run("Find the Discord invite in my Gmail")
        self.assertEqual(res.status, "done", res.trace_text())
        search_steps = [s for s in res.steps if s.tool == "gmail_search"]
        self.assertEqual(len(search_steps), 1)
        self.assertIn("GMAIL_SEARCH_OK", search_steps[0].result_summary)
        self.assertNotIn("482913", res.trace_text())

    def test_loop_gmail_send_is_sentinel_denied_by_default(self):
        model = ScriptedModel([
            native("gmail_send", {"to": "a@b.com", "subject": "s",
                                  "body": "b"}),
            native("finish", {"summary": "send was denied"}),
        ])
        loop = TaskLoop(model_call=model, workspace_root=self.root,
                        tool_names=list(tasktools.MAIL_TOOLS) + ["finish"])
        res = loop.run("Send an email")
        self.assertIn("sentinel-denied", [s.kind for s in res.steps])
        self.assertEqual(self.gmail.sent, [])


class SpecWiringTests(unittest.TestCase):
    SPEC = """Goal
    Read the Agentware registration email in Gmail and note the
    Discord invite; check the calendar for the kickoff.

    Acceptance criteria
    - [ ] The invite link from the email is recorded
    """

    def test_spec_tool_names_include_mail_and_calendar(self):
        spec = specmode.parse_spec(self.SPEC)
        names = specmode.spec_tool_names(spec)
        for n in tasktools.MAIL_TOOLS + tasktools.CALENDAR_TOOLS:
            self.assertIn(n, names)

    def test_plain_spec_has_no_mail_tools(self):
        spec = specmode.parse_spec(
            "Goal\nBuild a calculator.\n\nAcceptance criteria\n"
            "- [ ] Tests pass\n")
        names = specmode.spec_tool_names(spec)
        self.assertNotIn("gmail_search", names)
        self.assertNotIn("calendar_list", names)

    def test_task_text_carries_not_connected_honesty(self):
        spec = specmode.parse_spec(self.SPEC)
        txt = specmode.build_task_text(spec, "repo note")
        self.assertIn("GMAIL_NOT_CONNECTED", txt)
        self.assertIn("Never ask the user to fetch", txt)


class ConnectTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-mail-")
        self.vault = Vault(os.path.join(self.root, "vault.enc"),
                           insecure_stub=True)
        mailtools.set_vault_override(self.vault)
        self.captured = {}

    def tearDown(self):
        mailtools.set_vault_override(None)
        shutil.rmtree(self.root, ignore_errors=True)

    def test_missing_client_secrets_gives_setup_instructions(self):
        ok, msg = mailtools.connect(
            client_secrets=os.path.join(self.root, "nope.json"))
        self.assertFalse(ok)
        self.assertIn("Desktop app", msg)
        self.assertIn("--google-connect", msg)

    def test_connect_stores_token_in_vault_readonly_by_default(self):
        secrets_path = os.path.join(self.root, "client_secrets.json")
        with open(secrets_path, "w") as f:
            f.write("{}")

        class FakeCreds:
            def to_json(self):
                return json.dumps(
                    {"refresh_token": "rt-value",
                     "token": "at-value",
                     "scopes": self_captured["scopes"]})

        class FakeFlow:
            def run_local_server(self, port=0):
                return FakeCreds()

        self_captured = self.captured

        def factory(path, scopes):
            self.captured["scopes"] = list(scopes)
            self.captured["path"] = path
            return FakeFlow()

        ok, msg = mailtools.connect(client_secrets=secrets_path,
                                    flow_factory=factory)
        self.assertTrue(ok, msg)
        self.assertEqual(self.captured["scopes"], mailtools.READ_SCOPES)
        stored = self.vault.get(mailtools.TOKEN_VAULT_KEY)
        self.assertIsNotNone(stored)
        self.assertEqual(mailtools.granted_scopes(),
                         mailtools.READ_SCOPES)
        self.assertNotIn("rt-value", msg)  # token never in output

    def test_connect_with_send_adds_only_the_send_scope(self):
        secrets_path = os.path.join(self.root, "client_secrets.json")
        with open(secrets_path, "w") as f:
            f.write("{}")

        class FakeCreds:
            def to_json(self):
                return json.dumps({"refresh_token": "r", "token": "t",
                                   "scopes": []})

        class FakeFlow:
            def run_local_server(self, port=0):
                return FakeCreds()

        def factory(path, scopes):
            self.captured["scopes"] = list(scopes)
            return FakeFlow()

        ok, _msg = mailtools.connect(client_secrets=secrets_path,
                                     with_send=True,
                                     flow_factory=factory)
        self.assertTrue(ok)
        self.assertIn(mailtools.GMAIL_SEND_SCOPE, self.captured["scopes"])
        self.assertNotIn(mailtools.CALENDAR_WRITE_SCOPE,
                         self.captured["scopes"])


if __name__ == "__main__":
    unittest.main()
