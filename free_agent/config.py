"""Config from environment only. No secrets in code, files, or git.

Real keys belong in the vault (see vault.py) or in a local .env that is
gitignored — .env.example lists names only, never values.
"""
import os
from dataclasses import dataclass, field

@dataclass
class ProviderCfg:
    name: str
    base_url: str
    api_key_env: str
    models: list
    trusted_for_private: bool = False   # may this provider receive private content?
    enabled: bool = True
    # Path appended to base_url for the chat call. OpenAI-compatible
    # gateways use "/chat/completions"; Pollinations' keyless endpoint
    # takes the POST at the base URL itself ("" = POST to base_url).
    chat_path: str = "/chat/completions"
    keyless: bool = False               # True = works with NO key at all
    # Fallback law: when this route answers 402/429/503, the router
    # cools it down for this many seconds (or the provider's
    # Retry-After, when the transport reports one) and moves on.
    # The route is automatically re-probed after the cooldown.
    cooldown_seconds: int = 60
    # Transport kind (v0.12): "openai" = OpenAI-compatible chat
    # completions (the default for every route above). "horde" =
    # AI Horde's raw-completion queue API — no chat format and NO
    # tool calls, so the router skips it on the complete() path.
    kind: str = "openai"
    def key(self):
        if self.keyless or not self.api_key_env:
            return ""
        return os.environ.get(self.api_key_env, "")

@dataclass
class Config:
    privacy_mode: str = "strict"        # strict | override-per-task (explicit flag only)
    gmail_send_enabled: bool = False    # send/modify scopes OFF by default
    calendar_write_enabled: bool = False  # calendar create OFF by default
    prompt_logging: bool = False        # OFF by default — no telemetry, no analytics
    require_approval_for: tuple = ("send_email", "post", "delete", "money",
                                   "create_calendar_event")
    providers: list = field(default_factory=list)

    @classmethod
    def from_env(cls):
        c = cls(
            privacy_mode=os.environ.get("FREEAGENT_PRIVACY_MODE", "strict"),
            gmail_send_enabled=os.environ.get("FREEAGENT_GMAIL_SEND", "0") == "1",
            calendar_write_enabled=os.environ.get("FREEAGENT_CALENDAR_WRITE", "0") == "1",
        )
        c.providers = [
            # DEFAULT brain (v0.2, NO-KEYS DOCTRINE): Pollinations keyless
            # public endpoint. No key, no signup, nothing to collect from
            # anybody. POST https://text.pollinations.ai/openai
            # (OpenAI-compatible). Anonymous limits are real (~1 concurrent
            # request, ~45s between requests per IP, 402 + Retry-After
            # beyond that) — the router falls through on 402/429.
            # It is a PUBLIC endpoint => NOT trusted for private content.
            ProviderCfg("keyless-pollinations", "https://text.pollinations.ai/openai", "",
                        ["openai"], trusted_for_private=False, chat_path="", keyless=True,
                        cooldown_seconds=45),
            # FALLBACK OF LAST RESORT (v0.12, live-verified keyless
            # 2026-11-26 — ROUTES.md): AI Horde, the community
            # compute pool. The anonymous key "0000000000" is the
            # service's own public no-account key. Queue-based and
            # SLOW (tens of seconds to minutes), raw-completion API
            # only — no chat format, no tool calls, community
            # models of varying quality. It exists so a busy
            # Pollinations never dead-ends a chat. Public endpoint
            # => NOT trusted for private content.
            ProviderCfg("ai-horde", "https://stablehorde.net/api/v2", "",
                        ["any"], trusted_for_private=False, keyless=True,
                        cooldown_seconds=300, kind="horde"),
            # OPTIONAL personal keys (user's own, vault-only, never shipped
            # in the app and never collected by us). Enabled only when the
            # user has actually supplied their own key. v0.12.1: the chain
            # order after the keyless pair is Groq -> Gemini -> Cerebras ->
            # OpenRouter -> AgentRouter -> custom, the same order the app
            # walks (OwnKeyRoutes). Keys come from the environment ONLY;
            # absent = the route is simply not in the chain.
            # Groq free tier. External provider => not trusted by default.
            ProviderCfg("groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY",
                        ["llama-3.3-70b-versatile", "openai/gpt-oss-120b"], trusted_for_private=False,
                        enabled=bool(os.environ.get("GROQ_API_KEY"))),
            # Gemini free tier (AI Studio key), via Google's
            # OpenAI-compatible endpoint. gemini-flash-latest is the
            # documented alias for the current Flash model.
            ProviderCfg("gemini-free", "https://generativelanguage.googleapis.com/v1beta/openai/",
                        "GEMINI_API_KEY", ["gemini-flash-latest"], trusted_for_private=False,
                        enabled=bool(os.environ.get("GEMINI_API_KEY"))),
            # Cerebras free tier (~1M tokens/day; llama-3.3-70b is the
            # documented free-tier flagship).
            ProviderCfg("cerebras", "https://api.cerebras.ai/v1", "CEREBRAS_API_KEY",
                        ["llama-3.3-70b"], trusted_for_private=False,
                        enabled=bool(os.environ.get("CEREBRAS_API_KEY"))),
            # OpenRouter free models. "openrouter/free" is OpenRouter's
            # own free-model auto-router (verified present in the keyless
            # GET /models list 2026-11-26; the individual :free roster
            # rotates, the router id does not).
            ProviderCfg("openrouter-free", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY",
                        ["openrouter/free"], trusted_for_private=False,
                        enabled=bool(os.environ.get("OPENROUTER_API_KEY"))),
            # AgentRouter free credit. Community relay, unknown retention
            # => NOT trusted for private content.
            ProviderCfg("agentrouter", "https://agentrouter.org/v1", "AGENTROUTER_API_KEY",
                        ["claude-opus-4-8", "gpt-5.6-sol", "deepseek-v4-flash"], trusted_for_private=False,
                        enabled=bool(os.environ.get("AGENTROUTER_API_KEY")),
                        cooldown_seconds=1800),  # daily batch quota: cool longer
            # Custom OpenAI-compatible endpoint (the user's own server
            # or a relay they trust). Needs base URL + key (+ optional
            # model name) in the environment; absent = absent.
            ProviderCfg("custom-openai", os.environ.get("CUSTOM_LLM_BASE_URL", ""),
                        "CUSTOM_LLM_API_KEY",
                        [os.environ.get("CUSTOM_LLM_MODEL", "default")],
                        trusted_for_private=False,
                        enabled=bool(os.environ.get("CUSTOM_LLM_BASE_URL")) and
                                bool(os.environ.get("CUSTOM_LLM_API_KEY"))),
            ProviderCfg("cloudflare-workers-ai", "", "CLOUDFLARE_API_TOKEN",
                        [], trusted_for_private=False, enabled=False),
            # A local model (Ollama on the host) is the ONLY default-trusted path
            # for private content. Enable when installed.
            ProviderCfg("local-ollama", "http://127.0.0.1:11434/v1", "",
                        ["llama3.1:8b"], trusted_for_private=True, enabled=False),
        ]
        return c
