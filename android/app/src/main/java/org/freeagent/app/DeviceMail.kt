package org.freeagent.app

import org.json.JSONObject
import java.util.Base64

/**
 * GMAIL, THE PURE LAYER — the on-device counterpart of the
 * Python core's free_agent/mailtools.py Gmail handlers.
 *
 * Why this exists: given a job whose first step was "read
 * the invite from my Gmail", Lille must reach the mailbox
 * HERSELF (Ethuram's law: she handles tasks end to end;
 * gaps are closed by building, not by asking him to fetch
 * things). This file is the deterministic, testable half of
 * that: parsing Gmail API message resources, formatting the
 * observations the executor returns, extracting body text
 * and links, and the send gate. The Android half (OAuth on
 * the phone, the HTTP calls) implements [MailGateway]
 * against these shapes; it is deliberately not here — this
 * layer is pure Kotlin + org.json + java.util, with no
 * Android imports, so every security rule below is pinned
 * by JVM tests (DeviceMailTest).
 *
 * Security shape (mailtools.py's module docstring — the
 * same law, on the device):
 *
 * - The OAuth token never enters model context, a trace, a
 *   log, or this layer at all: a [MailGateway] holds it
 *   behind the interface, and nothing here ever sees it.
 * - EVERYTHING a tool returns passes [MailRedact] first.
 *   Headers are attacker-controlled text, so [GmailFormat]
 *   redacts From/Subject/Date/snippet before they land in a
 *   [MailHead]. Mail bodies reach the model ONLY as
 *   [MailRedact.snippet]s (≤ 500 chars, redacted).
 * - Links are extracted from the ALREADY-REDACTED body,
 *   with query and fragment stripped and credential-cue
 *   URLs (reset / token / verify / password / confirm /
 *   unsubscribe / code= / auth= / key=) dropped — which is
 *   how a plain invite link inside a long mail can still
 *   reach the task without a reset link riding along.
 * - HTML-only mail yields NO body text. Tags are never
 *   stripped and passed off as the message; [GmailFormat]
 *   says honestly that there is no plain-text body.
 * - Sending is gated THREE times by [MailSendGate]: the
 *   Settings flag (default OFF), the connected token
 *   actually carrying the send scope (the connection starts
 *   read-only), and a per-send approval. No path opens a
 *   gate silently; the refusal names the FIRST closed gate.
 * - Not connected is an honest state, not a failure and
 *   never a workaround: [NOT_CONNECTED] names the ONE setup
 *   step only the user can take, and Lille must report that
 *   step — never ask the user to fetch, copy or paste
 *   message contents instead.
 */

/**
 * The device-side not-connected observation, mirroring the
 * core's GMAIL_NOT_CONNECTED honesty (mailtools.py), with
 * the setup step translated to the phone: connect lives in
 * Settings here, not in a host command.
 */
const val NOT_CONNECTED: String =
    "GMAIL_NOT_CONNECTED: Gmail is not connected on this phone yet, " +
        "so Lille cannot read the mailbox herself. The one step: open " +
        "Settings → Gmail → Connect and approve the Google consent " +
        "(read-only). Do NOT ask the user to fetch, copy or paste " +
        "message contents."

/** One message's metadata, already redacted — the only
 *  shape of a mail header block the model ever sees. */
data class MailHead(
    val id: String,
    val from: String,
    val subject: String,
    val snippet: String,
    val date: String
)

/**
 * The mailbox itself, behind an interface so this layer
 * stays pure. Implementations hold the OAuth token and the
 * Android/network machinery; they return [MailHead]s (via
 * [GmailFormat.parseHead]) and OBSERVATION strings in the
 * executor's shapes: success starts with "GMAIL_" (or the
 * plain "wrote"-style wording of the calling tool) and
 * failures start with "error:". [read] returns
 * [GmailFormat.formatRead]'s output.
 */
interface MailGateway {
    fun list(max: Int): List<MailHead>
    fun search(query: String, max: Int): List<MailHead>
    fun read(id: String): String
    fun send(to: String, subject: String, body: String): String
}

/** Pure formatting / extraction over Gmail API resources. */
object GmailFormat {

    /** The not-connected observation, also reachable here
     *  for callers that only import this object. */
    const val NOT_CONNECTED: String =
        "GMAIL_NOT_CONNECTED: Gmail is not connected on this phone yet, " +
            "so Lille cannot read the mailbox herself. The one step: open " +
            "Settings → Gmail → Connect and approve the Google consent " +
            "(read-only). Do NOT ask the user to fetch, copy or paste " +
            "message contents."

    /**
     * Parse one Gmail API message resource into a [MailHead]:
     * `id`, the `payload.headers[]` entries named From /
     * Subject / Date (header names matched case-insensitively,
     * as the core's _headers lowercases them), and the
     * top-level `snippet` field. EVERY field passes
     * [MailRedact.redact] before it lands in the head —
     * headers are attacker-controlled text and a subject line
     * can carry a code as surely as a body can. Missing
     * pieces come out as empty strings, never invented.
     */
    fun parseHead(message: JSONObject): MailHead {
        val headers = mutableMapOf<String, String>()
        val payload = message.optJSONObject("payload")
        val headerArr = payload?.optJSONArray("headers")
        if (headerArr != null) {
            for (i in 0 until headerArr.length()) {
                val h = headerArr.optJSONObject(i) ?: continue
                headers[h.optString("name").lowercase()] =
                    h.optString("value")
            }
        }
        return MailHead(
            id = MailRedact.redact(message.optString("id")),
            from = MailRedact.redact(headers["from"] ?: ""),
            subject = MailRedact.redact(headers["subject"] ?: ""),
            snippet = MailRedact.redact(message.optString("snippet")),
            date = MailRedact.redact(headers["date"] ?: "")
        )
    }

    /**
     * The list observation, mirroring mailtools.py's search
     * formatting: a "GMAIL_LIST n=<count>" header, then per
     * head the from / subject / date / snippet lines. The
     * heads are already redacted (see [parseHead]); nothing
     * is added here that has not passed redaction.
     */
    fun formatList(heads: List<MailHead>): String {
        val lines = mutableListOf("GMAIL_LIST n=${heads.size}")
        for (head in heads) {
            lines.add("  from: ${head.from}")
            lines.add("  subject: ${head.subject}")
            lines.add("  date: ${head.date}")
            lines.add("  snippet: ${head.snippet}")
        }
        return lines.joinToString("\n")
    }

    /**
     * The decoded text/plain body of a Gmail message, or ""
     * when there is none. Walks `payload` and, recursively,
     * `payload.parts`, returning the FIRST text/plain part
     * carrying `body.data` — the core's _plain_body walk.
     * `body.data` is base64url; decoding tolerates the
     * missing padding Gmail omits. An undecodable part
     * yields "", as in the Python.
     *
     * HTML-only mail returns "" on purpose: the caller says
     * so honestly ([formatRead]'s no-plain-text line). Tags
     * are never stripped and passed off as text.
     */
    fun extractBodyText(message: JSONObject): String {
        val payload = message.optJSONObject("payload") ?: return ""
        return walkPart(payload)
    }

    private fun walkPart(part: JSONObject): String {
        val mime = part.optString("mimeType")
        val body = part.optJSONObject("body")
        val data = body?.optString("data") ?: ""
        if (mime == "text/plain" && data.isNotEmpty()) {
            return try {
                // Gmail omits base64 padding; restore it
                // (the Python's "=" * (-len(data) % 4)).
                val padded = data + "=".repeat((4 - data.length % 4) % 4)
                String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        }
        val parts = part.optJSONArray("parts") ?: return ""
        for (i in 0 until parts.length()) {
            val sub = parts.optJSONObject(i) ?: continue
            val text = walkPart(sub)
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private val LINK_RE = Regex("""https?://[^\s<>"')\]]+""")

    /** Credential cues: a URL whose lowered form contains
     *  any of these is dropped, never handed on (the task
     *  spec's list, mirroring mailtools._LINK_DROP_CUES). */
    private val LINK_DROP_CUES = listOf(
        "reset", "token", "verify", "password", "confirm",
        "unsubscribe", "code=", "auth=", "key="
    )

    /**
     * Links from ALREADY-REDACTED text (mailtools.py's
     * extract_links): http(s) URLs with trailing sentence
     * punctuation trimmed, credential-cue URLs dropped —
     * the cue check runs on the FULL URL, before stripping,
     * so a `?code=` in the query still drops the link — then
     * query and fragment stripped, deduplicated, capped at
     * [limit]. A plain invite link survives; a
     * password-reset or verification link never rides along.
     */
    fun extractLinks(redactedText: String, limit: Int = 10): List<String> {
        val out = mutableListOf<String>()
        for (m in LINK_RE.findAll(redactedText)) {
            val url = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            val low = url.lowercase()
            if (LINK_DROP_CUES.any { it in low }) continue
            val clean = url.split("?")[0].split("#")[0]
            if (clean.isNotEmpty() && clean !in out) out.add(clean)
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * The read observation for one message: the redacted
     * headers, then the body in the ONLY shape a mail body
     * reaches the model — a ≤ 500-char redacted snippet —
     * then any links extracted from the redacted body. When
     * the message has no plain-text body, the body line says
     * exactly that; content is never invented and HTML is
     * never dressed up as text.
     */
    fun formatRead(message: JSONObject): String {
        val head = parseHead(message)
        val lines = mutableListOf(
            "GMAIL_READ id=${head.id}",
            "from: ${head.from}",
            "subject: ${head.subject}",
            "date: ${head.date}"
        )
        val body = extractBodyText(message)
        if (body.isEmpty()) {
            lines.add("body: (no plain-text body in this message)")
            return lines.joinToString("\n")
        }
        lines.add(
            "body (redacted snippet, the only shape of mail " +
                "body the model sees):"
        )
        lines.add(MailRedact.snippet(body, 500))
        val links = extractLinks(MailRedact.redact(body))
        if (links.isNotEmpty()) {
            lines.add(
                "links (from the redacted body; credential links dropped):"
            )
            for (u in links) lines.add("- $u")
        }
        return lines.joinToString("\n")
    }
}

/**
 * THE SEND TRIPLE GATE, pure (mailtools.py's write-gate
 * discipline, on the device): a send happens only when ALL
 * THREE gates are open —
 *
 * 1. the Settings flag (default OFF; enabling it is the
 *    user's decision in Settings, never the model's),
 * 2. the connected token actually carrying the send scope
 *    (the Gmail connection is granted READ-ONLY access by
 *    default; the write scope is a separate, explicit
 *    grant), and
 * 3. a per-send approval for THIS send.
 *
 * No path opens a gate silently. [refusal] returns null
 * when all three are open, otherwise the "error: SENTINEL —"
 * line naming the FIRST closed gate, in the order above —
 * so the user is always told the earliest thing that is
 * actually blocking, never a later one.
 */
object MailSendGate {

    fun refusal(
        flagEnabled: Boolean,
        scopeGranted: Boolean,
        approvalGranted: Boolean
    ): String? = when {
        !flagEnabled ->
            "error: SENTINEL — sending is off — it is enabled " +
                "only in Settings, and it is off"
        !scopeGranted ->
            "error: SENTINEL — the Gmail connection was granted " +
                "read-only access"
        !approvalGranted ->
            "error: SENTINEL — this send has not been approved"
        else -> null
    }
}
