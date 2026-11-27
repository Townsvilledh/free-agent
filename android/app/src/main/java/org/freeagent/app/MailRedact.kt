package org.freeagent.app

/**
 * MAIL / CALENDAR REDACTION — a faithful Kotlin port of the
 * Python core's free_agent/redact.py.
 *
 * Why this exists: any text Lille pulls from a mailbox or a
 * calendar is third-party, attacker-controlled content, and
 * some of it is the user's own secrets in transit — one-time
 * codes, card numbers, credential assignments. The security
 * law (mailtools.py's module docstring, SECURITY.md DESIGN
 * #4) is deterministic and not the model's job: NO such text
 * reaches the model, a log, or a task trace until it has
 * passed through [redact]. Callers route mail and calendar
 * text through here FIRST, exactly as the core's handlers
 * route everything through redact() before it can leave
 * mailtools.py.
 *
 * The pattern list below is redact.py's _PATTERNS, in the
 * SAME ORDER, with the same replacements. Order is part of
 * the spec, not an accident:
 *
 * - The cue-word code pattern runs first, so a verification
 *   code is named a code before any digit-run pattern can
 *   claim its digits.
 * - The phone pattern runs BEFORE the 11-digit ID pattern,
 *   and its {10,15} digit-run shape also matches a bare
 *   11-digit run — so a bare NIN/BVN-length number is in
 *   practice claimed as [REDACTED-PHONE] and the ID pattern
 *   is the backstop for shapes the phone pattern declines.
 *   That shadowing exists in the Python too; it is kept,
 *   not "fixed", because the guarantee that matters — the
 *   digits never survive — holds either way.
 * - The phone pattern's per-digit optional separator also
 *   consumes ONE trailing space/dash after the final digit
 *   ("Call 4155550132 now" → "Call [REDACTED-PHONE]now").
 *   Verified against the Python itself: it does exactly
 *   the same. Kept for parity, not prettied up.
 *
 * Honest limits: this is a pattern list, not a proof. It
 * catches the shapes redact.py catches (codes near cue
 * words, card-like groupings, emails, phone-like runs,
 * key=value secrets, sk- keys, 11-digit runs) and nothing
 * else — a secret written in an unlisted shape passes.
 * That is why bodies additionally reach the model only as
 * short [snippet]s: redaction plus minimisation, together.
 *
 * One deliberate omission: redact.py's third pattern (a
 * 13–19 digit run redacted only when the REST of the text
 * mentions card/visa/mastercard) is NOT ported. It uses a
 * lookahead over the whole remainder of the string, which
 * has no direct equivalent here, and its ground is largely
 * covered by the card-like and phone patterns above. The
 * omission is documented rather than silently approximated
 * with a different rule.
 *
 * Pure Kotlin (java.util only via Regex); pinned by
 * MailRedactTest.
 */
object MailRedact {

    // redact.py _PATTERNS, in order. Replacements that carry
    // the matched cue/key word use the capture group, exactly
    // as the Python's \1 backreference does (original case
    // preserved).

    /** Cue-word codes: "code is 482913", "OTP: 1234", … */
    private val CUE_CODE = Regex(
        """\b(code|otp|verification|passcode|pin)\s*(?:is|:)?\s*\d{4,8}\b""",
        RegexOption.IGNORE_CASE
    )

    /** Card-like: four groups of four digits. */
    private val CARD_LIKE = Regex(
        """\b\d{4}[\s-]?\d{4}[\s-]?\d{4}[\s-]?\d{4}\b"""
    )

    /** Email addresses. */
    private val EMAIL = Regex(
        """[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""
    )

    /** Phone-like runs: 10–15 digits, optional leading +
     *  and single space/dash separators. Runs before the
     *  ID pattern — see the ordering note above. */
    private val PHONE_LIKE = Regex(
        """\b(?:\+?\d[\s-]?){10,15}\b"""
    )

    /** key=value secrets: "api_key: …", "token=…", … */
    private val KEY_VALUE_SECRET = Regex(
        """\b(api[_-]?key|secret|password|token|bearer)\s*[:=]\s*\S+""",
        RegexOption.IGNORE_CASE
    )

    /** sk- API keys. */
    private val SK_KEY = Regex(
        """\bsk-[A-Za-z0-9_\-]{8,}\b"""
    )

    /** NIN/BVN-length digit runs (NG context) — the
     *  backstop after the phone pattern, as in Python. */
    private val ID_RUN = Regex(
        """\b\d{11}\b"""
    )

    /**
     * Strip secrets/PII from [text], applying every pattern
     * in the Python's order. Empty input returns empty.
     */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = CUE_CODE.replace(out) { m ->
            "${m.groupValues[1]} [REDACTED-CODE]"
        }
        out = CARD_LIKE.replace(out, "[REDACTED-CARD]")
        out = EMAIL.replace(out, "[REDACTED-EMAIL]")
        out = PHONE_LIKE.replace(out, "[REDACTED-PHONE]")
        out = KEY_VALUE_SECRET.replace(out) { m ->
            "${m.groupValues[1]}=[REDACTED-SECRET]"
        }
        out = SK_KEY.replace(out, "[REDACTED-SECRET]")
        out = ID_RUN.replace(out, "[REDACTED-ID]")
        return out
    }

    /**
     * Minimal, redacted snippet — the only shape of private
     * content a model may see (redact.py's snippet). The cap
     * applies AFTER redaction, so a redaction marker is
     * never half-cut into leaking a secret's tail and the
     * limit counts what the model actually receives.
     */
    fun snippet(text: String, limit: Int = 500): String =
        redact(text).take(limit)
}
