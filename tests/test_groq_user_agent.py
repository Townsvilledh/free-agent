"""Regression pin (2026-11-26): Groq's edge answers HTTP 403 to the
default Python urllib User-Agent. Every request the core router sends
to the Groq provider must carry the browser User-Agent (the same one
the custom.groq connector helper sets); other providers' requests
must NOT gain it. Live evidence: the hostkeys bridge proof reached
Groq with a valid credential and got 403 until the UA was set."""
import json, os, sys, unittest
from unittest import mock
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from free_agent.config import Config, ProviderCfg
from free_agent.router import Router, GROQ_USER_AGENT


def cfg_with(providers):
    c = Config(); c.providers = providers; return c


def prov(name, base="https://example.invalid/v1"):
    return ProviderCfg(name, base, "NOPE_KEY", ["m"])


class FakeResp:
    status = 200

    def __init__(self, body=b"{}", lines=()):
        self._body = body
        self._lines = list(lines)

    def read(self):
        return self._body

    def __iter__(self):
        return iter(self._lines)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


CHAT_BODY = json.dumps(
    {"choices": [{"message": {"content": "ok"}}]}).encode()
STREAM_LINES = [
    b'data: {"choices": [{"delta": {"content": "ok"}}]}',
    b"data: [DONE]",
]


class TestGroqUserAgent(unittest.TestCase):
    def capture(self, fn, provider):
        """Run one real HTTP transport with urlopen stubbed; return
        the urllib Request it built."""
        seen = {}

        def fake_urlopen(req, timeout=None):
            seen["req"] = req
            return FakeResp(CHAT_BODY, STREAM_LINES)

        r = Router(cfg_with([provider]))
        with mock.patch("free_agent.router.urllib.request.urlopen",
                        fake_urlopen):
            fn(r, provider)
        return seen["req"]

    def test_plain_transport_sets_browser_ua_for_groq(self):
        req = self.capture(
            lambda r, p: r._http_transport(p, [{"role": "user",
                                                "content": "hi"}]),
            prov("groq", "https://api.groq.com/openai/v1"))
        self.assertEqual(req.get_header("User-agent"), GROQ_USER_AGENT)

    def test_stream_transport_sets_browser_ua_for_groq(self):
        req = self.capture(
            lambda r, p: r._http_stream_transport(
                p, [{"role": "user", "content": "hi"}]),
            prov("groq", "https://api.groq.com/openai/v1"))
        self.assertEqual(req.get_header("User-agent"), GROQ_USER_AGENT)

    def test_raw_transport_sets_browser_ua_for_groq(self):
        req = self.capture(
            lambda r, p: r._http_raw_transport(
                p, [{"role": "user", "content": "hi"}]),
            prov("groq", "https://api.groq.com/openai/v1"))
        self.assertEqual(req.get_header("User-agent"), GROQ_USER_AGENT)

    def test_other_providers_get_no_explicit_ua(self):
        req = self.capture(
            lambda r, p: r._http_transport(p, [{"role": "user",
                                                "content": "hi"}]),
            prov("keyless-pollinations"))
        self.assertIsNone(req.get_header("User-agent"))


if __name__ == "__main__":
    unittest.main()
