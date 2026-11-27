package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * SHARED PERSISTENCE SHAPE FOR THE MEMORY COMPONENTS.
 *
 * AgentMemory, SkillStore and SiteCards each persist ONE
 * JSON document, and none of them knows where that document
 * lives. [TextIO] is the whole seam: on the phone it is
 * backed by a file in the app's private storage; in tests
 * it is an in-memory string. Keeping the seam this small is
 * deliberate — the stores stay pure Kotlin + org.json, with
 * no android imports, so every law below is pinned by plain
 * JVM tests and the Android shell only supplies the bytes.
 */
interface TextIO {
    fun read(): String?
    fun write(text: String)
}

/**
 * TIERED MEMORY — THE CORE BLOCKS AND THEIR CONSOLIDATION.
 *
 * The pattern this reimplements (after Letta/MemGPT-style
 * tiered memory and Mem0-style consolidation) is two-tier:
 *
 * - CORE — a small, always-visible set of blocks that are
 *   rendered into the prompt at session start. Exactly
 *   three blocks exist: "user" (facts about the person),
 *   "persona" (how she should be) and "current_goals" (what
 *   is live right now). Each block is capped at
 *   [AgentMemory.CAP_CHARS] characters of lines, because a
 *   core that grows without bound stops being a core and
 *   becomes another archive nobody reads.
 * - JOURNAL — an append-only log of everything that was
 *   written to the core and everything she is asked to
 *   journal explicitly. The journal is searched, never
 *   injected whole.
 *
 * Writes to the core never blindly append. [decide] is the
 * consolidation law: a new fact is compared, token by
 * token, against what the block already holds, and is
 * classified ADD (genuinely new), UPDATE (it overlaps an
 * existing line strongly but says more or different),
 * DELETE (an explicit "forget …" / "remove …" instruction
 * that matches a line) or NOOP (already known, or a forget
 * that matches nothing). The comparison is deterministic —
 * Jaccard overlap over lowercase alphanumeric tokens minus
 * a small stopword set — so the same fact against the same
 * memory always lands the same way, on every device, with
 * no model call in the loop.
 *
 * Honest limits: token overlap is not understanding. Two
 * lines can mean the same thing with no shared words, and
 * two lines can share words and mean opposite things. This
 * law is a cheap, auditable first pass that keeps the core
 * from filling with near-duplicates; it does not claim to
 * resolve contradictions, and an UPDATE simply replaces
 * the line it overlapped most.
 */
object MemoryConsolidation {

    enum class Op { ADD, UPDATE, DELETE, NOOP }

    /** Words that carry no identity for overlap purposes. */
    val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "to", "of", "in", "on",
        "for", "is", "are", "was", "were", "be", "been", "has",
        "have", "had", "do", "does", "did", "will", "would",
        "can", "could", "should", "may", "might", "must", "i",
        "you", "he", "she", "it", "we", "they", "my", "your",
        "his", "her", "its", "our", "their", "this", "that",
        "these", "those", "with", "at", "by", "from", "as",
        "but", "if", "then", "so"
    )

    private val TOKEN_RE = Regex("[a-z0-9]+")

    /** The token set of [text]: lowercase alphanumeric
     *  words, stopwords removed. */
    fun tokens(text: String): Set<String> =
        TOKEN_RE.findAll(text.lowercase())
            .map { it.value }
            .filter { it !in STOPWORDS }
            .toSet()

    /** Jaccard overlap: |intersection| / |union|, 0 when
     *  either side has no tokens. */
    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0.0 else inter.toDouble() / union.toDouble()
    }

    /**
     * The consolidation decision for [fact] against a
     * block's [existing] lines, as (op, index). The index
     * names the line UPDATE/DELETE/NOOP-subset refers to;
     * ADD and a forget that matches nothing report -1.
     *
     * - "forget …" / "remove …" (case-insensitive, on the
     *   trimmed fact): the remainder is the target; the
     *   best-matching line at overlap >= 0.5 is DELETEd,
     *   otherwise NOOP.
     * - Otherwise the best-matching line at overlap >= 0.6
     *   decides: if the fact's tokens are a subset of that
     *   line's tokens the fact is already known → NOOP;
     *   else it extends or revises that line → UPDATE.
     * - No line reaching 0.6 → ADD.
     */
    fun decide(existing: List<String>, fact: String): Pair<Op, Int> {
        val trimmed = fact.trim()
        val low = trimmed.lowercase()
        val forgetPrefix = when {
            low.startsWith("forget ") -> "forget "
            low.startsWith("remove ") -> "remove "
            else -> null
        }
        if (forgetPrefix != null) {
            val target = trimmed.substring(forgetPrefix.length).trim()
            val targetTokens = tokens(target)
            var bestIdx = -1
            var bestScore = 0.0
            for ((i, line) in existing.withIndex()) {
                val score = jaccard(tokens(line), targetTokens)
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = i
                }
            }
            return if (bestIdx >= 0 && bestScore >= 0.5) {
                Pair(Op.DELETE, bestIdx)
            } else {
                Pair(Op.NOOP, -1)
            }
        }
        val factTokens = tokens(trimmed)
        var bestIdx = -1
        var bestScore = 0.0
        for ((i, line) in existing.withIndex()) {
            val score = jaccard(tokens(line), factTokens)
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
        if (bestIdx >= 0 && bestScore >= 0.6) {
            val lineTokens = tokens(existing[bestIdx])
            return if (lineTokens.containsAll(factTokens)) {
                Pair(Op.NOOP, bestIdx)
            } else {
                Pair(Op.UPDATE, bestIdx)
            }
        }
        return Pair(Op.ADD, -1)
    }
}

/**
 * THE STORE: one JSON document behind [TextIO], shaped
 * {"core":{"user":[...],"persona":[...],
 * "current_goals":[...]},
 *  "journal":[{"at":Long,"kind":String,"text":String}]}
 *
 * Every applied core write also appends a journal entry,
 * so the journal is the audit trail of how the core came
 * to say what it says. The journal itself is capped at
 * [JOURNAL_CAP] entries, oldest dropped — it is a working
 * log, not an archive of record.
 */
class AgentMemory(private val io: TextIO) {

    companion object {
        /** Per-block cap, in characters of lines. */
        const val CAP_CHARS = 1200

        /** Journal entries kept; the oldest fall off. */
        const val JOURNAL_CAP = 500

        /** The only blocks that exist. */
        val BLOCKS = listOf("user", "persona", "current_goals")

        const val SNAPSHOT_HEADER =
            "What Lille remembers (snapshot from the start of " +
                "this session — it does not change mid-session):"

        const val EMPTY_SNAPSHOT_LINE =
            "She has no stored memory yet."
    }

    data class WriteResult(
        val op: MemoryConsolidation.Op,
        val applied: Boolean,
        val note: String
    )

    // ---------- persistence ----------

    private fun load(): JSONObject {
        val raw = try {
            io.read()
        } catch (e: Exception) {
            null
        }
        if (raw.isNullOrBlank()) return freshDoc()
        return try {
            val doc = JSONObject(raw)
            if (!doc.has("core")) doc.put("core", JSONObject())
            if (!doc.has("journal")) doc.put("journal", JSONArray())
            val core = doc.getJSONObject("core")
            for (block in BLOCKS) {
                if (!core.has(block)) core.put(block, JSONArray())
            }
            doc
        } catch (e: Exception) {
            freshDoc()
        }
    }

    private fun freshDoc(): JSONObject {
        val core = JSONObject()
        for (block in BLOCKS) core.put(block, JSONArray())
        return JSONObject()
            .put("core", core)
            .put("journal", JSONArray())
    }

    private fun save(doc: JSONObject) {
        io.write(doc.toString())
    }

    private fun linesOf(doc: JSONObject, block: String): MutableList<String> {
        val out = mutableListOf<String>()
        val arr = doc.getJSONObject("core").optJSONArray(block) ?: return out
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    private fun putLines(doc: JSONObject, block: String, lines: List<String>) {
        val arr = JSONArray()
        lines.forEach { arr.put(it) }
        doc.getJSONObject("core").put(block, arr)
    }

    private fun appendJournal(
        doc: JSONObject, kind: String, text: String, at: Long
    ) {
        val arr = doc.optJSONArray("journal") ?: JSONArray()
        arr.put(
            JSONObject()
                .put("at", at)
                .put("kind", kind)
                .put("text", text)
        )
        while (arr.length() > JOURNAL_CAP) arr.remove(0)
        doc.put("journal", arr)
    }

    // ---------- core ----------

    /** The lines of [block]; an unknown block reads empty —
     *  there are exactly three blocks and inventing a fourth
     *  by typo must not create one. */
    fun coreLines(block: String): List<String> {
        if (block !in BLOCKS) return emptyList()
        return linesOf(load(), block)
    }

    /**
     * Write [fact] into [block] under the consolidation
     * law. ADD is refused when it would push the block over
     * [CAP_CHARS] — the note names the cap so the caller can
     * say plainly why — while UPDATE and DELETE are always
     * allowed (they never grow the block past a line it
     * already held in spirit, and a delete only shrinks it).
     * An unknown block is a NOOP that applies nothing.
     * Every applied write journals itself.
     */
    fun write(block: String, fact: String, at: Long): WriteResult {
        if (block !in BLOCKS) {
            return WriteResult(
                MemoryConsolidation.Op.NOOP, false,
                "unknown block \"$block\" — nothing written"
            )
        }
        val trimmed = fact.trim()
        val doc = load()
        val lines = linesOf(doc, block)
        val (op, index) = MemoryConsolidation.decide(lines, trimmed)
        when (op) {
            MemoryConsolidation.Op.NOOP -> {
                return WriteResult(
                    op, false,
                    "already known or nothing to forget — no change"
                )
            }
            MemoryConsolidation.Op.ADD -> {
                val used = lines.sumOf { it.length }
                if (used + trimmed.length > CAP_CHARS) {
                    return WriteResult(
                        op, false,
                        "block \"$block\" is at $used of $CAP_CHARS " +
                            "characters — adding this fact would " +
                            "exceed the $CAP_CHARS character cap"
                    )
                }
                lines.add(trimmed)
                putLines(doc, block, lines)
                appendJournal(doc, "core:$block", trimmed, at)
                save(doc)
                return WriteResult(op, true, "added to $block")
            }
            MemoryConsolidation.Op.UPDATE -> {
                val safeIndex = index.coerceIn(0, lines.size - 1)
                lines[safeIndex] = trimmed
                putLines(doc, block, lines)
                appendJournal(doc, "core:$block", trimmed, at)
                save(doc)
                return WriteResult(op, true, "updated $block")
            }
            MemoryConsolidation.Op.DELETE -> {
                val safeIndex = index.coerceIn(0, lines.size - 1)
                val removed = lines.removeAt(safeIndex)
                putLines(doc, block, lines)
                appendJournal(doc, "core:$block", removed, at)
                save(doc)
                return WriteResult(op, true, "removed from $block")
            }
        }
    }

    // ---------- journal ----------

    /** Append a journal entry. The journal is capped at
     *  [JOURNAL_CAP] entries; the oldest are dropped. */
    fun journal(kind: String, text: String, at: Long) {
        val doc = load()
        appendJournal(doc, kind, text, at)
        save(doc)
    }

    // ---------- snapshot ----------

    /**
     * The FROZEN core rendering injected at session start.
     * It is a plain String built from the core at call time:
     * an immutable copy by construction, so writes made
     * later in the session cannot leak into a snapshot a
     * session already started with — that stability is the
     * point (the model reasons against one memory all
     * session, and the header says so honestly). An empty
     * memory renders as one fixed line saying so.
     */
    fun snapshot(): String {
        val doc = load()
        val out = StringBuilder()
        out.append(SNAPSHOT_HEADER)
        var any = false
        for (block in BLOCKS) {
            val lines = linesOf(doc, block)
            if (lines.isEmpty()) continue
            any = true
            out.append("\n[").append(block).append("]")
            for (line in lines) {
                out.append("\n- ").append(line)
            }
        }
        if (!any) {
            out.append("\n").append(EMPTY_SNAPSHOT_LINE)
        }
        return out.toString()
    }

    // ---------- search ----------

    /**
     * Token-score search over journal texts and core lines.
     * Score is the count of distinct tokens shared with the
     * query; zero-score results are excluded, ties break
     * most-recent-first (journal entries by their timestamp;
     * core lines carry no timestamp and sort behind journal
     * entries of the same score). At most [limit] texts are
     * returned.
     */
    fun search(query: String, limit: Int = 5): List<String> {
        if (limit <= 0) return emptyList()
        val queryTokens = MemoryConsolidation.tokens(query)
        if (queryTokens.isEmpty()) return emptyList()
        val doc = load()
        data class Hit(val text: String, val score: Int, val at: Long, val seq: Int)
        val hits = mutableListOf<Hit>()
        var seq = 0
        for (block in BLOCKS) {
            for (line in linesOf(doc, block)) {
                val score = MemoryConsolidation.tokens(line)
                    .intersect(queryTokens).size
                if (score > 0) hits.add(Hit(line, score, Long.MIN_VALUE, seq++))
            }
        }
        val journal = doc.optJSONArray("journal") ?: JSONArray()
        for (i in 0 until journal.length()) {
            val entry = journal.optJSONObject(i) ?: continue
            val text = entry.optString("text")
            val score = MemoryConsolidation.tokens(text)
                .intersect(queryTokens).size
            if (score > 0) {
                hits.add(Hit(text, score, entry.optLong("at", 0L), seq++))
            }
        }
        // A core line also appears in the journal (every
        // applied write journals itself): identical texts
        // are one result, kept at their most recent hit.
        val deduped = LinkedHashMap<String, Hit>()
        for (hit in hits) {
            val prior = deduped[hit.text]
            if (prior == null || hit.at > prior.at) deduped[hit.text] = hit
        }
        return deduped.values
            .sortedWith(
                compareByDescending<Hit> { it.score }
                    .thenByDescending { it.at }
                    .thenByDescending { it.seq }
            )
            .take(limit)
            .map { it.text }
    }
}
