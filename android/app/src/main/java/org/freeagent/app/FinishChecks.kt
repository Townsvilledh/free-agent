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

    // ---- v1.0.16 (battery round 3, class 1): the device
    // mirror of the core loop's finish-vs-steps consistency
    // (free_agent.loop.finish_consistency). Round 3's
    // honesty failures were finishes contradicting the
    // run's OWN steps: a report listing items her sweep
    // never found, headline figures her own closing working
    // contradicted, a date her trace computed differently.
    // The checks judge the summary against ALL content the
    // run produced — tool observations AND her own step
    // texts — plus, for listed items, everything the steps
    // fetched. The runner bounces a contradicted finish
    // ONCE (the ledger below rides the record); a second
    // one is refused and the task parks with the
    // contradiction on the record.

    /** The record field counting consistency bounces. */
    const val CONSISTENCY_BOUNCES_FIELD = "consistencyBounces"

    /** The record field carrying the named contradiction. */
    const val CONSISTENCY_FIELD = "consistencyContradiction"

    /** Her own step texts from a record's message history
     *  (assistant turns — her working, in order). */
    fun assistantTextsOf(messages: org.json.JSONArray): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "assistant") {
                val c = m.optString("content")
                if (c.isNotEmpty()) out.add(c)
            }
        }
        return out
    }

    private val WEEKDAY_LONG_RE = Regex(
        """\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s*,?\s+(\d{1,2})\s+(january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{4})\b""",
        RegexOption.IGNORE_CASE
    )
    private val WEEKDAY_US_RE = Regex(
        """\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s*,?\s+(january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{1,2})(?:st|nd|rd|th)?\s*,?\s+(\d{4})\b""",
        RegexOption.IGNORE_CASE
    )
    private val WEEKDAYS = listOf(
        "monday", "tuesday", "wednesday", "thursday",
        "friday", "saturday", "sunday"
    )

    /** Date assertions whose NAMED weekday the calendar
     *  disagrees with — self-contradiction on the face of
     *  the text (deterministic calendar math). Each entry
     *  names the assertion, the claimed weekday and the
     *  true one. */
    fun weekdayContradictions(summary: String): List<String> {
        val out = mutableListOf<String>()
        fun judge(iso: String, named: String, span: String) {
            val actual = try {
                java.time.LocalDate.parse(iso).dayOfWeek.name
                    .lowercase()
            } catch (e: Exception) {
                return
            }
            if (named.lowercase() != actual) {
                out.add("your summary calls $iso a " +
                    "${named.lowercase()} (\"$span\"), but " +
                    "$iso is a $actual — the date contradicts " +
                    "itself")
            }
        }
        for (m in WEEKDAY_LONG_RE.findAll(summary)) {
            val month = MONTHS.indexOf(
                m.groupValues[3].lowercase()) + 1
            val iso = "${m.groupValues[4]}-" +
                month.toString().padStart(2, '0') + "-" +
                m.groupValues[2].padStart(2, '0')
            judge(iso, m.groupValues[1], m.value)
        }
        for (m in WEEKDAY_US_RE.findAll(summary)) {
            val month = MONTHS.indexOf(
                m.groupValues[2].lowercase()) + 1
            val iso = "${m.groupValues[4]}-" +
                month.toString().padStart(2, '0') + "-" +
                m.groupValues[3].padStart(2, '0')
            judge(iso, m.groupValues[1], m.value)
        }
        return out
    }

    /** The produced dates a finish summary contradicts:
     *  non-null when the summary asserts date(s), the run
     *  itself produced date(s) in its steps (observations
     *  + her own step texts), and NONE of the asserted
     *  dates is among them. Returns the produced dates to
     *  name in the bounce. */
    fun stepDateContradiction(
        summary: String, producedTexts: List<String>
    ): Set<String>? {
        val produced = mutableSetOf<String>()
        for (t in producedTexts) produced.addAll(extractDates(t))
        if (produced.isEmpty()) return null
        val asserted = extractDates(summary)
        if (asserted.isEmpty()) return null
        if (asserted.any { it in produced }) return null
        return produced
    }

    /** One numeric assertion segment: (anchor, result,
     *  number set, text) for a line/sentence carrying at
     *  least two numbers and at most twelve. */
    private data class Segment(
        val anchor: String, val result: String,
        val numset: Set<String>, val text: String
    )

    private fun assertionSegments(text: String): List<Segment> {
        val out = mutableListOf<Segment>()
        val parts = text.split(
            Regex("""[\n;]+|(?<=[.!?])\s+"""))
        for (raw in parts) {
            val seg = raw.trim()
            if (seg.isEmpty()) continue
            var stripped = ISO_DATE_RE.replace(seg, " ")
            stripped = LONG_DATE_RE.replace(stripped, " ")
            stripped = LONG_DATE_US_RE.replace(stripped, " ")
            val nums = mutableListOf<String>()
            for (m in NUMBER_RE.findAll(stripped)) {
                var n = m.value.replace(",", "").trimEnd('.')
                if ('.' in n) n = n.trimEnd('0').trimEnd('.')
                if (n.isNotEmpty()) nums.add(n)
            }
            if (nums.size < 2 || nums.size > 12) continue
            out.add(Segment(nums.first(), nums.last(),
                nums.toSet(), seg))
        }
        return out
    }

    /** The anchored-number contradiction between the
     *  summary and the run's produced content (or the
     *  summary itself): two segments sharing an anchor,
     *  overlapping by at least half their number sets,
     *  asserting different results — the numbers-a shape
     *  (headline figures contradicted by the run's own
     *  closing working). Returns the named contradiction
     *  or null. */
    fun numberContradiction(
        summary: String, producedTexts: List<String>
    ): String? {
        val summarySegs = assertionSegments(summary)
        if (summarySegs.isEmpty()) return null
        val others = summarySegs.toMutableList()
        for (t in producedTexts) others.addAll(assertionSegments(t))
        for ((i, s) in summarySegs.withIndex()) {
            if (s.result == s.anchor) continue
            for ((j, o) in others.withIndex()) {
                if (j == i) continue
                if (o.anchor != s.anchor) continue
                if (o.result == o.anchor) continue
                if (o.result == s.result) continue
                val union = s.numset union o.numset
                if (union.isEmpty()) continue
                if ((s.numset intersect o.numset).size.toDouble() /
                    union.size < 0.5
                ) {
                    continue
                }
                if (s.result !in o.numset &&
                    o.result !in s.numset
                ) {
                    continue
                }
                return "for the calculation starting from " +
                    "${s.anchor}, your summary states " +
                    "${s.result}, but your own working states " +
                    "${o.result}: \"${o.text.take(160)}\""
            }
        }
        return null
    }

    private val ITEM_HEADING_RE = Regex(
        """^#{1,6}\s+(.+?)\s*#*\s*$""", RegexOption.MULTILINE)
    private val ITEM_BOLD_RE = Regex(
        """^\s*(?:[-*+]|\d+[.)])\s+\*\*(.+?)\*\*""",
        RegexOption.MULTILINE)

    /** The external items a report lists: markdown
     *  heading titles and bold-led list entries, in
     *  order, deduped, structure stripped. */
    fun listedItems(summary: String): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        for (m in ITEM_HEADING_RE.findAll(summary)) {
            found.add(m.range.first to m.groupValues[1])
        }
        for (m in ITEM_BOLD_RE.findAll(summary)) {
            found.add(m.range.first to m.groupValues[1])
        }
        val out = mutableListOf<String>()
        for ((_, raw) in found.sortedBy { it.first }) {
            var title = raw.split(Regex("""\s+"""))
                .joinToString(" ").trim(' ', ':', '—', '-')
            title = title.replace(
                Regex("""^\d+[.)]\s*"""), "")
            title = title.replace("**", "").replace("__", "")
            title = title.split(Regex("""\s+"""))
                .joinToString(" ").trim(' ', ':', '—', '-')
            if (title.isNotEmpty() && title !in out) out.add(title)
        }
        return out
    }

    private fun normWords(text: String): List<String> =
        text.lowercase()
            .replace(Regex("""[^a-z0-9 ]+"""), " ")
            .split(Regex("""\s+""")).filter { it.isNotEmpty() }

    /** Listed report items that trace to NO source in the
     *  step record — the research-a shape. Judged only
     *  when the finish is research-shaped: at least two
     *  items listed, or fetched evidence was demanded and
     *  at least one is listed. */
    fun unsourcedItems(
        summary: String, corpusTexts: List<String>,
        fetchDemanded: Boolean
    ): List<String> {
        val items = listedItems(summary)
        if (items.isEmpty()) return emptyList()
        if (items.size < 2 && !fetchDemanded) return emptyList()
        val corpusNorm = corpusTexts
            .flatMap { normWords(it) }.joinToString(" ")
        val corpusWords = corpusNorm.split(" ").toSet()
        return items.filter { title ->
            val words = normWords(title)
            if (words.isEmpty()) return@filter false
            if (words.joinToString(" ") in corpusNorm) {
                return@filter false
            }
            var bigram = false
            for (k in 0 until words.size - 1) {
                if ("${words[k]} ${words[k + 1]}" in corpusNorm) {
                    bigram = true
                    break
                }
            }
            if (bigram) return@filter false
            !(words.size <= 2 &&
                words.all { it in corpusWords })
        }
    }

    /** The first named contradiction between a finish
     *  summary and the run's own steps, or null. The
     *  produced pool is the run's observations plus her
     *  own step texts; the item corpus is the same pool
     *  (everything the steps fetched or produced). */
    fun consistencyContradiction(
        summary: String,
        observations: List<String>,
        stepTexts: List<String>,
        fetchDemanded: Boolean
    ): String? {
        val weekday = weekdayContradictions(summary)
        if (weekday.isNotEmpty()) return weekday.first()
        val produced = observations + stepTexts
        val dates = stepDateContradiction(summary, produced)
        if (dates != null) {
            return "your summary gives date(s) " +
                extractDates(summary).sorted()
                    .joinToString(", ") +
                ", but your own steps in this task produced " +
                dates.sorted().joinToString(", ")
        }
        val num = numberContradiction(summary, produced)
        if (num != null) return num
        val unsourced = unsourcedItems(
            summary, produced, fetchDemanded)
        if (unsourced.isNotEmpty()) {
            return "your report lists item(s) that no " +
                "source fetched in this task mentions: " +
                unsourced.joinToString("; ") +
                " — a listed item must trace to a source " +
                "your steps actually fetched"
        }
        return null
    }
}

/**
 * MALFORMED-CALL RECOVERY (v1.0.16, battery round 3,
 * class 2 — the device mirror of the core loop's repair
 * ledger). A reply that TRIED to make a tool call but
 * whose JSON did not parse, or whose block carries no
 * "tool" field, is a malformed call — not plain text. Its
 * shape (the tool it named + the failure category) earns
 * ONE repair re-ask showing the specific format error;
 * only a malformed call after its shape's repair re-ask
 * counts toward the honest stop. Pure; pinned by
 * Lille116Test.
 */
object MalformedRepair {

    /** How many post-repair malformed strikes stop a run. */
    const val MAX_STRIKES = 2

    data class Attempt(val shape: String, val reason: String)

    private val TOOL_NAME_RE = Regex(
        """"tool"\s*:\s*"([a-z][a-z0-9_]*)"""")

    /** The malformed attempt a reply made, or null when
     *  the reply did not attempt a JSON-shaped call at all
     *  (plain text and pseudo-call text are other lanes'
     *  business). The caller has already established the
     *  reply did not parse as a call. */
    fun attempted(content: String): Attempt? {
        if (content.isBlank()) return null
        val trimmed = content.trim()
        val jsonish = trimmed.contains("```") ||
            trimmed.startsWith("{") || "\"tool\"" in trimmed
        if (!jsonish) return null
        val tool = TOOL_NAME_RE.find(trimmed)
            ?.groupValues?.get(1) ?: "unknown"
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        var parsed: org.json.JSONObject? = null
        if (start >= 0 && end > start) {
            parsed = try {
                org.json.JSONObject(
                    trimmed.substring(start, end + 1))
            } catch (e: Exception) {
                null
            }
        }
        val reason = when {
            parsed == null ->
                "the JSON in your reply did not parse"
            !parsed.has("tool") ->
                "the JSON block has no \"tool\" field"
            else ->
                "the call's arguments did not parse"
        }
        val category = when {
            parsed == null -> "json did not parse"
            !parsed.has("tool") -> "no tool field"
            else -> "args did not parse"
        }
        return Attempt("$tool|$category", reason)
    }

    /** The repair re-ask for a shape's first malformation:
     *  what was malformed, and the expected shape. A format
     *  correction only — it never widens what the task may
     *  do. */
    fun repairSteer(attempt: Attempt): String =
        "Your tool call was malformed: ${attempt.reason}. " +
            "What was malformed is the call's FORMAT — not " +
            "the task. The expected shape is exactly one " +
            "fenced JSON block {\"tool\": \"<name>\", " +
            "\"args\": {...}} whose args are valid JSON " +
            "(double quotes, no trailing commas, strings " +
            "escaped). Keep each argument short. Re-emit " +
            "the call in that shape now."

    /** The warning after a shape's post-repair strike:
     *  one more malformed call stops the run. */
    fun strikeSteer(attempt: Attempt): String =
        "That call was malformed again: ${attempt.reason}. " +
            "This call shape already had its repair re-ask; " +
            "one more malformed call stops the run. Emit " +
            "exactly one fenced JSON tool block with valid " +
            "JSON — or take a different, simpler step."
}
