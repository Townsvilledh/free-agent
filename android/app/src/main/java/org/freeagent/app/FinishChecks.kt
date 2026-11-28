package org.freeagent.app

/**
 * FINISH CHECKS (v1.0.14) — verify-by-state for the
 * device runner's finishes, mirroring the core loop's
 * acceptance pipeline (free_agent.loop._check_finish).
 *
 * Two graded failure shapes from the 1.0.13 battery:
 *
 * - UNPRODUCED CLAIMS (code-b): a finish claimed a file
 *   the run never wrote — on the core the artifact was
 *   the untouched fixture; the device shape is a note
 *   claimed as her work that no step of this task ever
 *   wrote. A claim stands only when the file is in the
 *   task's produced set (PendingFinish — stamped by the
 *   writers) or a recorded tool observation evidences
 *   the write ("wrote <rel>", "SCREENSHOT_OK path=<rel>").
 *
 * - DATE CONTRADICTION (schedule-b): her words answered
 *   a date her own tool output had already settled
 *   differently. When the record's tool observations
 *   contain dates and the finish summary asserts dates
 *   of which NONE appears among them, the claim
 *   contradicts her evidence: the finish bounces once
 *   with the observed dates named.
 *
 * Pure string/record logic; pinned by Lille114Test.
 */
object FinishChecks {

    /** Claimed outputs with no production evidence in
     *  this task — the claims that cannot stand. */
    fun unproducedClaims(
        claimed: List<String>,
        produced: List<String>,
        observations: List<String>
    ): List<String> {
        val producedSet = produced.toSet()
        return claimed.filter { rel ->
            rel !in producedSet && !observations.any { obs ->
                obs.contains("wrote $rel ") ||
                    obs.contains("wrote $rel(") ||
                    obs.contains("SCREENSHOT_OK path=$rel ")
            }
        }
    }

    private val ISO_DATE_RE = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november",
        "december"
    )
    private val LONG_DATE_RE = Regex(
        """\b(\d{1,2})\s+(january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{4})\b""",
        RegexOption.IGNORE_CASE
    )
    private val LONG_DATE_US_RE = Regex(
        """\b(january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{1,2})(?:st|nd|rd|th)?\s*,?\s+(\d{4})\b""",
        RegexOption.IGNORE_CASE
    )

    /** All dates a text asserts, normalised to ISO. */
    fun extractDates(text: String): Set<String> {
        val out = mutableSetOf<String>()
        for (m in ISO_DATE_RE.findAll(text)) {
            out.add("${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}")
        }
        for (m in LONG_DATE_RE.findAll(text)) {
            val month = MONTHS.indexOf(m.groupValues[2].lowercase()) + 1
            out.add("${m.groupValues[3]}-${month.toString().padStart(2, '0')}-" +
                m.groupValues[1].padStart(2, '0'))
        }
        for (m in LONG_DATE_US_RE.findAll(text)) {
            val month = MONTHS.indexOf(m.groupValues[1].lowercase()) + 1
            out.add("${m.groupValues[3]}-${month.toString().padStart(2, '0')}-" +
                m.groupValues[2].padStart(2, '0'))
        }
        return out
    }

    /** The observed dates a finish summary contradicts:
     *  non-null when the summary asserts date(s), none of
     *  them appears in the observations, and the
     *  observations do carry dates. Returns the observed
     *  (evidence) dates to name in the bounce. */
    fun dateContradiction(
        summary: String, observations: List<String>
    ): Set<String>? {
        val observed = mutableSetOf<String>()
        for (obs in observations) observed.addAll(extractDates(obs))
        if (observed.isEmpty()) return null
        val asserted = extractDates(summary)
        if (asserted.isEmpty()) return null
        if (asserted.any { it in observed }) return null
        return observed
    }

    /** Tool observation texts from a record's message
     *  history (the runner appends them as user messages
     *  of the shape "Tool result (<tool>): ..."). */
    fun observationsOf(messages: org.json.JSONArray): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val c = m.optString("content")
            if (c.startsWith("Tool result (")) out.add(c)
        }
        return out
    }

    // ---- v1.0.15 (battery round 2, classes 2 + 3): the
    // device mirror of the core loop's evidence gates
    // (free_agent.loop._check_finish / detect_pseudo_tool_call).
    // Round 2's worst failure was a finish accepted with
    // ZERO executed tool steps on a task whose text demands
    // reading named files, its report asserting a fabricated
    // table of their contents. The gates below judge a
    // finish against the task's own evidence demand,
    // derived structurally: files that exist in the task
    // sandbox AND are named by the task's words; a plan
    // that requires tool classes; values that must trace
    // to an executed step's output.

    private val NUMBER_RE = Regex("""\d[\d,]*(?:\.\d+)?""")

    /** Numeric literals a text asserts, normalised
     *  (commas dropped, trailing zeros trimmed), excluding
     *  the ones inside date assertions — dates have their
     *  own check above. */
    fun numericLiterals(text: String): List<String> {
        var stripped = ISO_DATE_RE.replace(text, " ")
        stripped = LONG_DATE_RE.replace(stripped, " ")
        stripped = LONG_DATE_US_RE.replace(stripped, " ")
        val out = mutableListOf<String>()
        for (m in NUMBER_RE.findAll(stripped)) {
            var n = m.value.replace(",", "").trimEnd('.')
            if ('.' in n) n = n.trimEnd('0').trimEnd('.')
            if (n.isNotEmpty() && n !in out) out.add(n)
        }
        return out
    }

    /** Existing files the task's own words name — the
     *  files the task is ABOUT. */
    fun demandedFiles(
        taskText: String, existingFiles: List<String>
    ): List<String> {
        val low = taskText.lowercase()
        return existingFiles.filter { rel ->
            val base = rel.substringAfterLast('/').lowercase()
            base.isNotEmpty() && base in low
        }.sorted()
    }

    /** A demanded file counts as read when an executed
     *  step's observation carries its content (the read
     *  tools return the content itself) or names the read. */
    fun readEvidenced(
        rel: String, content: String, observations: List<String>
    ): Boolean {
        val base = rel.substringAfterLast('/')
        if (observations.any {
                it.contains("read_note") && it.contains(base)
            }) {
            return true
        }
        val probe = content.split(Regex("""\s+"""))
            .joinToString(" ").take(60)
        if (probe.isEmpty()) return true
        return observations.any { obs ->
            obs.split(Regex("""\s+""")).joinToString(" ")
                .contains(probe)
        }
    }

    /** Values the summary asserts that appear in NO
     *  evidence: no tool observation, not the task text,
     *  and not the content of a demanded file this run
     *  actually read. */
    fun ungroundedContentValues(
        summary: String,
        observations: List<String>,
        taskText: String,
        readContents: List<String>
    ): List<String> {
        val nums = numericLiterals(summary)
        if (nums.isEmpty()) return emptyList()
        val allowed = (observations.joinToString("\n") + "\n" +
            taskText).replace(",", "")
        val contents = readContents.map { it.replace(",", "") }
        return nums.filter { n ->
            n !in allowed && contents.none { c -> n in c }
        }
    }

    private val PSEUDO_CALL_RE = Regex("""^([a-z][a-z0-9_]*)\s*\(""")

    /** Pseudo-tool-call text (class 3): the first REAL
     *  tool name a reply impersonates in call shape at a
     *  line's start (after bullet / speaker-prefix
     *  stripping), or null. Prose that merely mentions a
     *  call mid-sentence is not a pseudo call. */
    fun pseudoCallName(
        content: String, toolNames: Set<String>
    ): String? {
        if (content.isBlank()) return null
        for (raw in content.split('\n')) {
            var line = raw.trim()
            line = line.removePrefix("- ").removePrefix("* ")
            if (line.startsWith("Lille:", ignoreCase = true)) {
                line = line.substring(6).trim()
            } else if (line.startsWith(
                    "Assistant:", ignoreCase = true)
            ) {
                line = line.substring(10).trim()
            }
            val m = PSEUDO_CALL_RE.find(line) ?: continue
            if (m.groupValues[1] in toolNames) {
                return m.groupValues[1]
            }
        }
        return null
    }
}
