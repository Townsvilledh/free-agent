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
    def key(self):
        return os.environ.get(self.api_key_env, "")

@dataclass
class Config:
    privacy_mode: str = "strict"        # strict | override-per-task (explicit flag only)
    gmail_send_enabled: bool = False    # send/modify scopes OFF by default
    prompt_logging: bool = False        # OFF by default — no telemetry, no analytics
    require_approval_for: tuple = ("send_email", "post", "delete", "money")
    providers: list = field(default_factory=list)

    @classmethod
    def from_env(cls):
        c = cls(
            privacy_mode=os.environ.get("FREEAGENT_PRIVACY_MODE", "strict"),
            gmail_send_enabled=os.environ.get("FREEAGENT_GMAIL_SEND", "0") == "1",
        )
        c.providers = [
            # Primary: AgentRouter free credit. Community relay, unknown retention
            # => NOT trusted for private content.
            ProviderCfg("agentrouter", "https://agentrouter.org/v1", "AGENTROUTER_API_KEY",
                        ["claude-opus-4-8", "gpt-5.6-sol", "deepseek-v4-flash"], trusted_for_private=False),
            # Fallback: Groq free tier. Also an external provider => not trusted by default.
            ProviderCfg("groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY",
                        ["llama-3.3-70b-versatile", "openai/gpt-oss-120b"], trusted_for_private=False),
            # Config stubs for more free tiers (enable when keys exist).
            ProviderCfg("gemini-free", "https://generativelanguage.googleapis.com/v1beta/openai/",
                        "GEMINI_API_KEY", ["gemini-flash-latest"], trusted_for_private=False, enabled=False),
            ProviderCfg("openrouter-free", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY",
                        ["deepseek/deepseek-chat:free"], trusted_for_private=False, enabled=False),
            ProviderCfg("cloudflare-workers-ai", "", "CLOUDFLARE_API_TOKEN",
                        [], trusted_for_private=False, enabled=False),
            # A local model (Ollama on the host) is the ONLY default-trusted path
            # for private content. Enable when installed.
            ProviderCfg("local-ollama", "http://127.0.0.1:11434/v1", "",
                        ["llama3.1:8b"], trusted_for_private=True, enabled=False),
        ]
        return c
