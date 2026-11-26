package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device search fallback chain (v0.10) — the response
 * parsers for routes 2 and 3 of the core search chain
 * (free_agent/routes.py), pure and JVM-pinned like BrainPacing
 * and FluidGate. TaskRunner.webSearch does the HTTP and walks
 * the chain in the core's order:
 *
 *   1. duckduckgo-instant — Instant Answers API (keyless)
 *   2. wikipedia          — Wikipedia OpenSearch API (keyless)
 *   3. openlibrary        — Open Library search API (keyless)
 *
 * DDG Instant answers many queries with NOTHING (it is an
 * instant-answers API, not a web index), so a no-answer there
 * is a normal chain step, not a failure. Each parser returns
 * null for "no usable result" so the chain falls through, and
 * the tool result names the route that served — the model
 * (and anyone reading the trace) can see where an answer
 * came from.
 */
object SearchChain {

    /** Wikipedia OpenSearch: [query, [titles], [descriptions], [urls]]. */
    fun parseWikipediaOpenSearch(json: String, maxResults: Int = 3): String? {
        val root = try {
            JSONArray(json)
        } catch (e: Exception) {
            return null
        }
        if (root.length() < 4) return null
        val titles = root.optJSONArray(1) ?: return null
        val descriptions = root.optJSONArray(2)
        val urls = root.optJSONArray(3)
        if (titles.length() == 0) return null
        val lines = mutableListOf<String>()
        for (i in 0 until minOf(titles.length(), maxResults)) {
            val title = titles.optString(i, "").trim()
            if (title.isEmpty()) continue
            val desc = descriptions?.optString(i, "")?.trim().orEmpty()
            val url = urls?.optString(i, "")?.trim().orEmpty()
            lines.add(
                buildString {
                    append(title)
                    if (desc.isNotEmpty()) append(" — ").append(desc)
                    if (url.isNotEmpty()) append(" (").append(url).append(")")
                }
            )
        }
        return if (lines.isEmpty()) null else lines.joinToString("\n").take(1500)
    }

    /** Open Library search: {"docs":[{"title":…,"author_name":[…],
     *  "first_publish_year":…}]}. Books-shaped, so it sits last
     *  in the chain, exactly as in the core's table. */
    fun parseOpenLibrary(json: String, maxResults: Int = 3): String? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        val docs = root.optJSONArray("docs") ?: return null
        if (docs.length() == 0) return null
        val lines = mutableListOf<String>()
        for (i in 0 until minOf(docs.length(), maxResults)) {
            val doc = docs.optJSONObject(i) ?: continue
            val title = doc.optString("title", "").trim()
            if (title.isEmpty()) continue
            val authors = doc.optJSONArray("author_name")
            val authorText = if (authors != null && authors.length() > 0) {
                (0 until authors.length()).joinToString(", ") { authors.optString(it) }
            } else {
                ""
            }
            val year = if (doc.has("first_publish_year")) {
                doc.optInt("first_publish_year").toString()
            } else {
                ""
            }
            lines.add(
                buildString {
                    append(title)
                    if (authorText.isNotEmpty()) append(" by ").append(authorText)
                    if (year.isNotEmpty()) append(" (").append(year).append(")")
                }
            )
        }
        return if (lines.isEmpty()) null else lines.joinToString("\n").take(1500)
    }
}
