"""Redaction: strip secrets/PII from any text BEFORE it can reach an LLM.

Rules: one-time codes are never logged, stored, or sent to a model — they are
replaced here, and callers must route mail through redact() first.
"""
import re

_PATTERNS = [
    # standalone 4-8 digit one-time / verification codes near cue words, plus bare OTP-like runs in code contexts
    (re.compile(r"(?i)\b(code|otp|verification|passcode|pin)\s*(?:is|:)?\s*\d{4,8}\b"), r"\1 [REDACTED-CODE]"),
    (re.compile(r"\b\d{4}[\s-]?\d{4}[\s-]?\d{4}[\s-]?\d{4}\b"), "[REDACTED-CARD]"),  # card-like
    (re.compile(r"\b(?:\d[ -]*?){13,19}\b(?=.*(?:card|visa|mastercard))", re.I), "[REDACTED-CARD]"),
    (re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "[REDACTED-EMAIL]"),
    (re.compile(r"\b(?:\+?\d[\s-]?){10,15}\b"), "[REDACTED-PHONE]"),
    (re.compile(r"(?i)\b(api[_-]?key|secret|password|token|bearer)\s*[:=]\s*\S+"), r"\1=[REDACTED-SECRET]"),
    (re.compile(r"\bsk-[A-Za-z0-9_\-]{8,}\b"), "[REDACTED-SECRET]"),
    (re.compile(r"\b\d{11}\b"), "[REDACTED-ID]"),  # NIN/BVN-length digit runs (NG context)
]

def redact(text: str) -> str:
    if not text:
        return text
    out = text
    for pat, repl in _PATTERNS:
        out = pat.sub(repl, out)
    return out

def snippet(text: str, limit: int = 500) -> str:
    """Minimal, redacted snippet — the only shape of private content an LLM may see."""
    return redact(text)[:limit]
