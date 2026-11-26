"""v0.8 browser control + X lane tests.

Deterministic machinery tests (allowlist, secret gates, challenge
detection, vault metadata) run everywhere; the live-driver
integration test runs only where node + a Chromium binary exist
(it is skipped, never faked, elsewhere).
"""
import json, os, shutil, sys, tempfile, threading, unittest
from http.server import HTTPServer, SimpleHTTPRequestHandler

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from free_agent import browsertools, specmode, tasktools
from free_agent.loop import TaskLoop
from free_agent.specmode import parse_spec

try:
    import cryptography  # noqa: F401
    HAVE_CRYPTO = True
except ImportError:
    HAVE_CRYPTO = False


def native(name, args, cid="c1"):
    return {"content": None,
            "tool_calls": [{"id": cid, "name": name, "args": args}]}


class ScriptedModel:
    def __init__(self, replies):
        self.replies = list(replies)

    def __call__(self, messages, tools):
        if self.replies:
            return self.replies.pop(0)
        return {"content": "script exhausted", "tool_calls": None}


class AllowlistTests(unittest.TestCase):
    def sentinel(self, **ctx):
        base = {"push_branches": [], "main_authorised": False}
        base.update(ctx)
        return tasktools.Sentinel(spec_context=base)

    def test_no_domains_no_browsing(self):
        s = self.sentinel()
        ok, why = s.check("browser_open", {"url": "https://x.com/home"},
                          "/tmp")
        self.assertFalse(ok)
        self.assertIn("no browser domains", why)

    def test_domain_allow_and_deny(self):
        s = self.sentinel(browser_domains=["example.com", "x.com"])
        ok, _ = s.check("browser_open",
                        {"url": "https://example.com/a"}, "/tmp")
        self.assertTrue(ok)
        ok, _ = s.check("browser_open",
                        {"url": "https://sub.example.com/a"}, "/tmp")
        self.assertTrue(ok)
        ok, why = s.check("browser_open",
                          {"url": "https://evil.com/a"}, "/tmp")
        self.assertFalse(ok)
        self.assertIn("not in this task's browser allowlist", why)
        ok, _ = s.check("browser_open",
                        {"url": "file:///etc/passwd"}, "/tmp")
        self.assertFalse(ok)

    def test_x_tools_need_x_com(self):
        s = self.sentinel(browser_domains=["example.com"])
        ok, why = s.check("x_read_feed", {}, "/tmp")
        self.assertFalse(ok)
        self.assertIn("x.com", why)
        s2 = self.sentinel(browser_domains=["x.com"])
        ok, _ = s2.check("x_read_feed", {}, "/tmp")
        self.assertTrue(ok)


class XPostGateTests(unittest.TestCase):
    def sentinel(self, **ctx):
        base = {"push_branches": [], "main_authorised": False,
                "browser_domains": ["x.com"]}
        base.update(ctx)
        return tasktools.Sentinel(spec_context=base)

    def test_default_deny(self):
        ok, why = self.sentinel().check(
            "x_post", {"text": "hi"}, "/tmp")
        self.assertFalse(ok)
        self.assertIn("not authorised", why)

    def test_user_account_needs_explicit_approval(self):
        s = self.sentinel(x_post_mode="user")
        ok, why = s.check("x_post", {"text": "hi"}, "/tmp")
        self.assertFalse(ok)
        self.assertIn("explicit approval", why)
        s2 = self.sentinel(x_post_mode="user", x_post_approved=True)
        ok, _ = s2.check("x_post", {"text": "hi"}, "/tmp")
        self.assertTrue(ok)

    def test_own_account_allowed_by_lane(self):
        s = self.sentinel(x_post_mode="own")
        ok, _ = s.check("x_post", {"text": "hi"}, "/tmp")
        self.assertTrue(ok)


class SecretGateTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="lille-browser-")
        browsertools._SESSION.last_elements = {
            3: {"ref": 3, "tag": "input", "type": "password",
                "secret": True},
            4: {"ref": 4, "tag": "input", "type": "text"},
        }
        browsertools.set_context({"browser_domains": ["example.com"]})
        browsertools._SESSION.current_url = "https://example.com/form"

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)
        browsertools._SESSION.last_elements = {}
        browsertools._SESSION.current_url = ""

    def test_browser_type_refuses_password_field_from_snapshot(self):
        out = browsertools.h_browser_type(
            self.root, {"ref": 3, "text": "hunter2"})
        self.assertIn("SECRET_FIELD", out)
        self.assertIn("browser_type_secret", out)

    def test_args_summaries_never_carry_secret_values(self):
        s = tasktools.args_summary(
            "browser_type_secret",
            {"ref": 3, "vault_key": "x_password"})
        self.assertIn("x_password", s)
        s2 = tasktools.args_summary(
            "browser_type", {"ref": 4, "text": "someone@example.com"})
        self.assertNotIn("someone@example.com", s2)
        s3 = tasktools.args_summary("vault_gen_secret",
                                    {"key": "x_password"})
        self.assertIn("never logged", s3)

    def test_type_secret_value_never_reaches_trace(self):
        secret = "Sup3rSecretValue-12345"
        seen = {}

        def fake_rpc(action, **kw):
            seen[action] = kw
            return {"url": "https://example.com/form", "title": "F",
                    "text": "form", "elements": []}

        orig_rpc, orig_val = browsertools._rpc, browsertools._vault_value
        browsertools._rpc = fake_rpc
        browsertools._vault_value = lambda k: secret
        try:
            out = browsertools.h_browser_type_secret(
                self.root, {"ref": 3, "vault_key": "x_password"})
        finally:
            browsertools._rpc, browsertools._vault_value = orig_rpc, orig_val
        self.assertIn("TYPE_SECRET_OK", out)
        self.assertNotIn(secret, out)
        # The value DID cross to the driver (that's the point)...
        self.assertEqual(seen["type"]["text"], secret)
        # ...but a full loop run's persisted trace must not hold it.
        replies = [
            native("browser_type_secret",
                   {"ref": 3, "vault_key": "x_password"}),
            native("finish", {"summary": "typed it", "outputs": []}),
        ]
        browsertools._rpc = fake_rpc
        browsertools._vault_value = lambda k: secret
        try:
            loop = TaskLoop(model_call=ScriptedModel(replies),
                            workspace_root=self.root,
                            sentinel_context={
                                "browser_domains": ["example.com"]})
            res = loop.run("type the password")
        finally:
            browsertools._rpc, browsertools._vault_value = orig_rpc, orig_val
        self.assertEqual(res.status, "done")
        trace_path = os.path.join(
            self.root, "task_logs", res.task_id + ".json")
        with open(trace_path) as f:
            self.assertNotIn(secret, f.read())
        for step in res.steps:
            self.assertNotIn(secret, step.args_summary)
            self.assertNotIn(secret, step.result_summary)


class TaskPathTests(unittest.TestCase):
    """v0.8 proof attempt 2: screenshot names are sandbox paths —
    subdirectories honoured, escapes refused with an error."""

    def _shot(self, td, name):
        seen = {}

        def fake_rpc(action, **kw):
            seen.update(kw)
            return {"bytes": 10, "url": "https://example.com/"}

        orig = browsertools._rpc
        browsertools._rpc = fake_rpc
        try:
            out = browsertools.h_browser_screenshot(
                td, {"name": name})
        finally:
            browsertools._rpc = orig
        return out, seen.get("path")

    def test_subpath_honoured(self):
        with tempfile.TemporaryDirectory() as td:
            out, p = self._shot(td, "repo/wiki.png")
            self.assertIn("SCREENSHOT_OK path=repo/wiki.png", out)
            self.assertEqual(
                p, os.path.join(os.path.realpath(td), "repo",
                                "wiki.png"))

    def test_escape_refused(self):
        with tempfile.TemporaryDirectory() as td:
            out, p = self._shot(td, "../evil.png")
            self.assertTrue(out.startswith("error:"), out)
            self.assertIsNone(p)


class WaitArgTests(unittest.TestCase):
    """Regression (v0.8 proof attempt 1): the driver's text-wait
    deadline and the RPC timeout were both named 'timeout', so
    every browser_wait(text=...) died with a TypeError before
    reaching the driver."""

    def test_wait_text_passes_wait_timeout_not_timeout(self):
        seen = {}

        def fake_rpc(action, **kw):
            seen.update(kw)
            return {"url": "https://example.com/", "title": "T",
                    "text": "Coordinates appeared", "elements": []}

        orig = browsertools._rpc
        browsertools._rpc = fake_rpc
        try:
            out = browsertools.h_browser_wait(
                "/tmp", {"text": "Coordinates", "timeout": 10})
        finally:
            browsertools._rpc = orig
        self.assertIn("WAIT_OK", out)
        self.assertEqual(seen.get("wait_timeout"), 10.0)
        self.assertNotIn("timeout", seen)


class ChallengeDetectionTests(unittest.TestCase):
    def test_arkose_iframe_is_captcha(self):
        ch = browsertools.detect_challenge_text(
            "https://x.com/i/flow/signup", "Create your account",
            frames=["https://client-api.arkose.com/fc/assets"])
        self.assertEqual(ch["kind"], "captcha")

    def test_emailed_code_is_code_wall(self):
        ch = browsertools.detect_challenge_text(
            "https://x.com/i/flow/signup",
            "We sent a code to your email. Enter the code we sent.")
        self.assertEqual(ch["kind"], "code")

    def test_phone_demand(self):
        ch = browsertools.detect_challenge_text(
            "https://example.com/verify", "Please add a phone number")
        self.assertEqual(ch["kind"], "phone")

    def test_clean_page_no_challenge(self):
        self.assertIsNone(browsertools.detect_challenge_text(
            "https://example.com/", "Welcome to our perfectly normal page"))


@unittest.skipUnless(HAVE_CRYPTO, "cryptography not installed")
class VaultTests(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.mkdtemp(prefix="lille-home-")
        self.orig = os.environ.get("FREEAGENT_HOME")
        os.environ["FREEAGENT_HOME"] = self.home

    def tearDown(self):
        if self.orig is None:
            os.environ.pop("FREEAGENT_HOME", None)
        else:
            os.environ["FREEAGENT_HOME"] = self.orig
        shutil.rmtree(self.home, ignore_errors=True)

    def test_gen_secret_returns_metadata_only(self):
        out = browsertools.vault_gen_secret("x_password")
        self.assertTrue(out.startswith("VAULT_OK"))
        value = browsertools._vault().get("x_password")
        self.assertTrue(value and len(value) >= 20)
        self.assertNotIn(value, out)
        # The vault file on disk is ciphertext, not the value.
        with open(os.path.join(self.home, "vault.enc"), "rb") as f:
            self.assertNotIn(value.encode(), f.read())

    def test_record_account_stores_no_secret(self):
        browsertools.vault_gen_secret("x_password")
        value = browsertools._vault().get("x_password")
        out = browsertools.vault_record_account(
            "x.com", "LilleAgent", "x_password")
        self.assertIn("ACCOUNT_RECORDED", out)
        with open(os.path.join(self.home, "accounts.json")) as f:
            blob = f.read()
        self.assertIn("LilleAgent", blob)
        self.assertNotIn(value, blob)

    def test_bad_key_names_rejected(self):
        self.assertIn("error", browsertools.vault_gen_secret("bad key!"))
        s = tasktools.Sentinel(spec_context={})
        ok, _ = s.check("vault_gen_secret", {"key": "bad key!"}, "/tmp")
        self.assertFalse(ok)


class SpecParsingTests(unittest.TestCase):
    def test_browser_and_x_lane_sections(self):
        spec = parse_spec(
            "Goal\nMake an account.\n\nBrowser domains\n"
            "- `x.com`\n- guerrillamail.com\n\nX lane\n"
            "- account: own\n")
        self.assertEqual(spec["browser_domains"],
                         ["x.com", "guerrillamail.com"])
        self.assertEqual(spec["x_post_mode"], "own")
        self.assertFalse(spec["x_post_approved"])

    def test_user_mode_with_approval(self):
        spec = parse_spec(
            "Goal\nPost.\n\nBrowser domains\n- x.com\n\nX lane\n"
            "- account: user\n- post approved: yes\n")
        self.assertEqual(spec["x_post_mode"], "user")
        self.assertTrue(spec["x_post_approved"])


class SweepSessionSurfaceTests(unittest.TestCase):
    def test_no_profile_is_named_not_checked(self):
        from free_agent import sweep
        home = tempfile.mkdtemp(prefix="lille-empty-home-")
        orig = os.environ.get("FREEAGENT_HOME")
        os.environ["FREEAGENT_HOME"] = home
        try:
            with self.assertRaises(sweep.SurfaceError) as cm:
                sweep.surface_x_session("hackathons", ["hackathons"])
            self.assertIn("no browser profile", str(cm.exception))
        finally:
            if orig is None:
                os.environ.pop("FREEAGENT_HOME", None)
            else:
                os.environ["FREEAGENT_HOME"] = orig
            shutil.rmtree(home, ignore_errors=True)

    def test_session_surface_is_in_default_surfaces(self):
        from free_agent import sweep
        names = [n for n, _ in sweep.default_surfaces(
            sweep._http_get, sweep._http_json)]
        self.assertIn("x (via Lille's browser session)", names)


FIXTURE_FORM = """<!doctype html><title>Fixture Form</title>
<h1>Sign up fixture</h1>
<form action="/done" method="get">
<label>Name <input type="text" name="fullname" placeholder="Your name"></label>
<label>Password <input type="password" name="pw" placeholder="Password"></label>
<button type="submit">Join</button>
</form>
<button type="button" onclick="document.getElementById('reveal').innerText='Clicked and revealed'">Reveal</button>
<div id="reveal"></div>
"""

FIXTURE_DONE = """<!doctype html><title>Done</title>
<h1>Welcome aboard, fixture user</h1><p>The form worked.</p>
"""

FIXTURE_CHALLENGE = """<!doctype html><title>Verify</title>
<h1>Verify you are human</h1>
<iframe src="https://client-api.arkose.com/fc/assets/ec?t=1"></iframe>
"""


def _chrome_available():
    if not shutil.which("node"):
        return False
    return bool(browsertools._chrome_path())


def _data_url(html):
    import urllib.parse
    return "data:text/html," + urllib.parse.quote(html)


@unittest.skipUnless(_chrome_available(), "node/Chromium not available")
class DriverIntegrationTests(unittest.TestCase):
    """Real driver, hermetic fixture pages (data: URLs driven via
    the raw RPC — the http(s)-only + allowlist policy lives in the
    Python/Sentinel layer and is unit-tested above; this class
    proves the DRIVER mechanics: refs, the live SECRET_FIELD
    refusal, typing, click navigation, challenge reporting).

    Why not a localhost HTTP fixture? This Chromium build's Local
    Network Access checks block loopback navigation outright
    (ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS) even with the
    feature flag off — a named limitation in XLANE.md. Public
    http(s) browsing is proven by the live proof runs."""

    @classmethod
    def setUpClass(cls):
        cls.root = tempfile.mkdtemp(prefix="lille-fixture-")
        cls.orig_profile = os.environ.get("LILLE_BROWSER_PROFILE")
        os.environ["LILLE_BROWSER_PROFILE"] = os.path.join(
            cls.root, "profile")

    @classmethod
    def tearDownClass(cls):
        browsertools.close_session()
        if cls.orig_profile is None:
            os.environ.pop("LILLE_BROWSER_PROFILE", None)
        else:
            os.environ["LILLE_BROWSER_PROFILE"] = cls.orig_profile
        shutil.rmtree(cls.root, ignore_errors=True)

    def test_open_read_type_click_challenge(self):
        state = browsertools._rpc(
            "open", url=_data_url(FIXTURE_FORM), settle=2)
        els = {e.get("name"): e for e in state.get("elements", [])
               if e.get("name")}
        self.assertIn("fullname", els)
        self.assertIn("pw", els)
        self.assertTrue(els["pw"].get("secret"),
                        "password field must be flagged SECRET")
        # The live driver itself refuses to type a password via the
        # non-secret path (defence in depth beyond the snapshot).
        with self.assertRaises(browsertools.BrowserError) as cm:
            browsertools._rpc("type", ref=els["pw"]["ref"],
                              text="hunter2")
        self.assertEqual(getattr(cm.exception, "code", None),
                         "SECRET_FIELD")
        state = browsertools._rpc("type", ref=els["fullname"]["ref"],
                                  text="Fixture User")
        self.assertIn("Sign up fixture", state.get("text", ""))
        state = browsertools._rpc("click", text="Reveal")
        self.assertIn("Clicked and revealed", state.get("text", ""))
        # A second page via a fresh open (multi-page work).
        state = browsertools._rpc(
            "open", url=_data_url(FIXTURE_DONE), settle=2)
        self.assertIn("Welcome aboard", state.get("text", ""))
        # Handler-level screenshot on the live page.
        out = browsertools.h_browser_screenshot(
            self.root, {"name": "evidence.png"})
        self.assertIn("SCREENSHOT_OK", out)
        self.assertTrue(os.path.isfile(
            os.path.join(self.root, "evidence.png")))
        # Challenge fixture: Arkose iframe + human text -> captcha.
        state = browsertools._rpc(
            "open", url=_data_url(FIXTURE_CHALLENGE), settle=2)
        self.assertIsNotNone(state.get("challenge"))
        self.assertEqual(state["challenge"]["kind"], "captcha")


if __name__ == "__main__":
    unittest.main()


class PayloadDisciplineTests(unittest.TestCase):
    """v0.8 proof attempts 3-4: the keyless endpoint 413s
    oversized bodies. Spec runs must send subset schemas, and
    the loop must compact history."""

    def test_spec_tool_names_browser_spec(self):
        spec = specmode.parse_spec(open(
            os.path.join(os.path.dirname(__file__), "..",
                         "specs", "2026-11-25-browser-basics.md")
        ).read())
        names = specmode.spec_tool_names(spec)
        self.assertIn("browser_open", names)
        self.assertIn("browser_screenshot", names)
        self.assertNotIn("assemble_video", names)
        self.assertNotIn("x_post", names)
        import json as _json
        size = len(_json.dumps(tasktools.schemas_for(names)))
        self.assertLess(size, 7000, size)

    def test_spec_tool_names_x_spec(self):
        spec = specmode.parse_spec(open(
            os.path.join(os.path.dirname(__file__), "..",
                         "specs", "2026-11-25-lille-x-account.md")
        ).read())
        names = specmode.spec_tool_names(spec)
        for n in ("browser_type_secret", "vault_gen_secret",
                  "x_post", "x_read_feed", "browser_upload"):
            self.assertIn(n, names)
        self.assertNotIn("assemble_video", names)

    def test_compact_history_bounds_and_keeps_recent(self):
        from free_agent.loop import TaskLoop
        msgs = [{"role": "system", "content": "sys"},
                {"role": "user", "content": "task"}]
        for i in range(8):
            msgs.append({"role": "tool", "tool_call_id": str(i),
                         "content": f"obs-{i} " + "y" * 4000})
        TaskLoop._compact_history(msgs)
        total = sum(len(m.get("content") or "") for m in msgs)
        self.assertLessEqual(total,
                             TaskLoop.HISTORY_BUDGET_CHARS + 500)
        # The newest observation survives in full.
        self.assertIn("obs-7", msgs[-1]["content"])
        self.assertGreater(len(msgs[-1]["content"]), 4000)


class WallEvidenceTests(unittest.TestCase):
    """v0.8 X attempt 1: the model finished at step 2 claiming a
    CAPTCHA it never met (zero tool calls) and the claim graded
    NEEDS ETHURAM. Walls now require observed CHALLENGE
    evidence, and a browser spec cannot finish before any
    browser tool has run."""

    def test_wall_signal_requires_observed_challenge(self):
        self.assertIsNone(specmode._wall_signal(
            "hit a captcha wall", {"challenge": None}))
        self.assertIsNotNone(specmode._wall_signal(
            "anything", {"challenge": "CHALLENGE captcha: arkose"}))

    def test_unfounded_wall_claim_cannot_finish_browser_spec(self):
        def native(name, args, cid="c1"):
            return {"content": None,
                    "tool_calls": [{"id": cid, "name": name,
                                    "args": args}]}

        class Scripted:
            def __init__(self, replies):
                self.replies = list(replies)

            def __call__(self, messages, tools):
                if self.replies:
                    return self.replies.pop(0)
                return {"content": "done", "tool_calls": None}

        spec_text = open(os.path.join(
            os.path.dirname(__file__), "..", "specs",
            "2026-11-25-lille-x-account.md")).read()
        # v0.9: the shipped spec declares the DEVICE lane (it now
        # runs in the app, from Ethuram's phone). This test is
        # about the HOST lane's finish guard, so it forces the
        # host lane — the guard it exercises is lane-independent.
        spec_text = spec_text.replace("Lane\n- device", "Lane\n- host")
        model = Scripted([
            native("finish", {"summary": "Encountered a CAPTCHA "
                              "wall during X signup",
                              "outputs": []}),
            native("finish", {"summary": "stopping without "
                              "evidence", "outputs": []}, "c2"),
        ])
        with tempfile.TemporaryDirectory() as td:
            report, result, _td = specmode.run_spec(
                spec_text, repo=None, workspace_root=td,
                model_call=model, max_steps=6)
        self.assertTrue(report.startswith("STATUS: BLOCKED"),
                        report)
        self.assertIn("finish-rejected",
                      [s.kind for s in result.steps])


class CompactSchemaTests(unittest.TestCase):
    def test_compact_schemas_shrink_and_keep_structure(self):
        import json as _json
        names = specmode.spec_tool_names(specmode.parse_spec(open(
            os.path.join(os.path.dirname(__file__), "..", "specs",
                         "2026-11-25-lille-x-account.md")).read()))
        full = tasktools.schemas_for(names)
        comp = tasktools.compact_schemas(full)
        self.assertLess(len(_json.dumps(comp)),
                        len(_json.dumps(full)) * 0.75)
        for s in comp:
            fn = s.get("function", s)
            self.assertIn("name", fn)
            self.assertIn("parameters", fn)

    def test_model_dead_run_with_wall_grades_blocked(self):
        spec = {"criteria": ["x_search returns real results"],
                "push_branches": []}
        ev = {"repo_dir": "/tmp", "providers": [],
              "commit_hashes": [], "challenge": "CHALLENGE captcha",
              "tests_passed": False, "pushed": []}
        evaluated = [(spec["criteria"][0], False, "none")]

        class R:
            summary = "Model unavailable: All providers failed 413"
            steps = []
        report = specmode.build_report(spec, R(), ev, evaluated)
        self.assertTrue(report.startswith("STATUS: BLOCKED"),
                        report)
        R.summary = "Stopped at the wall"
        report = specmode.build_report(spec, R(), ev, evaluated)
        self.assertTrue(report.startswith("STATUS: NEEDS ETHURAM"),
                        report)


class PresentedChallengeTests(unittest.TestCase):
    """v0.8 X attempt 6: passive reCAPTCHA scoring iframes on
    usable mailbox homepages false-walled two routes. Only a
    PRESENTED (visible, sized) challenge iframe is a wall."""

    def test_hidden_scoring_iframe_is_not_a_wall(self):
        ch = browsertools.detect_challenge_text(
            "https://emailnator.com/", "Disposable Gmail Address",
            frames=[{"src": "https://www.google.com/recaptcha/api2/anchor",
                     "w": 0, "h": 0, "visible": False}])
        self.assertIsNone(ch)

    def test_visible_challenge_iframe_is_a_wall(self):
        ch = browsertools.detect_challenge_text(
            "https://example.com/", "page",
            frames=[{"src": "https://www.google.com/recaptcha/api2/bframe",
                     "w": 400, "h": 580, "visible": True}])
        self.assertIsNotNone(ch)
        self.assertEqual(ch["kind"], "captcha")


class UnworkedRouteTests(unittest.TestCase):
    """v0.8 X attempt 6: maildrop.cc was opened once, never used,
    then declared exhausted. The guard must name it unworked."""

    def _step(self, tool, args="", result=""):
        from free_agent.loop import Step
        return Step(1, "tool", tool, args, result)

    def test_opened_once_never_used_is_unworked(self):
        spec = {"browser_domains": ["x.com", "guerrillamail.com",
                                    "maildrop.cc", "emailnator.com"]}
        steps = [
            self._step("browser_open",
                       "url='https://guerrillamail.com'"),
            self._step("browser_read", "{}",
                       "CHALLENGE kind=captcha detail=x"),
            self._step("browser_open", "url='https://maildrop.cc'"),
            self._step("browser_open",
                       "url='https://emailnator.com'"),
            self._step("browser_read", "{}",
                       "CHALLENGE kind=captcha detail=x"),
        ]
        unworked = specmode._unworked_routes(spec, steps)
        self.assertIn("maildrop.cc", unworked)
        self.assertNotIn("guerrillamail.com", unworked)
        self.assertNotIn("emailnator.com", unworked)


class WireCapTests(unittest.TestCase):
    """v0.8 X attempts 5/7/8 died of 413 at steps 10-11 while
    content-char budgets reported headroom. The loop now
    measures the actual request body and compacts to fit."""

    def test_wire_size_and_aggressive_compaction(self):
        from free_agent.loop import TaskLoop
        msgs = [{"role": "system", "content": "sys"},
                {"role": "user", "content": "task"}]
        for i in range(10):
            msgs.append({"role": "assistant",
                         "content": "narration " * 60,
                         "tool_calls": [{
                             "id": str(i), "type": "function",
                             "function": {"name": "browser_open",
                                          "arguments": '{"url": "https://example.com/' + "p" * 200 + '"}'}}]})
            msgs.append({"role": "tool", "tool_call_id": str(i),
                         "content": "PAGE " + "y" * 3000})
        schemas = tasktools.compact_schemas(
            tasktools.schemas_for(["browser_open", "finish"]))
        before = TaskLoop._wire_size(msgs, schemas)
        TaskLoop._compact_aggressive(msgs)
        after = TaskLoop._wire_size(msgs, schemas)
        self.assertLess(after, before)
        # Newest observation truncated to the cap, not destroyed.
        self.assertTrue(msgs[-1]["content"].startswith("PAGE "))
        self.assertLessEqual(len(msgs[-1]["content"]), 2500)


class TrimHistoryTests(unittest.TestCase):
    """v0.8 X attempt 9: fully stubbed history still grew past
    the wire cap by step 17 — stub residue is linear. Old
    groups must leave the model's view entirely."""

    def test_trim_drops_oldest_groups_keeps_last_three(self):
        from free_agent.loop import TaskLoop
        msgs = [{"role": "system", "content": "sys"},
                {"role": "user", "content": "task"}]
        for i in range(8):
            msgs.append({"role": "assistant",
                         "content": f"step-{i} " + "n" * 300,
                         "tool_calls": []})
            msgs.append({"role": "tool", "tool_call_id": str(i),
                         "content": f"obs-{i} " + "y" * 900})
        schemas = tasktools.compact_schemas(
            tasktools.schemas_for(["browser_open", "finish"]))
        TaskLoop._trim_history(msgs, schemas, 5000)
        self.assertLessEqual(TaskLoop._wire_size(msgs, schemas),
                             5000)
        contents = " ".join(m.get("content") or "" for m in msgs)
        self.assertIn("obs-7", contents)
        self.assertNotIn("obs-0", contents)
        # System + task always survive.
        self.assertEqual(msgs[0]["content"], "sys")
        self.assertEqual(msgs[1]["content"], "task")
