package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * SELF-WRITTEN SKILLS — SHE KEEPS WHAT WORKED.
 *
 * The pattern this reimplements (after Hermes-style
 * self-written skills) is the learning loop of an agent
 * that does the same kinds of jobs twice: when a task
 * finishes, the run itself is distilled into a small,
 * reusable SKILL — a title, the trigger words that should
 * recall it, the ordered tools it used (with counts), and
 * the pitfalls it actually hit — and a later, similar job
 * is matched against the store so the skill can be handed
 * back as guidance.
 *
 * The gates are deliberately mechanical:
 *
 * - DISTILLATION GATE ([shouldDistil]): only a COMPLETED
 *   task with at least five real tool calls is worth a
 *   skill. Shorter runs are errands, not procedures, and a
 *   failed run's shape is not a recipe to repeat.
 * - NO MODEL IN THE LOOP: the title is a gist of the job
 *   text, the trigger is the job's distinctive keywords,
 *   the steps are counted off the recorded messages, and
 *   the pitfalls are the recorded tool results that carry
 *   an "error:" or a CHALLENGE. Everything is read off the
 *   task record the runner already wrote — deterministic,
 *   auditable, and identical on every device.
 * - PATCH, NEVER DUPLICATE: a run whose gist slugs to a
 *   skill that already exists patches that skill (new
 *   steps and pitfalls merged in, successes and version
 *   bumped) instead of forking a near-twin.
 *
 * Honest limits: a distilled skill is a memory of one
 * shape of success, not a guarantee. Tool counts say what
 * was used, not what was necessary, and a pitfall list
 * records what hurt once — [render] presents the skill as
 * guidance, and matching is keyword overlap, not
 * understanding.
 *
 * Pure Kotlin + org.json; persists one JSON document
 * through [TextIO]; pinned by SkillStoreTest.
 */
class SkillStore(private val io: TextIO) {

    companion object {
        /** Tool calls a completed task needs before it is
         *  worth distilling. */
        const val MIN_TOOL_CALLS = 5

        /** Pitfalls kept per skill at distillation. */
        const val MAX_PITFALLS = 4

        /** Trigger keywords kept per skill. */
        const val MAX_TRIGGER_TOKENS = 8
    }

    private val stopwords = setOf(
        "the", "a", "an", "and", "or", "to", "of", "in", "on",
        "for", "is", "are", "was", "were", "be", "been", "has",
        "have", "had", "do", "does", "did", "will", "would",
        "can", "could", "should", "may", "might", "must", "i",
        "you", "he", "she", "it", "we", "they", "my", "your",
        "his", "her", "its", "our", "their", "this", "that",
        "these", "those", "with", "at", "by", "from", "as",
        "but", "if", "then", "so"
    )

    private val tokenRe = Regex("[a-z0-9]+")

    private fun tokens(text: String): List<String> =
        tokenRe.findAll(text.lowercase()).map { it.value }.toList()

    private fun contentTokens(text: String): List<String> =
        tokens(text).filter { it !in stopwords }

    /** The first [max] characters of [text], cut back to a
     *  word boundary when the cut would split a word. */
    private fun gist(text: String, max: Int): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length <= max) return clean
        val cut = clean.substring(0, max)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace > 0) cut.substring(0, lastSpace) else cut
    }

    // ---------- persistence ----------

    private fun load(): JSONObject {
        val raw = try {
            io.read()
        } catch (e: Exception) {
            null
        }
        if (raw.isNullOrBlank()) return JSONObject().put("skills", JSONArray())
        return try {
            val doc = JSONObject(raw)
            if (!doc.has("skills")) doc.put("skills", JSONArray())
            doc
        } catch (e: Exception) {
            JSONObject().put("skills", JSONArray())
        }
    }

    private fun save(doc: JSONObject) {
        io.write(doc.toString())
    }

    private fun skillsArray(doc: JSONObject): JSONArray =
        doc.optJSONArray("skills") ?: JSONArray().also { doc.put("skills", it) }

    private fun findSkill(doc: JSONObject, slug: String): JSONObject? {
        val arr = skillsArray(doc)
        for (i in 0 until arr.length()) {
            val skill = arr.optJSONObject(i) ?: continue
            if (skill.optString("slug") == slug) return skill
        }
        return null
    }

    // ---------- reading a task record ----------

    /** The tool name an assistant message calls, or null.
     *  The runner records a call as a JSON object (usually
     *  fenced) with a "tool" field; anything else — prose,
     *  malformed JSON, a JSON value without "tool" — is not
     *  a tool call. */
    private fun toolNameOf(message: JSONObject): String? {
        if (message.optString("role") != "assistant") return null
        var content = message.optString("content").trim()
        if (content.startsWith("```")) {
            val firstNewline = content.indexOf('\n')
            if (firstNewline >= 0) content = content.substring(firstNewline + 1)
            content = content.removeSuffix("```").trim()
        }
        return try {
            val parsed = JSONObject(content)
            parsed.optString("tool").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun toolCalls(task: JSONObject): List<String> {
        val out = mutableListOf<String>()
        val messages = task.optJSONArray("messages") ?: return out
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            toolNameOf(message)?.let { out.add(it) }
        }
        return out
    }

    /** Ordered distinct tool names with their counts, in
     *  first-use order: "browser_open ×3". */
    private fun stepsOf(task: JSONObject): List<String> {
        val counts = LinkedHashMap<String, Int>()
        for (tool in toolCalls(task)) {
            counts[tool] = (counts[tool] ?: 0) + 1
        }
        return counts.map { (tool, count) -> "$tool ×$count" }
    }

    private fun stepNameOf(step: String): String =
        step.substringBefore(" ×").trim()

    /** The gist of each tool-result message whose content
     *  carries an "error:" or a CHALLENGE — the moments the
     *  run actually hurt — capped at [MAX_PITFALLS]. */
    private fun pitfallsOf(task: JSONObject): List<String> {
        val out = mutableListOf<String>()
        val messages = task.optJSONArray("messages") ?: return out
        for (i in 0 until messages.length()) {
            if (out.size >= MAX_PITFALLS) break
            val message = messages.optJSONObject(i) ?: continue
            if (message.optString("role") != "user") continue
            val content = message.optString("content")
            if (!content.startsWith("Tool result")) continue
            if (content.contains("error:") || content.contains("CHALLENGE")) {
                val pitfall = gist(content, 120)
                if (pitfall.isNotEmpty() && pitfall !in out) out.add(pitfall)
            }
        }
        return out
    }

    private fun slugify(title: String): String {
        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return slug.ifEmpty { "skill" }
    }

    private fun dedupeSlug(base: String, doc: JSONObject): String {
        if (findSkill(doc, base) == null) return base
        var n = 2
        while (findSkill(doc, "$base-$n") != null) n++
        return "$base-$n"
    }

    // ---------- the gate ----------

    /** A task is worth distilling only when it COMPLETED
     *  and made at least [MIN_TOOL_CALLS] real tool calls. */
    fun shouldDistil(task: JSONObject): Boolean =
        task.optString("state") == "completed" &&
            toolCalls(task).size >= MIN_TOOL_CALLS

    // ---------- distillation ----------

    /**
     * Distil [task] into a stored skill, or null when the
     * gate refuses it. When a skill with the same slug base
     * already exists, the run is treated as another success
     * of that skill — it is patched (see [patch]) and the
     * existing skill is returned — instead of a duplicate
     * being stored.
     */
    fun distil(task: JSONObject, at: Long): JSONObject? {
        if (!shouldDistil(task)) return null
        val doc = load()
        val text = task.optString("text")
        val title = gist(text, 60)
        val baseSlug = slugify(title)
        if (findSkill(doc, baseSlug) != null) {
            patch(baseSlug, task, at)
            return findSkill(load(), baseSlug)
        }
        val slug = dedupeSlug(baseSlug, doc)
        val trigger = contentTokens(text)
            .filter { it.length >= 4 }
            .distinct()
            .take(MAX_TRIGGER_TOKENS)
            .joinToString(" ")
        val steps = JSONArray()
        stepsOf(task).forEach { steps.put(it) }
        val pitfalls = JSONArray()
        pitfallsOf(task).forEach { pitfalls.put(it) }
        val skill = JSONObject()
            .put("slug", slug)
            .put("title", title)
            .put("trigger", trigger)
            .put("steps", steps)
            .put("pitfalls", pitfalls)
            .put("version", 1)
            .put("successes", 1)
            .put("updatedAt", at)
        skillsArray(doc).put(skill)
        save(doc)
        return skill
    }

    // ---------- matching & rendering ----------

    /** Skills whose trigger tokens overlap [jobText]'s
     *  tokens, best overlap first; at most [limit]. */
    fun match(jobText: String, limit: Int = 2): List<JSONObject> {
        if (limit <= 0) return emptyList()
        val jobTokens = tokens(jobText).toSet()
        val doc = load()
        val arr = skillsArray(doc)
        val scored = mutableListOf<Pair<JSONObject, Int>>()
        for (i in 0 until arr.length()) {
            val skill = arr.optJSONObject(i) ?: continue
            val triggerTokens = tokens(skill.optString("trigger")).toSet()
            val overlap = triggerTokens.intersect(jobTokens).size
            if (overlap > 0) scored.add(skill to overlap)
        }
        return scored
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** The skill as SKILL.md markdown: the shape she (or a
     *  human) reads when the skill is recalled for a job. */
    fun render(skill: JSONObject): String {
        val out = StringBuilder()
        out.append("# ").append(skill.optString("title")).append("\n")
        out.append("Trigger: ").append(skill.optString("trigger")).append("\n")
        out.append("## Steps\n")
        val steps = skill.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until steps.length()) {
            out.append("- ").append(steps.optString(i)).append("\n")
        }
        out.append("## Pitfalls\n")
        val pitfalls = skill.optJSONArray("pitfalls") ?: JSONArray()
        for (i in 0 until pitfalls.length()) {
            out.append("- ").append(pitfalls.optString(i)).append("\n")
        }
        out.append("Version: ").append(skill.optInt("version", 1))
        return out.toString()
    }

    // ---------- patching ----------

    /**
     * Merge a successful run into the skill named [slug]:
     * step names and pitfalls the skill does not already
     * carry are appended, successes and version each go up
     * by one, and updatedAt moves to [at]. False when the
     * slug is unknown — patching never creates a skill.
     */
    fun patch(slug: String, task: JSONObject, at: Long): Boolean {
        val doc = load()
        val skill = findSkill(doc, slug) ?: return false
        val steps = skill.optJSONArray("steps") ?: JSONArray()
            .also { skill.put("steps", it) }
        val knownSteps = mutableSetOf<String>()
        for (i in 0 until steps.length()) {
            knownSteps.add(stepNameOf(steps.optString(i)))
        }
        for (step in stepsOf(task)) {
            if (stepNameOf(step) !in knownSteps) {
                steps.put(step)
                knownSteps.add(stepNameOf(step))
            }
        }
        val pitfalls = skill.optJSONArray("pitfalls") ?: JSONArray()
            .also { skill.put("pitfalls", it) }
        val knownPitfalls = mutableSetOf<String>()
        for (i in 0 until pitfalls.length()) {
            knownPitfalls.add(pitfalls.optString(i))
        }
        for (pitfall in pitfallsOf(task)) {
            if (pitfall !in knownPitfalls) {
                pitfalls.put(pitfall)
                knownPitfalls.add(pitfall)
            }
        }
        skill.put("successes", skill.optInt("successes", 0) + 1)
        skill.put("version", skill.optInt("version", 1) + 1)
        skill.put("updatedAt", at)
        save(doc)
        return true
    }
}
