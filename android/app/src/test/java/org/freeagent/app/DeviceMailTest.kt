package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The Gmail pure layer: heads parsed from Gmail API
 * message resources (headers redacted — they are
 * attacker-controlled), bodies decoded from base64url
 * text/plain parts only, links extracted from the redacted
 * body with credential links dropped, the send triple gate
 * in all 8 combinations, and the honest not-connected and
 * no-plain-text-body states.
 */
class DeviceMailTest {

    // ---------- fixtures ----------

    private fun header(name: String, value: String): JSONObject =
        JSONObject().put("name", name).put("value", value)

    private fun b64url(text: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(text.toByteArray(Charsets.UTF_8))

    /** A metadata-shaped message. Header names are
     *  lowercase on purpose: name matching must be
     *  case-insensitive. The subject carries a code and the
     *  From an email address — both must be redacted. */
    private fun fixtureHeadMessage(): JSONObject {
        val headers = JSONArray()
            .put(header("from", "Alice Organiser <alice@example.com>"))
            .put(header("subject", "Your verification code is 482913 — welcome"))
            .put(header("date", "Fri, 27 Nov 2026 08:15:00 +0100"))
        val payload = JSONObject()
            .put("mimeType", "text/html")
            .put("headers", headers)
        return JSONObject()
            .put("id", "msg-1")
            .put("snippet", "Your verification code is 482913 — welcome aboard")
            .put("payload", payload)
    }

    /** A full message whose text/plain part (nested inside
     *  a multipart payload) carries an invite link, a
     *  password-reset link, and a code. */
    private fun fixtureFullMessage(): JSONObject {
        val body = "Hi Ethuram,\n\n" +
            "Your verification code is 482913.\n" +
            "Register here: https://lu.ma/agentware?ref=abc123#top\n" +
            "Reset your password: https://example.com/reset-password?token=xyz789\n" +
            "See you there."
        val headers = JSONArray()
            .put(header("From", "Alice Organiser <alice@example.com>"))
            .put(header("Subject", "Agentware invite"))
            .put(header("Date", "Fri, 27 Nov 2026 08:15:00 +0100"))
        val plainPart = JSONObject()
            .put("mimeType", "text/plain")
            .put("body", JSONObject().put("data", b64url(body)))
        val htmlPart = JSONObject()
            .put("mimeType", "text/html")
            .put("body", JSONObject().put("data", b64url("<p>hi</p>")))
        val payload = JSONObject()
            .put("mimeType", "multipart/alternative")
            .put("headers", headers)
            .put("parts", JSONArray().put(htmlPart).put(plainPart))
        return JSONObject()
            .put("id", "msg-2")
            .put("snippet", "Register here")
            .put("payload", payload)
    }

    /** An HTML-only message: no text/plain part anywhere. */
    private fun fixtureHtmlOnlyMessage(): JSONObject {
        val headers = JSONArray()
            .put(header("From", "Bob <bob@example.com>"))
            .put(header("Subject", "Pretty newsletter"))
            .put(header("Date", "Fri, 27 Nov 2026 09:00:00 +0100"))
        val payload = JSONObject()
            .put("mimeType", "text/html")
            .put("headers", headers)
            .put(
                "body",
                JSONObject().put("data", b64url("<p>Secret sale!</p>"))
            )
        return JSONObject()
            .put("id", "msg-3")
            .put("snippet", "Secret sale")
            .put("payload", payload)
    }

    // ---------- parseHead ----------

    @Test
    fun parseHeadReadsHeadersCaseInsensitively() {
        val head = GmailFormat.parseHead(fixtureHeadMessage())
        assertEquals("msg-1", head.id)
        assertEquals("Fri, 27 Nov 2026 08:15:00 +0100", head.date)
        assertTrue(head.from.startsWith("Alice Organiser"))
        assertTrue(head.subject.contains("welcome"))
    }

    @Test
    fun parseHeadRedactsEveryField() {
        val head = GmailFormat.parseHead(fixtureHeadMessage())
        // A code in the SUBJECT line is stripped, and the
        // sender's email address never survives into a head.
        assertFalse(head.subject.contains("482913"))
        assertTrue(head.subject.contains("[REDACTED-CODE]"))
        assertFalse(head.snippet.contains("482913"))
        assertFalse(head.from.contains("alice@example.com"))
        assertTrue(head.from.contains("[REDACTED-EMAIL]"))
    }

    @Test
    fun parseHeadMissingPiecesAreEmptyNotInvented() {
        val head = GmailFormat.parseHead(JSONObject().put("id", "msg-x"))
        assertEquals("msg-x", head.id)
        assertEquals("", head.from)
        assertEquals("", head.subject)
        assertEquals("", head.snippet)
        assertEquals("", head.date)
    }

    // ---------- formatList ----------

    @Test
    fun formatListMirrorsTheCoreShape() {
        val heads = listOf(
            GmailFormat.parseHead(fixtureHeadMessage()),
            MailHead(
                id = "msg-9", from = "Bob", subject = "Lunch?",
                snippet = "At noon", date = "Fri, 27 Nov 2026 10:00:00 +0100"
            )
        )
        val out = GmailFormat.formatList(heads)
        val lines = out.split("\n")
        assertEquals("GMAIL_LIST n=2", lines[0])
        assertEquals(1 + 2 * 4, lines.size)
        assertTrue(lines[1].startsWith("  from: "))
        assertTrue(lines[2].startsWith("  subject: "))
        assertTrue(lines[3].startsWith("  date: "))
        assertTrue(lines[4].startsWith("  snippet: "))
        // The listed head is the redacted one.
        assertFalse(out.contains("482913"))
        assertEquals("GMAIL_LIST n=0", GmailFormat.formatList(emptyList()))
    }

    // ---------- body extraction ----------

    @Test
    fun extractBodyTextDecodesNestedPlainPart() {
        val body = GmailFormat.extractBodyText(fixtureFullMessage())
        assertTrue(body.startsWith("Hi Ethuram,"))
        assertTrue(body.contains("See you there."))
    }

    @Test
    fun extractBodyTextToleratesMissingPadding() {
        // Lengths that need one and two padding chars.
        for (raw in listOf("ab", "abc", "abcd", "hello world, plain mail")) {
            val part = JSONObject()
                .put("mimeType", "text/plain")
                .put("body", JSONObject().put("data", b64url(raw)))
            val msg = JSONObject().put(
                "payload",
                JSONObject().put("mimeType", "text/plain")
                    .put("body", JSONObject().put("data", b64url(raw)))
            )
            assertEquals(raw, GmailFormat.extractBodyText(msg))
            assertEquals(
                raw,
                GmailFormat.extractBodyText(JSONObject().put("payload", part))
            )
        }
    }

    @Test
    fun htmlOnlyMessageHasNoBodyText() {
        assertEquals("", GmailFormat.extractBodyText(fixtureHtmlOnlyMessage()))
        assertEquals("", GmailFormat.extractBodyText(JSONObject()))
    }

    // ---------- links ----------

    @Test
    fun inviteLinkSurvivesWithQueryAndFragmentStripped() {
        val links = GmailFormat.extractLinks(
            "Register: https://lu.ma/agentware?ref=abc123#top please"
        )
        assertEquals(listOf("https://lu.ma/agentware"), links)
    }

    @Test
    fun credentialCueLinksAreDropped() {
        val text = listOf(
            "https://example.com/reset-password?token=xyz",
            "https://example.com/verify?code=1234",
            "https://example.com/confirm-signup",
            "https://example.com/unsubscribe?id=9",
            "https://example.com/login?auth=abc",
            "https://example.com/dl?key=secret",
            "https://example.com/plain-page"
        ).joinToString(" ")
        assertEquals(
            listOf("https://example.com/plain-page"),
            GmailFormat.extractLinks(text)
        )
    }

    @Test
    fun linksDedupeAndCap() {
        val text = (1..15).joinToString(" ") { "https://example.com/p$it" } +
            " https://example.com/p1?again=1"
        val links = GmailFormat.extractLinks(text)
        assertEquals(10, links.size)
        assertEquals(links.size, links.toSet().size)
        assertEquals(3, GmailFormat.extractLinks(text, limit = 3).size)
    }

    // ---------- formatRead ----------

    @Test
    fun formatReadShowsRedactedSnippetAndSafeLinksOnly() {
        val out = GmailFormat.formatRead(fixtureFullMessage())
        val lines = out.split("\n")
        assertEquals("GMAIL_READ id=msg-2", lines[0])
        assertEquals("from: Alice Organiser <[REDACTED-EMAIL]>", lines[1])
        assertEquals("subject: Agentware invite", lines[2])
        assertTrue(lines[3].startsWith("date: Fri, 27 Nov 2026"))
        assertTrue(
            out.contains(
                "body (redacted snippet, the only shape of mail " +
                    "body the model sees):"
            )
        )
        // The body's code is redacted in the snippet…
        assertFalse(out.contains("482913"))
        assertTrue(out.contains("[REDACTED-CODE]"))
        // …the invite link survives, query stripped…
        assertTrue(
            out.contains(
                "links (from the redacted body; credential links dropped):"
            )
        )
        assertTrue(out.contains("- https://lu.ma/agentware"))
        // The query/fragment stripping and the credential
        // drop govern the extracted LINKS list (the body
        // snippet is the redacted body text itself, where a
        // URL still reads as written — the core's gmail_read
        // has the same two shapes). So the pins are scoped
        // to the links section: exactly one link, the invite,
        // stripped; no reset link, no query, no token.
        val linksSection = out.substringAfter(
            "links (from the redacted body; credential links dropped):\n"
        )
        assertEquals("- https://lu.ma/agentware", linksSection)
        assertFalse(linksSection.contains("ref=abc123"))
        assertFalse(linksSection.contains("reset-password"))
        assertFalse(linksSection.contains("xyz789"))
    }

    @Test
    fun formatReadCapsTheBodySnippetAt500() {
        val longBody = "News. ".repeat(200)
        val payload = JSONObject()
            .put("mimeType", "text/plain")
            .put("headers", JSONArray())
            .put("body", JSONObject().put("data", b64url(longBody)))
        val msg = JSONObject().put("id", "msg-long").put("payload", payload)
        val out = GmailFormat.formatRead(msg)
        val snippetLine = out.split("\n").last()
        assertEquals(500, snippetLine.length)
    }

    @Test
    fun formatReadHtmlOnlySaysSoHonestly() {
        val out = GmailFormat.formatRead(fixtureHtmlOnlyMessage())
        assertTrue(out.contains("body: (no plain-text body in this message)"))
        // The HTML content is never dressed up as text.
        assertFalse(out.contains("Secret sale!"))
        assertFalse(out.contains("<p>"))
    }

    // ---------- the send triple gate ----------

    @Test
    fun sendGateOpensOnlyWhenAllThreeGatesAreOpen() {
        assertNull(MailSendGate.refusal(true, true, true))
    }

    @Test
    fun sendGateNamesTheFirstClosedGateInAllCombinations() {
        // All 8 combinations: the refusal must name the
        // FIRST closed gate in flag → scope → approval
        // order, and only the all-open row may pass.
        for (flag in listOf(false, true)) {
            for (scope in listOf(false, true)) {
                for (approval in listOf(false, true)) {
                    val refusal = MailSendGate.refusal(flag, scope, approval)
                    if (flag && scope && approval) {
                        assertNull(refusal)
                        continue
                    }
                    assertTrue(
                        "row flag=$flag scope=$scope approval=$approval",
                        refusal!!.startsWith("error: SENTINEL — ")
                    )
                    when {
                        !flag -> assertTrue(
                            refusal.contains(
                                "sending is off — it is enabled only " +
                                    "in Settings, and it is off"
                            )
                        )
                        !scope -> assertTrue(
                            refusal.contains(
                                "the Gmail connection was granted " +
                                    "read-only access"
                            )
                        )
                        else -> assertTrue(
                            refusal.contains("this send has not been approved")
                        )
                    }
                }
            }
        }
    }

    // ---------- not connected ----------

    @Test
    fun notConnectedIsHonestAndNamesTheOneStep() {
        assertTrue(NOT_CONNECTED.startsWith("GMAIL_NOT_CONNECTED:"))
        assertTrue(NOT_CONNECTED.contains("Settings → Gmail → Connect"))
        assertTrue(NOT_CONNECTED.contains("(read-only)"))
        assertTrue(
            NOT_CONNECTED.contains(
                "Do NOT ask the user to fetch, copy or paste message contents"
            )
        )
        assertEquals(NOT_CONNECTED, GmailFormat.NOT_CONNECTED)
    }
}
