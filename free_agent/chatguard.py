"""The chat-output guard (2026-11-26) — the Python core's
mirror of the Android ChatOutputGuard.

A deterministic screen applied to every CHAT reply before
it is shown. Born of one live reply — her answer to a
status probe — which stacked an invented placeholder
token (<DISCORD_INVITE_LINK>) presented as a fetched fact
with confabulated SELF-DIALOGUE (Answer: / Update: /
Lille: speaker turns inside a single reply, a fake Q&A
with herself). Ethuram: "I don't want whatever this is
to repeat itself."

Detectors run over the PROSE only — fenced code blocks
and inline code are stripped first, so HTML, generics
and angle brackets inside code never false-positive:

- PLACEHOLDER: angle-bracket SCREAMING tokens, <insert …>
  forms, bare *_LINK / *_URL / *_TOKEN words presented as
  content, TODO:/FIXME: markers standing in for content.
- SELF_DIALOGUE: two or more speaker-turn markers at line
  starts (Answer:, Update:, Question:, Response:, Reply:,
  Assistant:, User:, Lille:, Me:). One marker is ordinary
  prose and passes.

On a hit the router silently regenerates ONCE on the next
route; on a second hit the reply is replaced by
PLAIN_LINE and the rejected text stays in the audit/trace
only. The task-engine path (complete()) is NOT screened —
its text is evidence-graded by spec mode, which owns that
side.
"""
import re

PLAIN_LINE = "That reply didn't come out right — ask me again."

_FENCE_RE = re.compile(r"```.*?```", re.S)
_INLINE_CODE_RE = re.compile(r"`[^`\n]*`")

_ANGLE_PLACEHOLDER_RE = re.compile(r"<[A-Z][A-Z0-9_]{2,}>")
_ANGLE_INSERT_RE = re.compile(r"<\s*(insert|placeholder|your)\b[^>]*>", re.I)
_BARE_TOKEN_RE = re.compile(r"\b[A-Z][A-Z0-9_]{2,}_(LINK|URL|TOKEN|HERE)\b")
_TODO_MARKER_RE = re.compile(r"(^|\n)\s*(TODO|FIXME|XXX)\s*:", re.I)
_BRACKET_INSERT_RE = re.compile(r"\[\s*(insert|placeholder)\b[^\]]*\]", re.I)

_SPEAKER_TURN_RE = re.compile(
    r"^\s{0,3}(\*\*|__)?(Answer|Update|Question|Response|Reply|"
    r"Assistant|User|Lille|Me)(\*\*|__)?:",
    re.M,
)


def prose_only(text):
    """The prose view of a reply: code fenced off, because
    code legitimately contains the hunted shapes."""
    return _INLINE_CODE_RE.sub(" ", _FENCE_RE.sub(" ", text or ""))


def rejection_reason(text):
    """None when the reply is clean, else a trace tag
    ("placeholder" / "self-dialogue")."""
    prose = prose_only(text)
    if (_ANGLE_PLACEHOLDER_RE.search(prose) or
            _ANGLE_INSERT_RE.search(prose) or
            _BARE_TOKEN_RE.search(prose) or
            _TODO_MARKER_RE.search(prose) or
            _BRACKET_INSERT_RE.search(prose)):
        return "placeholder"
    if len(_SPEAKER_TURN_RE.findall(prose)) >= 2:
        return "self-dialogue"
    return None


def is_rejected(text):
    return rejection_reason(text) is not None
