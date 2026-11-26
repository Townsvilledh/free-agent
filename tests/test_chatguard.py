"""The chat-output guard (core mirror) + seamless fallback
ordering — 2026-11-26.

Pins:
- chatguard's detectors against the live failure's shapes
  (self-dialogue speaker turns, an invented <…_LINK>
  placeholder) and against healthy replies (multi-paragraph
  prose, the word "update" used legitimately, code fences
  full of angle brackets).
- The router's guard glue: a rejected reply regenerates
  ONCE on the next route (the rejecting provider excluded);
  a second rejection yields exactly the one plain line.
- Fallback ordering: a 429 on route A triggers route B at
  the SAME fake-clock instant — fallback never waits out a
  pacing window (the router has no sleeps; this pins that
  the call sequence is back-to-back).
"""
import os, sys, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from free_agent import chatguard
from free_agent.config import Config, ProviderCfg
from free_agent.router import Router


def cfg_with(providers):
    c = Config(); c.providers = providers; return c


def prov(name, trusted=False):
    return ProviderCfg(name, "https://example.invalid/v1", "NOPE_KEY",
                       ["m"], trusted_for_private=trusted)


class FakeClock:
    def __init__(self):
        self.t = 1_000_000.0

    def __call__(self):
        return self.t

    def advance(self, seconds):
        self.t += seconds


FAILURE_SHAPE = """Update: I checked on the work for you.

Answer: The Discord server is ready. Join here: <DISCORD_INVITE_LINK>

Question: Do you want me to continue?

Lille: Yes, I will fetch the remaining details next."""


class TestChatGuardDetectors(unittest.TestCase):
    def test_live_failure_shape_rejected(self):
        self.assertIsNotNone(chatguard.rejection_reason(FAILURE_SHAPE))
        self.assertTrue(chatguard.is_rejected(FAILURE_SHAPE))

    def test_placeholder_alone_rejected(self):
        self.assertEqual(
            "placeholder",
            chatguard.rejection_reason("Your link: <DISCORD_INVITE_LINK>"),
        )
        self.assertEqual(
            "placeholder",
            chatguard.rejection_reason("The file is at FILE_URL now."),
        )

    def test_self_dialogue_needs_two_turns(self):
        self.assertEqual(
            "self-dialogue",
            chatguard.rejection_reason("Answer: yes.\n\nQuestion: sure?"),
        )
        self.assertIsNone(
            chatguard.rejection_reason("Update: the meeting moved to 3pm.")
        )

    def test_healthy_replies_pass(self):
        healthy = (
            "Here is the summary you asked for.\n\n"
            "The update from the team is that the release slips "
            "by a week. Nothing about the plan changes.\n\n"
            "Next step: I will check again tomorrow."
        )
        self.assertIsNone(chatguard.rejection_reason(healthy))

    def test_code_fences_never_false_positive(self):
        with_code = (
            "Use a generic list and a div like this:\n\n"
            "```kotlin\n"
            "val items = ArrayList<String>()\n"
            "val el = \"<div>TODO: style me</div>\"\n"
            "```\n\n"
            "And in HTML, `<span>` wraps the text."
        )
        self.assertIsNone(chatguard.rejection_reason(with_code))

    def test_plain_line_is_clean(self):
        self.assertIsNone(chatguard.rejection_reason(chatguard.PLAIN_LINE))


class TestRouterGuardGlue(unittest.TestCase):
    def test_rejection_regenerates_once_on_next_route(self):
        calls = []

        def transport(p, messages):
            calls.append(p.name)
            if p.name == "r1":
                return 200, FAILURE_SHAPE
            return 200, "A clean, ordinary answer."

        r = Router(cfg_with([prov("r1"), prov("r2")]), transport=transport)
        out = r.chat([{"role": "user", "content": "update"}])
        self.assertEqual(out["provider"], "r2")
        self.assertEqual(out["text"], "A clean, ordinary answer.")
        self.assertTrue(out.get("guard_regenerated"))
        self.assertEqual(calls, ["r1", "r2"])

    def test_second_rejection_yields_the_one_plain_line(self):
        def transport(p, messages):
            return 200, FAILURE_SHAPE

        r = Router(cfg_with([prov("r1"), prov("r2")]), transport=transport)
        out = r.chat([{"role": "user", "content": "update"}])
        self.assertEqual(out["text"], chatguard.PLAIN_LINE)
        self.assertTrue(out.get("guard_rejected"))

    def test_clean_first_reply_is_untouched(self):
        r = Router(cfg_with([prov("r1")]),
                   transport=lambda p, m: (200, "All good."))
        out = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(out["text"], "All good.")
        self.assertFalse(out.get("guard_regenerated"))


class TestFallbackOrdering(unittest.TestCase):
    def test_429_falls_through_at_the_same_instant(self):
        clock = FakeClock()
        stamps = []

        def transport(p, messages):
            stamps.append((p.name, clock()))
            if p.name == "r1":
                return 429, ""
            return 200, "from-r2"

        r1 = prov("r1"); r1.cooldown_seconds = 60
        r = Router(cfg_with([r1, prov("r2")]), transport=transport,
                   clock=clock)
        out = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(out["provider"], "r2")
        # Route B was attempted at the SAME clock instant as
        # route A's refusal — no pacing delay in the sequence.
        self.assertEqual(stamps[0][1], stamps[1][1])
        self.assertEqual([s[0] for s in stamps], ["r1", "r2"])

    def test_refusal_also_falls_through_immediately(self):
        clock = FakeClock()
        stamps = []

        def transport(p, messages):
            stamps.append((p.name, clock()))
            return 200, "done"

        r = Router(cfg_with([prov("r1"), prov("r2")]), transport=transport,
                   clock=clock)
        out = r.chat([{"role": "user", "content": "hi"}])
        self.assertEqual(out["provider"], "r1")
        self.assertEqual(len(stamps), 1)


if __name__ == "__main__":
    unittest.main()
