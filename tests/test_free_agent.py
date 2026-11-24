import os, sys, tempfile, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from free_agent.config import Config, ProviderCfg
from free_agent.router import Router, PrivacyBlocked
from free_agent.redact import redact
from free_agent.vault import Vault

def cfg_with(providers):
    c = Config(); c.providers = providers; return c

def prov(name, trusted=False):
    return ProviderCfg(name, "https://example.invalid/v1", "NOPE_KEY", ["m"], trusted_for_private=trusted)

class TestRouterFallback(unittest.TestCase):
    def test_fallback_on_402_429_503(self):
        for status in (402, 429, 503):
            calls = []
            def transport(p, messages, _s=status):
                calls.append(p.name)
                return (_s, "") if p.name == "agentrouter" else (200, "from-groq")
            r = Router(cfg_with([prov("agentrouter"), prov("groq")]), transport=transport)
            out = r.chat([{"role": "user", "content": "hi"}])
            self.assertEqual(out["provider"], "groq", f"status {status}")
            self.assertEqual(calls, ["agentrouter", "groq"])

    def test_no_fallback_needed_on_200(self):
        r = Router(cfg_with([prov("agentrouter"), prov("groq")]),
                   transport=lambda p, m: (200, "ok"))
        self.assertEqual(r.chat([])["provider"], "agentrouter")

class TestKeylessProvider(unittest.TestCase):
    def test_keyless_is_default_first_and_needs_no_key(self):
        # Zero keys anywhere in the environment: the keyless provider must
        # still be first and enabled, and chat must succeed through it.
        import unittest.mock as mock
        with mock.patch.dict(os.environ, {}, clear=True):
            cfg = Config.from_env()
        first = cfg.providers[0]
        self.assertEqual(first.name, "keyless-pollinations")
        self.assertTrue(first.enabled)
        self.assertTrue(first.keyless)
        self.assertEqual(first.key(), "")
        # Keyed providers stay disabled until the user supplies their OWN key.
        by_name = {p.name: p for p in cfg.providers}
        self.assertFalse(by_name["agentrouter"].enabled)
        self.assertFalse(by_name["groq"].enabled)
        calls = []
        def transport(p, messages):
            calls.append(p.name); return 200, "keyless-ok"
        r = Router(cfg, transport=transport)
        out = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(out["provider"], "keyless-pollinations")
        self.assertEqual(out["text"], "keyless-ok")
        self.assertEqual(calls, ["keyless-pollinations"])

    def test_keyless_402_falls_through_to_next(self):
        keyless = ProviderCfg("keyless-pollinations", "https://text.pollinations.ai/openai",
                              "", ["openai"], chat_path="", keyless=True)
        def transport(p, messages):
            return (402, "") if p.name == "keyless-pollinations" else (200, "from-groq")
        r = Router(cfg_with([keyless, prov("groq")]), transport=transport)
        out = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(out["provider"], "groq")

    def test_private_blocked_from_keyless(self):
        keyless = ProviderCfg("keyless-pollinations", "https://text.pollinations.ai/openai",
                              "", ["openai"], chat_path="", keyless=True)
        seen = []
        def transport(p, messages):
            seen.append(p.name); return 200, "should-not-happen"
        r = Router(cfg_with([keyless, prov("agentrouter")]), transport=transport)
        with self.assertRaises(PrivacyBlocked):
            r.chat([{"role": "user", "content": "mail"}], category="private:gmail", private=True)
        self.assertEqual(seen, [])  # public endpoint never saw private content

class FakeClock:
    def __init__(self, t=1_000_000.0):
        self.t = t
    def __call__(self):
        return self.t
    def advance(self, seconds):
        self.t += seconds

class TestFallbackLaw(unittest.TestCase):
    def test_chain_falls_through_two_failures_to_third(self):
        calls = []
        statuses = {"r1": 503, "r2": 402}
        def transport(p, messages):
            calls.append(p.name)
            if p.name in statuses:
                return statuses[p.name], ""
            return 200, "third-route-answer"
        r = Router(cfg_with([prov("r1"), prov("r2"), prov("r3")]), transport=transport)
        out = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(out["provider"], "r3")
        self.assertEqual(out["text"], "third-route-answer")
        self.assertEqual(calls, ["r1", "r2", "r3"])

    def test_cooldown_skips_then_recovers(self):
        clock = FakeClock()
        state = {"r1_limited": True}
        calls = []
        def transport(p, messages):
            calls.append(p.name)
            if p.name == "r1" and state["r1_limited"]:
                return 429, ""
            return 200, f"from-{p.name}"
        r1 = prov("r1"); r1.cooldown_seconds = 60
        r = Router(cfg_with([r1, prov("r2")]), transport=transport, clock=clock)
        # t0: r1 limited (cools down 60s), r2 serves
        self.assertEqual(r.chat([])["provider"], "r2")
        # t+10: r1 must be SKIPPED (not even called), r2 serves again
        clock.advance(10); calls.clear()
        self.assertEqual(r.chat([])["provider"], "r2")
        self.assertNotIn("r1", calls)
        # t+61: cooldown expired -> preferred route re-probed and,
        # now healthy, serves again (automatic recovery)
        state["r1_limited"] = False
        clock.advance(51); calls.clear()
        out = r.chat([])
        self.assertEqual(out["provider"], "r1")
        self.assertEqual(calls[0], "r1")

    def test_all_routes_down_returns_queued_not_exception(self):
        clock = FakeClock()
        r = Router(cfg_with([prov("only")]),
                   transport=lambda p, m: (429, ""), clock=clock)
        out = r.chat([{"role": "user", "content": "hi"}])
        self.assertTrue(out["queued"])
        self.assertIsNone(out["provider"])
        self.assertIn("queued", out["text"])
        self.assertIn("Earliest retry", out["text"])
        self.assertGreater(out["retry_at"], clock.t)

    def test_retry_after_header_wins_over_default_cooldown(self):
        clock = FakeClock()
        r1 = prov("r1"); r1.cooldown_seconds = 60
        r = Router(cfg_with([r1]),
                   transport=lambda p, m: (402, "", 900), clock=clock)
        out = r.chat([])
        self.assertEqual(out["retry_at"], clock.t + 900)

    def test_private_trusted_cooling_queues_never_falls_to_untrusted(self):
        clock = FakeClock()
        trusted = prov("local-ollama", trusted=True); trusted.cooldown_seconds = 60
        state = {"limited": True}
        seen = []
        def transport(p, messages):
            seen.append(p.name)
            if p.name == "local-ollama" and state["limited"]:
                return 429, ""
            return 200, f"from-{p.name}"
        r = Router(cfg_with([prov("keyless-pollinations"), trusted]),
                   transport=transport, clock=clock)
        out = r.chat([], category="private:gmail", private=True)
        self.assertTrue(out["queued"])          # trusted limited -> queued honestly
        self.assertNotIn("keyless-pollinations", seen)  # untrusted NEVER tried

class TestSearchChain(unittest.TestCase):
    def test_search_falls_through_and_reports_honestly(self):
        from free_agent.tools.search_tool import search
        def miss(q):
            return None
        def hit(q):
            return {"route": "wikipedia-api", "title": "T", "text": "fact",
                    "source": "Wikipedia", "url": "u"}
        out = search("anything", routes=[miss, hit])
        self.assertEqual(out["route"], "wikipedia-api")
        out = search("anything", routes=[miss])
        self.assertIsNone(out["route"])
        self.assertIn("No live keyless search route", out["text"])
    def test_route_tables_have_depth_or_honest_gap(self):
        from free_agent.routes import ROUTE_TABLES
        for cap in ("text", "search", "voice_stt", "voice_tts", "hosting"):
            self.assertGreaterEqual(len(ROUTE_TABLES[cap]), 3, cap)
        self.assertGreaterEqual(len(ROUTE_TABLES["text"]), 5)

class TestPrivacyRouting(unittest.TestCase):
    def test_private_blocked_from_agentrouter(self):
        seen = []
        def transport(p, messages):
            seen.append(p.name); return 200, "should-not-happen"
        r = Router(cfg_with([prov("agentrouter"), prov("groq")]), transport=transport)
        with self.assertRaises(PrivacyBlocked):
            r.chat([{"role": "user", "content": "mail"}], category="private:gmail", private=True)
        self.assertEqual(seen, [])  # nothing was sent anywhere

    def test_private_goes_to_trusted_local(self):
        r = Router(cfg_with([prov("agentrouter"), prov("local-ollama", trusted=True)]),
                   transport=lambda p, m: (200, "local-ok"))
        out = r.chat([], category="private:gmail", private=True)
        self.assertEqual(out["provider"], "local-ollama")

    def test_audit_is_metadata_only(self):
        r = Router(cfg_with([prov("agentrouter")]), transport=lambda p, m: (200, "ok"))
        r.chat([{"role": "user", "content": "SECRET-CONTENT"}])
        self.assertNotIn("SECRET-CONTENT", repr(r.audit))

class TestRedact(unittest.TestCase):
    def test_redacts_email_code_card(self):
        s = redact("mail me at kanu@example.com, your code is 482913, card 4242 4242 4242 4242")
        self.assertNotIn("kanu@example.com", s)
        self.assertNotIn("482913", s)
        self.assertNotIn("4242", s)
    def test_redacts_api_key(self):
        self.assertNotIn("sk-abcdefgh12345678", redact("key: sk-abcdefgh12345678"))

class TestVault(unittest.TestCase):
    def test_round_trip(self):
        try:
            import cryptography  # noqa
        except ImportError:
            # Insecure stub exists ONLY for this test path; real use requires Fernet.
            with tempfile.TemporaryDirectory() as d:
                v = Vault(os.path.join(d, "v.json"), insecure_stub=True)
                v.set("GROQ_API_KEY", "test-value"); self.assertEqual(v.get("GROQ_API_KEY"), "test-value")
            return
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "vault.bin")
            v = Vault(path); v.set("GROQ_API_KEY", "test-value")
            self.assertEqual(Vault(path).get("GROQ_API_KEY"), "test-value")
            self.assertNotIn(b"test-value", open(path, "rb").read())  # encrypted at rest
            self.assertEqual(oct(os.stat(path).st_mode)[-3:], "600")

if __name__ == "__main__":
    unittest.main()
