package org.freeagent.app

import org.json.JSONObject

/**
 * Reply-to-message (v1.0.1, Ethuram 2026-11-26): a reply
 * carries the message it answers — visibly (a quoted block on
 * the sent bubble), in the store (quoteRole/quoteText on the
 * message record, so it survives restart and rides the
 * backup), and in what the model receives (the quoted content
 * marked as the referent, so "fix this" / "this one" resolves
 * to the quoted message instead of a guess).
 *
 * Pure text/record logic — MainActivity owns the long-press
 * menu, the composer quote bar and the rendering.
 */
object ReplyQuote {

    const val ROLE_FIELD = "quoteRole"
    const val TEXT_FIELD = "quoteText"
    const val REACTION_FIELD = "reaction"

    /** Snippets stay one-glance short. */
    const val SNIPPET_MAX = 240

    fun speakerLabel(role: String): String =
        if (role == "user") "You" else "Lille"

    /**
     * The snippet stored and shown for a quoted message: the
     * message's own display text, whitespace-collapsed and
     * trimmed. A message that was itself a reply contributes
     * its own words, not its quote block (no quote turtles).
     */
    fun snippet(text: String): String {
        var body = text.trim()
        if (body.startsWith("↪ ")) {
            val split = body.indexOf("\n\n")
            if (split >= 0) body = body.substring(split + 2).trim()
        }
        val flat = body.replace(Regex("\\s+"), " ")
        return if (flat.length <= SNIPPET_MAX) flat
        else flat.substring(0, SNIPPET_MAX - 1).trimEnd() + "…"
    }

    /** The visible quoted block prepended to a reply bubble. */
    fun quoteBlock(role: String, quotedText: String): String =
        "↪ ${speakerLabel(role)}: ${snippet(quotedText)}"

    /** What the bubble shows: quoted block, blank line, text. */
    fun displayText(
        quotedRole: String, quotedText: String, ownDisplay: String
    ): String = quoteBlock(quotedRole, quotedText) + "\n\n" + ownDisplay

    /**
     * What the model (and a task born from this reply)
     * receives: the referent marked as context, then the
     * user's own words.
     */
    fun markedContent(
        quotedRole: String, quotedText: String, ownContent: String
    ): String =
        "[Replying to ${speakerLabel(quotedRole)}: " +
            "\"${snippet(quotedText)}\"]\n\n" + ownContent

    /** Store the quote on a message record being built. */
    fun attach(
        msg: JSONObject, quotedRole: String, quotedText: String
    ): JSONObject {
        msg.put(ROLE_FIELD, quotedRole)
        msg.put(TEXT_FIELD, snippet(quotedText))
        return msg
    }

    fun quotedRole(msg: JSONObject): String? =
        msg.optString(ROLE_FIELD, "").ifEmpty { null }

    fun quotedText(msg: JSONObject): String? =
        msg.optString(TEXT_FIELD, "").ifEmpty { null }

    /** The stored reaction on a message, if any. */
    fun reaction(msg: JSONObject): String? =
        msg.optString(REACTION_FIELD, "").ifEmpty { null }
}
