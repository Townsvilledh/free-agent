"""Agent core loop (v0.1): load memory files -> route prompt -> reply.

Memory equivalents of Muse's files live in ./memory/: SOUL.md (persona),
USER.md (who the user is), MEMORY.md (durable facts). They contain NO
secrets — secrets live only in the vault.
"""
import os
from .router import Router

MEMORY_FILES = ["SOUL.md", "USER.md", "MEMORY.md"]

def load_memory(base):
    parts = []
    for name in MEMORY_FILES:
        p = os.path.join(base, name)
        if os.path.exists(p):
            parts.append(f"# {name}\n" + open(p).read())
    return "\n\n".join(parts)

class Agent:
    def __init__(self, config, memory_dir="memory", transport=None):
        self.router = Router(config, transport=transport)
        self.system = load_memory(memory_dir)

    def chat(self, user_text, private=False, category="public", override_privacy=False):
        messages = ([{"role": "system", "content": self.system}] if self.system else []) + \
                   [{"role": "user", "content": user_text}]
        return self.router.chat(messages, category=category,
                                private=private, override_privacy=override_privacy)
