"""Smart recovery helpers (v0.2.1): refusal recovery, stream stitching,
and extraction verification.

These exist because of the 2026-11-24 live battery findings:

- The free 20B model refuses some *phrasings* ("can you create an
  autonomous profitable agent") while happily doing the identical work
  phrased as a concrete task. Lille — not the user — carries the
  rephrasing work: ONE automatic reframe + retry, with a transparency
  note. Genuinely harmful/illegal requests are NEVER reframed; their
  refusals stand.
- Long answers can be cut mid-body by the transport. Streaming with
  partial preservation + auto-continue (max 3) stitches them whole.
- Small models slip on extraction fidelity ("Mr." -> "M."). JSON
  extractions get a verification pass against the source text, with
  one correction retry that names the mismatch.

No branding, sign-offs, or user-specific content live in this module —
that belongs to per-user config/memory, never code (DESIGN.md #11).
"""
import json, re

# ---------- refusal detection ----------

_REFUSAL_MARKERS = (
    "can't help with that",
    "cannot help with that",
    "i can't help",
    "i cannot help",
    "can't assist with that",
    "cannot assist with that",
)

def _normalize_quotes(s):
    return (s.replace("\u2019", "'").replace("\u2018", "'")
             .replace("\u201c", '"').replace("\u201d", '"'))

def is_refusal(text):
    """A refusal is a SHORT reply containing a refusal marker. Long
    replies that merely discuss refusals are not refusals."""
    if not text:
        return False
    t = text.strip()
    if len(t) > 300:
        return False
    low = _normalize_quotes(t.lower())
    return any(m in low for m in _REFUSAL_MARKERS) or \
        (low.startswith("i'm sorry") and "can't" in low) or \
        (low.startswith("i am sorry") and "cannot" in low)

# ---------- harmful categories: refusals stand, never reframe ----------

_HARMFUL_PATTERNS = (
    r"\b(bomb|explosive|weapon|firearm|gun|ammunition)\b.*\b(build|make|create|construct|assemble)\b",
    r"\b(build|make|create|construct|assemble)\b.*\b(bomb|explosive|weapon|firearm)\b",
    r"\b(kill|murder|hurt|harm|attack)\b.*\b(people|person|someone|him|her|them)\b",
    r"\b(steal|theft|rob|burglar)\b",
    r"\b(fraud|scam|phish|forge|counterfeit)\b",
    r"\b(hack into|break into|bypass.*password|steal.*password)\b",
    r"\b(child|minor)\b.*\b(sexual|abuse|exploit)\b",
    r"\b(malware|ransomware|virus|trojan)\b",
)

def is_harmful_request(text):
    low = (text or "").lower()
    return any(re.search(p, low) for p in _HARMFUL_PATTERNS)

# ---------- reframing ----------

def reframe_request(user_text):
    """Turn a vague outcome ask into a concrete, bounded task form.

    Strategy: keep the user's goal, but ask for it as a concrete
    build/write task, explicitly simulated/paper-only where money or
    real-world action is implied. This is a phrasing repair, not a
    jailbreak: the harmful check runs BEFORE this is ever used, and
    the reframed request adds safety bounds rather than removing any.
    """
    core = (user_text or "").strip()
    # Drop attachment bookkeeping notes — they are app metadata, and
    # "[attachment: ...]" noise does not belong in the reframed ask.
    core = re.sub(r"\[attachment:[^\]]*\]", "", core).strip()
    core = re.sub(r"\[Attached file:.*?\]", "", core, flags=re.S).strip()
    if not core:
        core = user_text.strip()
    low = core.lower()
    # Live finding (v0.2.1 re-run): merely WRAPPING the original wording
    # fails — the reframed request still contains the trigger phrase
    # ("autonomous profitable agent") and is refused again. The reframe
    # must REPLACE the vague outcome wording with a concrete spec (the
    # battery's 7b phrasing is the proven shape for agent+money asks).
    if "agent" in low and any(w in low for w in
                              ("profit", "trading", "trade", "btc", "bitcoin", "crypto", "money")):
        return (
            "Write a Python script for an autonomous agent that watches the BTC "
            "price and paper-trades a simple moving-average strategy, no real "
            "money. Include a short explanation of how it works and any risks."
        )
    moneyish = any(w in low for w in ("profit", "trading", "trade", "money", "invest", "crypto", "btc", "bitcoin"))
    # Sanitise known trigger words out of the embedded request, then
    # wrap it as a concrete build task with explicit safety bounds.
    sanitised = re.sub(r"\bprofitable\b", "", core, flags=re.I)
    sanitised = re.sub(r"\bmake money\b", "run a simulated strategy", sanitised, flags=re.I)
    sanitised = re.sub(r"\s+", " ", sanitised).strip()
    bounds = "as a simulation / paper-only version with no real money, no real accounts and no real-world transactions" \
        if moneyish else "as a self-contained example that acts on no real accounts or real-world systems"
    return (
        "Write a complete Python script that implements the following request "
        f"{bounds}. Include a short explanation of how it works and any risks. "
        f"Request: {sanitised}"
    )

REFUSAL_NOTE = "The free model refused that phrasing, so I re-asked it as a concrete task:"
REFUSAL_STILL = (
    "\n\n(The free model refused this one even after I re-asked it as a "
    "concrete task. Try a more specific wording yourself — for example "
    "\"Write a Python script that …\" naming exactly what it should do.)"
)

# ---------- streaming stitch ----------

class StreamDropped(Exception):
    """Raised by a streaming transport when the connection dies
    mid-body. Carries the partial text received so far."""
    def __init__(self, partial):
        super().__init__("stream dropped mid-body")
        self.partial = partial or ""

def stitch(parts):
    """Join continuation parts, trimming a small overlap when the
    continuation repeats the tail of what already arrived."""
    out = ""
    for part in parts:
        if not part:
            continue
        if not out:
            out = part
            continue
        overlap = 0
        for k in range(min(64, len(out), len(part)), 0, -1):
            if out.endswith(part[:k]):
                overlap = k
                break
        out += part[overlap:]
    return out

CONTINUE_PROMPT = ("Continue exactly from where you stopped. Do not repeat "
                   "any text you have already given.")

# ---------- extraction verification ----------

def _norm(s):
    return re.sub(r"\s+", " ", str(s)).strip().lower()

def extract_json(text):
    """Pull the first JSON object out of a model reply, or None."""
    if not text:
        return None
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end <= start:
        return None
    try:
        obj = json.loads(text[start:end + 1])
    except (ValueError, TypeError):
        return None
    return obj if isinstance(obj, dict) else None

def verify_extraction(source_text, json_text):
    """Check every extracted field against the source text.

    Returns a list of (field, value) mismatches: string values must
    appear (whitespace/case-normalised) in the source; numeric values
    must appear as digits once thousands separators are removed.
    An unparseable reply is itself a mismatch under field '_json'.
    """
    obj = extract_json(json_text)
    if obj is None:
        return [("_json", "reply was not valid JSON")]
    src = _norm(source_text)
    src_digits = re.sub(r"[,\s]", "", source_text)
    mismatches = []
    for field, value in obj.items():
        if isinstance(value, bool) or value is None:
            continue
        if isinstance(value, (int, float)):
            digits = str(int(value)) if float(value).is_integer() else str(value)
            if digits not in src_digits:
                mismatches.append((field, value))
        elif isinstance(value, str):
            if _norm(value) not in src:
                mismatches.append((field, value))
    return mismatches

def correction_prompt(mismatches):
    named = "; ".join(
        f"field '{f}' — you wrote {v!r}, which does not appear exactly in the source"
        for f, v in mismatches)
    return (
        "Your JSON has extraction errors: " + named + ". "
        "Copy names, amounts, dates and phone numbers EXACTLY as they "
        "appear in the source text. Return ONLY the corrected JSON."
    )
