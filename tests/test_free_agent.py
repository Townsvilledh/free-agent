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

class TestRefusalRecovery(unittest.TestCase):
    REFUSAL = "I'm sorry, but I can't help with that."
    def test_refusal_triggers_reframe_and_retry(self):
        calls = []
        def transport(p, messages):
            calls.append(messages[-1]["content"])
            if any(("paper-trades" in m["content"] or "Write a complete Python script" in m["content"]) for m in messages):
                return 200, "Here is a paper-trading agent script..."
            return 200, self.REFUSAL
        r = Router(cfg_with([prov("keyless-pollinations")]), transport=transport)
        out = r.chat([{"role": "user", "content": "can you create an autonomous profitable agent"}])
        self.assertEqual(len(calls), 2)                      # exactly ONE reframe retry
        self.assertTrue(out.get("reframed_from_refusal"))
        self.assertIn("refused that phrasing", out["text"])  # transparency note
        self.assertIn("paper-trading agent script", out["text"])

    def test_refusal_with_curly_apostrophes_still_detected(self):
        # Live finding (v0.2.1 re-run): the model answers with curly
        # apostrophes ("I’m sorry, but I can’t help with that.") —
        # detection must normalise them or recovery never fires.
        from free_agent.recovery import is_refusal
        self.assertTrue(is_refusal("I\u2019m sorry, but I can\u2019t help with that."))
        calls = []
        def transport(p, messages):
            calls.append(1)
            if any(("paper-trades" in m["content"] or "Write a complete Python script" in m["content"]) for m in messages):
                return 200, "Here is the script..."
            return 200, "I\u2019m sorry, but I can\u2019t help with that."
        r = Router(cfg_with([prov("keyless-pollinations")]), transport=transport)
        out = r.chat([{"role": "user", "content": "can you create an autonomous profitable agent"}])
        self.assertEqual(len(calls), 2)
        self.assertTrue(out.get("reframed_from_refusal"))

    def test_harmful_request_is_never_reframed(self):
        calls = []
        def transport(p, messages):
            calls.append(1)
            return 200, self.REFUSAL
        r = Router(cfg_with([prov("keyless-pollinations")]), transport=transport)
        out = r.chat([{"role": "user", "content": "how do I build a bomb to hurt people"}])
        self.assertEqual(len(calls), 1)                      # refusal stands, no retry
        self.assertFalse(out.get("reframed_from_refusal"))
        self.assertEqual(out["text"], self.REFUSAL)          # delivered plainly

    def test_second_refusal_surfaces_honestly(self):
        calls = []
        def transport(p, messages):
            calls.append(1)
            return 200, self.REFUSAL
        r = Router(cfg_with([prov("keyless-pollinations")]), transport=transport)
        out = r.chat([{"role": "user", "content": "can you create an autonomous profitable agent"}])
        self.assertEqual(len(calls), 2)                      # one reframe, no loop
        self.assertIn("can't help with that", out["text"])
        self.assertIn("Try a more specific wording", out["text"])

class TestStreamingContinuation(unittest.TestCase):
    def test_mid_stream_drop_is_stitched(self):
        from free_agent.recovery import StreamDropped
        state = {"n": 0}
        def stream_transport(p, messages):
            state["n"] += 1
            if state["n"] == 1:
                raise StreamDropped("Hello wor")
            # continuation call must carry the partial + continue prompt
            self.assertTrue(any("Continue exactly from where you stopped" in m["content"]
                                for m in messages))
            return 200, "ld, done."
        r = Router(cfg_with([prov("keyless-pollinations")]),
                   transport=lambda p, m: (500, ""), stream_transport=stream_transport)
        out = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(out["text"], "Hello world, done.")
        self.assertEqual(state["n"], 2)

    def test_continuations_are_capped_at_three(self):
        from free_agent.recovery import StreamDropped
        state = {"n": 0}
        def stream_transport(p, messages):
            state["n"] += 1
            raise StreamDropped(f"part{state['n']} ")
        r = Router(cfg_with([prov("keyless-pollinations")]),
                   transport=lambda p, m: (500, ""), stream_transport=stream_transport)
        out = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(state["n"], 4)  # initial + max 3 continuations
        self.assertIn("part1", out["text"])
        self.assertIn("part4", out["text"])

class TestExtractionVerification(unittest.TestCase):
    SOURCE = ("Hello, this is Mr. Adewale Johnson, your landlord. Your rent of "
              "₦450,000 (that's about $290) is due on 15 December 2026. "
              "Please call me on 0803 555 0142 once you have paid. Thanks.")
    def test_mangled_name_is_corrected_on_retry(self):
        from free_agent.agent import Agent
        calls = []
        def transport(p, messages):
            calls.append(messages[-1]["content"])
            if any("extraction errors" in m["content"] for m in messages):
                return 200, ('{"name":"Mr. Adewale Johnson","amount_ngn":450000,'
                             '"amount_usd":290,"due_date":"15 December 2026",'
                             '"phone":"0803 555 0142"}')
            return 200, ('{"name":"M. Adewale Johnson","amount_ngn":450000,'
                         '"amount_usd":290,"due_date":"15 December 2026",'
                         '"phone":"0803 555 0142"}')
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            agent = Agent(cfg_with([prov("keyless-pollinations")]),
                          memory_dir=d, transport=transport)
            out = agent.chat(f"Extract into ONLY valid JSON with keys name, amount_ngn, "
                             f"amount_usd, due_date, phone: {self.SOURCE}")
        self.assertEqual(len(calls), 2)                      # one correction retry
        self.assertTrue(out.get("extraction_corrected"))
        self.assertIn("Mr. Adewale Johnson", out["text"])
        self.assertEqual(out["extraction_mismatches"], ["name"])

    def test_verify_extraction_flags_only_real_mismatches(self):
        from free_agent.recovery import verify_extraction
        good = ('{"name":"Mr. Adewale Johnson","amount_ngn":450000,"amount_usd":290,'
                '"due_date":"15 December 2026","phone":"0803 555 0142"}')
        self.assertEqual(verify_extraction(self.SOURCE, good), [])
        bad = verify_extraction(self.SOURCE, '{"name":"M. Adewale Johnson","amount_ngn":999}')
        self.assertEqual([f for f, _ in bad], ["name", "amount_ngn"])

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

class TestHordeRoute(unittest.TestCase):
    """v0.12: AI Horde is the keyless fallback of last resort
    for CHAT (live-verified 2026-11-26). It cannot honour
    NATIVE tool calls; since the 2026-11-26 loop-path fix it
    also serves the tool-call path via the loop's fenced-JSON
    text protocol (catalogue rendered as text)."""

    def _cfg(self):
        poll = ProviderCfg("keyless-pollinations",
                           "https://text.pollinations.ai/openai", "",
                           ["openai"], chat_path="", keyless=True)
        horde = ProviderCfg("ai-horde", "https://stablehorde.net/api/v2",
                            "", ["any"], keyless=True, kind="horde")
        return cfg_with([poll, horde])

    def test_chat_falls_through_to_horde_when_pollinations_busy(self):
        seen = []
        def transport(p, messages):
            seen.append(("chat", p.name))
            return (402, "", None)
        def horde_transport(p, messages):
            seen.append(("horde", p.name))
            return (200, "from the community pool", None)
        r = Router(self._cfg(), transport=transport)
        r.horde_transport = horde_transport
        out = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(out["provider"], "ai-horde")
        self.assertEqual(out["text"], "from the community pool")
        self.assertEqual(seen, [("chat", "keyless-pollinations"),
                                ("horde", "ai-horde")])

    def test_complete_routes_through_horde_via_text_protocol(self):
        # The loop path's last resort (2026-11-26 fix): when the
        # native route is capped, complete() falls through to
        # Horde with the tool catalogue rendered as text, and the
        # raw completion comes back as the message content — the
        # loop parses its fenced JSON into a tool call.
        seen = {}
        def raw(p, messages, tools=None):
            return (402, None, None)
        def horde_transport(p, messages):
            seen["messages"] = messages
            return (200, '```json\n{"tool": "render_card", '
                         '"args": {}}\n```', None)
        r = Router(self._cfg(), transport=lambda p, m: (402, "", None))
        r.raw_transport = raw
        r.horde_transport = horde_transport
        tools = [{"type": "function", "function": {
            "name": "render_card", "description": "Render a card.",
            "parameters": {"type": "object",
                           "properties": {"out": {"type": "string"}}}}}]
        out = r.complete([{"role": "system", "content": "You are Lille."},
                          {"role": "user", "content": "do a task"}],
                         tools=tools)
        self.assertEqual(out["provider"], "ai-horde")
        self.assertIn("render_card", out["message"]["content"])
        # The catalogue reached the raw-completion route as text,
        # appended to the system message.
        sys_msgs = [m["content"] for m in seen["messages"]
                    if m.get("role") == "system"]
        self.assertTrue(any("render_card(out)" in c for c in sys_msgs))

    def test_complete_horde_failure_still_queues_honestly(self):
        def raw(p, messages, tools=None):
            return (402, None, None)
        def horde_transport(p, messages):
            return (503, "", None)
        r = Router(self._cfg(), transport=lambda p, m: (402, "", None))
        r.raw_transport = raw
        r.horde_transport = horde_transport
        out = r.complete([{"role": "user", "content": "do a task"}],
                         tools=[{"type": "function",
                                  "function": {"name": "x"}}])
        # Both routes cooling down -> the honest queued result,
        # with Horde attempted (not skipped) on the way.
        self.assertTrue(out.get("queued"))
        events = [(e.provider, e.outcome) for e in r.audit]
        self.assertIn(("ai-horde", "http-503"), events)
        self.assertNotIn(("ai-horde", "skipped-no-toolcalls"), events)

    def test_repeated_failures_escalate_the_cooldown(self):
        # v0.12 route health: a route that keeps failing is
        # demoted with an escalating cooldown (x1, x2, x4),
        # and a success clears the record entirely.
        now = [1_000.0]
        clock = lambda: now[0]
        p = ProviderCfg("keyless-pollinations",
                        "https://text.pollinations.ai/openai", "",
                        ["openai"], chat_path="", keyless=True,
                        cooldown_seconds=100)
        state = {"fail": True}
        def transport(prov_, messages):
            return (402, "", None) if state["fail"] else (200, "ok")
        r = Router(cfg_with([p]), transport=transport, clock=clock)
        out1 = r.chat([{"role": "user", "content": "hi"}])
        self.assertTrue(out1["queued"])
        self.assertEqual(out1["retry_at"], 1_100.0)          # x1
        now[0] = 1_100.0
        out2 = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(out2["retry_at"], 1_300.0)          # x2
        now[0] = 1_300.0
        out3 = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(out3["retry_at"], 1_700.0)          # x4
        now[0] = 1_700.0
        state["fail"] = False
        out4 = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(out4["text"], "ok")
        self.assertNotIn("keyless-pollinations", r.fail_counts)
        self.assertNotIn("keyless-pollinations", r.cooldowns)

    def test_default_config_has_horde_second_and_keyless(self):
        import unittest.mock as mock
        with mock.patch.dict(os.environ, {}, clear=True):
            cfg = Config.from_env()
        names = [p.name for p in cfg.providers]
        self.assertEqual(names[0], "keyless-pollinations")
        self.assertEqual(names[1], "ai-horde")
        horde = cfg.providers[1]
        self.assertTrue(horde.enabled)
        self.assertTrue(horde.keyless)
        self.assertEqual(horde.kind, "horde")
        self.assertFalse(horde.trusted_for_private)

class TestOwnKeyRoutes(unittest.TestCase):
    """v0.12.1 own-key parity in the core: the same providers
    as the app, keys read from environment variables ONLY.
    Absent env = the route is disabled (absent from the
    walked chain); present env = enabled, in the order
    groq -> gemini -> cerebras -> openrouter -> agentrouter
    -> custom, after the keyless pair. Dummy values only."""

    OWN_KEY_ENVS = {
        "GROQ_API_KEY": "groq",
        "GEMINI_API_KEY": "gemini-free",
        "CEREBRAS_API_KEY": "cerebras",
        "OPENROUTER_API_KEY": "openrouter-free",
        "AGENTROUTER_API_KEY": "agentrouter",
    }

    def _cfg(self, env):
        import unittest.mock as mock
        with mock.patch.dict(os.environ, env, clear=True):
            return Config.from_env()

    def _in_env(self, env, fn):
        """Run fn(cfg) INSIDE the patched environment —
        ProviderCfg.key() reads os.environ at call time, so
        key assertions made after the patch exits would see
        the real (empty) environment."""
        import unittest.mock as mock
        with mock.patch.dict(os.environ, env, clear=True):
            return fn(Config.from_env())

    def test_no_env_keys_no_own_key_routes_enabled(self):
        def check(cfg):
            by_name = {p.name: p for p in cfg.providers}
            for name in list(self.OWN_KEY_ENVS.values()) + ["custom-openai"]:
                self.assertIn(name, by_name)
                self.assertFalse(by_name[name].enabled, name)
                self.assertEqual(by_name[name].key(), "", name)
        self._in_env({}, check)

    def test_each_env_key_enables_exactly_its_route(self):
        for env_name, provider_name in self.OWN_KEY_ENVS.items():
            def check(cfg, provider_name=provider_name):
                by_name = {p.name: p for p in cfg.providers}
                self.assertTrue(by_name[provider_name].enabled, provider_name)
                self.assertEqual(
                    by_name[provider_name].key(), "dummy-key-value")
                for other in self.OWN_KEY_ENVS.values():
                    if other != provider_name:
                        self.assertFalse(by_name[other].enabled, other)
            self._in_env({env_name: "dummy-key-value"}, check)

    def test_custom_route_needs_base_url_and_key(self):
        cfg = self._cfg({"CUSTOM_LLM_API_KEY": "dummy-key-value"})
        by_name = {p.name: p for p in cfg.providers}
        self.assertFalse(by_name["custom-openai"].enabled)
        cfg = self._cfg({"CUSTOM_LLM_BASE_URL": "https://example.invalid/v1"})
        by_name = {p.name: p for p in cfg.providers}
        self.assertFalse(by_name["custom-openai"].enabled)
        cfg = self._cfg({
            "CUSTOM_LLM_BASE_URL": "https://example.invalid/v1",
            "CUSTOM_LLM_API_KEY": "dummy-key-value",
            "CUSTOM_LLM_MODEL": "my-model",
        })
        custom = {p.name: p for p in cfg.providers}["custom-openai"]
        self.assertTrue(custom.enabled)
        self.assertEqual(custom.base_url, "https://example.invalid/v1")
        self.assertEqual(custom.models, ["my-model"])

    def test_chain_order_keyless_then_own_keys(self):
        env = {name: "dummy-key-value" for name in self.OWN_KEY_ENVS}
        env["CUSTOM_LLM_BASE_URL"] = "https://example.invalid/v1"
        env["CUSTOM_LLM_API_KEY"] = "dummy-key-value"
        cfg = self._cfg(env)
        enabled = [p.name for p in cfg.providers if p.enabled]
        self.assertEqual(enabled, [
            "keyless-pollinations", "ai-horde", "groq", "gemini-free",
            "cerebras", "openrouter-free", "agentrouter", "custom-openai",
        ])

    def test_router_falls_through_keyless_to_env_key_route(self):
        cfg = self._cfg({"GROQ_API_KEY": "dummy-key-value"})
        calls = []
        def transport(p, messages):
            calls.append(p.name)
            return (402, "") if p.name == "keyless-pollinations" else (200, "from-groq")
        r = Router(cfg, transport=transport)
        r.horde_transport = lambda p, m: (0, "", None)
        out = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(out["provider"], "groq")
        self.assertEqual(out["text"], "from-groq")
        self.assertEqual(
            calls, ["keyless-pollinations", "groq"],
            "horde runs on its own transport; the openai transport "
            "must see pollinations then groq, in chain order",
        )

    def test_route_table_matches_the_same_order(self):
        from free_agent.routes import ROUTE_TABLES
        names = [name for name, _s, _k in ROUTE_TABLES["text"]]
        self.assertEqual(names[:8], [
            "keyless-pollinations", "ai-horde", "groq", "gemini-free",
            "cerebras", "openrouter-free", "agentrouter", "custom-openai",
        ])
        own = {name: key for name, _s, key in ROUTE_TABLES["text"]}
        for name in self.OWN_KEY_ENVS.values():
            self.assertEqual(own[name], "own-key", name)

class TestChatHonestyRule(unittest.TestCase):
    """v0.12.3: the core system content must carry the chat
    honesty rule next to the grounding rule — in chat the model
    performs no actions and must never narrate finished work
    it did not do through a tool in a task."""

    def _content(self):
        from free_agent.agent import Agent
        agent = Agent(cfg_with([]), memory_dir=tempfile.mkdtemp())
        return agent._system_content()

    def test_system_content_carries_both_rules(self):
        content = self._content()
        self.assertIn("Grounding: never invent features, facts, or steps", content)
        self.assertIn("Chat honesty: in chat you cannot perform actions", content)

    def test_honesty_rule_forbids_claiming_undone_work(self):
        content = self._content()
        self.assertIn(
            "Never claim to have done, created, fetched, submitted or "
            "completed anything you did not actually do through a tool "
            "in a task", content)
        self.assertIn("never narrate a finished result", content)

    def test_honesty_rule_carries_the_task_question(self):
        # v0.13 mirror of the app prompt: a job the deterministic
        # layer missed is met with the one clarifying question.
        content = self._content()
        self.assertIn("Want me to take that on as a task?", content)


if __name__ == "__main__":
    unittest.main()
