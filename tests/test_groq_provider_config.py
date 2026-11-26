"""Regression pin (2026-11-26): the hostkeys Groq proof, with the
User-Agent fix in, reached Groq and got HTTP 404. Root cause: every
router transport sends provider.models[0] only, and the Groq
provider listed "llama-3.3-70b-versatile" first — an id Groq
answers model_not_found (404) for on this account — while the
proven model "openai/gpt-oss-120b" (the custom.groq connector
helper's model, verified answering 200) sat unused at models[1].

These pins hold the Groq provider to the helper's proven shape:
same base URL, and the proven model first.
"""
import os, sys, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from free_agent.config import Config


def groq_provider():
    cfg = Config.from_env()
    for p in cfg.providers:
        if p.name == "groq":
            return p
    raise AssertionError("no groq provider in default config")


class GroqProviderConfigTest(unittest.TestCase):
    def test_base_url_matches_proven_helper(self):
        self.assertEqual(
            "https://api.groq.com/openai/v1", groq_provider().base_url)

    def test_chat_path_is_the_openai_compatible_one(self):
        self.assertEqual("/chat/completions", groq_provider().chat_path)

    def test_proven_model_is_sent_first(self):
        # The router only ever sends models[0]; it must be the
        # model verified live against this account.
        self.assertEqual("openai/gpt-oss-120b", groq_provider().models[0])

    def test_dead_model_never_leads(self):
        self.assertNotEqual(
            "llama-3.3-70b-versatile", groq_provider().models[0])


if __name__ == "__main__":
    unittest.main()
