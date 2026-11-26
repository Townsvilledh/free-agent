"""Agent core loop: load memory files -> route prompt -> reply.

Memory equivalents of Muse's files live in ./memory/: SOUL.md (persona),
USER.md (who the user is), MEMORY.md (durable facts). They contain NO
secrets — secrets live only in the vault.

v0.2.1 additions:
- GROUNDING rule in the system prompt (battery finding: a planning
  answer invented a login flow and gave one unsafe tip).
- Extraction verification: when the user asks for JSON, the reply's
  fields are checked against the source text in the request; a
  mismatch triggers ONE correction retry that names the mismatch
  (battery finding: "Mr." came back as "M.").
"""
import os
from .router import Router
from . import recovery

MEMORY_FILES = ["SOUL.md", "USER.md", "MEMORY.md"]

# Grounding (v0.2.1) — concise, applies to every reply. Branding and
# user-specific style live in the memory files / user config, never
# hard-coded here (DESIGN.md #11).
GROUNDING_RULE = (
    "Grounding: never invent features, facts, or steps the user did not "
    "give you. If something is unknown, say it is unknown or label it "
    "clearly as an assumption. Practical advice must be safe and "
    "standard practice."
)

# Chat honesty (v0.12.3) — a chat reply performs no actions.
# Born of a live failure: on a community fallback route the
# model answered a hackathon brief by narrating a fully
# completed submission (repo created, test pack fetched, form
# submitted) that never happened. In chat there are no tools;
# work happens in tasks, and only a task's verified finish may
# be reported as done.
CHAT_HONESTY_RULE = (
    "Chat honesty: in chat you cannot perform actions — nothing you "
    "say here does anything by itself. Never claim to have done, "
    "created, fetched, submitted or completed anything you did not "
    "actually do through a tool in a task. When the user gives you "
    "a job in chat, say you are starting it and what happens next, "
    "in the present tense — never narrate a finished result."
)

def load_memory(base):
    parts = []
    for name in MEMORY_FILES:
        p = os.path.join(base, name)
        if os.path.exists(p):
            parts.append(f"# {name}\n" + open(p).read())
    return "\n\n".join(parts)

class Agent:
    def __init__(self, config, memory_dir="memory", transport=None, stream_transport=None):
        self.router = Router(config, transport=transport, stream_transport=stream_transport)
        self.system = load_memory(memory_dir)

    def _system_content(self):
        rules = GROUNDING_RULE + "\n\n" + CHAT_HONESTY_RULE
        return (self.system + "\n\n" + rules) if self.system else rules

    def chat(self, user_text, private=False, category="public", override_privacy=False):
        messages = [{"role": "system", "content": self._system_content()},
                    {"role": "user", "content": user_text}]
        result = self.router.chat(messages, category=category,
                                  private=private, override_privacy=override_privacy)
        # Extraction verification pass (JSON asks only).
        if "json" in user_text.lower() and result.get("text") and not result.get("queued"):
            mismatches = recovery.verify_extraction(user_text, result["text"])
            if mismatches:
                followup = messages + [
                    {"role": "assistant", "content": result["text"]},
                    {"role": "user", "content": recovery.correction_prompt(mismatches)},
                ]
                corrected = self.router.chat(followup, category=category,
                                             private=private, override_privacy=override_privacy)
                if corrected.get("text") and not corrected.get("queued"):
                    corrected["extraction_corrected"] = True
                    corrected["extraction_mismatches"] = [f for f, _ in mismatches]
                    return corrected
        return result
