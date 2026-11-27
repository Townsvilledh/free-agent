package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redactor, pattern class by pattern class: every
 * class gets a positive (the secret is stripped) and a
 * near-miss negative (lookalike innocent text is left
 * alone — an over-eager redactor that eats ordinary mail
 * is its own bug). The verification-mail pin is the law's
 * reason for existing: a one-time code must appear
 * NOWHERE in anything the model can see.
 */
class MailRedactTest {

    // ---------- cue-word codes ----------

    @Test
    fun cueWordCodeIsRedactedKeepingTheCueWord() {
        // The cue word that anchors the match here is
        // "code" ("verification" alone is not followed by
        // digits) — the Python's output, verbatim.
        val out = MailRedact.redact("Your verification code is 482913")
        assertEquals("Your verification code [REDACTED-CODE]", out)
        assertFalse(out.contains("482913"))
    }

    @Test
    fun cueWordVariantsAreRedacted() {
        assertFalse(MailRedact.redact("OTP: 1234").contains("1234"))
        assertFalse(MailRedact.redact("passcode 998877").contains("998877"))
        assertFalse(MailRedact.redact("Your PIN is 4321").contains("4321"))
        assertFalse(MailRedact.redact("code: 12345678").contains("12345678"))
    }

    @Test
    fun threeDigitNumberIsNotACode() {
        // The pattern wants 4–8 digits: a 3-digit number
        // after a cue word is not a one-time code.
        assertEquals(
            "Your code is 123",
            MailRedact.redact("Your code is 123")
        )
    }

    @Test
    fun cueWordWithNoDigitsIsUntouched() {
        assertEquals(
            "Enter the code from your authenticator app",
            MailRedact.redact("Enter the code from your authenticator app")
        )
    }

    // ---------- cards ----------

    @Test
    fun cardLikeRunIsRedacted() {
        val out = MailRedact.redact("Card 4111 1111 1111 1111 was charged")
        assertTrue(out.contains("[REDACTED-CARD]"))
        assertFalse(out.contains("4111"))
        val dashed = MailRedact.redact("4111-1111-1111-1111")
        assertEquals("[REDACTED-CARD]", dashed)
    }

    @Test
    fun twoDigitGroupsAreNotACard() {
        assertEquals(
            "Order 4111 1111 confirmed",
            MailRedact.redact("Order 4111 1111 confirmed")
        )
    }

    // ---------- email ----------

    @Test
    fun emailAddressIsRedacted() {
        val out = MailRedact.redact("Write to john.doe+tag@example.co.uk today")
        assertEquals("Write to [REDACTED-EMAIL] today", out)
    }

    @Test
    fun atSignWithoutDomainIsNotAnEmail() {
        assertEquals(
            "Meet @ noon, bring the @ sign poster",
            MailRedact.redact("Meet @ noon, bring the @ sign poster")
        )
    }

    // ---------- phones ----------

    @Test
    fun phoneLikeRunIsRedacted() {
        // The missing space after the marker is the
        // PYTHON's own behaviour (verified against
        // redact.py): the pattern's per-digit optional
        // separator consumes the one trailing space. The
        // port keeps parity rather than prettifying.
        val out = MailRedact.redact("Call 4155550132 for details")
        assertEquals("Call [REDACTED-PHONE]for details", out)
        val intl = MailRedact.redact("Call +1 415 555 0132 for details")
        assertTrue(intl.contains("[REDACTED-PHONE]"))
        assertFalse(intl.contains("415"))
    }

    @Test
    fun shortDigitRunIsNotAPhone() {
        assertEquals(
            "Call 12345 for details",
            MailRedact.redact("Call 12345 for details")
        )
    }

    // ---------- key=value secrets ----------

    @Test
    fun keyValueSecretsAreRedacted() {
        assertEquals(
            "api_key=[REDACTED-SECRET]",
            MailRedact.redact("api_key: abc123xyz")
        )
        assertEquals(
            "token=[REDACTED-SECRET] done",
            MailRedact.redact("token=hunter2 done")
        )
        assertEquals(
            "password=[REDACTED-SECRET]",
            MailRedact.redact("password: correcthorsebattery")
        )
        assertFalse(MailRedact.redact("secret: s3cr3t-value").contains("s3cr3t"))
    }

    @Test
    fun keyWordWithoutAssignmentIsUntouched() {
        // No ':' or '=' — the word alone is not a secret.
        assertEquals(
            "Your token is safe with us",
            MailRedact.redact("Your token is safe with us")
        )
        assertEquals(
            "The password field is below",
            MailRedact.redact("The password field is below")
        )
    }

    // ---------- sk- keys ----------

    @Test
    fun skKeyIsRedacted() {
        val out = MailRedact.redact("key sk-abcdefgh12345678 end")
        assertEquals("key [REDACTED-SECRET] end", out)
    }

    @Test
    fun shortSkPrefixIsNotAKey() {
        assertEquals(
            "the sk-short label",
            MailRedact.redact("the sk-short label")
        )
    }

    // ---------- 11-digit ID runs ----------

    @Test
    fun elevenDigitRunNeverSurvives() {
        // The ordering trap, pinned honestly: the phone
        // pattern runs BEFORE the ID pattern and its
        // {10,15} shape claims a bare 11-digit run first,
        // exactly as in the Python. The guarantee that
        // matters — the digits appear nowhere — holds.
        val out = MailRedact.redact("NIN 12345678901 on file")
        assertFalse(out.contains("12345678901"))
        // (Trailing space consumed by the phone pattern —
        // the Python's behaviour too; see phoneLikeRun.)
        assertEquals("NIN [REDACTED-PHONE]on file", out)
    }

    // ---------- the whole-text pins ----------

    @Test
    fun normalSentenceIsUntouched() {
        val sentence = "The meeting is at noon tomorrow and lunch is free."
        assertEquals(sentence, MailRedact.redact(sentence))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", MailRedact.redact(""))
        assertEquals("", MailRedact.snippet(""))
    }

    @Test
    fun verificationMailCodeAppearsNowhere() {
        // THE PIN: a realistic verification mail, redacted —
        // the digits 482913 appear NOWHERE in the output,
        // in the full redaction or in the snippet shape the
        // model actually receives.
        val mail = "Hello,\n\nYour verification code is 482913. " +
            "It expires in 10 minutes. If you did not request " +
            "this, ignore this message.\n\n— The Team"
        val redacted = MailRedact.redact(mail)
        assertFalse(redacted.contains("482913"))
        assertTrue(redacted.contains("[REDACTED-CODE]"))
        val snip = MailRedact.snippet(mail)
        assertFalse(snip.contains("482913"))
        assertTrue(snip.contains("[REDACTED-CODE]"))
    }

    @Test
    fun snippetCapsAtLimitAfterRedaction() {
        val text = "Your verification code is 482913 " + "and more ".repeat(100)
        val snip = MailRedact.snippet(text, 500)
        assertEquals(500, snip.length)
        assertFalse(snip.contains("482913"))
        // The cap counts the REDACTED text: snippet is
        // exactly redact(text) truncated, never a truncation
        // of the raw text that redaction then missed.
        assertEquals(MailRedact.redact(text).take(500), snip)
        assertEquals(MailRedact.redact(text).take(20), MailRedact.snippet(text, 20))
        // Default limit is 500.
        assertEquals(500, MailRedact.snippet(text).length)
    }
}
