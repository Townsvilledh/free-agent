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
