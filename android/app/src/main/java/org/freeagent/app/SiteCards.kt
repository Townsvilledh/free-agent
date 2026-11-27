package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * PER-SITE CARDS — WHAT SHE LEARNED ABOUT A SITE, KEPT.
 *
 * The pattern this reimplements (after DroidRun/AppAgent-
 * style per-app knowledge) is a small card per domain that
 * accumulates across runs: the key screens the site has,
 * the elements that mattered, and the dead-ends that wasted
 * steps. The next run on that domain is handed the card up
 * front ([renderFor]) so she starts from what earlier runs
 * paid to learn instead of rediscovering the same walls.
 *
 * The discipline is in the caps and the framing:
 *
 * - BOUNDED: each list on a card holds at most [LIST_CAP]
 *   entries, deduplicated, most-recent-last — when a list
 *   overflows, the oldest entry falls off, because a card
 *   is a field guide to the site as it is now, not a log.
 * - SHORT: entries are trimmed and cut at [ENTRY_MAX]
 *   characters; a card entry is a pointer ("checkout button
 *   is below the fold"), never a page dump.
 * - HONEST FRAMING: [renderFor] labels the notes as learned
 *   from earlier runs — "a guide, not gospel" — because
 *   sites change and a stale card followed blindly is worse
 *   than no card.
 *
 * Pure Kotlin + org.json; persists one JSON document
 * through [TextIO]; pinned by SiteCardsTest.
 */
class SiteCards(private val io: TextIO) {

    companion object {
        /** Entries kept per list on a card. */
        const val LIST_CAP = 12

        /** Characters kept per entry. */
        const val ENTRY_MAX = 120
    }

    // ---------- persistence ----------

    private fun load(): JSONObject {
        val raw = try {
            io.read()
        } catch (e: Exception) {
            null
        }
        if (raw.isNullOrBlank()) return JSONObject().put("cards", JSONArray())
        return try {
            val doc = JSONObject(raw)
            if (!doc.has("cards")) doc.put("cards", JSONArray())
            doc
        } catch (e: Exception) {
            JSONObject().put("cards", JSONArray())
        }
    }

    private fun save(doc: JSONObject) {
        io.write(doc.toString())
    }

    private fun cardsArray(doc: JSONObject): JSONArray =
        doc.optJSONArray("cards") ?: JSONArray().also { doc.put("cards", it) }

    private fun normalise(domain: String): String = domain.trim().lowercase()

    private fun findCard(doc: JSONObject, domain: String): JSONObject? {
        val wanted = normalise(domain)
        val arr = cardsArray(doc)
        for (i in 0 until arr.length()) {
            val card = arr.optJSONObject(i) ?: continue
            if (card.optString("domain") == wanted) return card
        }
        return null
    }

    private fun listOf(card: JSONObject, field: String): JSONArray {
        val arr = card.optJSONArray(field)
        if (arr != null) return arr
        val fresh = JSONArray()
        card.put(field, fresh)
        return fresh
    }

    /** Append [raw] to [card]'s [field] list when it is
     *  non-blank and not already present; the entry is
     *  trimmed and cut at [ENTRY_MAX], and the list is
     *  capped at [LIST_CAP] with the oldest dropped. */
    private fun growList(card: JSONObject, field: String, raw: String?) {
        if (raw == null) return
        val entry = raw.trim().take(ENTRY_MAX)
        if (entry.isEmpty()) return
        val arr = listOf(card, field)
        for (i in 0 until arr.length()) {
            if (arr.optString(i) == entry) return
        }
        arr.put(entry)
        while (arr.length() > LIST_CAP) arr.remove(0)
    }

    // ---------- reads ----------

    /** The card for [domain], or null when no run has
     *  learned anything about it yet. */
    fun cardFor(domain: String): JSONObject? = findCard(load(), domain)

    // ---------- growth ----------

    /**
     * Grow [domain]'s card from one run's findings: the
     * card is created if absent, each non-null finding is
     * appended to its list (deduplicated, capped), and the
     * card's updatedAt moves to [at].
     */
    fun grow(
        domain: String,
        at: Long,
        keyScreen: String? = null,
        element: String? = null,
        deadEnd: String? = null
    ) {
        val doc = load()
        val existing = findCard(doc, domain)
        val card: JSONObject = if (existing != null) {
            existing
        } else {
            val created = JSONObject()
                .put("domain", normalise(domain))
                .put("keyScreens", JSONArray())
                .put("elements", JSONArray())
                .put("deadEnds", JSONArray())
                .put("updatedAt", at)
            cardsArray(doc).put(created)
            created
        }
        growList(card, "keyScreens", keyScreen)
        growList(card, "elements", element)
        growList(card, "deadEnds", deadEnd)
        card.put("updatedAt", at)
        save(doc)
    }

    // ---------- rendering ----------

    private fun joinList(card: JSONObject, field: String): String {
        val arr = card.optJSONArray(field) ?: return ""
        val parts = mutableListOf<String>()
        for (i in 0 until arr.length()) parts.add(arr.optString(i))
        return parts.joinToString(", ")
    }

    /**
     * The prompt-ready notes for [domains]: one block per
     * domain that has a card, naming only the lists that
     * hold something. Domains without a card contribute
     * nothing, and when no domain has a card the result is
     * the empty string — silence, not a placeholder.
     */
    fun renderFor(domains: List<String>): String {
        val doc = load()
        val blocks = mutableListOf<String>()
        for (domain in domains) {
            val card = findCard(doc, domain) ?: continue
            val out = StringBuilder()
            out.append("Site notes for ")
                .append(card.optString("domain"))
                .append(" (learned from earlier runs — a guide, not gospel):")
            val screens = joinList(card, "keyScreens")
            if (screens.isNotEmpty()) out.append("\nScreens: ").append(screens)
            val elements = joinList(card, "elements")
            if (elements.isNotEmpty()) out.append("\nElements: ").append(elements)
            val deadEnds = joinList(card, "deadEnds")
            if (deadEnds.isNotEmpty()) out.append("\nKnown dead-ends: ").append(deadEnds)
            blocks.add(out.toString())
        }
        return blocks.joinToString("\n")
    }
}
